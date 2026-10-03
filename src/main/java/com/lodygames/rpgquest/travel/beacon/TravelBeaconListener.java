package com.lodygames.rpgquest.travel.beacon;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Traduit les événements Bukkit en appels à {@link TravelBeaconService} — même patron que
 * {@code ui.QuestJournalListener} : tout clic/drag dans un inventaire du menu de voyage
 * ({@link BeaconMenuHolder}) est systématiquement annulé (aucun objet de menu récupérable ni
 * duplicable), la logique s'exécute ensuite indépendamment de l'annulation.
 */
final class TravelBeaconListener implements Listener {

    private final TravelBeaconService service;

    TravelBeaconListener(TravelBeaconService service) {
        this.service = service;
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
        if (service.handleButtonInteract(event.getPlayer(), block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BeaconMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        if (holder.kind() == BeaconMenuHolder.Kind.SEARCH) {
            if (event.getSlot() == 2) {
                service.handleSearchResultClick(player, event.getCurrentItem());
            }
            return;
        }

        BeaconMenuSession session = service.sessionOf(player);
        if (session == null) {
            return;
        }
        if (holder.kind() == BeaconMenuHolder.Kind.ROOT) {
            switch (event.getSlot()) {
                case 2 -> service.openWaypoints(player, 0, "");
                case 4, 6 -> player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<yellow>Pas encore disponible dans cette version.</yellow>"));
                default -> { }
            }
        } else if (holder.kind() == BeaconMenuHolder.Kind.WAYPOINTS) {
            service.handleWaypointsClick(player, event.getSlot(), session);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BeaconMenuHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (touchesTop) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BeaconMenuHolder
                && event.getPlayer() instanceof Player player) {
            service.handleClose(player);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (event.getInventory().getHolder() instanceof BeaconMenuHolder) {
            service.handlePrepareAnvil(event);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.handleClose(event.getPlayer());
    }
}
