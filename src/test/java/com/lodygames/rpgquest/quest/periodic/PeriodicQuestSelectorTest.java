package com.lodygames.rpgquest.quest.periodic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #15 — sélection déterministe et anti-répétition. */
class PeriodicQuestSelectorTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000015");
    private static final List<String> POOL = List.of("q:a", "q:b", "q:c", "q:d", "q:e");

    @Test
    void sameInputsGiveTheSameSelection() {
        List<String> first = PeriodicQuestSelector.select(PLAYER, "daily", "2026-10-17", POOL, Set.of(), 2);
        List<String> second = PeriodicQuestSelector.select(PLAYER, "daily", "2026-10-17",
                List.of("q:e", "q:d", "q:c", "q:b", "q:a"), Set.of(), 2);
        assertEquals(first, second, "l'ordre de la réserve ne change rien");
        assertEquals(2, first.size());
        assertEquals(2, new HashSet<>(first).size(), "jamais deux fois la même quête");
    }

    @Test
    void previousQuestsAreAvoidedWhenThePoolAllowsIt() {
        Set<String> previous = Set.of("q:a", "q:b", "q:c");
        for (int day = 1; day <= 20; day++) {
            List<String> chosen = PeriodicQuestSelector.select(PLAYER, "daily", "2026-10-" + (10 + day % 18),
                    POOL, previous, 2);
            assertTrue(Set.of("q:d", "q:e").containsAll(chosen), "reprise évitable : " + chosen);
        }
    }

    @Test
    void aSmallPoolFallsBackOnPreviousQuests() {
        List<String> chosen = PeriodicQuestSelector.select(PLAYER, "daily", "2026-10-17", List.of("q:a", "q:b"),
                Set.of("q:a"), 2);
        assertEquals("q:b", chosen.get(0), "la nouvelle d'abord");
        assertEquals("q:a", chosen.get(1));
    }

    @Test
    void countIsBoundedByThePool() {
        assertEquals(5, PeriodicQuestSelector.select(PLAYER, "weekly", "2026-10-12", POOL, Set.of(), 9).size());
        assertTrue(PeriodicQuestSelector.select(PLAYER, "weekly", "2026-10-12", POOL, Set.of(), 0).isEmpty());
        assertTrue(PeriodicQuestSelector.select(PLAYER, "weekly", "2026-10-12", List.of(), Set.of(), 3).isEmpty());
    }

    @Test
    void playersAndPeriodsDoNotAllGetTheSameQuests() {
        Set<List<String>> selections = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            selections.add(PeriodicQuestSelector.select(UUID.nameUUIDFromBytes(new byte[] {(byte) i}), "daily",
                    "2026-10-17", POOL, Set.of(), 2));
        }
        assertFalse(selections.size() < 3, "la graine dépend du joueur : " + selections);
    }
}
