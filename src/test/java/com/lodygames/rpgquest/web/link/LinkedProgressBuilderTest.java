package com.lodygames.rpgquest.web.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestStatisticsRepository;
import com.lodygames.rpgquest.database.StatisticsRepository;
import com.lodygames.rpgquest.progression.model.ProgressionCurve;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.stats.BoardPeriod;
import com.lodygames.rpgquest.stats.StatBoard;
import com.lodygames.rpgquest.stats.StatisticsDefinition;
import java.nio.file.Path;
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
 * Issue #53 : la progression personnelle envoyée au portail, calculée sur une vraie base — niveaux,
 * quêtes, place sur les classements publiés — sans pseudo d'autrui et sans valeur inventée.
 */
class LinkedProgressBuilderTest {

    private static final long TIMEOUT = 10;
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final ProgressionCurve CURVE = new ProgressionCurve(100, 1.5, 50);

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private ProgressionRepository progression;
    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        progression = new ProgressionRepository(database);
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);
        profiles.findOrCreate(alice, "Alice").get(TIMEOUT, TimeUnit.SECONDS);
        profiles.findOrCreate(bob, "Bob").get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static StatBoard board(String id, String metric, boolean visible) {
        return new StatBoard(id, "Classement " + id, "combat", null, metric, Map.of(), Map.of(), BoardPeriod.LIFETIME,
                null, null, 10, false, null, StatBoard.Status.ENABLED, 0, visible, null, null);
    }

    private Map<String, Object> build(UUID player, StatisticsDefinition stats) throws Exception {
        return new LinkedProgressBuilder(progression, () -> CURVE, new QuestStatisticsRepository(database),
                new StatisticsRepository(database), () -> stats, periodId -> false, () -> NOW)
                .build(player).get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsLevelsQuestsAndPublishedBoardStandings() throws Exception {
        progression.grantXp(alice, SkillType.COMBAT, 300, "combat_kill", "k1", Long.MAX_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        progression.grantXp(bob, SkillType.COMBAT, 900, "combat_kill", "k2", Long.MAX_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        StatisticsDefinition stats = new StatisticsDefinition(List.of(), Map.of(), Set.of("admin_grant"),
                List.of(board("combat_total", "RPG_XP_COMBAT", true), board("cache", "RPG_XP_COMBAT", false),
                        board("quetes", "QUESTS_COMPLETED", true)),
                StatisticsDefinition.DEFAULT_ZONE);

        Map<String, Object> progress = build(alice, stats);

        List<Map<String, Object>> skills = (List<Map<String, Object>>) progress.get("skills");
        assertEquals(SkillType.values().length, skills.size());
        Map<String, Object> combat = skills.stream().filter(s -> "COMBAT".equals(s.get("skill"))).findFirst().orElseThrow();
        assertEquals(300L, combat.get("xp"));
        assertEquals(CURVE.levelForTotalXp(300), combat.get("level"));
        assertEquals(Map.of("completed", 0L), progress.get("quests"), "aucune quête : ni rang ni effectif inventés");

        List<Map<String, Object>> boards = (List<Map<String, Object>>) progress.get("boards");
        assertEquals(List.of("combat_total", "quetes"), boards.stream().map(b -> b.get("id")).toList(),
                "seuls les classements publiés");
        assertEquals(300L, boards.get(0).get("value"));
        assertEquals(2, boards.get(0).get("rank"));
        assertEquals(2L, boards.get(0).get("players"));
        assertEquals("ACTIVE", boards.get(0).get("status"));
        assertFalse(boards.get(1).containsKey("rank"));
        assertFalse(progress.toString().contains("Bob"), "aucun pseudo d'un autre joueur");
        assertFalse(progress.toString().contains(bob.toString()), "aucun UUID");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExcludedPlayerHasNoPlaceOnThatBoard() throws Exception {
        progression.grantXp(alice, SkillType.COMBAT, 300, "combat_kill", "k1", Long.MAX_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        StatisticsDefinition stats = new StatisticsDefinition(List.of(),
                Map.of(alice, Set.of(StatisticsDefinition.ALL_SCOPES)), Set.of("admin_grant"),
                List.of(board("combat_total", "RPG_XP_COMBAT", true)), StatisticsDefinition.DEFAULT_ZONE);

        assertTrue(((List<?>) build(alice, stats).get("boards")).isEmpty());
    }
}
