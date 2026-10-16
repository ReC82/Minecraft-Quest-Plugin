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
}
