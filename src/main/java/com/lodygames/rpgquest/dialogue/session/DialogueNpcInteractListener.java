package com.lodygames.rpgquest.dialogue.session;

import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Un « PNJ » est une entité vivante marquée par {@link NpcIdentityService}
 * (même identifiant stable que pour {@code TALK_TO_NPC}, voir
 * {@code com.lodygames.rpgquest.quest.progress.QuestNpcInteractListener}) :
 * cliquer sur une entité marquée ouvre le dialogue dont l'id correspond à son
 * identifiant stable (namespace par défaut {@code rpgquest}). Contrairement à
 * l'ancienne implémentation, cette clé n'est **jamais** construite à partir
 * d'un texte choisi librement pour l'affichage — {@link NpcIdentityService}
 * garantit que l'identifiant est déjà un fragment valide de
 * {@link NamespacedKey}, ce qui élimine l'{@code IllegalArgumentException}
 * historique (« Non [a-z0-9_-./] character in key ») déclenchée par un nom
 * personnalisé contenant une majuscule ou une espace.
 *
 * <p>Ne traite jamais un PNJ Citizens (voir {@link DialogueCitizensNpcInteractListener})
 * : Citizens ne fait pas toujours propager {@code PlayerInteractEntityEvent}
 * de façon fiable pour ses propres entités.</p>
 */
final class DialogueNpcInteractListener implements Listener {

    private static final String DEFAULT_NAMESPACE = "rpgquest";

    private final DialogueSessionEngine sessionEngine;
    private final YamlDialogueEngine dialogueEngine;
    private final NpcIdentityService npcIdentityService;

    DialogueNpcInteractListener(DialogueSessionEngine sessionEngine, YamlDialogueEngine dialogueEngine,
                                 NpcIdentityService npcIdentityService) {
        this.sessionEngine = sessionEngine;
        this.dialogueEngine = dialogueEngine;
        this.npcIdentityService = npcIdentityService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (npcIdentityService.isCitizensNpc(event.getRightClicked())) {
            return;
        }
        Optional<String> npcId = npcIdentityService.currentId(event.getRightClicked());
        if (npcId.isEmpty()) {
            return;
        }
        NamespacedKey dialogueId = new NamespacedKey(DEFAULT_NAMESPACE, npcId.get());
        if (dialogueEngine.find(dialogueId).isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        sessionEngine.open(player, dialogueId);
    }
}
