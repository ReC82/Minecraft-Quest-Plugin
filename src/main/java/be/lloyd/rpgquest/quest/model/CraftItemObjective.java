package be.lloyd.rpgquest.quest.model;

import org.bukkit.Material;

public record CraftItemObjective(Material material, int amount) implements QuestObjective {

    public CraftItemObjective {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.CRAFT_ITEM;
    }
}
