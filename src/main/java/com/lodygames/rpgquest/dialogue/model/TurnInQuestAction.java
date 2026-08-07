package com.lodygames.rpgquest.dialogue.model;

import org.bukkit.NamespacedKey;

public record TurnInQuestAction(NamespacedKey questId) implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.TURN_IN_QUEST;
    }
}
