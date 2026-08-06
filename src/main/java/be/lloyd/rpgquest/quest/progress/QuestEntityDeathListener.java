package be.lloyd.rpgquest.quest.progress;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

final class QuestEntityDeathListener implements Listener {

    private final QuestProgressEngine engine;

    QuestEntityDeathListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() instanceof Player killer) {
            engine.handleKillEntity(killer, event.getEntityType());
        }
    }
}
