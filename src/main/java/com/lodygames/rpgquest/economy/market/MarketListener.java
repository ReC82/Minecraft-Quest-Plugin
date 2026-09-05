package com.lodygames.rpgquest.economy.market;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Traduit les événements Bukkit en appels à {@link MarketService}. Même
 * protection anti-vol/duplication que {@code QuestJournalListener}/
 * {@code MerchantShopListener} : tout clic ou drag touchant la vitrine
 * ({@link MarketInventoryHolder}) est systématiquement annulé, quel que
 * soit le type de clic, avant même d'interpréter le slot cliqué.
 */
final class MarketListener implements Listener {

    private final MarketService service;

    MarketListener(MarketService service) {
        this.service = service;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketInventoryHolder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        service.handleClick(player, event.getSlot());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketInventoryHolder)) {
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
        if (event.getView().getTopInventory().getHolder() instanceof MarketInventoryHolder
                && event.getPlayer() instanceof Player player) {
            service.handleClose(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }
}
