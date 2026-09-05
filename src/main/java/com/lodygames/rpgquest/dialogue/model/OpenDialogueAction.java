package com.lodygames.rpgquest.dialogue.model;

import org.bukkit.NamespacedKey;

public record OpenDialogueAction(NamespacedKey dialogueId) implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.OPEN_DIALOGUE;
    }
}
