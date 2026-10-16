package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.waypoint.model.Waypoint;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaypointHintTest {

    private static Waypoint wp(String id, String name, String world, int x, int z, boolean active) {
        return new Waypoint(id, name, world, "b@0,0", "minecraft:plains", 0, 0, x, 64, z, "NORTH", 1, active,
                Instant.EPOCH);
    }

    @Test
    void picksTheNearestActiveWaypointOfTheSameWorld() {
        List<Waypoint> all = List.of(wp("a", "Loin", "wild", 500, 0, true), wp("b", "Proche", "wild", 100, 0, true),
                wp("c", "Désactivé", "wild", 1, 0, false), wp("d", "Autre monde", "other", 0, 0, true));
        WaypointHint hint = WaypointHint.nearest(all, "wild", 0, 0).orElseThrow();
        assertEquals("Proche", hint.waypointName());
        assertEquals(100, hint.distance());
        assertEquals("ouest", hint.direction(), "le boss est à l'ouest du waypoint");
    }

    @Test
    void noWaypointBeyondTheMaximumDistanceIsEverCited() {
        assertTrue(WaypointHint.nearest(List.of(wp("a", "Trop loin", "wild", 5000, 0, true)), "wild", 0, 0).isEmpty());
        assertTrue(WaypointHint.nearest(List.of(), "wild", 0, 0).isEmpty());
    }

    @Test
    void veryCloseWaypointHasNoDirection() {
        assertEquals("", WaypointHint.nearest(List.of(wp("a", "Ici", "wild", 5, 5, true)), "wild", 0, 0)
                .orElseThrow().direction());
    }

    @Test
    void minecraftDirectionsUseNegativeZAsNorth() {
        assertEquals("nord", WaypointHint.direction(0, -100));
        assertEquals("sud", WaypointHint.direction(0, 100));
        assertEquals("est", WaypointHint.direction(100, 0));
        assertEquals("nord-est", WaypointHint.direction(100, -100));
        assertEquals("sud-ouest", WaypointHint.direction(-100, 100));
    }
}
