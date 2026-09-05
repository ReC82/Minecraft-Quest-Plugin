package com.lodygames.rpgquest.crafting;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Comme {@code YamlCustomItemRegistryTest}, a besoin de MockBukkit : enregistrer
 * une vraie recette Bukkit (et résoudre des {@code ItemStack} personnalisés)
 * nécessite un serveur vivant.
 */
class YamlCraftingRegistryTest {

    private static final NamespacedKey FOREST_BLADE_RECIPE = new NamespacedKey("rpgquest", "forest_blade_recipe");

    @TempDir
    Path tempDir;

    private YamlCustomItemRegistry itemRegistry;
    private YamlCraftingRegistry craftingRegistry;
    private Path recipesDir;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        itemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), NOPLogger.NOP_LOGGER);
        itemRegistry.start();

        recipesDir = tempDir.resolve("recipes");
        Files.createDirectories(recipesDir);
        craftingRegistry = new YamlCraftingRegistry(recipesDir, itemRegistry, NOPLogger.NOP_LOGGER);
    }

    @AfterEach
    void tearDown() {
        craftingRegistry.stop();
        itemRegistry.stop();
        MockBukkit.unmock();
    }

    @Test
    void startGeneratesTheThreeBundledExamplesAndRegistersThemWithBukkit() {
        craftingRegistry.start();

        assertEquals(0, craftingRegistry.lastReport().issues().size(), () -> "issues: " + craftingRegistry.lastReport().issues());
        assertEquals(3, craftingRegistry.recipes().size());
        assertTrue(craftingRegistry.find(FOREST_BLADE_RECIPE).isPresent());
        assertTrue(bukkitHasRecipe(FOREST_BLADE_RECIPE));
    }

    @Test
    void recipeReferencingAnUnknownCustomItemIngredientIsRejected() throws Exception {
        writeRecipe("bad.yml", """
                id: rpgquest:bad_recipe
                type: SHAPELESS
                result:
                  material: STICK
                  amount: 1
                ingredients:
                  - custom-item: rpgquest:does_not_exist
                    amount: 1
                """);

        RecipeLoadReport report = craftingRegistry.reload();

        assertEquals(0, report.loaded().size());
        assertTrue(report.issues().get(0).message().contains("ingrédient personnalisé inconnu"), report.issues().toString());
        assertTrue(craftingRegistry.find(new NamespacedKey("rpgquest", "bad_recipe")).isEmpty());
    }

    @Test
    void recipeReferencingAnUnknownCustomItemResultIsRejected() throws Exception {
        writeRecipe("bad.yml", """
                id: rpgquest:bad_recipe
                type: SHAPELESS
                result:
                  custom-item: rpgquest:does_not_exist
                  amount: 1
                ingredients:
                  - material: STICK
                    amount: 1
                """);

        RecipeLoadReport report = craftingRegistry.reload();

        assertEquals(0, report.loaded().size());
        assertTrue(report.issues().get(0).message().contains("résultat personnalisé inconnu"), report.issues().toString());
    }

    @Test
    void unknownRecipeIdIsNotFound() {
        craftingRegistry.start();

        assertTrue(craftingRegistry.find(new NamespacedKey("rpgquest", "totally_unknown_recipe")).isEmpty(),
                "une recette jamais chargée ne doit jamais être trouvée");
    }

    @Test
    void reloadingTwiceDoesNotThrowOnDuplicateBukkitKeys() {
        craftingRegistry.start();

        assertDoesNotThrow(() -> craftingRegistry.reload(),
                "recharger doit désenregistrer les anciennes recettes avant d'en réenregistrer de nouvelles");
        assertEquals(3, craftingRegistry.recipes().size());
        assertTrue(bukkitHasRecipe(FOREST_BLADE_RECIPE));
    }

    @Test
    void validateDoesNotRegisterAnythingWithBukkit() {
        craftingRegistry.validate();

        assertTrue(craftingRegistry.recipes().isEmpty(), "validate() ne doit toucher à aucun état actif");
        assertFalseBukkitHasRecipe(FOREST_BLADE_RECIPE);
    }

    private boolean bukkitHasRecipe(NamespacedKey key) {
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe instanceof org.bukkit.Keyed keyed && keyed.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private void assertFalseBukkitHasRecipe(NamespacedKey key) {
        assertTrue(!bukkitHasRecipe(key), "aucune recette ne doit être enregistrée avant reload()/start()");
    }

    private void writeRecipe(String fileName, String yaml) throws Exception {
        Files.writeString(recipesDir.resolve(fileName), yaml);
    }
}
