package be.lloyd.rpgquest.crafting.model;

/** Une entrée de la liste d'ingrédients d'une {@link ShapelessRecipeDefinition} : un ingrédient et sa quantité. */
public record RecipeIngredientEntry(RecipeIngredient ingredient, int amount) {

    public RecipeIngredientEntry {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }
}
