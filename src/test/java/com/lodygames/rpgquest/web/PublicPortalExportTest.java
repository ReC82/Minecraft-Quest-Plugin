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
        var rows = questStatistics.topCompleted(10).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of(new QuestStatisticsRepository.Row("Alice", 2), new QuestStatisticsRepository.Row("Bob", 1)), rows);
    }

    @Test
    void aHiddenPlayerDisappearsFromEveryBoard() throws Exception {
        variables.set(bob, PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.HIDDEN_VALUE).get(TIMEOUT, TimeUnit.SECONDS);

        var xp = progression.topPlayers(SkillType.GLOBAL, 10).get(TIMEOUT, TimeUnit.SECONDS);
        var questRows = questStatistics.topCompleted(10).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of("Alice"), xp.stream().map(ProgressionRepository.LeaderboardRow::name).toList());
        assertEquals(List.of("Alice"), questRows.stream().map(QuestStatisticsRepository.Row::name).toList());
    }

    @Test
    void makingTheProfilePublicAgainBringsThePlayerBack() throws Exception {
        variables.set(bob, PublicProfilePolicy.HIDDEN_KEY, PublicProfilePolicy.HIDDEN_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        variables.set(bob, PublicProfilePolicy.HIDDEN_KEY, "false").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(2, progression.topPlayers(SkillType.GLOBAL, 10).get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    @Test
    void statisticsExposeTheLifetimeScopeAndKeepSeasonAndEventsEmpty() throws Exception {
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> boards = new EnumMap<>(SkillType.class);
        boards.put(SkillType.GLOBAL, progression.topPlayers(SkillType.GLOBAL, 10).get(TIMEOUT, TimeUnit.SECONDS));

        Map<String, Object> statistics = WebSnapshotWriter.statistics(boards,
                questStatistics.topCompleted(10).get(TIMEOUT, TimeUnit.SECONDS));

        assertEquals(List.of("lifetime"), statistics.get("scopes"));
        @SuppressWarnings("unchecked")
        Map<String, Object> lifetime = (Map<String, Object>) statistics.get("lifetime");
        assertEquals(List.of(Map.of("name", "Bob", "value", 900L), Map.of("name", "Alice", "value", 500L)),
                lifetime.get("RPG_XP_GLOBAL"));
        assertEquals(List.of(Map.of("name", "Alice", "value", 2L), Map.of("name", "Bob", "value", 1L)),
                lifetime.get("QUESTS_COMPLETED"));
        assertNull(statistics.get("season"), "aucune saison n'existe : rien d'inventé");
        assertEquals(List.of(), statistics.get("events"));
    }

    @Test
    void profilesCarryOnlyWhatTheBoardsSayAndNeverAnUuid() throws Exception {
        Map<SkillType, List<ProgressionRepository.LeaderboardRow>> boards = new EnumMap<>(SkillType.class);
        boards.put(SkillType.GLOBAL, progression.topPlayers(SkillType.GLOBAL, 10).get(TIMEOUT, TimeUnit.SECONDS));

        var result = WebSnapshotWriter.profiles(boards, questStatistics.topCompleted(10).get(TIMEOUT, TimeUnit.SECONDS));

        assertEquals(2, result.size());
        Map<String, Object> alicesProfile = result.get(0);
        assertEquals("Alice", alicesProfile.get("name"));
        assertEquals(Map.of("GLOBAL", 500L), alicesProfile.get("skills"));
        assertEquals(2L, alicesProfile.get("questsCompleted"));
        assertFalse(result.toString().contains(alice.toString()), "jamais d'UUID dans l'export public");
        assertTrue(alicesProfile.keySet().stream().allMatch(k -> List.of("name", "skills", "questsCompleted").contains(k)));
    }
}
