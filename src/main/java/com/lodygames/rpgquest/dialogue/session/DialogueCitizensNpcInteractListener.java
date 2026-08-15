package com.lodygames.rpgquest.dialogue.session;

import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import java.util.Optional;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Équivalent de {@link DialogueNpcInteractListener} pour un PNJ Citizens :
 * Citizens ne fait pas toujours propager {@code PlayerInteractEntityEvent}
 * de façon fiable pour ses propres entités, donc l'ouverture d'un dialogue
 * sur un PNJ Citizens est détectée via {@code NPCRightClickEvent}, l'événement
 * dédié de CitizensAPI.
 *
 * <p>Cette classe référence un type Citizens : elle n'est construite et
 * enregistrée que si Citizens est réellement présent et actif, voir
 * {@link NpcIdentityService#citizensAvailable()} et
 * {@code DialogueSessionEngine#citizensNpcInteractListener()}.</p>
 */
final class DialogueCitizensNpcInteractListener implements Listener {

    private static final String DEFAULT_NAMESPACE = "rpgquest";

    private final DialogueSessionEngine sessionEngine;
    private final YamlDialogueEngine dialogueEngine;
    private final NpcIdentityService npcIdentityService;

    DialogueCitizensNpcInteractListener(DialogueSessionEngine sessionEngine, YamlDialogueEngine dialogueEngine,
                                         NpcIdentityService npcIdentityService) {
        this.sessionEngine = sessionEngine;
        this.dialogueEngine = dialogueEngine;
        this.npcIdentityService = npcIdentityService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(NPCRightClickEvent event) {
        Optional<String> npcId = npcIdentityService.currentId(event.getNPC().getEntity());
        if (npcId.isEmpty()) {
            return;
        }
        NamespacedKey dialogueId = new NamespacedKey(DEFAULT_NAMESPACE, npcId.get());
        if (dialogueEngine.find(dialogueId).isEmpty()) {
            return;
        }
        Player player = event.getClicker();
        sessionEngine.open(player, dialogueId);
    }
}
