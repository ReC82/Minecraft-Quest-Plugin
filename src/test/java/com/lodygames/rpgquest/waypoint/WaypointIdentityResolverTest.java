package com.lodygames.rpgquest.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.waypoint.model.BiomeInstanceKey;
import org.junit.jupiter.api.Test;

/**
 * Issue #124 : « instance de biome » = {@code (world, biomeKey, regionX, regionZ)}. Couvre
 * {@link WaypointIdentityResolver} en JUnit pur — surtout le point critique : jamais un simple
 * {@code biomeType -> waypoint}, deux forêts éloignées produisent deux instances.
 */
class WaypointIdentityResolverTest {

    private static final long REGION = 256L;
    private final WaypointIdentityResolver resolver = new WaypointIdentityResolver();

    @Test
    void samePositionAndBiomeAlwaysResolvesToTheSameInstance() {
        BiomeInstanceKey a = resolver.resolve(REGION, "wild", "minecraft:forest", 100, 100);
        BiomeInstanceKey b = resolver.resolve(REGION, "wild", "minecraft:forest", 130, 140);
        assertEquals(a, b, "deux points de la même tuile + même biome = une seule instance");
        assertEquals(a.serialize(), b.serialize());
        assertEquals(a.waypointId(), b.waypointId());
    }

    @Test
    void twoFarApartZonesOfTheSameBiomeAreDifferentInstances() {
        BiomeInstanceKey near = resolver.resolve(REGION, "wild", "minecraft:forest", 50, 50);
        BiomeInstanceKey far = resolver.resolve(REGION, "wild", "minecraft:forest", 50 + (int) (5 * REGION), 50);
        assertNotEquals(near, far, "deux forêts séparées de plusieurs régions = deux waypoints distincts");
        assertNotEquals(near.waypointId(), far.waypointId());
    }

    @Test
    void differentBiomesInTheSameRegionAreDifferentInstances() {
        BiomeInstanceKey forest = resolver.resolve(REGION, "wild", "minecraft:forest", 10, 10);
        BiomeInstanceKey desert = resolver.resolve(REGION, "wild", "minecraft:desert", 10, 10);
        assertNotEquals(forest, desert);
        assertEquals(forest.regionX(), desert.regionX(), "même tuile…");
        assertNotEquals(forest.serialize(), desert.serialize(), "…mais identité différente car biome différent");
    }

    @Test
    void negativeCoordinatesUseFloorDivisionSoRegionsTileContiguously() {
        assertEquals(-1L, resolver.regionOf(REGION, -1));
        assertEquals(-1L, resolver.regionOf(REGION, -(int) REGION));
        assertEquals(-2L, resolver.regionOf(REGION, -(int) REGION - 1));
        assertEquals(0L, resolver.regionOf(REGION, 0));
        assertEquals(0L, resolver.regionOf(REGION, (int) REGION - 1));
        assertEquals(1L, resolver.regionOf(REGION, (int) REGION));
    }

    @Test
    void adjacentPositionsAcrossARegionBorderResolveToDifferentInstances() {
        BiomeInstanceKey left = resolver.resolve(REGION, "wild", "minecraft:plains", (int) REGION - 1, 0);
        BiomeInstanceKey right = resolver.resolve(REGION, "wild", "minecraft:plains", (int) REGION, 0);
        assertNotEquals(left, right, "compromis documenté : une zone à cheval sur une frontière peut donner deux instances");
    }

    @Test
    void sameInstanceHelperMatchesResolve() {
        assertTrue(resolver.sameInstance(REGION, "wild", "minecraft:forest", 10, 10, "minecraft:forest", 200, 200));
        assertTrue(!resolver.sameInstance(REGION, "wild", "minecraft:forest", 10, 10, "minecraft:forest", 10 + (int) REGION, 10));
    }

    @Test
    void waypointIdIsFilesystemAndSqlSafeAndStable() {
        BiomeInstanceKey key = resolver.resolve(REGION, "wild", "minecraft:old_growth_pine_taiga", -300, 700);
        assertEquals("wp_wild_old_growth_pine_taiga_-2_2", key.waypointId());
        assertEquals("minecraft:old_growth_pine_taiga@-2,2", key.serialize());
    }
}
