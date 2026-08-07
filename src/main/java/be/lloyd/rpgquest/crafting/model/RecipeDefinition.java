package be.lloyd.rpgquest.crafting.model;

import org.bukkit.NamespacedKey;

/**
 * Une recette personnalisée, façonnée ({@link ShapedRecipeDefinition}) ou
 * non ({@link ShapelessRecipeDefinition}). Même discipline « correct par
 * construction » que {@code QuestDefinition}/{@code CustomItemDefinition} :
 * les invariants sont validés par les records eux-mêmes, pas seulement par
 * le parseur.
 */
public sealed interface RecipeDefinition permits ShapedRecipeDefinition, ShapelessRecipeDefinition {

    NamespacedKey id();

    RecipeResult result();
}
