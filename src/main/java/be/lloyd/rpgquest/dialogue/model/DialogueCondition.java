package be.lloyd.rpgquest.dialogue.model;

public sealed interface DialogueCondition
        permits QuestStateCondition, HasItemCondition, HasPermissionCondition, VariableEqualsCondition {

    ConditionType type();
}
