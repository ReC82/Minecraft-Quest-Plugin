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
    void statisticsRelayEveryScopeThroughTheWhiteList() {
        Object raw = Map.of("scopes", List.of("lifetime", "season", "event"),
                "metrics", List.of(Map.of("id", "RPG_XP_COMBAT", "aggregation", "SUM", "order", "DESC",
                        "scopes", List.of("lifetime", "season", "event"), "sql", "x")),
                "lifetime", Map.of("QUESTS_COMPLETED", List.of(Map.of("name", "Alice", "value", 2, "rank", 1, "ip", "x"))),
                "season", Map.of("id", "s1", "name", "Saison 1", "start", "2026-11-01T00:00:00Z",
                        "end", "2026-12-01T00:00:00Z", "status", "ACTIVE", "uuid", "u-1",
                        "leaderboards", Map.of("RPG_XP_COMBAT", List.of(Map.of("name", "Bob", "value", 9, "rank", 1, "uuid", "u-2")))),
                "events", List.of(Map.of("id", "e1", "status", "SCHEDULED", "leaderboards", Map.of()), "pas une période"),
                "debug", "x");

        Map<String, Object> safe = PublicPlayerData.statistics(raw);

        assertEquals(List.of("scopes", "metrics", "lifetime", "season", "events"), List.copyOf(safe.keySet()));
        assertEquals(List.of(Map.of("id", "RPG_XP_COMBAT", "aggregation", "SUM", "order", "DESC",
                "scopes", List.of("lifetime", "season", "event"))), safe.get("metrics"));
        assertEquals(Map.of("QUESTS_COMPLETED", List.of(Map.of("name", "Alice", "value", 2, "rank", 1))), safe.get("lifetime"));
        assertEquals(Map.of("id", "s1", "name", "Saison 1", "start", "2026-11-01T00:00:00Z", "end", "2026-12-01T00:00:00Z",
                "status", "ACTIVE", "leaderboards", Map.of("RPG_XP_COMBAT", List.of(Map.of("name", "Bob", "value", 9, "rank", 1)))),
                safe.get("season"));
        assertEquals(List.of(Map.of("id", "e1", "status", "SCHEDULED", "leaderboards", Map.of())), safe.get("events"));
    }

    @Test
    void statisticsWithoutPeriodsStayEmpty() {
        Map<String, Object> safe = PublicPlayerData.statistics(Map.of("scopes", List.of("lifetime")));

        assertNull(safe.get("season"));
        assertEquals(List.of(), safe.get("events"));
    }
}
