package com.lodygames.rpgquest.dialogue.model;

public record VariableEqualsCondition(String key, String value) implements DialogueCondition {

    public VariableEqualsCondition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key ne peut pas être vide.");
        }
        if (value == null) {
            throw new IllegalArgumentException("value ne peut pas être nul.");
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.VARIABLE_EQUALS;
    }
}
