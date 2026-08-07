package com.lodygames.rpgquest.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.item.model.ToolBehavior;
import com.lodygames.rpgquest.item.model.WeaponBehavior;
import java.io.StringReader;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Comme {@code ItemDefinitionParserTest} : besoin de MockBukkit pour la résolution du registre de potions. */
class ItemDefinitionParserBehaviorTest {

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
    void combatSectionIsParsedWithConditionalEffect() {
        ItemDefinitionParser.ParseResult result = parser.parse("weapon.yml", load(minimalWeapon("""
                combat:
                  base-damage: 2.5
                  attack-speed-bonus: 0.3
                  critical-chance: 0.2
                  critical-multiplier: 1.5
                  hit-message: "<red>Coup critique !</red>"
                  particle: CRIT
                  particle-count: 10
                  effect:
                    ability-id: "slow_on_hit"
                    type: SLOWNESS
                    duration-ticks: 60
                    amplifier: 1
                    chance: 0.25
                    cooldown-seconds: 8
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        WeaponBehavior behavior = result.item().weaponBehavior();
        assertNotNull(behavior);
        assertEquals(2.5, behavior.baseDamage());
        assertEquals(0.3, behavior.attackSpeedBonus());
        assertEquals(0.2, behavior.criticalChance());
        assertEquals(1.5, behavior.criticalMultiplier());
        assertNotNull(behavior.conditionalEffect());
        assertEquals("slow_on_hit", behavior.conditionalEffect().abilityId());
        assertEquals(PotionEffectType.SLOWNESS, behavior.conditionalEffect().effectType());
        assertEquals(8000L, behavior.conditionalEffect().cooldownMillis());
        assertNull(result.item().toolBehavior());
    }

    @Test
    void absentCombatSectionLeavesWeaponBehaviorNull() {
        ItemDefinitionParser.ParseResult result = parser.parse("weapon.yml", load(minimalWeapon("")));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertNull(result.item().weaponBehavior());
    }

    @Test
    void invalidCriticalChanceIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("weapon.yml", load(minimalWeapon("""
                combat:
                  critical-chance: 1.5
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("critical-chance")));
    }

    @Test
    void unknownEffectTypeIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("weapon.yml", load(minimalWeapon("""
                combat:
                  effect:
                    type: NOT_A_REAL_EFFECT
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("effect.type inconnu")));
    }

    @Test
    void negativeCooldownIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("weapon.yml", load(minimalWeapon("""
                combat:
                  effect:
                    type: SLOWNESS
                    cooldown-seconds: -5
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("cooldown")));
    }

    @Test
    void toolSectionIsParsedWithAllowedBlocksAndSpecialAbility() {
        ItemDefinitionParser.ParseResult result = parser.parse("tool.yml", load(minimalTool("""
                tool:
                  mining-speed-bonus: 1.5
                  allowed-blocks:
                    - IRON_ORE
                    - GOLD_ORE
                  durability-cost: 2
                  harvest-bonus-chance: 0.2
                  harvest-bonus-amount: 1
                  special-ability:
                    ability-id: "rush"
                    cooldown-seconds: 30
                    activation-message: "<aqua>Ruée !</aqua>"
                    particle: CRIT
                    particle-count: 5
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        ToolBehavior behavior = result.item().toolBehavior();
        assertNotNull(behavior);
        assertEquals(1.5, behavior.miningSpeedBonus());
        assertEquals(2, behavior.allowedBlocks().size());
        assertTrue(behavior.appliesTo(Material.IRON_ORE));
        assertFalse(behavior.appliesTo(Material.DIAMOND_ORE));
        assertEquals(2, behavior.durabilityCost());
        assertNotNull(behavior.specialAbility());
        assertEquals("rush", behavior.specialAbility().abilityId());
        assertEquals(30_000L, behavior.specialAbility().cooldownMillis());
        assertNull(result.item().weaponBehavior());
    }

    @Test
    void emptyAllowedBlocksMeansAllBlocksAllowed() {
        ItemDefinitionParser.ParseResult result = parser.parse("tool.yml", load(minimalTool("""
                tool:
                  durability-cost: 1
                """)));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertTrue(result.item().toolBehavior().appliesTo(Material.DIRT));
        assertTrue(result.item().toolBehavior().appliesTo(Material.DIAMOND_ORE));
    }

    @Test
    void negativeDurabilityCostIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("tool.yml", load(minimalTool("""
                tool:
                  durability-cost: -1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("durability-cost")));
    }

    @Test
    void invalidHarvestBonusChanceIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("tool.yml", load(minimalTool("""
                tool:
                  harvest-bonus-chance: -0.1
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("harvest-bonus-chance")));
    }

    @Test
    void unknownAllowedBlockMaterialIsRejected() {
        ItemDefinitionParser.ParseResult result = parser.parse("tool.yml", load(minimalTool("""
                tool:
                  allowed-blocks:
                    - NOT_A_REAL_BLOCK
                """)));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("allowed-blocks")));
    }

    @Test
    void nonWeaponItemCanStillDeclareCombatSection() {
        // Pas de contrainte de type imposée : donnée pure, cohérente avec le reste du modèle.
        ItemDefinitionParser.ParseResult result = parser.parse("weird.yml", load("""
                id: rpgquest:weird
                type: RESOURCE
                material: STICK
                name: "Test"
                combat:
                  base-damage: 1.0
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertNotNull(result.item().weaponBehavior());
    }

    private String minimalWeapon(String extraYaml) {
        return """
                id: rpgquest:test_weapon
                type: WEAPON
                material: DIAMOND_SWORD
                name: "Test"
                """ + extraYaml;
    }

    private String minimalTool(String extraYaml) {
        return """
                id: rpgquest:test_tool
                type: TOOL
                material: DIAMOND_PICKAXE
                name: "Test"
                """ + extraYaml;
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
