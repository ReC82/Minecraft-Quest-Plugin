package be.lloyd.rpgquest.dialogue.model;

import org.bukkit.NamespacedKey;

public record AdvanceQuestAction(NamespacedKey questId) implements DialogueAction {

    @Override
    public ActionType type() {
        return ActionType.ADVANCE_QUEST;
    }
}
