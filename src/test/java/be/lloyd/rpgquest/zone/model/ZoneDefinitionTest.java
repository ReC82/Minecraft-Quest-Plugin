package be.lloyd.rpgquest.zone.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZoneDefinitionTest {

    private final ZoneDefinition zone = new ZoneDefinition(
            "central_village", "world", -10, 0, -10, 10, 255, 10, ZoneFlags.defaults());

    @Test
    void containsIsTrueForInteriorPosition() {
        assertTrue(zone.contains("world", 0, 64, 0), "intérieur");
    }

    @Test
    void containsIsTrueForBorderPosition() {
        assertTrue(zone.contains("world", 10, 64, 10), "les bornes max sont incluses");
        assertTrue(zone.contains("world", -10, 64, -10), "les bornes min sont incluses");
    }

    @Test
    void containsIsFalseForExteriorPosition() {
        assertFalse(zone.contains("world", 11, 64, 0), "extérieur");
        assertFalse(zone.contains("world", 0, 64, -11), "extérieur");
    }

    @Test
    void containsIsFalseForADifferentWorld() {
        assertFalse(zone.contains("world_nether", 0, 64, 0));
    }

    @Test
    void rejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneDefinition("bad", "world", 10, 0, 0, -10, 10, 10, ZoneFlags.defaults()));
    }

    @Test
    void rejectsBlankId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneDefinition("", "world", 0, 0, 0, 10, 10, 10, ZoneFlags.defaults()));
    }

    @Test
    void rejectsUppercaseOrSpecialCharactersInId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneDefinition("Central Village!", "world", 0, 0, 0, 10, 10, 10, ZoneFlags.defaults()));
    }

    @Test
    void overlapsDetectsIntersectingCuboidsInTheSameWorld() {
        ZoneDefinition other = new ZoneDefinition("other", "world", 5, 0, 5, 20, 255, 20, ZoneFlags.defaults());
        assertTrue(zone.overlaps(other));
        assertTrue(other.overlaps(zone), "symétrique");
    }

    @Test
    void overlapsIsFalseForDisjointCuboids() {
        ZoneDefinition other = new ZoneDefinition("other", "world", 100, 0, 100, 120, 255, 120, ZoneFlags.defaults());
        assertFalse(zone.overlaps(other));
    }

    @Test
    void overlapsIsFalseAcrossDifferentWorlds() {
        ZoneDefinition other = new ZoneDefinition("other", "world_nether", -10, 0, -10, 10, 255, 10, ZoneFlags.defaults());
        assertFalse(zone.overlaps(other));
    }
}
