package com.lodygames.rpgquest.dialogue.model;

public sealed interface DialogueCondition
        permits QuestStateCondition, HasItemCondition, HasPermissionCondition, VariableEqualsCondition,
        NoMainClaimCondition, HasMainClaimCondition, LacksCustomItemCondition, NegatedCondition {

    ConditionType type();
}
