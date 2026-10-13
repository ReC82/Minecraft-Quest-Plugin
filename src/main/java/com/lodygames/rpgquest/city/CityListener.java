package com.lodygames.rpgquest.city;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Plomberie d'événements de la découverte des villes (issue #178). Les décisions sont prises par
 * {@link CityDiscoveryTracker} et {@link CityIndex}, testables sans serveur.
 *
 * <p>{@code MONITOR} et {@code ignoreCancelled} : on observe un déplacement qui a réellement eu
 * lieu, jamais un déplacement qu'un autre plugin a refusé.</p>
 */
public final class CityListener implements Listener {

    private final CityDiscoveryService service;

    CityListener(CityDiscoveryService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        // Seul un changement de colonne (X/Z) ou de monde peut changer de ville : l'altitude est
        // ignorée par la règle, et la tête qui tourne ne coûte rien.
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()
                && to.getWorld() == from.getWorld()) {
            return;
        }
        service.check(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        service.check(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.load(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        service.check(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }
}
