package com.lodygames.rpgquest.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.resource.model.CustomItemDrop;
import com.lodygames.rpgquest.resource.model.ResourceNodeDefinition;
import com.lodygames.rpgquest.resource.model.VanillaItemDrop;
import java.io.StringReader;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ResourceNodeDefinitionParserTest {

    private final ResourceNodeDefinitionParser parser = new ResourceNodeDefinitionParser();

    @Test
    void validFileParsesSuccessfully() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:crystal_ore
                active-material: EMERALD_ORE
                depleted-material: STONE
                required-tools:
                  - IRON_PICKAXE
                respawn-seconds: 300
                drops:
                  - custom-item: rpgquest:refined_crystal
                    weight: 30
                    min-amount: 1
                    max-amount: 1
                  - material: QUARTZ
                    weight: 70
                    min-amount: 1
                    max-amount: 3
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        ResourceNodeDefinition definition = result.definition();
        assertEquals("rpgquest:crystal_ore", definition.id().toString());
        assertEquals(Material.EMERALD_ORE, definition.activeMaterial());
        assertEquals(Material.STONE, definition.depletedMaterial());
        assertEquals(300, definition.respawnSeconds());
        assertEquals(1, definition.requiredTools().size());
        assertFalse(definition.anyToolAllowed());
        assertEquals(2, definition.drops().size());
        assertEquals(new CustomItemDrop(org.bukkit.NamespacedKey.fromString("rpgquest:refined_crystal"), 30, 1, 1),
                definition.drops().get(0));
        assertEquals(new VanillaItemDrop(Material.QUARTZ, 70, 1, 3), definition.drops().get(1));
        assertEquals(100, definition.totalWeight());
    }

    @Test
    void emptyRequiredToolsMeansAnyToolAllowed() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("valid.yml", load(minimalNode()));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertTrue(result.definition().anyToolAllowed());
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("""
                active-material: EMERALD_ORE
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(ResourceNodeLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("depleted-material"), combined);
        assertTrue(combined.contains("respawn-seconds"), combined);
        assertTrue(combined.contains("drops"), combined);
    }

    @Test
    void sameActiveAndDepletedMaterialIsRejected() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("same.yml", load("""
                id: rpgquest:bad
                active-material: STONE
                depleted-material: STONE
                respawn-seconds: 60
                drops:
                  - material: COBBLESTONE
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("différents"), result.issues().toString());
    }

    @Test
    void nonBlockMaterialIsRejected() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                active-material: DIAMOND
                depleted-material: STONE
                respawn-seconds: 60
                drops:
                  - material: COBBLESTONE
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("bloc"), result.issues().toString());
    }

    @Test
    void dropWithBothCustomItemAndMaterialIsRejected() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                active-material: EMERALD_ORE
                depleted-material: STONE
                respawn-seconds: 60
                drops:
                  - custom-item: rpgquest:refined_crystal
                    material: QUARTZ
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("exactement un"), result.issues().toString());
    }

    @Test
    void maxAmountBelowMinAmountIsRejected() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                active-material: EMERALD_ORE
                depleted-material: STONE
                respawn-seconds: 60
                drops:
                  - material: QUARTZ
                    weight: 1
                    min-amount: 5
                    max-amount: 1
                """));

        assertFalse(result.isSuccess());
    }

    @Test
    void unknownMaterialIsRejected() {
        ResourceNodeDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: rpgquest:bad
                active-material: NOT_A_MATERIAL
                depleted-material: STONE
                respawn-seconds: 60
                drops:
                  - material: QUARTZ
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("inconnu"), result.issues().toString());
    }

    private String minimalNode() {
        return """
                id: rpgquest:crystal_ore
                active-material: EMERALD_ORE
                depleted-material: STONE
                respawn-seconds: 300
                drops:
                  - material: QUARTZ
                    weight: 1
                    min-amount: 1
                    max-amount: 1
                """;
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
