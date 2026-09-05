package com.lodygames.rpgquest.waystone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mission « Waystones Wild » : la décision de génération est déterministe (seed + cellule) et
 * idempotente — couvre {@link WaystoneCellPlanner} en JUnit pur (aucune dépendance Bukkit).
 */
class WaystoneCellPlannerTest {

    private final WaystoneCellPlanner planner = new WaystoneCellPlanner();

    private static WaystoneConfig config(double chance) {
        return new WaystoneConfig(1000L, chance, 300, 16, 3);
    }

    @Test
    void sameSeedAndCellAlwaysProduceTheSameDecisionAndPosition() {
        long seed = 123456789L;
        for (long cx = -3; cx <= 3; cx++) {
            for (long cz = -3; cz <= 3; cz++) {
                Optional<WaystoneCellPlanner.Candidate> a = planner.planCell(seed, cx, cz, config(0.5));
                Optional<WaystoneCellPlanner.Candidate> b = planner.planCell(seed, cx, cz, config(0.5));
                assertEquals(a.isPresent(), b.isPresent(), "présence déterministe pour la cellule " + cx + "," + cz);
                if (a.isPresent()) {
                    assertEquals(a.get().blockX(), b.get().blockX());
                    assertEquals(a.get().blockZ(), b.get().blockZ());
                    assertEquals(a.get().name(), b.get().name());
                }
            }
        }
    }

    @Test
    void differentCellsAreDecidedIndependently() {
        long seed = 42L;
        int present = 0;
        for (long cx = 0; cx < 50; cx++) {
            if (planner.planCell(seed, cx, 0, config(0.5)).isPresent()) {
                present++;
            }
        }
        assertTrue(present > 5 && present < 45, "avec chance=0.5, ~la moitié des cellules ont une Waystone (trouvé " + present + ")");
    }

    @Test
    void chanceZeroNeverGeneratesAndChanceOneAlwaysDoes() {
        for (long cx = 0; cx < 30; cx++) {
            assertFalse(planner.planCell(7L, cx, cx, config(0.0)).isPresent(), "chance=0 : jamais de Waystone");
            assertTrue(planner.planCell(7L, cx, cx, config(1.0)).isPresent(), "chance=1 : toujours une Waystone");
        }
    }

    @Test
    void theCandidateAlwaysFallsInsideItsOwnCell() {
        long size = 1000L;
        for (long cellX = -5; cellX <= 5; cellX++) {
            for (long cellZ = -5; cellZ <= 5; cellZ++) {
                Optional<WaystoneCellPlanner.Candidate> c = planner.planCell(999L, cellX, cellZ, config(1.0));
                assertTrue(c.isPresent());
                assertEquals(cellX, planner.cellOf(c.get().blockX(), size), "blockX doit rester dans la cellule d'origine");
                assertEquals(cellZ, planner.cellOf(c.get().blockZ(), size), "blockZ doit rester dans la cellule d'origine");
            }
        }
    }
}
