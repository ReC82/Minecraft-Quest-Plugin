package com.lodygames.rpgquest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.QuestStatisticsRepository;
import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.lodygames.rpgquest.player.PublicProfilePolicy.Visibility;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issues #54 / #55 / #56 — ce que l'export public contient, et surtout ce qu'il ne contient pas :
 * un joueur qui a masqué son profil n'apparaît nulle part. Vraie base SQLite.
 */
class PublicPortalExportTest {

    private static final long TIMEOUT = 5;
    private static final Visibility PUBLIC = Visibility.PUBLIC;
    private static final Visibility PRIVATE = Visibility.PRIVATE;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PlayerProfileRepository profiles;
    private PlayerVariableRepository variables;
    private ProgressionRepository progression;
    private QuestProgressRepository quests;
    private QuestStatisticsRepository questStatistics;

    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        profiles = new PlayerProfileRepository(database);
        variables = new PlayerVariableRepository(database);
        progression = new ProgressionRepository(database);
        quests = new QuestProgressRepository(database);
        questStatistics = new QuestStatisticsRepository(database);
        profiles.findOrCreate(alice, "Alice").get(TIMEOUT, TimeUnit.SECONDS);
        profiles.findOrCreate(bob, "Bob").get(TIMEOUT, TimeUnit.SECONDS);
        progression.grantXp(alice, SkillType.GLOBAL, 500, "test", "e1", Long.MAX_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        progression.grantXp(bob, SkillType.GLOBAL, 900, "test", "e2", Long.MAX_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        complete(alice, "a");
        complete(alice, "b");
        complete(bob, "a");
        quests.upsertState(bob, new NamespacedKey("rpgquest", "c"), QuestState.ACTIVE, "s").get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private void complete(UUID player, String quest) throws Exception {
        quests.upsertState(player, new NamespacedKey("rpgquest", quest), QuestState.COMPLETED, null)
                .get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void questsCompletedCountsOnlyCompletedQuests() throws Exception {
        var rows = questStatistics.topCompleted(10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of(new QuestStatisticsRepository.Row("Alice", 2), new QuestStatisticsRepository.Row("Bob", 1)), rows);
    }

    @Test
    void aHiddenPlayerDisappearsFromEveryBoard() throws Exception {
        variables.set(bob, PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.HIDDEN_VALUE).get(TIMEOUT, TimeUnit.SECONDS);

        var xp = progression.topPlayers(SkillType.GLOBAL, 10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS);
        var questRows = questStatistics.topCompleted(10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of("Alice"), xp.stream().map(ProgressionRepository.LeaderboardRow::name).toList());
        assertEquals(List.of("Alice"), questRows.stream().map(QuestStatisticsRepository.Row::name).toList());
    }

    @Test
    void makingTheProfilePublicAgainBringsThePlayerBack() throws Exception {
        variables.set(bob, PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.HIDDEN_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        variables.set(bob, PublicProfilePolicy.HIDDEN_KEY, "false").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(2, progression.topPlayers(SkillType.GLOBAL, 10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    @Test
    void statisticsExposeTheLifetimeScopeAndKeepSeasonAndEventsEmpty() throws Exception {
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> boards = new EnumMap<>(SkillType.class);
        boards.put(SkillType.GLOBAL, progression.topPlayers(SkillType.GLOBAL, 10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS));

        Map<String, Object> statistics = WebSnapshotWriter.statistics(boards,
                questStatistics.topCompleted(10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS));

        assertEquals(List.of("lifetime"), statistics.get("scopes"));
        @SuppressWarnings("unchecked")
        Map<String, Object> lifetime = (Map<String, Object>) statistics.get("lifetime");
        assertEquals(List.of(Map.of("name", "Bob", "value", 900L, "rank", 1), Map.of("name", "Alice", "value", 500L, "rank", 2)),
                lifetime.get("RPG_XP_GLOBAL"));
        assertEquals(List.of(Map.of("name", "Alice", "value", 2L, "rank", 1), Map.of("name", "Bob", "value", 1L, "rank", 2)),
                lifetime.get("QUESTS_COMPLETED"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) statistics.get("metrics");
        assertEquals(Map.of("id", "QUESTS_COMPLETED", "aggregation", "SUM", "order", "DESC", "scopes", List.of("lifetime"),
                        "filters", List.of()),
                metrics.stream().filter(m -> m.get("id").equals("QUESTS_COMPLETED")).findFirst().orElseThrow(),
                "le contrat dit au portail quelles portées existent pour chaque métrique");
        assertNull(statistics.get("season"), "aucune saison n'existe : rien d'inventé");
        assertEquals(List.of(), statistics.get("events"));
    }

    @Test
    void anExcludedPlayerLeavesTheLifetimeBoards() throws Exception {
        var xp = progression.topPlayers(SkillType.GLOBAL, 10, PUBLIC, java.util.Set.of(bob)).get(TIMEOUT, TimeUnit.SECONDS);
        var questRows = questStatistics.topCompleted(10, PUBLIC, java.util.Set.of(bob)).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of("Alice"), xp.stream().map(ProgressionRepository.LeaderboardRow::name).toList());
        assertEquals(List.of("Alice"), questRows.stream().map(QuestStatisticsRepository.Row::name).toList());
    }

    @Test
    void periodsAreExportedWithTheirWindowStatusAndBoards() {
        var season = new com.lodygames.rpgquest.stats.StatPeriod("season_01", com.lodygames.rpgquest.stats.StatScope.SEASON,
                "Saison 1", java.time.Instant.parse("2026-11-01T00:00:00Z"), java.time.Instant.parse("2026-12-01T00:00:00Z"),
                List.of("RPG_XP_COMBAT"));
        var board = new WebSnapshotWriter.PeriodBoard(season, "ARCHIVED", Map.of("RPG_XP_COMBAT",
                List.of(new com.lodygames.rpgquest.database.StatisticsRepository.RankedRow("Alice", 30, 1))));

        Map<String, Object> statistics = WebSnapshotWriter.statistics(Map.of(), List.of(),
                new WebSnapshotWriter.PeriodBoards(board, List.of()));

        assertEquals(List.of("lifetime", "season"), statistics.get("scopes"));
        assertEquals(Map.of("id", "season_01", "name", "Saison 1", "start", "2026-11-01T00:00:00Z",
                "end", "2026-12-01T00:00:00Z", "status", "ARCHIVED", "ranking", "HIGHEST_TOTAL",
                "leaderboards", Map.of("RPG_XP_COMBAT", List.of(Map.of("name", "Alice", "value", 30L, "rank", 1)))),
                statistics.get("season"));
        assertEquals(List.of(), statistics.get("events"));
    }

    @Test
    void eventsPublishTheirRulesCommunityProgressAndRewardLabelsButNeverTheEntitlementKey() {
        var rules = new com.lodygames.rpgquest.stats.EventRules(com.lodygames.rpgquest.stats.EventRanking.COMMUNITY_GOAL,
                Map.of("entity", java.util.Set.of("ZOMBIE", "HUSK")), 500,
                List.of(new com.lodygames.rpgquest.stats.EventReward(com.lodygames.rpgquest.stats.EventReward.Kind.COMMUNITY,
                        0, "secret_internal_key", "Chapeau pour tous")));
        var event = new com.lodygames.rpgquest.stats.StatPeriod("zombie_weekend", com.lodygames.rpgquest.stats.StatScope.EVENT,
                "Zombie Weekend", java.time.Instant.parse("2026-11-07T18:00:00Z"), java.time.Instant.parse("2026-11-09T22:00:00Z"),
                List.of("ENTITY_KILL"), com.lodygames.rpgquest.stats.StatPeriod.State.PLANNED, rules);
        var board = new WebSnapshotWriter.PeriodBoard(event, "ACTIVE", Map.of("ENTITY_KILL",
                List.of(new com.lodygames.rpgquest.database.StatisticsRepository.RankedRow("Alice", 12, 1))), 512L);

        Map<String, Object> statistics = WebSnapshotWriter.statistics(Map.of(), List.of(),
                new WebSnapshotWriter.PeriodBoards(null, List.of(board)));

        @SuppressWarnings("unchecked")
        Map<String, Object> exported = ((List<Map<String, Object>>) statistics.get("events")).get(0);
        assertEquals("COMMUNITY_GOAL", exported.get("ranking"));
        assertEquals(Map.of("entity", List.of("HUSK", "ZOMBIE")), exported.get("filter"));
        assertEquals(Map.of("goal", 500L, "total", 512L, "reached", true), exported.get("community"));
        assertEquals(List.of(Map.of("kind", "COMMUNITY", "label", "Chapeau pour tous")), exported.get("rewards"));
        assertFalse(com.lodygames.rpgquest.web.Json.write(statistics).contains("secret_internal_key"));
    }

    @Test
    void profilesCarryOnlyPersistedValuesAndNeverAnUuid() throws Exception {
        var activity = new com.lodygames.rpgquest.database.ActivityLogRepository(database);
        var statistics = new com.lodygames.rpgquest.database.StatisticsRepository(database);
        java.time.Instant at = java.time.Instant.parse("2026-10-20T10:00:00Z");
        activity.record(new com.lodygames.rpgquest.database.ActivityLogRepository.Activity("k1", alice,
                com.lodygames.rpgquest.stats.StatMetric.ENTITY_KILL, "ZOMBIE", null, 1, at)).get(TIMEOUT, TimeUnit.SECONDS);
        activity.record(new com.lodygames.rpgquest.database.ActivityLogRepository.Activity("k2", alice,
                com.lodygames.rpgquest.stats.StatMetric.ENTITY_KILL, "SKELETON", null, 1, at)).get(TIMEOUT, TimeUnit.SECONDS);
        // Un retry de la même action ne compte pas deux fois.
        activity.record(new com.lodygames.rpgquest.database.ActivityLogRepository.Activity("k2", alice,
                com.lodygames.rpgquest.stats.StatMetric.ENTITY_KILL, "SKELETON", null, 1, at)).get(TIMEOUT, TimeUnit.SECONDS);
        var kills = statistics.lifetimeTotals(com.lodygames.rpgquest.stats.StatMetric.find(
                com.lodygames.rpgquest.stats.StatMetric.ENTITY_KILL).orElseThrow(), 10, PUBLIC, List.of())
                .get(TIMEOUT, TimeUnit.SECONDS);
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> skills = new EnumMap<>(SkillType.class);
        skills.put(SkillType.GLOBAL, progression.topPlayers(SkillType.GLOBAL, 10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS));

        var result = WebSnapshotWriter.profiles(new WebSnapshotWriter.ProfileSources(skills,
                questStatistics.topCompleted(10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS),
                Map.of(com.lodygames.rpgquest.stats.StatMetric.ENTITY_KILL, kills)));

        assertEquals(2, result.size());
        Map<String, Object> alicesProfile = result.get(0);
        assertEquals("Alice", alicesProfile.get("name"));
        assertEquals(Map.of("GLOBAL", 500L), alicesProfile.get("skills"));
        assertEquals(2L, alicesProfile.get("questsCompleted"));
        assertEquals(Map.of("ENTITY_KILL", 2L), alicesProfile.get("stats"));
        assertNull(result.get(1).get("stats"), "aucune valeur inventée pour Bob");
        assertFalse(result.toString().contains(alice.toString()), "jamais d'UUID dans l'export public");
        assertTrue(alicesProfile.keySet().stream()
                .allMatch(k -> List.of("name", "skills", "questsCompleted", "stats").contains(k)));
    }

    @Test
    void lifetimeTotalsSkipHiddenAndExcludedPlayersAndRefuseXp() throws Exception {
        var activity = new com.lodygames.rpgquest.database.ActivityLogRepository(database);
        var statistics = new com.lodygames.rpgquest.database.StatisticsRepository(database);
        var metric = com.lodygames.rpgquest.stats.StatMetric.find(com.lodygames.rpgquest.stats.StatMetric.BLOCK_BREAK).orElseThrow();
        java.time.Instant at = java.time.Instant.parse("2026-10-20T10:00:00Z");
        activity.record(new com.lodygames.rpgquest.database.ActivityLogRepository.Activity("b1", alice,
                metric.id(), "STONE", null, 3, at)).get(TIMEOUT, TimeUnit.SECONDS);
        activity.record(new com.lodygames.rpgquest.database.ActivityLogRepository.Activity("b2", bob,
                metric.id(), "STONE", null, 5, at)).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of(new com.lodygames.rpgquest.database.StatisticsRepository.RankedRow("Bob", 5, 1),
                new com.lodygames.rpgquest.database.StatisticsRepository.RankedRow("Alice", 3, 2)),
                statistics.lifetimeTotals(metric, 10, PUBLIC, List.of()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of("Alice"), statistics.lifetimeTotals(metric, 10, PUBLIC, List.of(bob))
                .get(TIMEOUT, TimeUnit.SECONDS).stream().map(row -> row.name()).toList());
        variables.set(alice, PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.HIDDEN_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(List.of("Bob"), statistics.lifetimeTotals(metric, 10, PUBLIC, List.of())
                .get(TIMEOUT, TimeUnit.SECONDS).stream().map(row -> row.name()).toList());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> statistics.lifetimeTotals(
                com.lodygames.rpgquest.stats.StatMetric.find("RPG_XP_GLOBAL").orElseThrow(), 10, PUBLIC, List.of()));
    }

    @Test
    void everyVisiblePlayerGetsAProfileNotOnlyTheTopOfABoard() {
        List<ProgressionRepository.LeaderboardRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(new ProgressionRepository.LeaderboardRow("P" + i, 100 - i));
        }
        var result = WebSnapshotWriter.profiles(new WebSnapshotWriter.ProfileSources(
                Map.of(SkillType.GLOBAL, rows), List.of(), Map.of()));
        assertEquals(25, result.size());
    }

    @Test
    void privateByDefaultPublishesNobodyWithoutExplicitConsent() throws Exception {
        assertEquals(List.of(), progression.topPlayers(SkillType.GLOBAL, 10, PRIVATE).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of(), questStatistics.topCompleted(10, PRIVATE).get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void privateByDefaultPublishesOnlyPlayersWhoOptedIn() throws Exception {
        variables.set(bob, PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.PUBLIC_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        variables.set(alice, PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.HIDDEN_VALUE).get(TIMEOUT, TimeUnit.SECONDS);

        var xp = progression.topPlayers(SkillType.GLOBAL, 10, PRIVATE).get(TIMEOUT, TimeUnit.SECONDS);
        var questRows = questStatistics.topCompleted(10, PRIVATE).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of("Bob"), xp.stream().map(ProgressionRepository.LeaderboardRow::name).toList());
        assertEquals(List.of("Bob"), questRows.stream().map(QuestStatisticsRepository.Row::name).toList());
    }

    @Test
    void anExplicitChoiceAlwaysWinsOverTheServerDefault() {
        assertFalse(PublicProfilePolicy.isVisible(Optional.of(PublicProfilePolicy.HIDDEN_VALUE), PUBLIC));
        assertTrue(PublicProfilePolicy.isVisible(Optional.of(PublicProfilePolicy.PUBLIC_VALUE), PRIVATE));
        assertFalse(PublicProfilePolicy.isVisible(Optional.empty(), PRIVATE));
        assertTrue(PublicProfilePolicy.isVisible(Optional.empty(), PUBLIC));
        assertFalse(PublicProfilePolicy.isVisible(Optional.of("n'importe quoi"), PRIVATE),
                "une valeur inconnue suit le réglage par défaut, jamais plus permissive");
    }
}
