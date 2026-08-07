package be.lloyd.rpgquest.crafting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.crafting.model.CustomItemResult;
import be.lloyd.rpgquest.crafting.model.RecipeDefinition;
import be.lloyd.rpgquest.crafting.model.ShapedRecipeDefinition;
import be.lloyd.rpgquest.crafting.model.ShapelessRecipeDefinition;
import java.io.StringReader;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RecipeDefinitionParserTest {

    private final RecipeDefinitionParser parser = new RecipeDefinitionParser();

    @Test
    void validShapedRecipeParsesSuccessfully() {
        RecipeDefinitionParser.ParseResult result = parser.parse("shaped.yml", load("""
                id: rpgquest:forest_blade_recipe
                type: SHAPED
                result:
                  custom-item: rpgquest:forest_blade
                  amount: 1
                pattern:
                  - " F "
                  - " F "
                  - " S "
                key:
                  F:
                    custom-item: rpgquest:spider_fang
                  S:
                    material: STICK
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        RecipeDefinition recipe = result.recipe();
        assertEquals("rpgquest:forest_blade_recipe", recipe.id().toString());
        assertEquals(new CustomItemResult(org.bukkit.NamespacedKey.fromString("rpgquest:forest_blade"), 1), recipe.result());
        assertTrue(recipe instanceof ShapedRecipeDefinition);
        ShapedRecipeDefinition shaped = (ShapedRecipeDefinition) recipe;
        assertEquals(3, shaped.pattern().size());
        assertEquals(2, shaped.key().size());
    }

    @Test
    void validShapelessRecipeParsesSuccessfully() {
        RecipeDefinitionParser.ParseResult result = parser.parse("shapeless.yml", load("""
                id: rpgquest:refined_crystal_recipe
                type: SHAPELESS
                result:
                  custom-item: rpgquest:refined_crystal
                  amount: 1
                ingredients:
                  - material: QUARTZ
                    amount: 4
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertTrue(result.recipe() instanceof ShapelessRecipeDefinition);
        ShapelessRecipeDefinition shapeless = (ShapelessRecipeDefinition) result.recipe();
        assertEquals(1, shapeless.ingredients().size());
        assertEquals(4, shapeless.ingredients().get(0).amount());
    }

    @Test
    void unknownRecipeTypeIsRejected() {
        RecipeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                type: NOT_A_TYPE
                result:
                  material: STICK
                  amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("inconnu"), result.issues().toString());
    }

    @Test
    void missingTypeIsRejected() {
        RecipeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                result:
                  material: STICK
                  amount: 1
                """));

        assertFalse(result.isSuccess());
    }

    @Test
    void resultWithBothCustomItemAndMaterialIsRejected() {
        RecipeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                type: SHAPELESS
                result:
                  custom-item: rpgquest:refined_crystal
                  material: STICK
                  amount: 1
                ingredients:
                  - material: QUARTZ
                    amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("exactement un"), result.issues().toString());
    }

    @Test
    void shapedRecipeWithPatternCharacterMissingFromKeyIsRejected() {
        RecipeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                type: SHAPED
                result:
                  material: STICK
                  amount: 1
                pattern:
                  - "FS"
                key:
                  F:
                    material: STICK
                """));

        assertFalse(result.isSuccess());
    }

    @Test
    void shapelessRecipeWithoutIngredientsIsRejected() {
        RecipeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                type: SHAPELESS
                result:
                  material: STICK
                  amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("ingredients"), result.issues().toString());
    }

    @Test
    void shapelessRecipeExceedingGridSizeIsRejected() {
        RecipeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                type: SHAPELESS
                result:
                  material: STICK
                  amount: 1
                ingredients:
                  - material: QUARTZ
                    amount: 10
                """));

        assertFalse(result.isSuccess());
    }

    @Test
    void vanillaResultParsesWithMaterial() {
        RecipeDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:sticks
                type: SHAPELESS
                result:
                  material: STICK
                  amount: 4
                ingredients:
                  - material: OAK_PLANKS
                    amount: 2
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(Material.STICK, ((be.lloyd.rpgquest.crafting.model.VanillaResult) result.recipe().result()).material());
    }

    private ConfigurationSection load(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(new StringReader(yaml));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return configuration;
    }
}
