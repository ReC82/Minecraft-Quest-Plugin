package com.lodygames.rpgquest.quest.progress;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

final class QuestBlockBreakListener implements Listener {

    private final QuestProgressEngine engine;

    QuestBlockBreakListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        engine.handleBreakBlock(event.getPlayer(), event.getBlock().getType());
    }
}
