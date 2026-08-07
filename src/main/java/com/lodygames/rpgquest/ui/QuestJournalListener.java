package com.lodygames.rpgquest.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Traduit les événements Bukkit en appels à {@link QuestJournalService}.
 * Toute la protection anti-vol/duplication est ici : un clic ou un drag qui
 * touche un inventaire du journal ({@link JournalInventoryHolder}) est
 * systématiquement annulé, quel que soit le type de clic (gauche, droit,
 * shift, double clic, touche numérique, échange main secondaire) — la
 * logique de menu (navigation, détails, suivi) s'exécute ensuite,
 * indépendamment de l'annulation.
 */
final class QuestJournalListener implements Listener {

    private final QuestJournalService service;

    QuestJournalListener(QuestJournalService service) {
        this.service = service;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof JournalInventoryHolder holder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        JournalSession session = service.sessionOf(player);
        if (session == null) {
            return;
        }

        int slot = event.getSlot();
        if (holder.kind() == JournalKind.LIST) {
            service.handleListClick(player, session, slot, event.isRightClick());
        } else {
            service.handleDetailClick(player, session, slot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof JournalInventoryHolder)) {
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
        if (event.getView().getTopInventory().getHolder() instanceof JournalInventoryHolder
                && event.getPlayer() instanceof Player player) {
            service.handleClose(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        service.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        service.handleQuit(event.getPlayer());
    }
}
