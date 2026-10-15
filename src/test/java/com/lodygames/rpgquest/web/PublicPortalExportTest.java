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
        assertEquals(Map.of("id", "QUESTS_COMPLETED", "aggregation", "SUM", "order", "DESC", "scopes", List.of("lifetime")),
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
                "end", "2026-12-01T00:00:00Z", "status", "ARCHIVED",
                "leaderboards", Map.of("RPG_XP_COMBAT", List.of(Map.of("name", "Alice", "value", 30L, "rank", 1)))),
                statistics.get("season"));
        assertEquals(List.of(), statistics.get("events"));
    }

    @Test
    void profilesCarryOnlyWhatTheBoardsSayAndNeverAnUuid() throws Exception {
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> boards = new EnumMap<>(SkillType.class);
        boards.put(SkillType.GLOBAL, progression.topPlayers(SkillType.GLOBAL, 10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS));

        var result = WebSnapshotWriter.profiles(boards, questStatistics.topCompleted(10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS));

        assertEquals(2, result.size());
        Map<String, Object> alicesProfile = result.get(0);
        assertEquals("Alice", alicesProfile.get("name"));
        assertEquals(Map.of("GLOBAL", 500L), alicesProfile.get("skills"));
        assertEquals(2L, alicesProfile.get("questsCompleted"));
        assertFalse(result.toString().contains(alice.toString()), "jamais d'UUID dans l'export public");
        assertTrue(alicesProfile.keySet().stream().allMatch(k -> List.of("name", "skills", "questsCompleted").contains(k)));
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
