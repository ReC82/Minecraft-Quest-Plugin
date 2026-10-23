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
    void anEmptyListPublishesNoBoardAndDisabledBoardsArePausedNotComputed() throws Exception {
        assertEquals(List.of(), load("leaderboards: []").definition().boards());
        var result = load("""
                leaderboards:
                  - {id: a, name: "A", metric: ENTITY_KILL, period: weekly, enabled: false}
                  - {id: b, name: "B", metric: BLOCK_BREAK, period: daily, category: recolte, size: 5, featured: true}
                """);
        assertTrue(result.valid(), errors(result));
        assertEquals(List.of("b"), result.definition().activeBoards().stream().map(StatBoard::id).toList());
        assertEquals(StatBoard.Status.PAUSED, result.definition().board("a").orElseThrow().status(),
                "enabled: false (format #734) = en pause (#735)");
        StatBoard board = result.definition().activeBoards().get(0);
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

    // ---- Issue #735 : champs administrés depuis PlugAdmin ---------------------------------------

    @Test
    void statusOrderVisibilityAndDescriptionAreReadAndBoardsSortedByOrder() throws Exception {
        var result = load("""
                leaderboards:
                  - {id: a, name: A, metric: ENTITY_KILL, period: weekly, order: 30}
                  - {id: b, name: B, metric: ENTITY_KILL, period: weekly, order: 10, status: draft,
                     description: "Pour les braves"}
                  - {id: c, name: C, metric: ENTITY_KILL, period: weekly, order: 20, visible: false}
                  - {id: d, name: D, metric: ENTITY_KILL, period: weekly, order: 5, status: archived}
                """);
        assertTrue(result.valid(), errors(result));
        StatisticsDefinition definition = result.definition();
        assertEquals(List.of("d", "b", "c", "a"), definition.boards().stream().map(StatBoard::id).toList());
        assertEquals(List.of("c", "a"), definition.activeBoards().stream().map(StatBoard::id).toList());
        assertEquals(List.of("a"), definition.publishedBoards().stream().map(StatBoard::id).toList(),
                "caché = calculé mais pas publié");
        assertEquals("Pour les braves", definition.board("b").orElseThrow().description());
        assertEquals(List.of(), StatisticsService.endedBoardWindows(load("""
                leaderboards: [{id: x, name: X, metric: ENTITY_KILL, period: daily, status: paused}]
                """).definition(), Instant.parse("2026-10-23T10:00:00Z")), "un classement en pause n'est jamais figé");
    }

    @Test
    void invalidAdministeredFieldsAreRefused() throws Exception {
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, status: live}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, order: -1}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, visible: maybe}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, description: \"%s\"}]"
                .formatted("a".repeat(201))).valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, period: event}]").valid(),
                "un classement d'événement cite son événement");
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, period: event, event: nope}]").valid());
        assertFalse(load("leaderboards: [{id: x, name: X, metric: ENTITY_KILL, period: weekly, event: nope}]").valid());
    }

    @Test
    void anEventBoardFollowsItsEventDates() throws Exception {
        var definition = load("""
                events:
                  - {id: halloween, name: Halloween, start: "2026-10-31T00:00:00Z", end: "2026-11-02T00:00:00Z",
                     metrics: [ENTITY_KILL]}
                leaderboards:
                  - {id: spooky, name: Spooky, metric: ENTITY_KILL, period: event, event: halloween}
                """).definition();
        StatBoard board = definition.board("spooky").orElseThrow();

        var window = board.current(Instant.parse("2026-11-01T10:00:00Z"), definition).orElseThrow();
        assertEquals(new BoardPeriod.Window("halloween", Instant.parse("2026-10-31T00:00:00Z"),
                Instant.parse("2026-11-02T00:00:00Z")), window);
        assertEquals(List.of("board_spooky_halloween"), StatisticsService.endedBoardWindows(definition,
                Instant.parse("2026-11-03T00:00:00Z")).stream().map(StatPeriod::id).toList(), "figé à la fin de l'événement");
    }

    @Test
    void aManagedFileReplacesTheBundledCatalogueButNotAListInStatisticsYml() throws Exception {
        YamlConfiguration managed = new YamlConfiguration();
        managed.loadFromString("timezone: UTC\nleaderboards: [{id: m, name: M, metric: BLOCK_BREAK, period: daily}]");
        YamlConfiguration empty = new YamlConfiguration();
        var result = StatisticsDefinitionLoader.load(empty, managed);
        assertTrue(result.valid(), errors(result));
        assertEquals(StatisticsDefinitionLoader.BoardsSource.MANAGED_FILE, result.boardsSource());
        assertEquals(List.of("m"), result.definition().boards().stream().map(StatBoard::id).toList());
        assertEquals(ZoneId.of("UTC"), result.definition().zone());

        YamlConfiguration statistics = new YamlConfiguration();
        statistics.loadFromString("leaderboards: [{id: s, name: S, metric: BLOCK_BREAK}]");
        var legacy = StatisticsDefinitionLoader.load(statistics, managed);
        assertEquals(StatisticsDefinitionLoader.BoardsSource.STATISTICS_FILE, legacy.boardsSource());
        assertEquals(List.of("s"), legacy.definition().boards().stream().map(StatBoard::id).toList());
    }
}
