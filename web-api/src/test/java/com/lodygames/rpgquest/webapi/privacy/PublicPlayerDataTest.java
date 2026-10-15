package com.lodygames.rpgquest.webapi.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #55 — seconde barrière : ce qui n'est pas dans la liste blanche ne sort jamais. */
class PublicPlayerDataTest {

    @Test
    void profilesKeepOnlyNameSkillsAndQuestCount() {
        Object raw = List.of(Map.of("name", "Alice", "skills", Map.of("GLOBAL", 500, "note", "x"),
                "questsCompleted", 2, "uuid", "u-1", "email", "a@example.org", "birthDate", "2014-01-01"));

        assertEquals(List.of(Map.of("name", "Alice", "skills", Map.of("GLOBAL", 500), "questsCompleted", 2)),
                PublicPlayerData.profiles(raw));
    }

    @Test
    void malformedEntriesAreDroppedNotGuessed() {
        assertEquals(List.of(), PublicPlayerData.profiles(List.of(Map.of("uuid", "u-1"), "Alice", 3)));
        assertEquals(List.of(), PublicPlayerData.profiles("pas une liste"));
        assertEquals(List.of(), PublicPlayerData.rankingRows(List.of(Map.of("name", "Alice", "value", "beaucoup")), "value"));
        assertEquals(List.of("Bob"), PublicPlayerData.names(List.of("Bob", Map.of("name", "Alice", "ip", "x"))));
    }

    @Test
    void statisticsRelayOnlyLifetimeRowsUntilSeasonsAndEventsExist() {
        Object raw = Map.of("scopes", List.of("lifetime"),
                "lifetime", Map.of("QUESTS_COMPLETED", List.of(Map.of("name", "Alice", "value", 2, "ip", "x"))),
                "season", Map.of("id", "s1"), "events", List.of(Map.of("id", "e1")), "debug", "x");

        Map<String, Object> safe = PublicPlayerData.statistics(raw);

        assertEquals(List.of("scopes", "lifetime", "season", "events"), List.copyOf(safe.keySet()));
        assertEquals(Map.of("QUESTS_COMPLETED", List.of(Map.of("name", "Alice", "value", 2))), safe.get("lifetime"));
        assertNull(safe.get("season"));
        assertEquals(List.of(), safe.get("events"));
    }
}
