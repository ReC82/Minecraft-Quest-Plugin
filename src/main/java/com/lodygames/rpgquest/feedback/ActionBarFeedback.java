package com.lodygames.rpgquest.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Le canal partagé de l'action bar (issues #69 et #209).
 *
 * <h2>Le défaut corrigé</h2>
 *
 * <p>Tuer une araignée de quête produit <em>deux</em> messages dans l'action bar : la progression
 * (« Araignées : 3/10 ») envoyée pendant l'événement, puis l'XP de combat (« +5 XP (Combat) »)
 * envoyée au tick suivant. L'action bar n'affiche qu'une ligne : la seconde remplaçait la première
 * avant qu'elle soit lisible, et le joueur croyait que le kill n'avait pas compté.</p>
 *
 * <h2>La règle, explicite</h2>
 *
 * <ul>
 *   <li>une progression d'objectif est <strong>prioritaire</strong> et reste affichée
 *       {@value #HOLD_MILLIS} ms ;</li>
 *   <li>plusieurs objectifs qui progressent dans la même fenêtre (un kill qui compte pour deux
 *       quêtes, des kills rapides) sont <strong>regroupés</strong> sur la même ligne, un par
 *       objectif, avec sa dernière valeur — au plus {@value #MAX_PARTS}, les plus récents ;</li>
 *   <li>un message d'information (XP gagnée) pendant cette fenêtre est <strong>ajouté</strong> à la
 *       ligne au lieu de la remplacer ; hors fenêtre, il s'affiche seul, comme avant.</li>
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

    private static final Component SEPARATOR = Component.text("  ·  ", NamedTextColor.DARK_GRAY);

    private final LongSupplier clock;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    public ActionBarFeedback() {
        this(System::currentTimeMillis);
    }

    public ActionBarFeedback(LongSupplier clock) {
        this.clock = clock;
    }

    /** Les progressions encore affichées pour un joueur. */
    private record Held(LinkedHashMap<String, Component> parts, long until) {
    }

    /** Affiche la progression d'un objectif ; {@code key} identifie l'objectif (quête + étape + rang). */
    public void objective(Player player, String key, Component message) {
        player.sendActionBar(composeObjective(player.getUniqueId(), key, message));
    }

    /** Affiche une information de moindre priorité (XP gagnée…), sans effacer une progression récente. */
    public void info(Player player, Component message) {
        player.sendActionBar(composeInfo(player.getUniqueId(), message));
    }

    Component composeObjective(UUID playerId, String key, Component message) {
        long now = clock.getAsLong();
        // Purge des fenêtres échues : la table ne dépasse jamais le nombre de joueurs récemment
        // notifiés, même après des déconnexions.
        held.entrySet().removeIf(entry -> now >= entry.getValue().until());
        Held current = active(playerId, now).orElse(null);
        LinkedHashMap<String, Component> parts = current == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(current.parts());
        // Réinsérer en fin : l'objectif qui vient de bouger devient le plus récent.
        parts.remove(key);
        parts.put(key, message);
        while (parts.size() > MAX_PARTS) {
            parts.remove(parts.keySet().iterator().next());
        }
        held.put(playerId, new Held(parts, now + HOLD_MILLIS));
        return join(new ArrayList<>(parts.values()));
    }

    Component composeInfo(UUID playerId, Component message) {
        Optional<Held> current = active(playerId, clock.getAsLong());
        if (current.isEmpty()) {
            return message;
        }
        List<Component> parts = new ArrayList<>(current.get().parts().values());
        parts.add(message);
        return join(parts);
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
