package com.lodygames.rpgquest.building.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Issue #213 — la conversion yaw → orientation cardinale.
 *
 * <p>C'est le genre de conversion qu'on croit évidente et qu'on écrit à l'envers une fois sur deux :
 * dans Minecraft, le yaw {@code 0} regarde le <strong>sud</strong>, pas le nord. Ces tests existent
 * surtout pour que l'erreur, si elle est faite un jour, soit attrapée ici et pas en jeu.</p>
 */
class FacingTest {

    @Test
    void theFourCardinalYawsMapToTheRightDirection() {
        assertEquals(Facing.SOUTH, Facing.fromYaw(0f), "yaw 0 regarde le SUD dans Minecraft");
        assertEquals(Facing.WEST, Facing.fromYaw(90f));
        assertEquals(Facing.NORTH, Facing.fromYaw(180f));
        assertEquals(Facing.EAST, Facing.fromYaw(270f));
    }

    /** Un joueur ne vise jamais exactement un cardinal : c'est le cas réel. */
    @Test
    void anApproximateYawSnapsToTheNearestCardinal() {
        assertEquals(Facing.SOUTH, Facing.fromYaw(12.7f));
        assertEquals(Facing.SOUTH, Facing.fromYaw(-30f));
        assertEquals(Facing.WEST, Facing.fromYaw(103.2f));
        assertEquals(Facing.NORTH, Facing.fromYaw(177.9f));
        assertEquals(Facing.EAST, Facing.fromYaw(265.4f));
    }

    /** Paper renvoie couramment des yaw négatifs ou de plusieurs tours. */
    @Test
    void negativeAndMultiTurnYawsAreNormalised() {
        assertEquals(Facing.NORTH, Facing.fromYaw(-180f));
        assertEquals(Facing.EAST, Facing.fromYaw(-90f));
        assertEquals(Facing.SOUTH, Facing.fromYaw(360f));
        assertEquals(Facing.SOUTH, Facing.fromYaw(720f));
        assertEquals(Facing.WEST, Facing.fromYaw(450f));
        assertEquals(Facing.WEST, Facing.fromYaw(-270f));
    }

    /** Une diagonale exacte n'a pas de bonne réponse, mais elle doit en avoir une stable. */
    @Test
    void exactDiagonalsAreDeterministic() {
        assertEquals(Facing.WEST, Facing.fromYaw(45f));
        assertEquals(Facing.NORTH, Facing.fromYaw(135f));
        assertEquals(Facing.EAST, Facing.fromYaw(225f));
        assertEquals(Facing.SOUTH, Facing.fromYaw(315f));
        // Deux appels identiques donnent la même réponse : c'est tout ce qu'on exige d'un arbitraire.
        assertEquals(Facing.fromYaw(45f), Facing.fromYaw(45f));
    }

    @Test
    void everyYawOfAFullTurnYieldsACardinal() {
        for (int yaw = -720; yaw <= 720; yaw++) {
            assertTrue(Facing.fromYaw(yaw) != null, "yaw " + yaw);
        }
    }

    // ---- Lecture et vecteurs -------------------------------------------------------------------

    @Test
    void parsingIsToleranttoCaseAndSpaces() {
        assertEquals(Facing.NORTH, Facing.of("north").orElseThrow());
        assertEquals(Facing.WEST, Facing.of("  WeSt ").orElseThrow());
        assertTrue(Facing.of("").isEmpty());
        assertTrue(Facing.of(null).isEmpty());
        assertTrue(Facing.of("UP").isEmpty(), "une face verticale n'est pas une orientation");
        assertTrue(Facing.of("NORTH_EAST").isEmpty());
    }

    /** Les vecteurs servent au placement futur : une erreur de signe s'y verrait tard. */
    @Test
    void theDirectionVectorsMatchMinecraftAxes() {
        assertEquals(-1, Facing.NORTH.modZ());
        assertEquals(0, Facing.NORTH.modX());
        assertEquals(1, Facing.SOUTH.modZ());
        assertEquals(1, Facing.EAST.modX());
        assertEquals(-1, Facing.WEST.modX());
        assertEquals(0, Facing.EAST.modZ());
    }

    @Test
    void everyFacingHasAFrenchLabel() {
        for (Facing facing : Facing.values()) {
            assertTrue(facing.label() != null && !facing.label().isBlank(), facing.name());
        }
        assertEquals("ouest", Facing.WEST.label());
    }
}
