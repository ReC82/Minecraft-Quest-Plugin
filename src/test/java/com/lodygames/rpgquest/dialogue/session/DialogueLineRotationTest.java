package com.lodygames.rpgquest.dialogue.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DialogueLineRotationTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void singleLineIsAlwaysTheText() {
        DialogueLineRotation rotation = new DialogueLineRotation(bound -> {
            throw new AssertionError("aucun tirage attendu pour une seule réplique");
        });
        assertEquals(0, rotation.pick(PLAYER, "d#n", 1));
        assertEquals(0, rotation.pick(PLAYER, "d#n", 1));
    }

    @Test
    void neverRepeatsTheLastLineAndEventuallyShowsThemAll() {
        Random random = new Random(42);
        DialogueLineRotation rotation = new DialogueLineRotation(random::nextInt);
        Set<Integer> seen = new HashSet<>();
        int previous = rotation.pick(PLAYER, "d#n", 3);
        seen.add(previous);
        for (int i = 0; i < 200; i++) {
            int next = rotation.pick(PLAYER, "d#n", 3);
            assertNotEquals(previous, next);
            assertTrue(next >= 0 && next < 3);
            seen.add(next);
            previous = next;
        }
        assertEquals(Set.of(0, 1, 2), seen);
    }

    @Test
    void twoLinesAlternate() {
        DialogueLineRotation rotation = new DialogueLineRotation(bound -> 0);
        int first = rotation.pick(PLAYER, "d#n", 2);
        assertNotEquals(first, rotation.pick(PLAYER, "d#n", 2));
    }

    @Test
    void memoryIsPerPlayerAndPerNodeAndForgottenOnUnload() {
        DialogueLineRotation rotation = new DialogueLineRotation(bound -> 0);
        UUID other = UUID.randomUUID();
        assertEquals(0, rotation.pick(PLAYER, "d#a", 2));
        assertEquals(0, rotation.pick(other, "d#a", 2));
        assertEquals(0, rotation.pick(PLAYER, "d#b", 2));
        assertEquals(1, rotation.pick(PLAYER, "d#a", 2));
        rotation.forget(PLAYER);
        assertEquals(0, rotation.pick(PLAYER, "d#a", 2));
    }

    @Test
    void shrunkPoolAfterReloadStaysInRange() {
        DialogueLineRotation rotation = new DialogueLineRotation(bound -> bound - 1);
        assertEquals(4, rotation.pick(PLAYER, "d#n", 5));
        int next = rotation.pick(PLAYER, "d#n", 2);
        assertTrue(next >= 0 && next < 2);
    }
}
