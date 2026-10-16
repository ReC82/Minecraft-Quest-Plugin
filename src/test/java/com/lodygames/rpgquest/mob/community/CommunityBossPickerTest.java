package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.mob.model.BossMode;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class CommunityBossPickerTest {

    private static SpecialMobDefinition boss(String id, int weight) {
        return new SpecialMobDefinition(new NamespacedKey("rpgquest", id), MobCategory.BOSS, true, EntityType.ZOMBIE,
                id, 0.0, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, BossMode.COMMUNITY, weight);
    }

    @Test
    void emptyListPicksNothing() {
        assertTrue(CommunityBossPicker.pick(List.of(), new SplittableRandom(1)).isEmpty());
    }

    @Test
    void weightsDriveTheDistribution() {
        List<SpecialMobDefinition> defs = List.of(boss("rare", 1), boss("common", 9));
        SplittableRandom random = new SplittableRandom(7);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            counts.merge(CommunityBossPicker.pick(defs, random).orElseThrow().id().getKey(), 1, Integer::sum);
        }
        int rare = counts.getOrDefault("rare", 0);
        assertTrue(rare > 800 && rare < 1200, "≈10 % attendu, obtenu " + rare);
        assertEquals(10_000, rare + counts.getOrDefault("common", 0));
    }
}
