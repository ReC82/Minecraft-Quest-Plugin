package com.lodygames.rpgquest.travel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/**
 * Issue #191 — autorisation <strong>explicite et temporaire</strong> de casser les blocs d'un
 * waypoint ou d'une borne de voyage, en remplacement du bypass implicite précédent.
 *
 * <p><strong>Ce qui ne marchait pas.</strong> Les deux écouteurs de protection
 * ({@code waypoint.WaypointProtectionListener}, {@code travel.beacon.TravelBeaconProtectionListener})
 * laissaient passer tout joueur ayant {@code rpgquest.admin.world}, dont le défaut est {@code op}.
 * N'importe quel compte opérateur cassait donc le bouton puis toute la structure sans le moindre
 * geste délibéré — exactement le symptôme signalé en jeu. L'enregistrement des positions protégées
 * et la couverture des blocs du modèle étaient, eux, corrects (y compris après redémarrage, via la
 * réindexation au chargement) : le trou était uniquement ce bypass.</p>
 *
 * <p><strong>Règle désormais appliquée.</strong> Deux conditions cumulatives, jamais une seule :
 * <ol>
 *   <li>la permission dédiée {@link #PERMISSION}, dont le défaut est {@code false} — elle n'est
 *       <em>jamais</em> accordée par le simple statut OP, contrairement à
 *       {@code rpgquest.admin.world} ;</li>
 *   <li>une activation volontaire par le joueur lui-même ({@code /rpgadmin travel maintenance on}),
 *       qui <strong>expire d'elle-même</strong> au bout de {@link #TTL_MILLIS} pour qu'un mode
 *       oublié ne devienne pas un trou permanent.</li>
 * </ol>
 * La permission seule ne suffit donc pas à casser quoi que ce soit.</p>
 *
 * <p>État volontairement en mémoire seule : un redémarrage remet tout le monde en protection
 * normale, ce qui est le défaut sûr.</p>
 */
public final class TravelMaintenanceMode {

    /** Permission dédiée, {@code default: false} dans {@code plugin.yml} — jamais implicite via OP. */
    public static final String PERMISSION = "rpgquest.admin.travel.maintenance";

    /** Durée de validité d'une activation (5 minutes) : assez pour une réparation, trop court pour être oublié. */
    public static final long TTL_MILLIS = 5 * 60_000L;

    private final Map<UUID, Long> activeUntil = new ConcurrentHashMap<>();

    /** @return {@code false} si le joueur n'a pas la permission dédiée (rien n'est activé dans ce cas). */
    public boolean enable(Player player) {
        if (player == null || !player.hasPermission(PERMISSION)) {
            return false;
        }
        activeUntil.put(player.getUniqueId(), System.currentTimeMillis() + TTL_MILLIS);
        return true;
    }

    public void disable(Player player) {
        if (player != null) {
            activeUntil.remove(player.getUniqueId());
        }
    }

    /**
     * Le joueur peut-il, <em>à cet instant</em>, altérer une structure de voyage ? Exige la
     * permission dédiée <strong>et</strong> une activation non expirée.
     */
    public boolean isActive(Player player) {
        if (player == null || !player.hasPermission(PERMISSION)) {
            return false;
        }
        Long until = activeUntil.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (until < System.currentTimeMillis()) {
            activeUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** Millisecondes restantes, ou vide si le mode n'est pas actif. */
    public java.util.Optional<Long> remainingMillis(Player player) {
        return isActive(player)
                ? java.util.Optional.of(activeUntil.get(player.getUniqueId()) - System.currentTimeMillis())
                : java.util.Optional.empty();
    }

    /** Nettoyage à la déconnexion : aucun mode ne survit à une session. */
    public void clear(UUID playerId) {
        if (playerId != null) {
            activeUntil.remove(playerId);
        }
    }
}
