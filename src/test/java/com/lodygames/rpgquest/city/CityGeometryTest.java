package com.lodygames.rpgquest.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #178 — carré horizontal, coins inclus, altitude ignorée, chevauchement déterministe. */
class CityGeometryTest {

    private static VillageCenter city(String id, String world, double x, double z, int distance) {
        return new VillageCenter(id, id, world, x, 64, z, 0f, 0f, true, Instant.EPOCH, distance);
    }

    @Test
    void defaultDistanceIsFiftyInEachDirection() {
        VillageCenter c = new VillageCenter("v", "V", "world_hub", 100.5, 70, -20.5, 0f, 0f, true, Instant.EPOCH);
        assertEquals(50, c.detectionDistance());
        CityPerimeter.Bounds b = CityPerimeter.bounds(c);
        assertEquals(50, b.minX());
        assertEquals(150, b.maxX());
        assertEquals(-71, b.minZ());
        assertEquals(29, b.maxZ());
        assertEquals(101, b.width(), "50 dans chaque direction + le bloc central");
    }

    @Test
    void boundariesAndCornersAreInsideAndOneBlockFurtherIsOutside() {
        VillageCenter c = city("v", "world_hub", 0.5, 0.5, 50);
        assertTrue(CityPerimeter.contains(c, "world_hub", 50, 0));
        assertTrue(CityPerimeter.contains(c, "world_hub", -50, 0));
        assertTrue(CityPerimeter.contains(c, "world_hub", 0, 50));
        assertTrue(CityPerimeter.contains(c, "world_hub", 0, -50));
        assertTrue(CityPerimeter.contains(c, "world_hub", 50, 50), "coin inclus : ce n'est pas un cercle");
        assertTrue(CityPerimeter.contains(c, "world_hub", -50, -50));
        assertFalse(CityPerimeter.contains(c, "world_hub", 51, 0));
        assertFalse(CityPerimeter.contains(c, "world_hub", 0, -51));
        assertFalse(CityPerimeter.contains(c, "world_hub", 51, 51));
    }

    @Test
    void customSizeAndNegativeCenter() {
        VillageCenter c = city("v", "world_hub", -200.5, -300.5, 12);
        // bloc central = (-201, -301)
        assertTrue(CityPerimeter.contains(c, "world_hub", -213, -313));
        assertTrue(CityPerimeter.contains(c, "world_hub", -189, -289));
        assertFalse(CityPerimeter.contains(c, "world_hub", -214, -301));
        assertFalse(CityPerimeter.contains(c, "world_hub", -201, -288));
    }

    @Test
    void anotherWorldIsNeverInside() {
        VillageCenter c = city("v", "world_hub", 0.5, 0.5, 50);
        assertFalse(CityPerimeter.contains(c, "wild", 0, 0));
        assertTrue(CityIndex.of(List.of(c)).primaryAt("wild", 0, 0).isEmpty());
    }

    @Test
    void distanceLimitsAreValidated() {
        assertTrue(CityPerimeter.checkDistance(4).isPresent());
        assertTrue(CityPerimeter.checkDistance(5).isEmpty());
        assertTrue(CityPerimeter.checkDistance(500).isEmpty());
        assertTrue(CityPerimeter.checkDistance(501).isPresent());
    }

    @Test
    void indexFindsCitiesAcrossCellBoundariesAndIgnoresInactiveOnes() {
        VillageCenter a = city("a", "world_hub", 63.5, 63.5, 50); // déborde sur 4 cellules
        VillageCenter off = new VillageCenter("off", "Off", "world_hub", 1000.5, 64, 1000.5, 0f, 0f,
                false, Instant.EPOCH, 50);
        CityIndex index = CityIndex.of(List.of(a, off));
        assertEquals("a", index.primaryAt("world_hub", 13, 13).orElseThrow().id());
        assertEquals("a", index.primaryAt("world_hub", 113, 113).orElseThrow().id());
        assertTrue(index.primaryAt("world_hub", 114, 63).isEmpty());
        assertTrue(index.primaryAt("world_hub", 1000, 1000).isEmpty(), "ville inactive : jamais détectée");
        assertEquals(1, index.size());
    }

    @Test
    void overlapPicksTheNearestCenterThenTheSmallerCityThenTheId() {
        VillageCenter big = city("big", "world_hub", 0.5, 0.5, 100);
        VillageCenter small = city("small", "world_hub", 60.5, 0.5, 20);
        CityIndex index = CityIndex.of(List.of(big, small));
        assertEquals("small", index.primaryAt("world_hub", 55, 0).orElseThrow().id(), "centre le plus proche");
        assertEquals("big", index.primaryAt("world_hub", 25, 0).orElseThrow().id());
        assertEquals(2, index.containing("world_hub", 45, 0).size());
        assertTrue(CityPerimeter.overlaps(big, small));

        VillageCenter twinA = city("twin_a", "world_hub", 500.5, 500.5, 30);
        VillageCenter twinB = city("twin_b", "world_hub", 500.5, 500.5, 30);
        VillageCenter twinSmall = city("twin_z", "world_hub", 500.5, 500.5, 10);
        CityIndex twins = CityIndex.of(List.of(twinB, twinA, twinSmall));
        assertEquals("twin_z", twins.primaryAt("world_hub", 505, 505).orElseThrow().id(), "même centre : la plus petite");
        assertEquals("twin_a", twins.primaryAt("world_hub", 520, 500).orElseThrow().id(), "puis l'identifiant");
    }

    @Test
    void namesAreCheckedAndIdsAreStableSlugs() {
        assertFalse(CityName.check(" ").ok());
        assertFalse(CityName.check("x".repeat(41)).ok());
        assertEquals("Port Lily", CityName.check("  Port   Lily ").name());
        assertEquals("ville_bourg_neuf", CityName.newId("Bourg-Neuf", id -> false));
        assertEquals("ville_eleonore", CityName.newId("Éléonore", id -> false));
        assertEquals("ville_bourg_neuf_2", CityName.newId("Bourg Neuf", id -> id.equals("ville_bourg_neuf")));
        assertEquals("ville_sans_nom", CityName.newId("!!", id -> false));
    }

    @Test
    void trackerReservesOnceAndReleasesOnFailure() {
        VillageCenter c = city("v", "world_hub", 0.5, 0.5, 50);
        CityIndex index = CityIndex.of(List.of(c));
        CityDiscoveryTracker tracker = new CityDiscoveryTracker();
        java.util.UUID p = java.util.UUID.randomUUID();
        assertTrue(tracker.observe(p, index, "world_hub", 0, 0).isEmpty(), "relevé non chargé : on n'observe pas");
        tracker.loaded(p, List.of());
        assertEquals("v", tracker.observe(p, index, "world_hub", 10, 10).orElseThrow().id());
        assertTrue(tracker.observe(p, index, "world_hub", 11, 10).isEmpty(), "déjà réservée");
        tracker.release(p, "v");
        assertTrue(tracker.observe(p, index, "world_hub", 12, 10).isPresent(), "rendue découvrable après un échec");
        tracker.loaded(p, List.of("v"));
        assertTrue(tracker.observe(p, index, "world_hub", 0, 0).isEmpty(), "déjà découverte en base");
        assertEquals(1, tracker.count(p, id -> true));
    }
}
