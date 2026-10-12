package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Issue #218 — paliers 3 à 5 du kit de départ : contigus, sans fer, et chaque quête demande
 * EXACTEMENT les matériaux des recettes vanilla des nouveautés de son palier.
 */
class KitTiersContentTest {

    private static YamlConfiguration resource(String path) throws Exception {
        try (var in = KitTiersContentTest.class.getResourceAsStream(path)) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** Recettes vanilla des objets que le kit peut contenir (matériaux par exemplaire). */
    private static final Map<String, Map<String, Integer>> RECIPES = Map.ofEntries(
            Map.entry("STONE_SWORD", Map.of("STICK", 1, "COBBLESTONE", 2)),
            Map.entry("STONE_PICKAXE", Map.of("STICK", 2, "COBBLESTONE", 3)),
            Map.entry("STONE_AXE", Map.of("STICK", 2, "COBBLESTONE", 3)),
            Map.entry("STONE_SHOVEL", Map.of("STICK", 2, "COBBLESTONE", 1)),
            Map.entry("LEATHER_BOOTS", Map.of("LEATHER", 4)),
            Map.entry("LEATHER_HELMET", Map.of("LEATHER", 5)),
            Map.entry("LEATHER_LEGGINGS", Map.of("LEATHER", 7)),
            Map.entry("LEATHER_CHESTPLATE", Map.of("LEATHER", 8)),
            // Exception d'onboarding assumée au palier 2 seulement (graines) ; ensuite, du blé.
            Map.entry("BREAD", Map.of("WHEAT", 3)));

    @Test
    void tiersThreeToFiveAskExactlyTheRecipesOfTheirNewItemsAndNoIron() throws Exception {
        List<Map<?, ?>> tiers = resource("/config.yml").getMapList("starter-tool-kit.tiers");
        assertEquals(5, tiers.size());
        for (int i = 0; i < tiers.size(); i++) {
            assertEquals(i + 1, ((Number) tiers.get(i).get("level")).intValue(), "paliers contigus");
            for (Object item : (List<?>) tiers.get(i).get("items")) {
                assertFalse(String.valueOf(item).matches(".*(IRON|DIAMOND|NETHERITE|GOLD).*"), "pas de " + item);
            }
        }
        for (int tier = 3; tier <= 5; tier++) {
            Map<String, Integer> expected = new LinkedHashMap<>();
            List<String> previous = new java.util.ArrayList<>(((List<?>) tiers.get(tier - 2).get("items")).stream()
                    .map(String::valueOf).toList());
            for (Object raw : (List<?>) tiers.get(tier - 1).get("items")) {
                String item = String.valueOf(raw);
                if (!previous.remove(item)) {
                    RECIPES.get(item).forEach((m, n) -> expected.merge(m, n, Integer::sum));
                }
            }
            YamlConfiguration quest = resource("/quests/kit_tier" + tier + ".yml");
            Map<String, Integer> asked = new LinkedHashMap<>();
            for (Map<?, ?> objective : quest.getMapList("steps.0.objectives").isEmpty()
                    ? stepObjectives(quest) : quest.getMapList("steps.0.objectives")) {
                assertEquals("DELIVER_ITEM_TO_NPC", objective.get("type"), "remise réelle (#123)");
                assertEquals("guide", objective.get("npc"));
                asked.merge(String.valueOf(objective.get("material")), ((Number) objective.get("amount")).intValue(),
                        Integer::sum);
            }
            assertEquals(expected, asked, "palier " + tier);
            assertTrue(quest.getStringList("prerequisites").contains("rpgquest:kit_tier" + (tier - 1)),
                    "impossible de sauter un palier");
            assertTrue(quest.getMapList("rewards").toString().contains("grant-tier %player% " + tier));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> stepObjectives(YamlConfiguration quest) {
        List<Map<?, ?>> steps = quest.getMapList("steps");
        return (List<Map<?, ?>>) steps.get(0).get("objectives");
    }

    @Test
    void theDefaultsMatchTheShippedConfig() throws Exception {
        ConfigurationSection kit = resource("/config.yml").getConfigurationSection("starter-tool-kit");
        assertEquals("rpgquest:kit_tier5", kit.getMapList("tiers").get(4).get("unlock-quest"));
    }
}
