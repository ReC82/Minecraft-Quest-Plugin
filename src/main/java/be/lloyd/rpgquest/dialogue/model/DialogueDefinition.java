package be.lloyd.rpgquest.dialogue.model;

import java.util.Map;
import org.bukkit.NamespacedKey;

public record DialogueDefinition(NamespacedKey id, String startNodeId, Map<String, DialogueNode> nodes) {

    public DialogueDefinition {
        nodes = Map.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("le dialogue « " + id + " » doit avoir au moins un nœud.");
        }
        if (startNodeId == null || startNodeId.isBlank()) {
            throw new IllegalArgumentException("startNodeId ne peut pas être vide (dialogue « " + id + " »).");
        }
        if (!nodes.containsKey(startNodeId)) {
            throw new IllegalArgumentException(
                    "le nœud de départ « " + startNodeId + "» n'existe pas dans le dialogue « " + id + " ».");
        }
    }

    public DialogueNode startNode() {
        return nodes.get(startNodeId);
    }
}
