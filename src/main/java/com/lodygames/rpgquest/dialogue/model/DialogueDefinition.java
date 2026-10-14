package com.lodygames.rpgquest.dialogue.model;

import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;

/**
 * {@code entries} (issue #158) : points d'entrée conditionnels évalués avant {@code startNodeId},
 * voir {@link DialogueEntry}. Vide = comportement historique (toujours {@code startNodeId}).
 */
public record DialogueDefinition(NamespacedKey id, String startNodeId, Map<String, DialogueNode> nodes,
                                 List<DialogueEntry> entries) {

    public DialogueDefinition {
        nodes = Map.copyOf(nodes);
        entries = entries == null ? List.of() : List.copyOf(entries);
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
        for (DialogueEntry entry : entries) {
            if (!nodes.containsKey(entry.nodeId())) {
                throw new IllegalArgumentException(
                        "l'entrée vers « " + entry.nodeId() + " » désigne un nœud inexistant (dialogue « " + id + " »).");
            }
        }
    }

    public DialogueDefinition(NamespacedKey id, String startNodeId, Map<String, DialogueNode> nodes) {
        this(id, startNodeId, nodes, List.of());
    }

    public DialogueNode startNode() {
        return nodes.get(startNodeId);
    }

    /** Même dialogue (départ et entrées conservés) avec d'autres nœuds. */
    public DialogueDefinition withNodes(Map<String, DialogueNode> newNodes) {
        return new DialogueDefinition(id, startNodeId, newNodes, entries);
    }
}
