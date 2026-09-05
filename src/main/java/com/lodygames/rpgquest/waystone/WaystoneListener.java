package com.lodygames.rpgquest.waystone;

import com.lodygames.rpgquest.waystone.model.Waystone;
import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Écouteur Bukkit de {@link WaystoneService} : chargement de chunk (génération), clic droit, dégâts, connexion. */
final class WaystoneListener implements Listener {

    private final WaystoneService service;

    WaystoneListener(WaystoneService service) {
        this.service = service;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        service.handleChunkLoad(event.getChunk());
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
        Optional<Waystone> waystone = service.waystoneAtBlock(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (waystone.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        service.handleClick(event.getPlayer(), waystone.get());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && service.isChanneling(player.getUniqueId())) {
            service.handleDamage(player);
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
}
