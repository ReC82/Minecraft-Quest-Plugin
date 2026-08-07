package be.lloyd.rpgquest.dialogue.model;

import org.bukkit.Material;

public record HasItemCondition(Material material, int amount) implements DialogueCondition {

    public HasItemCondition {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ConditionType type() {
        return ConditionType.HAS_ITEM;
    }
}
