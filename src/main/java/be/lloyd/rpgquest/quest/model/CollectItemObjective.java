package be.lloyd.rpgquest.quest.model;

import org.bukkit.Material;

public record CollectItemObjective(Material material, int amount) implements QuestObjective {

    public CollectItemObjective {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.COLLECT_ITEM;
    }
}
