package com.lodygames.rpgquest.quest.progress;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

final class QuestBlockPlaceListener implements Listener {

    private final QuestProgressEngine engine;

    QuestBlockPlaceListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        engine.handlePlaceBlock(event.getPlayer(), event.getBlockPlaced().getType());
    }
}
