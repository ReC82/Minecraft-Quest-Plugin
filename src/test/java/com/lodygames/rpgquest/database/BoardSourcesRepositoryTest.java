package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.player.PublicProfilePolicy.Visibility;
import com.lodygames.rpgquest.stats.BoardPeriod;
import com.lodygames.rpgquest.stats.StatBoard;
import com.lodygames.rpgquest.stats.StatDimensions;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatPeriod;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #734 — les nouvelles sources des classements, sur une vraie base SQLite : découvertes lues
 * dans leurs tables avec leur vraie date, stories terminées, blocs filtrés par famille, « suivi
 * depuis », fenêtre de calendrier figée puis relue dans son archive.
 */
class BoardSourcesRepositoryTest {

    private static final long TIMEOUT = 10;
    private static final Instant WEEK_START = Instant.parse("2026-10-18T22:00:00Z");
    private static final Instant WEEK_END = Instant.parse("2026-10-25T23:00:00Z");

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private StatisticsRepository statistics;
    private ActivityLogRepository journal;
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private int sequence;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        statistics = new StatisticsRepository(database);
        journal = new ActivityLogRepository(database);
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);
        profiles.findOrCreate(alice, "Alice").get(TIMEOUT, TimeUnit.SECONDS);
        profiles.findOrCreate(bob, "Bob").get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private void insert(String sql, Object... values) throws Exception {
        database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) {
                    statement.setString(i + 1, String.valueOf(values[i]));
                }
                return statement.executeUpdate();
            }
        }).get(TIMEOUT, TimeUnit.SECONDS);
    }

    private void broke(UUID player, String material, String at) throws Exception {
        journal.record(new ActivityLogRepository.Activity("break:" + (++sequence), player, StatMetric.BLOCK_BREAK,
                material, null, 1, Instant.parse(at))).get(TIMEOUT, TimeUnit.SECONDS);
    }

    private static StatBoard board(String metric, Map<String, Set<String>> filter, BoardPeriod period) {
        return new StatBoard("test", "Test", "autres", null, metric, filter, Map.of(), period, null, null, 10, false);
    }

    private static StatPeriod week(StatBoard board) {
        return board.periodFor(new BoardPeriod.Window("2026-W43", WEEK_START, WEEK_END));
    }

    private List<StatisticsRepository.RankedRow> live(StatPeriod period, String metric) throws Exception {
        return statistics.liveTop(period, StatMetric.find(metric).orElseThrow(), 10, Visibility.PUBLIC, Set.of(), Set.of())
                .get(TIMEOUT, TimeUnit.SECONDS);
    }

    private static StatisticsRepository.RankedRow row(String name, long value, int rank) {
        return new StatisticsRepository.RankedRow(name, value, rank);
    }

    @Test
    void waypointDiscoveriesAreReadWithTheirRealDateAndOncePerWaypoint() throws Exception {
        WaypointRepository waypoints = new WaypointRepository(database);
        waypoints.recordDiscovery(alice, "wp_forest", Instant.parse("2026-10-20T10:00:00Z")).get(TIMEOUT, TimeUnit.SECONDS);
        waypoints.recordDiscovery(alice, "wp_forest", Instant.parse("2026-10-21T10:00:00Z")).get(TIMEOUT, TimeUnit.SECONDS);
        waypoints.recordDiscovery(alice, "wp_desert", Instant.parse("2026-10-01T10:00:00Z")).get(TIMEOUT, TimeUnit.SECONDS);
        waypoints.recordDiscovery(bob, "wp_forest", Instant.parse("2026-10-22T10:00:00Z")).get(TIMEOUT, TimeUnit.SECONDS);

        StatBoard board = board(StatMetric.WAYPOINT_DISCOVERED, Map.of(), BoardPeriod.WEEKLY);
        assertEquals(List.of(row("Alice", 1, 1), row("Bob", 1, 1)), live(week(board), StatMetric.WAYPOINT_DISCOVERED),
                "revisiter un waypoint n'est pas une nouvelle découverte ; ex aequo au même rang");
        StatBoard ever = board(StatMetric.WAYPOINT_DISCOVERED, Map.of(), BoardPeriod.LIFETIME);
        StatPeriod lifetime = ever.periodFor(ever.current(Instant.now(), java.time.ZoneOffset.UTC, java.util.Optional.empty())
                .orElseThrow());
        assertEquals(List.of(row("Alice", 2, 1), row("Bob", 1, 2)), live(lifetime, StatMetric.WAYPOINT_DISCOVERED));
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), statistics.trackedSince(
                StatMetric.find(StatMetric.WAYPOINT_DISCOVERED).orElseThrow()).get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void onlyCompletedStoriesCount() throws Exception {
        String sql = "INSERT INTO story_progress (player_uuid, story_id, state, current_index, updated_at) VALUES (?, ?, ?, 0, ?)";
        insert(sql, alice, "lily", "COMPLETED", "2026-10-20T10:00:00Z");
        insert(sql, bob, "lily", "IN_PROGRESS", "2026-10-20T10:00:00Z");

        StatBoard board = board(StatMetric.STORY_COMPLETED, Map.of(), BoardPeriod.WEEKLY);
        assertEquals(List.of(row("Alice", 1, 1)), live(week(board), StatMetric.STORY_COMPLETED));
    }

    @Test
    void aBlockFamilyFilterCountsEveryMemberAndCombinesWithExplicitBlocks() throws Exception {
        broke(alice, "IRON_ORE", "2026-10-20T10:00:00Z");
        broke(alice, "DEEPSLATE_DIAMOND_ORE", "2026-10-20T10:01:00Z");
        broke(alice, "STONE", "2026-10-20T10:02:00Z");
        broke(bob, "OAK_LOG", "2026-10-20T10:03:00Z");
        Set<String> ores = StatDimensions.bundled().family(StatDimensions.Kind.BLOCK, "ores").orElseThrow().members();

        assertEquals(List.of(row("Alice", 2, 1)),
                live(week(board(StatMetric.BLOCK_BREAK, Map.of("block-family", ores), BoardPeriod.WEEKLY)),
                        StatMetric.BLOCK_BREAK));
        assertEquals(List.of(row("Alice", 2, 1), row("Bob", 1, 2)),
                live(week(board(StatMetric.BLOCK_BREAK, Map.of("block-family", ores, "block", Set.of("OAK_LOG")),
                        BoardPeriod.WEEKLY)), StatMetric.BLOCK_BREAK), "famille OU bloc explicite");
    }

    @Test
    void aMetricWithoutDataHasNoTrackedSinceDate() throws Exception {
        assertNull(statistics.trackedSince(StatMetric.find(StatMetric.CITY_DISCOVERED).orElseThrow())
                .get(TIMEOUT, TimeUnit.SECONDS), "jamais une date inventée");
    }

    @Test
    void anEndedWeekIsFrozenOnceAndThenReadFromItsArchive() throws Exception {
        broke(alice, "STONE", "2026-10-20T10:00:00Z");
        StatBoard board = board(StatMetric.BLOCK_BREAK, Map.of(), BoardPeriod.WEEKLY);
        StatPeriod period = week(board);
        StatMetric metric = StatMetric.find(StatMetric.BLOCK_BREAK).orElseThrow();

        assertTrue(statistics.archive(period, Set.of(), Instant.parse("2026-10-26T00:00:00Z")).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(false, statistics.archive(period, Set.of(), Instant.parse("2026-10-26T00:01:00Z")).get(TIMEOUT, TimeUnit.SECONDS));
        broke(alice, "STONE", "2026-10-21T10:00:00Z"); // rejeu tardif : l'archive ne bouge plus

        assertEquals(List.of(row("Alice", 1, 1)),
                statistics.archivedTop(period, metric, 10, Visibility.PUBLIC, Set.of()).get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(statistics.archivedPeriodIds().get(TIMEOUT, TimeUnit.SECONDS).contains("board_test_2026w43"));
    }
}
