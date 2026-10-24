package com.lodygames.rpgquest.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import com.lodygames.rpgquest.ui.TrackedQuestSummary;
import org.bukkit.entity.Player;

/**
 * Le canal partagé de l'action bar (issues #69, #209 et #817).
 *
 * <h2>Les défauts corrigés</h2>
 *
 * <ul>
 *   <li>#69 / #209 — l'XP de combat envoyée au tick suivant effaçait la progression du kill avant
 *       qu'elle soit lisible ;</li>
 *   <li>#817 — une bûche qui compte pour trois quêtes produisait trois segments « Quête ·
 *       Objectif x/y » d'une quarantaine de caractères chacun : la ligne débordait de l'écran
 *       (petite résolution, grand facteur d'échelle). Les récompenses d'une quête terminée
 *       (« +20 XP RPG », « +5 LodyCoins ») étaient collées aux compteurs d'une autre et semblaient
 *       lui appartenir, et le compteur « 20/20 » d'une étape close revenait sur les lignes
 *       suivantes.</li>
 * </ul>
 *
 * <h2>La règle, explicite</h2>
 *
 * <ul>
 *   <li>une progression d'objectif est <strong>prioritaire</strong> et reste affichée
 *       {@value #HOLD_MILLIS} ms ; plusieurs objectifs qui progressent dans cette fenêtre sont
 *       <strong>regroupés</strong>, au plus {@value #MAX_PARTS}, les plus récents ;</li>
 *   <li>dès que la ligne concerne plusieurs quêtes, chaque compteur est <strong>attribué</strong> à
 *       sa quête sous une forme courte : le libellé de l'objectif une seule fois, puis le titre de
 *       chaque quête abrégé à {@value #SHORT_TITLE} caractères — « Bûche de chêne : Bois du jour
 *       19/20, Le stock de… 7/20 » ;</li>
 *   <li>la ligne ne dépasse jamais {@value #MAX_WIDTH_PX} px estimés (écran minimal, tout facteur
 *       GUI) : titres plus courts, puis libellé commun retiré, puis les compteurs les plus anciens
 *       résumés en « +N » ;</li>
 *   <li>un message d'information (XP, pièces) pendant cette fenêtre n'est <strong>plus collé</strong>
 *       aux compteurs : il est mis en file et affiché seul dès que la fenêtre se libère, au plus
 *       tard {@value #INFO_MAX_DELAY_MILLIS} ms après son arrivée ; la file garde les
 *       {@value #MAX_INFOS} plus récents, sans doublon. Rien d'essentiel n'y est perdu : le bilan
 *       d'une quête terminée part aussi dans le chat ;</li>
 *   <li>l'étape close ou la quête terminée/abandonnée est <strong>oubliée</strong> ({@link
 *       #forgetQuest}) : son ancien compteur ne réapparaît pas sur la ligne suivante.</li>
 * </ul>
 *
 * <p>Aucun compteur n'est tenu ici : les valeurs viennent du moteur de quêtes après mutation. Ce
 * canal ne décide que de la mise en forme et de l'ordre d'affichage. À utiliser depuis le thread
 * principal.</p>
 */
public final class ActionBarFeedback {

    /** Durée pendant laquelle une progression d'objectif garde la priorité. */
    public static final long HOLD_MILLIS = 2_000L;

    /** Nombre maximal d'objectifs regroupés sur une ligne : au-delà, elle deviendrait illisible. */
    public static final int MAX_PARTS = 3;

    /** Longueur maximale d'un titre de quête sur une ligne multi-quêtes, points de suspension compris. */
    public static final int SHORT_TITLE = 12;

    /** Titres raccourcis davantage, dans cet ordre, tant que la ligne dépasse {@link #MAX_WIDTH_PX}. */
    private static final int[] TITLE_LENGTHS = {SHORT_TITLE, 10, 8};

    /**
     * Largeur maximale estimée de la ligne : comme la bossbar (#71), elle tient sur l'écran minimal
     * de Minecraft (320 px mis à l'échelle, quel que soit le facteur GUI) sans recouvrir le chat.
     */
    public static final int MAX_WIDTH_PX = TrackedQuestSummary.MAX_WIDTH_PX;

    /** Délai maximal d'attente d'une information derrière des compteurs. */
    public static final long INFO_MAX_DELAY_MILLIS = HOLD_MILLIS;

    /** Nombre d'informations en file conservées (les plus récentes). */
    public static final int MAX_INFOS = 2;

    private static final Component SEPARATOR = Component.text("  ·  ", NamedTextColor.DARK_GRAY);
    private static final Component LIST_SEPARATOR = Component.text(", ", NamedTextColor.DARK_GRAY);
    private static final Component LABEL_SEPARATOR = Component.text(" : ", NamedTextColor.GRAY);

    /**
     * Progression d'un objectif, déjà mise en forme par le moteur ({@code messages.yml}).
     * {@code questKey} identifie la quête (attribution et {@link #forgetQuest}), {@code objectiveKey}
     * l'objectif (quête + étape + rang). {@code alone} est la forme d'une ligne à une seule quête
     * (« Bûche de chêne 5/20 ») ; {@code entry} celle d'une ligne à plusieurs quêtes, sans libellé
     * (« Bois du jour 5/20 ») qui reçoit le titre déjà abrégé, regroupée sous {@code objective}.
     */
    public record Progress(String questKey, String objectiveKey, String questTitle, Component objective,
                           Component alone, Function<String, Component> entry) {
    }

    private final LongSupplier clock;
    private final BiConsumer<Runnable, Long> later;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param later planifie une tâche sur le thread principal après le délai donné en millisecondes
     */
    public ActionBarFeedback(LongSupplier clock, BiConsumer<Runnable, Long> later) {
        this.clock = clock;
        this.later = later;
    }

    /** Les progressions encore affichées pour un joueur. */
    private record Held(LinkedHashMap<String, Progress> parts, long until) {
    }

    /** Les informations en attente d'une fenêtre libre. */
    private record Pending(Player player, List<Component> infos, long deadline) {
    }

    /** Affiche la progression d'un objectif. */
    public void objective(Player player, Progress progress) {
        player.sendActionBar(composeObjective(player.getUniqueId(), progress));
    }

    /** Affiche une information de moindre priorité (XP, pièces) sans écraser une progression récente. */
    public void info(Player player, Component message) {
        UUID playerId = player.getUniqueId();
        Optional<Component> now = composeInfo(playerId, player, message);
        if (now.isPresent()) {
            player.sendActionBar(now.get());
        }
    }

    /** L'étape de {@code questKey} est close (ou la quête terminée/abandonnée) : ses compteurs sortent. */
    public void forgetQuest(UUID playerId, String questKey) {
        held.computeIfPresent(playerId, (id, current) -> {
            LinkedHashMap<String, Progress> parts = new LinkedHashMap<>(current.parts());
            parts.values().removeIf(part -> part.questKey().equals(questKey));
            return parts.isEmpty() ? null : new Held(parts, current.until());
        });
    }

    Component composeObjective(UUID playerId, Progress progress) {
        long now = clock.getAsLong();
        // Purge des fenêtres échues : la table ne dépasse jamais le nombre de joueurs récemment
        // notifiés, même après des déconnexions.
        held.entrySet().removeIf(entry -> now >= entry.getValue().until());
        Held current = active(playerId, now).orElse(null);
        LinkedHashMap<String, Progress> parts = current == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(current.parts());
        // Réinsérer en fin : l'objectif qui vient de bouger devient le plus récent.
        parts.remove(progress.objectiveKey());
        parts.put(progress.objectiveKey(), progress);
        while (parts.size() > MAX_PARTS) {
            parts.remove(parts.keySet().iterator().next());
        }
        held.put(playerId, new Held(parts, now + HOLD_MILLIS));
        return render(new ArrayList<>(parts.values()));
    }

    /**
     * Rend la ligne à afficher tout de suite, ou rien si l'information est mise en file derrière des
     * compteurs ; dans ce cas, une vérification est planifiée.
     */
    Optional<Component> composeInfo(UUID playerId, Player player, Component message) {
        long now = clock.getAsLong();
        Optional<Held> current = active(playerId, now);
        if (current.isEmpty()) {
            return Optional.of(message);
        }
        Pending existing = pending.get(playerId);
        List<Component> infos = existing == null ? new ArrayList<>() : new ArrayList<>(existing.infos());
        infos.remove(message);
        infos.add(message);
        while (infos.size() > MAX_INFOS) {
            infos.remove(0);
        }
        long deadline = existing == null ? now + INFO_MAX_DELAY_MILLIS : existing.deadline();
        pending.put(playerId, new Pending(player, infos, deadline));
        if (existing == null) {
            scheduleFlush(playerId, Math.min(current.get().until(), deadline) - now);
        }
        return Optional.empty();
    }

    /**
     * Les informations en file, si leur tour est venu (fenêtre libérée ou délai maximal atteint) ;
     * sinon une nouvelle vérification est planifiée.
     */
    Optional<Component> flushDue(UUID playerId) {
        Pending waiting = pending.get(playerId);
        if (waiting == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        Optional<Held> current = active(playerId, now);
        if (current.isPresent() && now < waiting.deadline()) {
            scheduleFlush(playerId, Math.min(current.get().until(), waiting.deadline()) - now);
            return Optional.empty();
        }
        pending.remove(playerId, waiting);
        return Optional.of(join(waiting.infos()));
    }

    private void scheduleFlush(UUID playerId, long delayMillis) {
        later.accept(() -> {
            Pending waiting = pending.get(playerId);
            Optional<Component> line = flushDue(playerId);
            if (line.isPresent() && waiting != null && waiting.player().isOnline()) {
                waiting.player().sendActionBar(line.get());
            }
        }, Math.max(1L, delayMillis));
    }

    private Optional<Held> active(UUID playerId, long now) {
        Held current = held.get(playerId);
        if (current == null) {
            return Optional.empty();
        }
        if (now >= current.until()) {
            held.remove(playerId, current);
            return Optional.empty();
        }
        return Optional.of(current);
    }

    /**
     * Une seule quête : « Bûche de chêne 5/20 ». Plusieurs : un groupe par libellé d'objectif,
     * chaque compteur précédé du titre court de sa quête. Si la ligne dépasse {@link #MAX_WIDTH_PX},
     * dans l'ordre : titres plus courts, libellé commun retiré (le joueur vient de faire l'action),
     * puis les compteurs les plus anciens remplacés par « +N » — jamais une ligne hors de l'écran.
     */
    private static Component render(List<Progress> parts) {
        boolean severalQuests = parts.stream().map(Progress::questKey).distinct().count() > 1;
        Component line = Component.empty();
        for (int keep = parts.size(); keep >= 1; keep--) {
            List<Progress> kept = parts.subList(parts.size() - keep, parts.size());
            int hidden = parts.size() - keep;
            if (!severalQuests) {
                line = withHidden(join(kept.stream().map(Progress::alone).toList()), hidden);
                if (fits(line)) {
                    return line;
                }
                continue;
            }
            for (boolean labelled : new boolean[] {true, false}) {
                for (int length : TITLE_LENGTHS) {
                    line = withHidden(severalQuests(kept, length, labelled), hidden);
                    if (fits(line)) {
                        return line;
                    }
                }
            }
        }
        return line;
    }

    private static Component severalQuests(List<Progress> parts, int titleLength, boolean labelled) {
        LinkedHashMap<Component, List<Component>> byObjective = new LinkedHashMap<>();
        for (Progress part : parts) {
            byObjective.computeIfAbsent(part.objective(), key -> new ArrayList<>())
                    .add(part.entry().apply(shortTitle(part.questTitle(), titleLength)));
        }
        // Sans libellé seulement si tous les compteurs portent sur le même objectif : sinon on ne
        // saurait plus de quoi parle chaque compteur.
        boolean withLabel = labelled || byObjective.size() > 1;
        List<Component> groups = new ArrayList<>();
        byObjective.forEach((objective, entries) -> {
            Component group = withLabel ? objective.colorIfAbsent(NamedTextColor.YELLOW).append(LABEL_SEPARATOR)
                    : Component.empty();
            for (int i = 0; i < entries.size(); i++) {
                group = (i > 0 ? group.append(LIST_SEPARATOR) : group).append(entries.get(i));
            }
            groups.add(group);
        });
        return join(groups);
    }

    private static Component withHidden(Component line, int hidden) {
        return hidden == 0 ? line : line.append(Component.text(" +" + hidden, NamedTextColor.DARK_GRAY));
    }

    private static boolean fits(Component line) {
        return TrackedQuestSummary.estimatedWidthOf(line) <= MAX_WIDTH_PX;
    }

    /** Titre de quête abrégé à {@code max} caractères au plus, points de suspension compris. */
    static String shortTitle(String title, int max) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.codePointCount(0, trimmed.length()) <= max) {
            return trimmed;
        }
        int end = trimmed.offsetByCodePoints(0, max - 1);
        return trimmed.substring(0, end).stripTrailing() + "…";
    }

    private static Component join(List<Component> parts) {
        Component line = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                line = line.append(SEPARATOR);
            }
            line = line.append(parts.get(i));
        }
        return line;
    }
}
