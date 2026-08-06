package be.lloyd.rpgquest.crafting;

import be.lloyd.rpgquest.crafting.model.CustomItemIngredient;
import be.lloyd.rpgquest.crafting.model.RecipeDefinition;
import be.lloyd.rpgquest.crafting.model.RecipeIngredient;
import be.lloyd.rpgquest.crafting.model.ShapedRecipeDefinition;
import be.lloyd.rpgquest.crafting.model.ShapelessRecipeDefinition;
import be.lloyd.rpgquest.crafting.model.VanillaIngredient;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/**
 * Défense en profondeur en plus du {@link org.bukkit.inventory.RecipeChoice.ExactChoice}
 * déjà posé par {@link YamlCraftingRegistry} sur chaque ingrédient personnalisé :
 * revalide, à chaque préparation de fabrication (clic, shift-clic, recette
 * automatique via le livre de recettes — tous déclenchent {@link
 * PrepareItemCraftEvent}), que chaque objet non vide de la grille correspond
 * bien à un ingrédient réellement attendu par la recette matchée par Bukkit.
 * Pas de suivi positionnel exact du motif (Bukkit décale librement un
 * pattern SHAPED dans la grille 3x3) : la vérification porte sur
 * l'<em>ensemble</em> des identités attendues, ce qui suffit à rejeter un
 * substitut vanilla pour un ingrédient personnalisé (ou l'inverse) sans
 * réimplémenter le calcul de décalage de Bukkit.
 */
public final class RecipeCraftGuardListener implements Listener {

    private final YamlCraftingRegistry craftingRegistry;
    private final YamlCustomItemRegistry itemRegistry;

    public RecipeCraftGuardListener(YamlCraftingRegistry craftingRegistry, YamlCustomItemRegistry itemRegistry) {
        this.craftingRegistry = craftingRegistry;
        this.itemRegistry = itemRegistry;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed)) {
            return; // recette vanilla, hors du périmètre de ce garde.
        }
        Optional<RecipeDefinition> definition = craftingRegistry.find(keyed.getKey());
        if (definition.isEmpty()) {
            return; // pas une de nos recettes.
        }

        if (!isValidMatrix(definition.get(), event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    /**
     * Cœur testable de la vérification, indépendant de {@link PrepareItemCraftEvent} : {@code true}
     * si chaque objet non vide de {@code matrix} correspond à une identité réellement attendue par
     * {@code definition} (objet personnalisé attendu ↔ id exact, matériau vanilla attendu ↔ matériau
     * exact — jamais l'inverse).
     */
    boolean isValidMatrix(RecipeDefinition definition, ItemStack[] matrix) {
        Set<NamespacedKey> allowedCustomIds = new HashSet<>();
        Set<Material> allowedMaterials = new HashSet<>();
        collectAllowed(definition, allowedCustomIds, allowedMaterials);

        for (ItemStack stack : matrix) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            Optional<NamespacedKey> customId = itemRegistry.identify(stack);
            boolean valid = customId.isPresent()
                    ? allowedCustomIds.contains(customId.get())
                    : allowedMaterials.contains(stack.getType());
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private void collectAllowed(RecipeDefinition definition, Set<NamespacedKey> customIds, Set<Material> materials) {
        switch (definition) {
            case ShapedRecipeDefinition shaped -> shaped.key().values().forEach(i -> classify(i, customIds, materials));
            case ShapelessRecipeDefinition shapeless ->
                    shapeless.ingredients().forEach(e -> classify(e.ingredient(), customIds, materials));
        }
    }

    private void classify(RecipeIngredient ingredient, Set<NamespacedKey> customIds, Set<Material> materials) {
        switch (ingredient) {
            case CustomItemIngredient custom -> customIds.add(custom.itemId());
            case VanillaIngredient vanilla -> materials.add(vanilla.material());
        }
    }
}
