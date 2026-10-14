package com.lodygames.rpgquest.encounter;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Écouteur Bukkit de {@link EncounterService} (issue #13). {@code PlayerMoveEvent} n'appelle le
 * service qu'au changement de bloc horizontal ; le service applique ensuite son propre throttle.
 * {@code ChunkLoadEvent} ne coûte qu'une lecture de table : seuls les chunks qui portent une
 * rencontre déclenchent quelque chose.
 */
final class EncounterListener implements Listener {

    private final EncounterService service;

    EncounterListener(EncounterService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        service.handleMovement(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        service.handleChunkLoad(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.forgetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        service.forgetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        service.forgetPlayer(event.getPlayer().getUniqueId());
    }
}
