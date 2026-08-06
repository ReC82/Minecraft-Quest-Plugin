package be.lloyd.rpgquest.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.item.model.CustomItemDefinition;
import be.lloyd.rpgquest.item.model.CustomItemType;
import be.lloyd.rpgquest.item.model.ItemRarity;
import java.io.StringReader;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Contrairement à {@code QuestDefinitionParserTest}/{@code DialogueDefinitionParserTest},
 * ce parseur a besoin de MockBukkit : {@code Registry.ATTRIBUTE}/{@code Registry.ENCHANTMENT}
 * sont peuplés depuis un serveur Bukkit vivant (contrairement à {@code Material}/
 * {@code EntityType}, de simples enums résolubles sans serveur) — voir docs/ARCHITECTURE.md.
 */
class ItemDefinitionParserTest {

    private final ItemDefinitionParser parser = new ItemDefinitionParser();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void validFileParsesSuccessfully() {
        ItemDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: rpgquest:forest_blade
                type: WEAPON
                material: DIAMOND_SWORD
                name: "<green>Lame de la forêt</green>"
                lore:
                  - "<gray>Une lame légendaire.</gray>"
                rarity: RARE
                stackable: false
                max-durability: 1800
                attributes:
                  - attribute: attack_damage
                    amount: 4.0
                    operation: ADD_NUMBER
                    slot: mainhand
                enchantments:
                  - type: SHARPNESS
                    level: 3
                gameplay-tags:
                  - "starter_weapon"
                special-behavior: "leaf_trail"
                crafting:
                  craftable: false
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        CustomItemDefinition item = result.item();
        assertEquals("rpgquest:forest_blade", item.id().toString());
        assertEquals(CustomItemType.WEAPON, item.type());
        assertEquals(Material.DIAMOND_SWORD, item.baseMaterial());
        assertEquals(ItemRarity.RARE, item.rarity());
        assertFalse(item.stackable());
        assertEquals(1, item.effectiveMaxStackSize());
        assertEquals(1800, item.maxDurability());
        assertEquals(1, item.attributes().size());
        assertEquals(1, item.enchantments().size());
        assertEquals("leaf_trail", item.specialBehavior());
        assertFalse(item.crafting().craftable());
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        ItemDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("""
                stackable: true
                """));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(ItemLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("type"), combined);
        assertTrue(combined.contains("material"), combined);
        assertTrue(combined.contains("name"), combined);
    }

    @Test
    void unknownTypeIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("bad-type.yml", load(minimalItem("""
                type: SPACESHIP
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("« type » inconnu")));
    }

    @Test
    void unknownMaterialIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("bad-material.yml", load(minimalItem("""
                material: NOT_A_REAL_BLOCK
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("matériau inconnu")));
    }

    @Test
    void unknownAttributeIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("bad-attribute.yml", load(minimalItem("""
                attributes:
                  - attribute: not_a_real_attribute
                    amount: 1.0
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("attribut inconnu")));
    }

    @Test
    void unknownEnchantmentIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("bad-enchant.yml", load(minimalItem("""
                enchantments:
                  - type: NOT_A_REAL_ENCHANT
                    level: 1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("enchantement inconnu")));
    }

    @Test
    void stackableItemWithDurabilityIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("bad-combo.yml", load(minimalItem("""
                material: DIAMOND_SWORD
                stackable: true
                max-durability: 100
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("empilable")));
    }

    @Test
    void durabilityOnAMaterialWithoutDurabilityIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("bad-durability.yml", load(minimalItem("""
                material: STONE
                stackable: false
                max-durability: 100
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("durabilité")));
    }

    @Test
    void defaultRarityIsCommonAndDefaultStackableIsTrue() {
        ItemDefinitionParser.ParseResult result = parser.parse("defaults.yml", load(minimalItem("")));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(ItemRarity.COMMON, result.item().rarity());
        assertTrue(result.item().stackable());
    }

    private String minimalItem(String extraYaml) {
        return """
                id: rpgquest:test_item
                type: RESOURCE
                material: STONE
                name: "Test"
                """ + extraYaml;
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
