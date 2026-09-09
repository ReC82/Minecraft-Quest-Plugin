package com.lodygames.rpgquest.waypoint;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Écouteur Bukkit de {@link WaypointService} (issue #124) : entrée dans une instance de biome
 * (mouvement throttlé), interaction explicite avec le bouton, cycle de connexion.
 *
 * <p>{@code PlayerMoveEvent} n'appelle le service qu'au <strong>changement de bloc horizontal</strong>
 * (jamais à chaque micro-mouvement) ; le service applique ensuite son propre throttle temporel et
 * ne fait de recherche terrain qu'une fois par instance — coût amorti, comme les autres écouteurs
 * de déplacement du projet.</p>
 */
final class WaypointListener implements Listener {

    private final WaypointService service;

    WaypointListener(WaypointService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        service.handleMovement(event.getPlayer(), to);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getWorld() == null) {
            return;
        }
        if (service.handleInteract(event.getPlayer(), block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        service.resetPlayerInstance(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        service.resetPlayerInstance(event.getPlayer());
    }
}
