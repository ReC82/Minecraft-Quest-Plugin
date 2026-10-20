package com.lodygames.rpgquest.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.waypoint.WaypointWorldEligibility.Reason;
import java.util.Map;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/** Issue #136 : politique de mondes de la génération automatique de waypoints. */
class WaypointWorldEligibilityTest {

    private static WaypointWorldEligibility.Decision decide(boolean hubEnabled, Map<String, Boolean> worlds,
                                                            String world) {
        return WaypointWorldEligibility.evaluate(true, hubEnabled, worlds, "wild", "world_hub", "claims", world);
    }

    @Test
    void defaultsKeepTodaysBehaviour() {
        assertEquals(Reason.WILD_ROLE, decide(true, Map.of(), "wild").reason());
        assertTrue(decide(true, Map.of(), "wild").allowed());
        assertTrue(decide(true, Map.of(), "world_hub").allowed());
        assertEquals(Reason.HUB_DISABLED, decide(false, Map.of(), "world_hub").reason());
        assertEquals(Reason.CLAIMS_WORLD, decide(true, Map.of(), "claims").reason());
        assertEquals(Reason.NOT_LISTED, decide(true, Map.of(), "evenement").reason());
        assertFalse(decide(true, Map.of(), null).allowed());
    }

    @Test
    void anExplicitEntryWinsOverRolesButNeverOverClaims() {
        assertTrue(decide(true, Map.of("wild_2", true), "wild_2").allowed());
        assertEquals(Reason.LISTED_DISABLED, decide(true, Map.of("wild", false), "wild").reason());
        assertFalse(decide(true, Map.of("world_hub", false), "world_hub").allowed());
        assertTrue(decide(false, Map.of("world_hub", true), "world_hub").allowed());
        assertEquals(Reason.CLAIMS_WORLD, decide(true, Map.of("claims", true), "claims").reason());
    }

    @Test
    void theSwitchDisablesEveryWorld() {
        assertEquals(Reason.SYSTEM_DISABLED, WaypointWorldEligibility.evaluate(false, true,
                Map.of("wild_2", true), "wild", "world_hub", "claims", "wild").reason());
    }

    @Test
    void onlySurfaceDimensionsHaveAPlacementStrategy() {
        assertTrue(WaypointWorldEligibility.supportsSurfacePlacement(World.Environment.NORMAL));
        assertTrue(WaypointWorldEligibility.supportsSurfacePlacement(World.Environment.CUSTOM));
        assertFalse(WaypointWorldEligibility.supportsSurfacePlacement(World.Environment.NETHER));
        assertFalse(WaypointWorldEligibility.supportsSurfacePlacement(World.Environment.THE_END));
    }
}
