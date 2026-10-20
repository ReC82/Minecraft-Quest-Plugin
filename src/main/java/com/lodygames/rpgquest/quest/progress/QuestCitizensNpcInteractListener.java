package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.npc.NpcIdentityService;
import java.util.Optional;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Équivalent de {@link QuestNpcInteractListener} pour un PNJ Citizens.
 * Citizens gère lui-même la détection de clic sur ses entités et ne fait pas
 * toujours propager {@code PlayerInteractEntityEvent} de façon fiable pour
 * elles ; l'objectif {@code TALK_TO_NPC} d'un PNJ Citizens est donc détecté
 * via {@link NPCRightClickEvent}, l'événement dédié de CitizensAPI.
 *
 * <p>Cette classe référence un type Citizens ({@code NPCRightClickEvent}) :
 * elle n'est donc construite et enregistrée que si Citizens est réellement
 * présent et actif, voir {@link NpcIdentityService#citizensAvailable()} et
 * le câblage dans {@code QuestProgressEngine}/{@code RPGQuestBootstrap}.</p>
 */
final class QuestCitizensNpcInteractListener implements Listener {

    private final QuestProgressEngine engine;
    private final NpcIdentityService npcIdentityService;

    QuestCitizensNpcInteractListener(QuestProgressEngine engine, NpcIdentityService npcIdentityService) {
        this.engine = engine;
        this.npcIdentityService = npcIdentityService;
    }

    // LOW : l'objectif progresse AVANT l'ouverture du dialogue du même clic (priorité NORMAL), qui
    // affiche donc l'état à jour — quête rendue, plus « Où en est ma tâche ? » (#13, TC-306).
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(NPCRightClickEvent event) {
        Optional<String> npcId = npcIdentityService.currentId(event.getNPC().getEntity());
        if (npcId.isEmpty()) {
            return;
        }
        engine.handleTalkToNpc(event.getClicker(), npcId.get());
    }
}
