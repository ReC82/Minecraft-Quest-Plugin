package be.lloyd.rpgquest.quest.progress;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

final class QuestBlockPlaceListener implements Listener {

    private final QuestProgressEngine engine;

    QuestBlockPlaceListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        engine.handlePlaceBlock(event.getPlayer(), event.getBlockPlaced().getType());
    }
}
