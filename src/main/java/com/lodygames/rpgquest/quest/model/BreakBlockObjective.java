package com.lodygames.rpgquest.quest.model;

import org.bukkit.Material;

public record BreakBlockObjective(Material material, int amount) implements QuestObjective {

    public BreakBlockObjective {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.BREAK_BLOCK;
    }
}
