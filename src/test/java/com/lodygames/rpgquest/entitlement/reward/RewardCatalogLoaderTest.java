package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #422 — validation de entitlements.yml : tout ou rien. */
class RewardCatalogLoaderTest {

    private static RewardCatalogLoader.Result load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return RewardCatalogLoader.load(configuration);
    }

    @Test
    void validCatalogIsLoaded() throws Exception {
        RewardCatalogLoader.Result result = load("""
                enabled: true
                rewards:
                  title_zombie_slayer:
                    label: "Titre"
                    items:
                      - {item: DIAMOND, amount: 3}
                      - {item: "rpgquest:pierre_retour"}
                    perk: {key: title_zombie, tier: GOLD}
                  badge:
                    label: "Badge"
                    perk: {key: badge_zombie, tier: TOP10}
                """);
        assertTrue(result.valid(), result.errors().toString());
        assertTrue(result.catalog().enabled());
        RewardDefinition title = result.catalog().find("title_zombie_slayer").orElseThrow();
        assertEquals(List.of(new RewardDefinition.Item("DIAMOND", 3), new RewardDefinition.Item("rpgquest:pierre_retour", 1)),
                title.items());
        assertTrue(title.items().get(1).custom());
        assertEquals(new RewardDefinition.Perk("title_zombie", "GOLD"), title.perk());
        assertTrue(result.catalog().find("badge").orElseThrow().items().isEmpty());
    }

    @Test
    void enabledDefaultsToFalseAndEmptyFileIsValid() throws Exception {
        RewardCatalogLoader.Result result = load("rewards: {}\n");
        assertTrue(result.valid());
        assertFalse(result.catalog().enabled());
        assertNull(result.catalog().find("x").orElse(null));
    }

    @Test
    void anyErrorDisablesTheWholeFile() throws Exception {
        RewardCatalogLoader.Result result = load("""
                enabled: true
                rewards:
                  good:
                    label: "Bon"
                    items: [{item: DIAMOND, amount: 1}]
                  Bad-Key:
                    label: "x"
                    items: [{item: DIAMOND}]
                  no_label:
                    items: [{item: DIAMOND}]
                  nothing:
                    label: "Rien"
                  bad_item:
                    label: "x"
                    items: [{item: NOT_A_MATERIAL}, {item: AIR}, {item: DIAMOND, amount: 65}, {item: DIAMOND, amount: 1.5}]
                  backpack:
                    label: "Sac"
                    perk: {key: backpack, tier: LARGE}
                """);
        assertFalse(result.valid());
        assertEquals(RewardCatalog.DISABLED, result.catalog());
        assertEquals(8, result.errors().size(), result.errors().toString());
    }

    // ---- issue #177 : tables de récompenses aléatoires, enchantements, potions --------------

    private static final String REWARDS = """
            enabled: true
            rewards:
              gem: {label: "Gemme", items: [{item: EMERALD, amount: 2}]}
              book:
                label: "Livre Tranchant III"
                items: [{item: ENCHANTED_BOOK, enchantments: {sharpness: 3}}]
              potion:
                label: "Potion de force"
                items: [{item: POTION, potion: "minecraft:strength"}]
            """;

    @Test
    void tablesAreLoadedWithExplicitModesAndExactChances() throws Exception {
        RewardCatalogLoader.Result result = load(REWARDS + """
                tables:
                  dragon_loot:
                    label: "Butin du dragon"
                    mode: single
                    entries:
                      - {id: gem, reward: gem, chance: 12.5}
                      - {id: book, reward: book, chance: "0,01"}
                  chest:
                    label: "Coffre"
                    mode: independent
                    enabled: false
                    entries:
                      - {id: a, reward: gem, chance: 80}
                      - {id: b, reward: potion, chance: 70}
                """);
        assertTrue(result.valid(), result.errors().toString());
        RewardTable dragon = result.catalog().findTable("dragon_loot").orElseThrow();
        assertEquals(RewardTable.Mode.SINGLE, dragon.mode());
        assertTrue(dragon.enabled());
        assertEquals(List.of(new RewardTable.Entry("gem", "gem", 1250), new RewardTable.Entry("book", "book", 1)),
                dragon.entries());
        RewardTable chest = result.catalog().findTable("chest").orElseThrow();
        assertFalse(chest.enabled());
        assertEquals(15_000, chest.totalChance(), "la somme peut dépasser 100 % en mode indépendant");
        RewardDefinition.Item book = result.catalog().find("book").orElseThrow().items().get(0);
        assertEquals(java.util.Map.of("sharpness", 3), book.enchantments());
        assertEquals("strength", result.catalog().find("potion").orElseThrow().items().get(0).potion());
    }

    @Test
    void invalidTablesRejectTheWholeFile() throws Exception {
        assertRejected("""
                tables:
                  t: {label: "T", entries: [{id: a, reward: gem, chance: 10}]}
                """, "« mode » obligatoire");
        assertRejected("""
                tables:
                  t:
                    label: "T"
                    mode: single
                    entries: [{id: a, reward: gem, chance: 60}, {id: b, reward: book, chance: 50}]
                """, "ne peut pas dépasser 100 %");
        assertRejected("""
                tables:
                  t: {label: "T", mode: single, entries: [{id: a, reward: nope, chance: 10}]}
                """, "« reward »");
        assertRejected("""
                tables:
                  t: {label: "T", mode: single, entries: [{id: a, reward: gem, chance: 12.345}]}
                """, "« chance »");
        assertRejected("""
                tables:
                  t: {label: "T", mode: single, entries: [{id: a, reward: gem, chance: 0}]}
                """, "« chance »");
        assertRejected("""
                tables:
                  t: {label: "T", mode: independent, entries: [{id: a, reward: gem, chance: 10}, {id: a, reward: book, chance: 10}]}
                """, "en double");
        assertRejected("""
                tables:
                  Bad.Id: {label: "T", mode: single, entries: [{id: a, reward: gem, chance: 10}]}
                """, "identifiant");
    }

    @Test
    void enchantmentsAndPotionsAreValidated() throws Exception {
        assertFalse(load("""
                rewards:
                  x: {label: "X", items: [{item: DIAMOND_SWORD, enchantments: {sharpness: 11}}]}
                """).valid());
        assertFalse(load("""
                rewards:
                  x: {label: "X", items: [{item: "rpgquest:pierre", enchantments: {sharpness: 1}}]}
                """).valid());
        assertFalse(load("""
                rewards:
                  x: {label: "X", items: [{item: DIAMOND, potion: strength}]}
                """).valid());
        assertTrue(load("""
                rewards:
                  x: {label: "X", items: [{item: DIAMOND_SWORD, enchantments: {"minecraft:sharpness": 5, unbreaking: 3}}]}
                """).valid());
    }

    @Test
    void chanceParsingNeverRounds() {
        assertEquals(1250, RewardCatalogLoader.chance(12.5));
        assertEquals(10_000, RewardCatalogLoader.chance(100));
        assertEquals(1, RewardCatalogLoader.chance("0.01"));
        assertNull(RewardCatalogLoader.chance(100.01));
        assertNull(RewardCatalogLoader.chance(0.001));
        assertNull(RewardCatalogLoader.chance(-5));
        assertNull(RewardCatalogLoader.chance("abc"));
        assertNull(RewardCatalogLoader.chance(null));
    }

    private static void assertRejected(String tables, String expected) throws Exception {
        RewardCatalogLoader.Result result = load(REWARDS + tables);
        assertFalse(result.valid());
        assertFalse(result.catalog().enabled());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains(expected)), result.errors().toString());
    }
}
