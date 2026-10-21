package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.ArrivalSafety.Spot;
import com.lodygames.rpgquest.travel.ArrivalSafety.Verdict;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Issue #566 : sécurité d'un point fixe, sur une vue de blocs simulée (aucun monde réel). */
class ArrivalSafetyTest {

    /** Sol de pierre en y = 63, air au-dessus ; blocs posés à la main. Bordure : |x|, |z| ≤ 1000. */
    static final class FakeView implements ArrivalSafety.BlockView {

        final Map<String, Material> blocks = new HashMap<>();

        FakeView set(int x, int y, int z, Material m) {
            blocks.put(x + "," + y + "," + z, m);
            return this;
        }

        @Override
        public Material type(int x, int y, int z) {
            Material m = blocks.get(x + "," + y + "," + z);
            if (m != null) {
                return m;
            }
            return y <= 63 ? Material.STONE : Material.AIR;
        }

        @Override
        public int minHeight() {
            return -64;
        }

        @Override
        public int maxHeight() {
            return 320;
        }

        @Override
        public boolean insideBorder(int x, int z) {
            return Math.abs(x) <= 1000 && Math.abs(z) <= 1000;
        }
    }

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void aPointOnSolidGroundWithRoomIsSafe() {
        assertEquals(Verdict.SAFE, ArrivalSafety.check(new FakeView(), 10, 64, 10));
        assertEquals(Optional.of(new Spot(10, 64, 10)), ArrivalSafety.findNear(new FakeView(), 10, 64, 10));
    }

    @Test
    void eachUnsafeCaseHasItsOwnVerdict() {
        assertEquals(Verdict.DANGEROUS, ArrivalSafety.check(new FakeView().set(0, 63, 0, Material.LAVA), 0, 64, 0));
        assertEquals(Verdict.DANGEROUS, ArrivalSafety.check(new FakeView().set(0, 64, 0, Material.FIRE), 0, 64, 0));
        assertEquals(Verdict.NO_GROUND, ArrivalSafety.check(new FakeView(), 0, 70, 0));
        assertEquals(Verdict.BLOCKED, ArrivalSafety.check(new FakeView(), 0, 63, 0));
        assertEquals(Verdict.BLOCKED, ArrivalSafety.check(new FakeView().set(0, 65, 0, Material.STONE), 0, 64, 0));
        assertEquals(Verdict.OUT_OF_HEIGHT, ArrivalSafety.check(new FakeView(), 0, -64, 0));
        assertEquals(Verdict.OUT_OF_HEIGHT, ArrivalSafety.check(new FakeView(), 0, 319, 0));
        assertEquals(Verdict.OUTSIDE_BORDER, ArrivalSafety.check(new FakeView(), 2000, 64, 0));
    }

    @Test
    void anUnsafePointIsReplacedByTheNearestSafeSpotInsideTheBoundedBox() {
        // Le point est 2 blocs sous la surface (dans la roche) : la surface juste au-dessus est retenue.
        assertEquals(Optional.of(new Spot(5, 64, 5)), ArrivalSafety.findNear(new FakeView(), 5, 62, 5));
        // Le point est sur de la lave : un bloc à côté suffit.
        FakeView lava = new FakeView().set(5, 63, 5, Material.LAVA);
        Spot near = ArrivalSafety.findNear(lava, 5, 64, 5).orElseThrow();
        assertEquals(64, near.y());
        assertEquals(1, Math.abs(near.x() - 5) + Math.abs(near.z() - 5));
    }

    @Test
    void nothingSafeInTheBoxMeansRefusalNeverAFarFallback() {
        FakeView pool = new FakeView();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 60; y <= 68; y++) {
                    pool.set(x, y, z, Material.LAVA);
                }
            }
        }
        assertTrue(ArrivalSafety.findNear(pool, 0, 64, 0).isEmpty());
        // Point très en l'air : au-delà de la recherche verticale bornée, pas de chute « arrangée ».
        assertTrue(ArrivalSafety.findNear(new FakeView(), 0, 80, 0).isEmpty());
    }

    @Test
    void theSearchIsBoundedAndStartsWithThePointItself() {
        List<Spot> candidates = ArrivalSafety.candidates(0, 64, 0);
        assertEquals(ArrivalSafety.MAX_CANDIDATES, candidates.size());
        assertEquals(new Spot(0, 64, 0), candidates.get(0));
        assertEquals(64, candidates.get(1).y(), "à distance égale, d'abord au même niveau");
        assertTrue(candidates.indexOf(new Spot(0, 65, 0)) < candidates.indexOf(new Spot(0, 63, 0)),
                "puis plutôt au-dessus qu'en dessous");
        assertTrue(candidates.stream().allMatch(s -> Math.abs(s.x()) <= ArrivalSafety.HORIZONTAL_RADIUS
                && Math.abs(s.z()) <= ArrivalSafety.HORIZONTAL_RADIUS
                && Math.abs(s.y() - 64) <= ArrivalSafety.VERTICAL_RANGE));
    }

    @Test
    void chunksAroundCoverTheSearchBoxAcrossAChunkBorder() {
        assertEquals(1, ArrivalSafety.chunksAround(8, 8).size());
        assertEquals(4, ArrivalSafety.chunksAround(0, 0).size(), "x et z de -2 à 2 : chunks -1 et 0 sur chaque axe");
    }
}
