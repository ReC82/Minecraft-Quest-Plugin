package com.lodygames.rpgquest.quest.progress;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

final class QuestItemPickupListener implements Listener {

    private final QuestProgressEngine engine;

    QuestItemPickupListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            engine.handleCollectItem(player, event.getItem().getItemStack().getType());
        }
    }
}
