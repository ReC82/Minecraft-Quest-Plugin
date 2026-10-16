package com.lodygames.rpgquest.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class StarterToolKitConfigTest {

    private final StarterToolKitConfig config = new StarterToolKitConfig(true, List.of(
            new StarterKitTier(1, "Débutant", List.of(Material.WOODEN_SWORD), null),
            new StarterKitTier(2, "Premiers pas", List.of(Material.STONE_SWORD), "rpgquest:kit_tier2"),
            new StarterKitTier(3, "Explorateur", List.of(Material.STONE_PICKAXE), "rpgquest:kit_tier3")));

    /** Issue #475 : toute quête de palier est reconnue, quel que soit le palier. */
    @Test
    void theTierUnlockedByAQuestIsFoundForEveryTier() {
        assertEquals(2, config.tierUnlockedBy("rpgquest:kit_tier2").orElseThrow().level());
        assertEquals(3, config.tierUnlockedBy("rpgquest:kit_tier3").orElseThrow().level());
    }

    @Test
    void anOrdinaryQuestUnlocksNoTier() {
        assertTrue(config.tierUnlockedBy("rpgquest:lily_tarte").isEmpty());
        assertTrue(config.tierUnlockedBy("").isEmpty(), "le palier 1 sans quête ne doit jamais correspondre");
    }
}
