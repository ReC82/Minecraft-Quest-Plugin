package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.npc.NpcIdentityService;
import java.util.Optional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Un « PNJ » est une entité vivante marquée par {@link NpcIdentityService}
 * (identifiant stable, indépendant du nom personnalisé affiché — voir
 * docs/ARCHITECTURE.md) via {@code /rpgadmin npc tag}. Le nom affiché reste
 * purement cosmétique : le renommer à l'enclume ne casse jamais le lien avec
 * l'objectif {@code TALK_TO_NPC}.
 */
final class QuestNpcInteractListener implements Listener {

    private final QuestProgressEngine engine;
    private final NpcIdentityService npcIdentityService;

    QuestNpcInteractListener(QuestProgressEngine engine, NpcIdentityService npcIdentityService) {
        this.engine = engine;
        this.npcIdentityService = npcIdentityService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Optional<String> npcId = npcIdentityService.currentId(event.getRightClicked());
        if (npcId.isEmpty()) {
            return;
        }
        engine.handleTalkToNpc(event.getPlayer(), npcId.get());
    }
}
