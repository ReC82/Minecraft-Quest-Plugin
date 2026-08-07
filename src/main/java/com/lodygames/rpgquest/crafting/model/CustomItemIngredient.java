package com.lodygames.rpgquest.crafting.model;

import org.bukkit.NamespacedKey;

public record CustomItemIngredient(NamespacedKey itemId) implements RecipeIngredient {
}
