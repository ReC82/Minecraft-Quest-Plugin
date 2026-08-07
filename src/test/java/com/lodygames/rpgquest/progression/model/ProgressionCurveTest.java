package com.lodygames.rpgquest.progression.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProgressionCurveTest {

    // baseXp=100, growthFactor=1.15, maxLevel=5 : coûts 100 / 115 / 132 / 152 (arrondis).
    private final ProgressionCurve curve = new ProgressionCurve(100L, 1.15, 5);

    @Test
    void xpToNextLevelFollowsGeometricGrowthRoundedToNearestLong() {
        assertEquals(100L, curve.xpToNextLevel(1));
        assertEquals(115L, curve.xpToNextLevel(2));
        assertEquals(132L, curve.xpToNextLevel(3));
        assertEquals(152L, curve.xpToNextLevel(4));
    }

    @Test
    void xpToNextLevelAtMaxLevelIsZero() {
        assertEquals(0L, curve.xpToNextLevel(5));
    }

    @Test
    void totalXpForLevelIsCumulative() {
        assertEquals(0L, curve.totalXpForLevel(1));
        assertEquals(100L, curve.totalXpForLevel(2));
        assertEquals(215L, curve.totalXpForLevel(3));
        assertEquals(347L, curve.totalXpForLevel(4));
        assertEquals(499L, curve.totalXpForLevel(5));
    }

    @Test
    void maxTotalXpMatchesTotalXpForMaxLevel() {
        assertEquals(499L, curve.maxTotalXp());
    }

    // ---- calcul de niveau ----------------------------------------------------------------

    @Test
    void levelForTotalXpFindsTheCorrectBracket() {
        assertEquals(1, curve.levelForTotalXp(0L));
        assertEquals(1, curve.levelForTotalXp(99L));
        assertEquals(2, curve.levelForTotalXp(100L));
        assertEquals(2, curve.levelForTotalXp(214L));
        assertEquals(3, curve.levelForTotalXp(215L));
    }

    @Test
    void levelForTotalXpJumpingMultipleBracketsAtOnceSkipsIntermediateLevels() {
        // 300 XP d'un coup depuis 0 : niveau 1 -> 3 directement (test "montée de plusieurs niveaux").
        assertEquals(3, curve.levelForTotalXp(300L));
    }

    // ---- valeurs maximales ----------------------------------------------------------------

    @Test
    void levelForTotalXpNeverExceedsMaxLevelEvenWithExcessXp() {
        assertEquals(5, curve.levelForTotalXp(499L));
        assertEquals(5, curve.levelForTotalXp(1_000_000L));
    }

    @Test
    void levelForTotalXpRejectsNegativeXp() {
        assertThrows(IllegalArgumentException.class, () -> curve.levelForTotalXp(-1L));
    }

    @Test
    void xpIntoCurrentLevelIsRelativeToTheCurrentBracket() {
        assertEquals(35L, curve.xpIntoCurrentLevel(250L)); // niveau 3 (seuil 215) : 250 - 215 = 35
        assertEquals(0L, curve.xpIntoCurrentLevel(215L));
    }

    // ---- construction invalide --------------------------------------------------------------

    @Test
    void rejectsNonPositiveBaseXp() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressionCurve(0L, 1.15, 10));
    }

    @Test
    void rejectsGrowthFactorBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressionCurve(100L, 0.99, 10));
    }

    @Test
    void rejectsNonPositiveMaxLevel() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressionCurve(100L, 1.15, 0));
    }
}
