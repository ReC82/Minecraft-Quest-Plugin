package com.lodygames.rpgquest.crafting.model;

import org.bukkit.Material;

public record VanillaResult(Material material, int amount) implements RecipeResult {

    public VanillaResult {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }
}
