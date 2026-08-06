package be.lloyd.rpgquest.quest.progress;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Aucun système de PNJ dédié n'existe encore (voir docs/ARCHITECTURE.md) :
 * un « PNJ » est ici simplement une entité vivante dont le nom personnalisé
 * (renommé à l'enclume, ou via {@code /data merge entity ... CustomName})
 * correspond à l'identifiant configuré dans l'objectif {@code TALK_TO_NPC}.
 */
final class QuestNpcInteractListener implements Listener {

    private final QuestProgressEngine engine;

    QuestNpcInteractListener(QuestProgressEngine engine) {
        this.engine = engine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Component customName = event.getRightClicked().customName();
        if (customName == null) {
            return;
        }
        String npcId = PlainTextComponentSerializer.plainText().serialize(customName);
        engine.handleTalkToNpc(event.getPlayer(), npcId);
    }
}
