package com.lodygames.rpgquest.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
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
 *   <li>#817 — une bûche qui compte pour plusieurs quêtes produisait une ligne trop longue. La
 *       première correction (25/10) concaténait des titres abrégés : rejetée en jeu le 29/10
 *       (« {@code Oak Log : <gold>Bois... 2/16, <gold>La re... 2/20} ») — le titre MiniMessage
 *       brut était coupé avant d'être interprété, ses balises s'affichaient en clair, et deux
 *       compteurs tronqués sur une ligne restaient illisibles.</li>
 * </ul>
 *
 * <h2>La règle, explicite : un message à la fois, chacun à son tour</h2>
 *
 * <ul>
 *   <li>une ligne ne porte <strong>jamais</strong> qu'une seule progression (ou une seule
 *       information) ; aucune concaténation ;</li>
 *   <li>chaque ligne reste au moins {@value #SLOT_MILLIS} ms ; les messages arrivés entre-temps
 *       attendent leur tour, dans l'ordre d'arrivée — une bûche qui compte pour trois quêtes montre
 *       les trois quêtes l'une après l'autre ;</li>
 *   <li>dès que plusieurs quêtes se partagent le canal, chaque ligne nomme sa quête : « Bois du
 *       jour · Bûche de chêne 2/16 ». Le titre est du <strong>texte brut</strong> (balises retirées
 *       avant toute coupe, jamais réinterprété) raccourci au pixel pour que la ligne tienne en
 *       {@value #MAX_WIDTH_PX} px — l'écran minimal de Minecraft, quel que soit le facteur GUI ;</li>
 *   <li>un objectif déjà en attente est <strong>mis à jour sur place</strong> (il garde sa place,
 *       affiche sa dernière valeur) ; celui qui est à l'écran est réaffiché tout de suite avec sa
 *       nouvelle valeur, sans prolonger son tour — les autres quêtes ne sont jamais affamées ;</li>
 *   <li>aucun événement n'est perdu : chaque progression en attente est affichée au moins une
 *       fois avec sa dernière valeur. Les informations (XP, pièces, niveau) sont dédoublonnées et
 *       les {@value #MAX_INFOS} plus récentes gardées ; le bilan d'une quête terminée part aussi
 *       dans le chat ;</li>
 *   <li>une progression qui arrive juste après une information (moins de {@value #PREEMPT_MILLIS}
 *       ms, même événement) passe devant elle ; l'information est réaffichée ensuite (#69).</li>
 * </ul>
 *
 * <p>Aucun compteur n'est tenu ici : les valeurs viennent du moteur de quêtes après mutation. Ce
 * canal ne décide que de la mise en forme et de l'ordre d'affichage. À utiliser depuis le thread
 * principal.</p>
 */
public final class ActionBarFeedback {

    /** Durée minimale d'affichage d'une ligne avant de passer à la suivante. */
    public static final long SLOT_MILLIS = 1_500L;

    /** Une progression remplace une information affichée depuis moins longtemps que ça. */
    public static final long PREEMPT_MILLIS = 250L;

    /** Largeur maximale de la ligne (écran minimal, tout facteur GUI), comme la bossbar (#71). */
    public static final int MAX_WIDTH_PX = TrackedQuestSummary.MAX_WIDTH_PX;

    /**
     * Marge retirée du budget du titre : le libellé de l'objectif est souvent traduit côté client et
     * son estimation serveur reste approchée (« oak_log » estimé à 60 px, « Bûche de chêne » en fait
     * 75 px). Sans elle, un titre très long laisserait la ligne réelle à 318 px sur 320.
     */
    static final int TITLE_MARGIN_PX = 20;

    /** Nombre d'informations en attente conservées (les plus récentes). */
    public static final int MAX_INFOS = 3;

    /**
     * Progression d'un objectif, déjà mise en forme par le moteur ({@code messages.yml}).
     * {@code questKey} identifie la quête, {@code objectiveKey} l'objectif (quête + étape + rang).
     * {@code alone} est la ligne d'une quête seule (« Bûche de chêne 5/20 ») ; {@code named} celle
     * d'une quête parmi plusieurs, qui reçoit le titre en texte brut déjà raccourci (« Bois du jour ·
     * Bûche de chêne 5/20 »). {@code plainTitle} ne contient aucune balise MiniMessage.
     */
    public record Progress(String questKey, String objectiveKey, String plainTitle,
                           Component alone, Function<String, Component> named) {
    }

    /** Un message en attente ou à l'écran : une progression ou une information. */
    private record Slot(String key, Progress progress, Component info) {

        static Slot of(Progress progress) {
            return new Slot("objective:" + progress.objectiveKey(), progress, null);
        }

        static Slot of(Component info) {
            return new Slot("info:" + System.identityHashCode(info) + ":" + info.hashCode(), null, info);
        }

        boolean isInfo() {
            return progress == null;
        }
    }

    /** La file d'un joueur : ce qui est à l'écran et ce qui attend son tour. */
    private static final class Rotation {
        Consumer<Component> send;
        BooleanSupplier online;
        Slot current;
        long shownAt;
        long generation;
        /** Plusieurs quêtes se partagent le canal depuis le début de cette série : on les nomme. */
        boolean named;
        final LinkedHashMap<String, Slot> waiting = new LinkedHashMap<>();
    }

    private final LongSupplier clock;
    private final BiConsumer<Runnable, Long> later;
    private final Map<UUID, Rotation> rotations = new ConcurrentHashMap<>();

    /**
     * @param later planifie une tâche sur le thread principal après le délai donné en millisecondes
     */
    public ActionBarFeedback(LongSupplier clock, BiConsumer<Runnable, Long> later) {
        this.clock = clock;
        this.later = later;
    }

    /** Affiche la progression d'un objectif, maintenant ou à son tour. */
    public void objective(Player player, Progress progress) {
        objective(player.getUniqueId(), player::sendActionBar, player::isOnline, progress);
    }

    /** Affiche une information de moindre priorité (XP, pièces), maintenant ou à son tour. */
    public void info(Player player, Component message) {
        info(player.getUniqueId(), player::sendActionBar, player::isOnline, message);
    }

    /**
     * La quête est terminée ou abandonnée : ses progressions encore en attente sortent de la file
     * (le titre de fin et le bilan du chat prennent le relais). Celle à l'écran finit son tour.
     */
    public void forgetQuest(UUID playerId, String questKey) {
        Rotation rotation = rotations.get(playerId);
        if (rotation != null) {
            rotation.waiting.values().removeIf(slot -> !slot.isInfo() && slot.progress().questKey().equals(questKey));
        }
    }

    void objective(UUID playerId, Consumer<Component> send, BooleanSupplier online, Progress progress) {
        long now = clock.getAsLong();
        Rotation rotation = rotation(playerId, send, online);
        Slot slot = Slot.of(progress);
        boolean showing = isShowing(rotation, now);
        if (showing && rotation.current.key().equals(slot.key())) {
            // Même objectif à l'écran : nouvelle valeur tout de suite, sans prolonger son tour.
            rotation.current = slot;
            updateNaming(rotation);
            display(rotation);
            return;
        }
        if (showing && rotation.current.isInfo() && now - rotation.shownAt < PREEMPT_MILLIS) {
            // #69 : l'XP du même kill est arrivée en premier ; la progression passe devant.
            requeueFirst(rotation, rotation.current);
            show(rotation, slot, now);
            return;
        }
        if (!showing) {
            showNow(rotation, slot, now);
            return;
        }
        rotation.waiting.put(slot.key(), slot); // déjà en attente : mis à jour sur place
        if (updateNaming(rotation)) {
            display(rotation);
        }
    }

    void info(UUID playerId, Consumer<Component> send, BooleanSupplier online, Component message) {
        long now = clock.getAsLong();
        Rotation rotation = rotation(playerId, send, online);
        if (!isShowing(rotation, now)) {
            showNow(rotation, Slot.of(message), now);
            return;
        }
        if (rotation.current.isInfo() && rotation.current.info().equals(message)) {
            return;
        }
        rotation.waiting.values().removeIf(slot -> slot.isInfo() && slot.info().equals(message));
        Slot slot = Slot.of(message);
        rotation.waiting.put(slot.key(), slot);
        List<Slot> infos = rotation.waiting.values().stream().filter(Slot::isInfo).toList();
        for (int i = 0; i < infos.size() - MAX_INFOS; i++) {
            rotation.waiting.remove(infos.get(i).key());
        }
    }

    /** Nombre de messages en attente pour ce joueur (tests et diagnostic). */
    int waiting(UUID playerId) {
        Rotation rotation = rotations.get(playerId);
        return rotation == null ? 0 : rotation.waiting.size();
    }

    private Rotation rotation(UUID playerId, Consumer<Component> send, BooleanSupplier online) {
        Rotation rotation = rotations.computeIfAbsent(playerId, id -> new Rotation());
        rotation.send = send;
        rotation.online = online;
        return rotation;
    }

    private static boolean isShowing(Rotation rotation, long now) {
        return rotation.current != null && now - rotation.shownAt < SLOT_MILLIS;
    }

    /**
     * Le tour précédent est fini mais sa suite n'a pas encore été planifiée (arrondi au tick) : ce
     * qui attendait passe d'abord, le nouveau message prend sa place dans la file.
     */
    private void showNow(Rotation rotation, Slot slot, long now) {
        if (rotation.waiting.isEmpty()) {
            show(rotation, slot, now);
            return;
        }
        rotation.waiting.put(slot.key(), slot);
        Slot next = rotation.waiting.values().iterator().next();
        rotation.waiting.remove(next.key());
        show(rotation, next, now);
    }

    private void show(Rotation rotation, Slot slot, long now) {
        rotation.current = slot;
        rotation.shownAt = now;
        updateNaming(rotation);
        display(rotation);
        long generation = ++rotation.generation;
        later.accept(() -> advance(rotation, generation), SLOT_MILLIS);
    }

    /** Fin du tour : le message suivant, ou la fin de la série. */
    private void advance(Rotation rotation, long generation) {
        if (rotation.generation != generation) {
            return; // un affichage plus récent a reprogrammé la suite
        }
        if (!rotation.online.getAsBoolean() || rotation.waiting.isEmpty()) {
            rotation.current = null;
            rotation.waiting.clear();
            rotation.named = false;
            rotations.values().remove(rotation);
            return;
        }
        Slot next = rotation.waiting.values().iterator().next();
        rotation.waiting.remove(next.key());
        show(rotation, next, clock.getAsLong());
    }

    private static void requeueFirst(Rotation rotation, Slot slot) {
        LinkedHashMap<String, Slot> rest = new LinkedHashMap<>(rotation.waiting);
        rotation.waiting.clear();
        rotation.waiting.put(slot.key(), slot);
        rest.forEach(rotation.waiting::putIfAbsent);
    }

    /** Passe en lignes nommées dès que deux quêtes se partagent la série ; vrai si ça vient de changer. */
    private static boolean updateNaming(Rotation rotation) {
        if (rotation.named) {
            return false;
        }
        List<Slot> slots = new ArrayList<>(rotation.waiting.values());
        slots.add(rotation.current);
        rotation.named = slots.stream().filter(slot -> slot != null && !slot.isInfo())
                .map(slot -> slot.progress().questKey()).distinct().count() > 1;
        return rotation.named;
    }

    private static void display(Rotation rotation) {
        Slot slot = rotation.current;
        rotation.send.accept(slot.isInfo() ? slot.info() : render(slot.progress(), rotation.named));
    }

    /** La ligne d'une progression : seule, ou nommée avec le titre raccourci au pixel près. */
    static Component render(Progress progress, boolean named) {
        if (!named) {
            return progress.alone();
        }
        int budget = MAX_WIDTH_PX - TITLE_MARGIN_PX
                - TrackedQuestSummary.estimatedWidthOf(progress.named().apply(""));
        return progress.named().apply(TrackedQuestSummary.truncate(progress.plainTitle(), budget));
    }
}
