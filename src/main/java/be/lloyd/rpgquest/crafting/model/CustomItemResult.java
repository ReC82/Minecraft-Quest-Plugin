package be.lloyd.rpgquest.crafting.model;

import org.bukkit.NamespacedKey;

public record CustomItemResult(NamespacedKey itemId, int amount) implements RecipeResult {

    public CustomItemResult {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }
}
