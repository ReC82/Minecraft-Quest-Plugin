package be.lloyd.rpgquest.dialogue.model;

import be.lloyd.rpgquest.quest.model.QuestState;
import org.bukkit.NamespacedKey;

public record QuestStateCondition(NamespacedKey questId, QuestState state) implements DialogueCondition {

    @Override
    public ConditionType type() {
        return ConditionType.QUEST_STATE;
    }
}
