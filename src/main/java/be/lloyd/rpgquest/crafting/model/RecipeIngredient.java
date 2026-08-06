package be.lloyd.rpgquest.crafting.model;

/**
 * Un ingrédient de recette : un objet personnalisé (identifié uniquement via
 * son PersistentDataContainer au moment de la fabrication, jamais son
 * matériau/nom/lore) ou un matériau vanilla brut.
 */
public sealed interface RecipeIngredient permits CustomItemIngredient, VanillaIngredient {
}
