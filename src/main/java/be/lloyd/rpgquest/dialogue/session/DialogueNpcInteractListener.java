package be.lloyd.rpgquest.dialogue.session;

import be.lloyd.rpgquest.dialogue.YamlDialogueEngine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Aucun système de PNJ dédié n'existe (comme pour {@code TALK_TO_NPC} des
 * quêtes, voir docs/ARCHITECTURE.md) : cliquer sur n'importe quelle entité
 * vivante dont le nom personnalisé correspond à un id de dialogue chargé
 * ouvre ce dialogue — même convention de nommage que les objectifs
 * {@code TALK_TO_NPC}, pour qu'un même PNJ renommé serve les deux usages.
 */
final class DialogueNpcInteractListener implements Listener {

    private static final String DEFAULT_NAMESPACE = "rpgquest";

    private final DialogueSessionEngine sessionEngine;
    private final YamlDialogueEngine dialogueEngine;

    DialogueNpcInteractListener(DialogueSessionEngine sessionEngine, YamlDialogueEngine dialogueEngine) {
        this.sessionEngine = sessionEngine;
        this.dialogueEngine = dialogueEngine;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Component customName = event.getRightClicked().customName();
        if (customName == null) {
            return;
        }
        String raw = PlainTextComponentSerializer.plainText().serialize(customName);
        NamespacedKey dialogueId = raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
        if (dialogueId == null || dialogueEngine.find(dialogueId).isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        sessionEngine.open(player, dialogueId);
    }
}
