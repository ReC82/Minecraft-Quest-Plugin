package com.lodygames.rpgquest.travel;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Écouteur Bukkit de {@link ItemTravelService} — clic droit (air ou bloc), dégâts, déconnexion. */
final class ItemTravelListener implements Listener {

    private final ItemTravelService service;

    ItemTravelListener(ItemTravelService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        service.handleInteract(event.getPlayer(), item);
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
