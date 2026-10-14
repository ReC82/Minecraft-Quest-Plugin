package com.lodygames.rpgquest.dialogue.model;

import java.util.List;

/**
 * Issue #158 — point d'entrée conditionnel d'un dialogue ({@code entries:} dans le YAML).
 *
 * <p>À l'ouverture du dialogue, les entrées sont évaluées <strong>dans l'ordre</strong> : la
 * première dont toutes les conditions passent choisit le nœud affiché ; aucune ne passe → le nœud
 * {@code start}. Les conditions sont celles des choix ({@code QUEST_STATE}, {@code QUEST_AVAILABLE},
 * {@code HAS_PENDING_DELIVERY}…), évaluées par le même code : un PNJ peut ainsi accueillir
 * différemment un joueur qui a déjà terminé sa quête, sans dupliquer de règle.</p>
 */
public record DialogueEntry(String nodeId, List<DialogueCondition> conditions) {

    public DialogueEntry {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("une entrée doit désigner un nœud (« node »).");
        }
        conditions = List.copyOf(conditions);
    }
}
