package be.lloyd.rpgquest.resource.model;

import org.bukkit.NamespacedKey;

public record CustomItemDrop(NamespacedKey itemId, int weight, int minAmount, int maxAmount) implements ResourceDrop {

    public CustomItemDrop {
        if (weight <= 0) {
            throw new IllegalArgumentException("weight doit être strictement positif : " + weight);
        }
        if (minAmount <= 0 || maxAmount < minAmount) {
            throw new IllegalArgumentException(
                    "minAmount/maxAmount invalides (min=" + minAmount + ", max=" + maxAmount + ")");
        }
    }
}
