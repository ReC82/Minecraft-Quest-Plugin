package com.lodygames.rpgquest.dialogue.model;

import org.bukkit.NamespacedKey;

public record StartQuestAction(NamespacedKey questId) implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.START_QUEST;
    }
}
