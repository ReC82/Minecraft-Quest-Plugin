package com.lodygames.rpgquest.resource.model;

import org.bukkit.Material;

public record VanillaItemDrop(Material material, int weight, int minAmount, int maxAmount) implements ResourceDrop {

    public VanillaItemDrop {
        if (weight <= 0) {
            throw new IllegalArgumentException("weight doit être strictement positif : " + weight);
        }
        if (minAmount <= 0 || maxAmount < minAmount) {
            throw new IllegalArgumentException(
                    "minAmount/maxAmount invalides (min=" + minAmount + ", max=" + maxAmount + ")");
        }
    }
}
