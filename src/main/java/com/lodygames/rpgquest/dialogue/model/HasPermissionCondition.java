package com.lodygames.rpgquest.dialogue.model;

public record HasPermissionCondition(String permission) implements DialogueCondition {

    public HasPermissionCondition {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("permission ne peut pas être vide.");
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.HAS_PERMISSION;
    }
}
