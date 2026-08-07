package com.lodygames.rpgquest.quest.model;

import org.bukkit.Material;

public record ItemReward(Material material, int amount) implements QuestReward {

    public ItemReward {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public RewardType type() {
        return RewardType.ITEM;
    }
}
