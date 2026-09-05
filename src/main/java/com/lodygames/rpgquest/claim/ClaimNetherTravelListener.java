package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.config.ClaimConfig;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Mission « bloquer le Nether depuis {@code claims} » : tant que le rôle futur du Nether n'est pas
 * décidé, un portail Nether activé <strong>depuis</strong> le monde des claims est refusé —
 * construire un cadre en obsidienne dans son claim reste autorisé (aucun événement de construction
 * touché ici), seul le voyage lui-même est bloqué.
 *
 * <p>Ne réagit qu'à {@link PlayerPortalEvent} avec {@link PlayerTeleportEvent.TeleportCause#NETHER_PORTAL}
 * et {@code event.getFrom()} dans le monde des claims — un portail Nether utilisé pour
 * <strong>revenir</strong> vers {@code claims} (départ depuis le Nether) n'est jamais concerné, ni
 * un portail dans un autre monde, ni les portails RPGQuest ({@code travel.WorldPortalTeleportListener}
 * utilise {@link PlayerTeleportEvent} avec la cause {@code PLUGIN}, jamais {@code NETHER_PORTAL} —
 * aucune interférence possible). Bascule via {@link ClaimConfig#blockNetherTravel()} : {@code
 * false} réautorise instantanément sans changement de code.</p>
 */
public final class ClaimNetherTravelListener implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Supplier<ClaimConfig> config;

    public ClaimNetherTravelListener(Supplier<ClaimConfig> config) {
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!config.get().blockNetherTravel() || event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }
        World from = event.getFrom().getWorld();
        if (from == null || !from.getName().equals(config.get().world())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(MM.deserialize(
                "<gray><italic>Une force mystérieuse empêche l'ouverture d'un portail depuis ce monde.</italic></gray>"));
    }
}
