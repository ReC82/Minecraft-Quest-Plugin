package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Issue #734 — catalogue déclaratif des classements dans {@code statistics.yml} : catalogue par
 * défaut du JAR, familles résolues, périodes, refus explicites, fenêtres à figer.
 */
class BoardCatalogLoaderTest {

    private static StatisticsDefinitionLoader.Result load(String yaml) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return StatisticsDefinitionLoader.load(configuration);
    }

    private static String errors(StatisticsDefinitionLoader.Result result) {
        return String.join("\n", result.errors());
    }

    @Test
    void withoutLeaderboardsTheBundledCatalogueIsLoadedWithoutError() throws Exception {
        var result = load("");

        assertTrue(result.valid(), errors(result));
        List<String> ids = result.definition().boards().stream().map(StatBoard::id).toList();
        assertTrue(ids.containsAll(List.of("mobs_week", "zombies_week", "skeletons_week", "spiders_week",
                "creepers_week", "mobs_total", "blocks_week", "waypoints_total", "quests_total")), ids.toString());
        assertEquals(ZoneId.of("Europe/Brussels"), result.definition().zone());
        StatBoard zombies = result.definition().board("zombies_week").orElseThrow();
        assertTrue(zombies.filter().get("entity-family").containsAll(Set.of("ZOMBIE", "HUSK", "DROWNED")));
        assertEquals(Map.of("entity-family", List.of("zombies")), zombies.declared(), "seul le filtre écrit est publié");
    }

    @Test
    void anEmptyListPublishesNoBoardAndDisabledBoardsAreSkipped() throws Exception {
        assertEquals(List.of(), load("leaderboards: []").definition().boards());
        var result = load("""
                leaderboards:
                  - {id: a, name: "A", metric: ENTITY_KILL, period: weekly, enabled: false}
                  - {id: b, name: "B", metric: BLOCK_BREAK, period: daily, category: recolte, size: 5, featured: true}
                """);
        assertTrue(result.valid(), errors(result));
        assertEquals(List.of("b"), result.definition().boards().stream().map(StatBoard::id).toList());
        StatBoard board = result.definition().boards().get(0);
        assertEquals(BoardPeriod.DAILY, board.period());
        assertEquals(5, board.size());
        assertTrue(board.featured());
    }

    @Test
    void customDatesAndTimezoneAreRead() throws Exception {
        var result = load("""
                timezone: Europe/Paris
                leaderboards:
                  - {id: halloween, name: "Halloween", metric: ENTITY_KILL, period: custom,
                     start: "2026-10-31T00:00:00+01:00", end: "2026-11-02T00:00:00+01:00",
                     filter: {entity: [zombie], entity-family: [skeletons]}}
                """);
        assertTrue(result.valid(), errors(result));
        StatBoard board = result.definition().boards().get(0);
        assertEquals(Instant.parse("2026-10-30T23:00:00Z"), board.start());
        assertEquals(Set.of("ZOMBIE"), board.filter().get("entity"));
        assertTrue(board.filter().get("entity-family").contains("STRAY"));
        assertEquals(ZoneId.of("Europe/Paris"), result.definition().zone());
    }

    @Test
    void invalidBoardsRejectTheWholeFile() throws Exception {
        assertFalse(load("leaderboards: [{id: x, name: X, metric: NOPE}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, period: yearly}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: QUESTS_COMPLETED, period: weekly}]").valid(),
                "quest_progress n'a pas d'historique daté");
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, period: custom}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, start: \"2026-10-31T00:00:00Z\"}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, filter: {entity-family: [dragons]}}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: FISH_CAUGHT, filter: {entity: [ZOMBIE]}}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, category: vip}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, size: 500}]").valid());
        assertFalse(load("timezone: Mars/Olympus").valid());
        var duplicate = load("""
                events:
                  - {id: x, start: "2026-11-01T00:00:00Z", end: "2026-11-02T00:00:00Z", metrics: [ENTITY_KILL]}
                leaderboards: [{id: x, name: X, metric: ENTITY_KILL}]
                """);
        assertFalse(duplicate.valid());
        assertEquals(StatisticsDefinition.EMPTY, duplicate.definition(), "jamais un fichier appliqué à moitié");
    }

    @Test
    void anExclusionMayTargetABoard() throws Exception {
        String player = "00000000-0000-0000-0000-0000000000aa";
        var result = load("""
                leaderboards: [{id: zombies, name: Z, metric: ENTITY_KILL, period: weekly}]
                exclusions: [{player: %s, scopes: [zombies]}]
                """.formatted(player));
        assertTrue(result.valid(), errors(result));
        assertEquals(Set.of(UUID.fromString(player)), result.definition().excludedFrom("zombies"));
    }

    @Test
    void endedWindowsToFreezeAreThePreviousCalendarWindowAndFinishedCustomDates() throws Exception {
        var definition = load("""
                leaderboards:
                  - {id: day, name: D, metric: ENTITY_KILL, period: daily}
                  - {id: ever, name: E, metric: ENTITY_KILL}
                  - {id: quests, name: Q, metric: QUESTS_COMPLETED}
                  - {id: past, name: P, metric: ENTITY_KILL, period: custom,
                     start: "2026-10-01T00:00:00Z", end: "2026-10-02T00:00:00Z"}
                  - {id: future, name: F, metric: ENTITY_KILL, period: custom,
                     start: "2026-11-01T00:00:00Z", end: "2026-11-02T00:00:00Z"}
                  - {id: season, name: S, metric: ENTITY_KILL, period: season}
                """).definition();

        List<StatPeriod> windows = StatisticsService.endedBoardWindows(definition, Instant.parse("2026-10-23T10:00:00Z"));

        assertEquals(List.of("board_day_20261022", "board_past_custom"), windows.stream().map(StatPeriod::id).toList());
        assertEquals(Optional.empty(), definition.board("season").orElseThrow()
                .current(Instant.parse("2026-10-23T10:00:00Z"), definition.zone(), definition.currentSeason(Instant.now())),
                "sans saison définie, aucun classement de saison n'est calculé");
    }
}
