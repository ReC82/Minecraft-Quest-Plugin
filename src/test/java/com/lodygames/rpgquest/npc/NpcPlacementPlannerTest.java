package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.NpcPlacementPlanner.BlockKind;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner.Limits;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Recherche d'un emplacement sûr près d'une ancre, sur des mondes en mémoire — donc avec des cas
 * que l'on ne pourrait pas provoquer à la demande sur un vrai serveur : sol absent, lave partout,
 * portail à côté, PNJ déjà posés, budget épuisé.
 */
class NpcPlacementPlannerTest {

    private static final Limits LIMITS = new Limits(4, 2, 2000);

    /** Monde plat : sol plein à y-1, air au-dessus. */
    private static final class FlatWorld implements NpcPlacementPlanner.Probe {
        private final int groundY;
        private final Set<String> npcs = new HashSet<>();
        private final Set<String> blocked = new HashSet<>();
        private final Set<String> liquid = new HashSet<>();
        private final Set<String> portal = new HashSet<>();

        FlatWorld(int groundY) {
            this.groundY = groundY;
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }

        FlatWorld withNpc(int x, int y, int z) {
            npcs.add(key(x, y, z));
            return this;
        }

        FlatWorld withBlock(int x, int y, int z) {
            blocked.add(key(x, y, z));
            return this;
        }

        FlatWorld withLiquid(int x, int y, int z) {
            liquid.add(key(x, y, z));
            return this;
        }

        FlatWorld withPortal(int x, int y, int z) {
            portal.add(key(x, y, z));
            return this;
        }

        @Override
        public BlockKind kindAt(int x, int y, int z) {
            String k = key(x, y, z);
            if (portal.contains(k)) {
                return BlockKind.PORTAL;
            }
            if (liquid.contains(k)) {
                return BlockKind.LIQUID;
            }
            if (blocked.contains(k)) {
                return BlockKind.SOLID;
            }
            if (y == groundY) {
                return BlockKind.SOLID;
            }
            return y > groundY ? BlockKind.PASSABLE : BlockKind.SOLID;
        }

        @Override
        public boolean occupiedByNpc(int x, int y, int z) {
            return npcs.contains(key(x, y, z));
        }
    }

    @Test
    void theAnchorItselfIsUsedWhenItIsFree() {
        NpcPlacementPlanner.Result r =
                NpcPlacementPlanner.findNearest(10, 65, -20, LIMITS, new FlatWorld(64));

        assertTrue(r.found());
        assertEquals(10, r.spot().orElseThrow().x());
        assertEquals(65, r.spot().orElseThrow().y());
        assertEquals(-20, r.spot().orElseThrow().z());
        assertEquals(1, r.inspected(), "l'ancre est le premier candidat examiné");
    }

    @Test
    void anOccupiedAnchorPushesTheSearchToTheNearestFreeCell() {
        FlatWorld world = new FlatWorld(64).withNpc(10, 65, -20);

        NpcPlacementPlanner.Result r = NpcPlacementPlanner.findNearest(10, 65, -20, LIMITS, world);

        assertTrue(r.found());
        NpcPlacementPlanner.Spot spot = r.spot().orElseThrow();
        assertFalse(spot.x() == 10 && spot.z() == -20, "jamais la case déjà occupée");
        assertTrue(Math.abs(spot.x() - 10) <= 1 && Math.abs(spot.z() + 20) <= 1,
                "le plus proche possible : anneau 1");
    }

    @Test
    void aBlockAtHeadHeightIsRefusedEvenWithSolidGround() {
        // Sol bon, mais la case de la tête est pleine : le PNJ ne tient pas.
        FlatWorld world = new FlatWorld(64).withBlock(10, 66, -20);

        NpcPlacementPlanner.Result r = NpcPlacementPlanner.findNearest(10, 65, -20, LIMITS, world);

        assertTrue(r.found());
        assertFalse(r.spot().orElseThrow().x() == 10 && r.spot().orElseThrow().z() == -20);
    }

    @Test
    void liquidIsNeverAGroundNorAPlace() {
        FlatWorld world = new FlatWorld(64).withLiquid(10, 64, -20).withLiquid(10, 65, -20);

        NpcPlacementPlanner.Result r = NpcPlacementPlanner.findNearest(10, 65, -20, LIMITS, world);

        assertTrue(r.found(), "un emplacement voisin reste trouvable");
        assertFalse(r.spot().orElseThrow().x() == 10 && r.spot().orElseThrow().z() == -20,
                "jamais dans le liquide");
    }

    @Test
    void aPortalIsAvoided() {
        FlatWorld world = new FlatWorld(64).withPortal(10, 65, -20);

        NpcPlacementPlanner.Result r = NpcPlacementPlanner.findNearest(10, 65, -20, LIMITS, world);

        assertTrue(r.found());
        assertFalse(r.spot().orElseThrow().x() == 10 && r.spot().orElseThrow().z() == -20);
    }

    @Test
    void aWorldWithoutAnyGroundYieldsNoSafeSpot() {
        NpcPlacementPlanner.Probe voidWorld = new NpcPlacementPlanner.Probe() {
            @Override
            public BlockKind kindAt(int x, int y, int z) {
                return BlockKind.PASSABLE; // que de l'air : aucun sol
            }

            @Override
            public boolean occupiedByNpc(int x, int y, int z) {
                return false;
            }
        };

        NpcPlacementPlanner.Result r = NpcPlacementPlanner.findNearest(0, 70, 0, LIMITS, voidWorld);

        assertFalse(r.found());
        assertEquals(NpcPlacementPlanner.Failure.NO_SAFE_SPOT, r.failure());
        assertTrue(r.inspected() > 0, "la recherche a réellement eu lieu");
    }

    @Test
    void anUnknownWorldYieldsNoSafeSpotRatherThanAGuess() {
        NpcPlacementPlanner.Probe unknown = new NpcPlacementPlanner.Probe() {
            @Override
            public BlockKind kindAt(int x, int y, int z) {
                return BlockKind.UNKNOWN;
            }

            @Override
            public boolean occupiedByNpc(int x, int y, int z) {
                return false;
            }
        };

        assertFalse(NpcPlacementPlanner.findNearest(0, 70, 0, LIMITS, unknown).found());
    }

    @Test
    void theCandidateBudgetIsRespectedAndReported() {
        NpcPlacementPlanner.Result r = NpcPlacementPlanner.findNearest(0, 70, 0,
                new Limits(64, 8, 5), new FlatWorld(64).withNpc(0, 70, 0));

        // Budget volontairement minuscule : la recherche s'arrête, et le dit.
        assertTrue(r.inspected() <= 5, "jamais plus de candidats que le budget");
        if (!r.found()) {
            assertEquals(NpcPlacementPlanner.Failure.BUDGET_EXHAUSTED, r.failure());
        }
    }

    @Test
    void theSearchStaysInsideItsRadius() {
        // Tout est occupé dans un rayon de 3 : avec un rayon de recherche de 2, on échoue.
        FlatWorld world = new FlatWorld(64);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    world.withNpc(dx, 65 + dy, dz);
                }
            }
        }

        NpcPlacementPlanner.Result r =
                NpcPlacementPlanner.findNearest(0, 65, 0, new Limits(2, 2, 100000), world);

        assertFalse(r.found(), "la recherche ne sort pas de son rayon");
        assertEquals(NpcPlacementPlanner.Failure.NO_SAFE_SPOT, r.failure());
    }

    @Test
    void invalidLimitsAreRefusedUpFront() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Limits(-1, 2, 10));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Limits(4, 2, 0));
    }

    @Test
    void acceptabilityRequiresTwoFreeCellsAboveSolidGround() {
        FlatWorld world = new FlatWorld(64);
        assertTrue(NpcPlacementPlanner.isAcceptable(0, 65, 0, world));
        assertFalse(NpcPlacementPlanner.isAcceptable(0, 64, 0, world), "dans le sol");
        assertFalse(NpcPlacementPlanner.isAcceptable(0, 67, 0, world), "sans sol sous les pieds");
    }
}
