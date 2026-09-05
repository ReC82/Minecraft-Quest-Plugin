package com.lodygames.rpgquest.ui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Clic droit (air ou bloc, main principale) avec le Journal des quêtes → {@link QuestJournalBookService#open}. */
final class QuestJournalBookListener implements Listener {

    private final QuestJournalBookService service;

    QuestJournalBookListener(QuestJournalBookService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack item = event.getItem();
        if (!service.isJournal(item)) {
            return;
        }
        event.setCancelled(true);
        service.open(event.getPlayer());
    }
}
