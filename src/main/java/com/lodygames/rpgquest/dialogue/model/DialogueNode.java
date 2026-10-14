package com.lodygames.rpgquest.dialogue.model;

import com.lodygames.rpgquest.quest.model.LocalizedText;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code variants} (issue #158) : répliques alternatives au {@code text}. Le texte affiché est tiré
 * parmi {@link #lines()} ({@code text} puis les variantes) en évitant de répéter immédiatement la
 * même réplique au même joueur — voir {@code dialogue.session.DialogueLineRotation}.
 */
public record DialogueNode(String id, String speaker, LocalizedText text, List<DialogueChoice> choices,
                           List<LocalizedText> variants) {

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
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    public DialogueNode(String id, String speaker, LocalizedText text, List<DialogueChoice> choices) {
        this(id, speaker, text, choices, List.of());
    }

    /** Toutes les répliques possibles du nœud : {@code text} d'abord, puis les variantes. */
    public List<LocalizedText> lines() {
        List<LocalizedText> lines = new ArrayList<>(1 + variants.size());
        lines.add(text);
        lines.addAll(variants);
        return List.copyOf(lines);
    }

    /** Même nœud (choix et variantes conservés) avec un autre locuteur / texte. */
    public DialogueNode withSpeakerAndText(String newSpeaker, LocalizedText newText) {
        return new DialogueNode(id, newSpeaker, newText, choices, variants);
    }

    /** Même nœud (texte et variantes conservés) avec d'autres choix. */
    public DialogueNode withChoices(List<DialogueChoice> newChoices) {
        return new DialogueNode(id, speaker, text, newChoices, variants);
    }
}
