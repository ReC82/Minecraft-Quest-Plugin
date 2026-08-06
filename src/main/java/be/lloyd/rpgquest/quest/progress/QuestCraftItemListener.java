package be.lloyd.rpgquest.quest.progress;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;

final class QuestCraftItemListener implements Listener {

    private final QuestProgressEngine engine;

    QuestCraftItemListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            engine.handleCraftItem(player, event.getRecipe().getResult().getType());
        }
    }
}
