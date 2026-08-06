package be.lloyd.rpgquest.crafting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Vérifie le cœur testable {@link RecipeCraftGuardListener#isValidMatrix}
 * directement (sans passer par un vrai {@code PrepareItemCraftEvent}, dont la
 * construction manuelle ne reflète pas fidèlement le calcul de décalage de
 * motif d'un vrai serveur) — voir la javadoc de la classe.
 */
class RecipeCraftGuardListenerTest {

    private static final NamespacedKey FOREST_BLADE_RECIPE = new NamespacedKey("rpgquest", "forest_blade_recipe");

    @TempDir
    Path tempDir;

    private YamlCustomItemRegistry itemRegistry;
    private YamlCraftingRegistry craftingRegistry;
    private RecipeCraftGuardListener guard;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        itemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), NOPLogger.NOP_LOGGER);
        itemRegistry.start();

        Path recipesDir = tempDir.resolve("recipes");
        Files.createDirectories(recipesDir);
        craftingRegistry = new YamlCraftingRegistry(recipesDir, itemRegistry, NOPLogger.NOP_LOGGER);
        craftingRegistry.start();

        guard = new RecipeCraftGuardListener(craftingRegistry, itemRegistry);
    }

    @AfterEach
    void tearDown() {
        craftingRegistry.stop();
        itemRegistry.stop();
        MockBukkit.unmock();
    }

    @Test
    void matrixWithOnlyExpectedIngredientsIsValid() {
        var definition = craftingRegistry.find(FOREST_BLADE_RECIPE).orElseThrow();
        ItemStack spiderFang = itemRegistry.create(new NamespacedKey("rpgquest", "spider_fang"), 1).orElseThrow();
        ItemStack stick = new ItemStack(Material.STICK);

        assertTrue(guard.isValidMatrix(definition, new ItemStack[]{spiderFang, spiderFang, stick}));
    }

    @Test
    void emptySlotsAreIgnored() {
        var definition = craftingRegistry.find(FOREST_BLADE_RECIPE).orElseThrow();
        ItemStack spiderFang = itemRegistry.create(new NamespacedKey("rpgquest", "spider_fang"), 1).orElseThrow();

        assertTrue(guard.isValidMatrix(definition, new ItemStack[]{null, spiderFang, new ItemStack(Material.AIR)}));
    }

    @Test
    void vanillaLookalikeInPlaceOfACustomIngredientIsRejected() {
        var definition = craftingRegistry.find(FOREST_BLADE_RECIPE).orElseThrow();
        // spider_fang a BONE comme matériau de base : un os vanilla ordinaire ne doit jamais
        // satisfaire le slot qui attend explicitement rpgquest:spider_fang.
        ItemStack fakeSpiderFang = new ItemStack(Material.BONE);

        assertFalse(guard.isValidMatrix(definition, new ItemStack[]{fakeSpiderFang}));
    }

    @Test
    void customItemInPlaceOfAVanillaIngredientIsRejected() {
        var definition = craftingRegistry.find(FOREST_BLADE_RECIPE).orElseThrow();
        // forest_blade_recipe attend un STICK vanilla, pas n'importe quel objet personnalisé.
        ItemStack refinedCrystal = itemRegistry.create(new NamespacedKey("rpgquest", "refined_crystal"), 1).orElseThrow();

        assertFalse(guard.isValidMatrix(definition, new ItemStack[]{refinedCrystal}));
    }

    @Test
    void wrongCustomItemIdIsRejectedEvenIfSameFamily() {
        var definition = craftingRegistry.find(FOREST_BLADE_RECIPE).orElseThrow();
        // Un autre objet personnalisé (pas spider_fang) ne doit pas satisfaire le slot F.
        ItemStack refinedCrystal = itemRegistry.create(new NamespacedKey("rpgquest", "refined_crystal"), 1).orElseThrow();
        ItemStack stick = new ItemStack(Material.STICK);

        assertFalse(guard.isValidMatrix(definition, new ItemStack[]{refinedCrystal, stick}));
    }
}
