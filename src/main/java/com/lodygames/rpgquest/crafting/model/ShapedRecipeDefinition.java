package com.lodygames.rpgquest.crafting.model;

import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;

public record ShapedRecipeDefinition(
        NamespacedKey id,
        RecipeResult result,
        List<String> pattern,
        Map<Character, RecipeIngredient> key
) implements RecipeDefinition {

    public ShapedRecipeDefinition {
        if (id == null) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (result == null) {
            throw new IllegalArgumentException("result est obligatoire.");
        }
        if (pattern == null || pattern.isEmpty() || pattern.size() > 3) {
            throw new IllegalArgumentException("pattern doit contenir entre 1 et 3 lignes.");
        }
        int width = pattern.get(0).length();
        if (width == 0 || width > 3) {
            throw new IllegalArgumentException("chaque ligne de pattern doit contenir entre 1 et 3 caractères.");
        }
        for (String row : pattern) {
            if (row.length() != width) {
                throw new IllegalArgumentException("toutes les lignes de pattern doivent avoir la même longueur.");
            }
        }
        key = Map.copyOf(key == null ? Map.of() : key);
        for (String row : pattern) {
            for (char c : row.toCharArray()) {
                if (c != ' ' && !key.containsKey(c)) {
                    throw new IllegalArgumentException(
                            "le caractère « " + c + "» du pattern n'a pas d'entrée correspondante dans « key ».");
                }
            }
        }
        pattern = List.copyOf(pattern);
    }
}
