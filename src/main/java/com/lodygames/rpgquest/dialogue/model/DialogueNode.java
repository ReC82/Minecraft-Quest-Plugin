package com.lodygames.rpgquest.dialogue.model;

import com.lodygames.rpgquest.quest.model.LocalizedText;
import java.util.List;

public record DialogueNode(String id, String speaker, LocalizedText text, List<DialogueChoice> choices) {

    public DialogueNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id du nœud ne peut pas être vide.");
        }
        if (speaker == null || speaker.isBlank()) {
            throw new IllegalArgumentException("speaker ne peut pas être vide (nœud « " + id + " »).");
        }
        choices = List.copyOf(choices);
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("le nœud « " + id + " » doit avoir au moins un choix.");
        }
    }
}
