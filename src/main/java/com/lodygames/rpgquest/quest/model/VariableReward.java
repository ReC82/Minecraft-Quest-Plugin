package com.lodygames.rpgquest.quest.model;

/** Écrit {@code key=value} dans {@code player_variables} à l'octroi de la récompense (câblage prévu ultérieurement). */
public record VariableReward(String key, String value) implements QuestReward {

    public VariableReward {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key ne peut pas être vide.");
        }
        if (value == null) {
            throw new IllegalArgumentException("value ne peut pas être nul.");
        }
    }

    @Override
    public RewardType type() {
        return RewardType.VARIABLE;
    }
}
