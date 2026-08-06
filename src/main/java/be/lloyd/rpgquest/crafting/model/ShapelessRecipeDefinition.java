package be.lloyd.rpgquest.crafting.model;

import java.util.List;
import org.bukkit.NamespacedKey;

public record ShapelessRecipeDefinition(
        NamespacedKey id,
        RecipeResult result,
        List<RecipeIngredientEntry> ingredients
) implements RecipeDefinition {

    private static final int MAX_TOTAL_INGREDIENTS = 9; // taille de la grille de craft vanilla (3x3).

    public ShapelessRecipeDefinition {
        if (id == null) {
            throw new IllegalArgumentException("id est obligatoire.");
        }
        if (result == null) {
            throw new IllegalArgumentException("result est obligatoire.");
        }
        if (ingredients == null || ingredients.isEmpty()) {
            throw new IllegalArgumentException("ingredients doit contenir au moins une entrée.");
        }
        int total = ingredients.stream().mapToInt(RecipeIngredientEntry::amount).sum();
        if (total > MAX_TOTAL_INGREDIENTS) {
            throw new IllegalArgumentException(
                    "ingredients : " + total + " exemplaires au total dépasse la grille de fabrication (max "
                            + MAX_TOTAL_INGREDIENTS + ").");
        }
        ingredients = List.copyOf(ingredients);
    }
}
