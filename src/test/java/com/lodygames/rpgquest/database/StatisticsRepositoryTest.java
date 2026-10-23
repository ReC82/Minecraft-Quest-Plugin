package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.player.PublicProfilePolicy.Visibility;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatScope;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #54 — classements de saisons et d'événements lus dans le journal {@code xp_grants}. Vraie
 * base SQLite ; les octrois sont posés à des instants choisis pour tester les fenêtres.
 */
class StatisticsRepositoryTest {

    private static final long TIMEOUT = 10;
    private static final Visibility PUBLIC = Visibility.PUBLIC;
    private static final Set<String> ADMIN = Set.of("admin_grant");
    private static final StatMetric COMBAT = StatMetric.find("RPG_XP_COMBAT").orElseThrow();
    private static final StatMetric GLOBAL = StatMetric.find("RPG_XP_GLOBAL").orElseThrow();

    private static final StatPeriod SEASON_1 = period("season_01", StatScope.SEASON,
            "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", "RPG_XP_COMBAT", "RPG_XP_GLOBAL");
    private static final StatPeriod SEASON_2 = period("season_02", StatScope.SEASON,
            "2026-12-01T00:00:00Z", "2027-01-01T00:00:00Z", "RPG_XP_COMBAT");

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private StatisticsRepository statistics;
    private ProgressionRepository progression;

    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private final UUID carol = UUID.fromString("00000000-0000-0000-0000-0000000ca201");
    private int sequence;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        statistics = new StatisticsRepository(database);
        progression = new ProgressionRepository(database);
        PlayerProfileRepository profiles = new PlayerProfileRepository(database);
        profiles.findOrCreate(alice, "Alice").get(TIMEOUT, TimeUnit.SECONDS);
        profiles.findOrCreate(bob, "Bob").get(TIMEOUT, TimeUnit.SECONDS);
        profiles.findOrCreate(carol, "Carol").get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static StatPeriod period(String id, StatScope scope, String start, String end, String... metrics) {
        return new StatPeriod(id, scope, id, Instant.parse(start), Instant.parse(end), List.of(metrics));
    }

    /** Pose un octroi dans le journal à un instant précis (même forme que {@code Instant#toString()}). */
    private void grant(UUID player, SkillType skill, long amount, String reason, String at) throws Exception {
        grant(player, skill, amount, reason, at, "e" + (++sequence));
    }

    private void grant(UUID player, SkillType skill, long amount, String reason, String at, String eventId)
            throws Exception {
        database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO xp_grants (player_uuid, skill, event_id, amount, reason, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, skill.name());
                statement.setString(3, eventId);
                statement.setLong(4, amount);
                statement.setString(5, reason);
                statement.setString(6, Instant.parse(at).toString());
                return statement.executeUpdate();
            }
        }).get(TIMEOUT, TimeUnit.SECONDS);
    }

    private List<StatisticsRepository.RankedRow> live(StatPeriod period, StatMetric metric) throws Exception {
        return statistics.liveTop(period, metric, 10, PUBLIC, Set.of(), ADMIN).get(TIMEOUT, TimeUnit.SECONDS);
    }

    private static StatisticsRepository.RankedRow row(String name, long value, int rank) {
        return new StatisticsRepository.RankedRow(name, value, rank);
    }

    @Test
    void lifetimeIsIsolatedFromSeasons() throws Exception {
        progression.grantXp(alice, SkillType.COMBAT, 1000, "combat_kill", "old", Long.MAX_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
        grant(alice, SkillType.COMBAT, 30, "combat_kill", "2026-11-10T12:00:00Z");

        assertEquals(List.of(row("Alice", 30, 1)), live(SEASON_1, COMBAT), "la saison ne voit que sa fenêtre");
        assertEquals(1000L, progression.topPlayers(SkillType.COMBAT, 10, PUBLIC).get(TIMEOUT, TimeUnit.SECONDS)
                .get(0).totalXp(), "le lifetime reste le total de la piste");
    }

    @Test
    void twoSeasonsKeepTheirValuesApartAndBoundsAreHalfOpen() throws Exception {
        grant(alice, SkillType.COMBAT, 10, "combat_kill", "2026-11-01T00:00:00Z");      // premier instant de S1
        grant(alice, SkillType.COMBAT, 5, "combat_kill", "2026-11-30T23:59:59.999Z");   // dernier instant de S1
        grant(alice, SkillType.COMBAT, 7, "combat_kill", "2026-12-01T00:00:00Z");       // premier instant de S2
        grant(alice, SkillType.COMBAT, 99, "combat_kill", "2026-10-31T23:59:59.5Z");    // avant S1

        assertEquals(List.of(row("Alice", 15, 1)), live(SEASON_1, COMBAT));
        assertEquals(List.of(row("Alice", 7, 1)), live(SEASON_2, COMBAT));
    }

    @Test
    void twoSimultaneousEventsCountTheSameAction() throws Exception {
        StatPeriod weekend = period("zombie_weekend", StatScope.EVENT, "2026-11-07T18:00:00Z", "2026-11-09T22:00:00Z",
                "RPG_XP_COMBAT");
        StatPeriod month = period("hunt_month", StatScope.EVENT, "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z",
                "RPG_XP_COMBAT");
        grant(bob, SkillType.COMBAT, 12, "combat_kill", "2026-11-08T10:00:00Z");
        grant(bob, SkillType.COMBAT, 3, "combat_kill", "2026-11-20T10:00:00Z");

        assertEquals(List.of(row("Bob", 12, 1)), live(weekend, COMBAT));
        assertEquals(List.of(row("Bob", 15, 1)), live(month, COMBAT));
    }

    @Test
    void aRetriedActionIsNeverCountedTwice() throws Exception {
        grant(bob, SkillType.COMBAT, 12, "combat_kill", "2026-11-08T10:00:00Z", "kill:42");
        grant(bob, SkillType.COMBAT, 12, "combat_kill", "2026-11-08T10:00:01Z", "kill:42");

        assertEquals(List.of(row("Bob", 12, 1)), live(SEASON_1, COMBAT));
    }

    @Test
    void adminGrantsAndExcludedPlayersDoNotReachThePublicBoard() throws Exception {
        grant(alice, SkillType.COMBAT, 5000, "admin_grant", "2026-11-08T10:00:00Z");
        grant(alice, SkillType.COMBAT, 4, "combat_kill", "2026-11-08T11:00:00Z");
        grant(carol, SkillType.COMBAT, 50, "combat_kill", "2026-11-08T11:00:00Z");

        assertEquals(List.of(row("Alice", 4, 1)),
                statistics.liveTop(SEASON_1, COMBAT, 10, PUBLIC, Set.of(carol), ADMIN).get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void topNIsCorrectAndTiesAreDeterministic() throws Exception {
        grant(carol, SkillType.COMBAT, 20, "combat_kill", "2026-11-08T10:00:00Z");
        grant(bob, SkillType.COMBAT, 20, "combat_kill", "2026-11-08T10:00:00Z");
        grant(alice, SkillType.COMBAT, 50, "combat_kill", "2026-11-08T10:00:00Z");

        assertEquals(List.of(row("Alice", 50, 1), row("Bob", 20, 2), row("Carol", 20, 2)), live(SEASON_1, COMBAT));
        assertEquals(List.of(row("Alice", 50, 1), row("Bob", 20, 2)),
                statistics.liveTop(SEASON_1, COMBAT, 2, PUBLIC, Set.of(), ADMIN).get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void competitionRankingSkipsAfterTies() {
        assertEquals(List.of(row("A", 9, 1), row("B", 9, 1), row("C", 5, 3)),
                StatisticsRepository.rank(List.of("A", "B", "C"), List.of(9L, 9L, 5L)));
    }

    @Test
    void archivingFreezesResultsOnceAndSurvivesAPurgedJournal() throws Exception {
        grant(alice, SkillType.COMBAT, 30, "combat_kill", "2026-11-08T10:00:00Z");
        grant(alice, SkillType.GLOBAL, 3, "combat_kill", "2026-11-08T10:00:00Z");
        grant(bob, SkillType.COMBAT, 999, "admin_grant", "2026-11-08T10:00:00Z");
        Instant end = Instant.parse("2026-12-01T00:00:05Z");

        assertTrue(statistics.archive(SEASON_1, ADMIN, end).get(TIMEOUT, TimeUnit.SECONDS));
        grant(alice, SkillType.COMBAT, 1000, "combat_kill", "2026-11-09T10:00:00Z"); // arrivé trop tard
        assertFalse(statistics.archive(SEASON_1, ADMIN, end).get(TIMEOUT, TimeUnit.SECONDS), "idempotent");
        progression.resetPlayer(alice).get(TIMEOUT, TimeUnit.SECONDS);                // purge du journal

        assertEquals(Set.of("season_01"), statistics.archivedPeriodIds().get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of(row("Alice", 30, 1)),
                statistics.archivedTop(SEASON_1, COMBAT, 10, PUBLIC, Set.of()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of(row("Alice", 3, 1)),
                statistics.archivedTop(SEASON_1, GLOBAL, 10, PUBLIC, Set.of()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of(), statistics.archivedTop(SEASON_1, COMBAT, 10, PUBLIC, Set.of(alice))
                .get(TIMEOUT, TimeUnit.SECONDS), "une exclusion décidée après coup s'applique aussi à l'archive");
    }

    @Test
    void anArchivedSeasonDoesNotTouchTheNextOne() throws Exception {
        grant(alice, SkillType.COMBAT, 30, "combat_kill", "2026-11-08T10:00:00Z");
        grant(alice, SkillType.COMBAT, 8, "combat_kill", "2026-12-08T10:00:00Z");
        statistics.archive(SEASON_1, ADMIN, Instant.parse("2026-12-01T00:01:00Z")).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of(row("Alice", 8, 1)), live(SEASON_2, COMBAT));
        assertEquals(List.of(row("Alice", 30, 1)),
                statistics.archivedTop(SEASON_1, COMBAT, 10, PUBLIC, Set.of()).get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void hiddenProfilesStayOffPeriodBoards() throws Exception {
        grant(alice, SkillType.COMBAT, 30, "combat_kill", "2026-11-08T10:00:00Z");

        assertEquals(List.of(), statistics.liveTop(SEASON_1, COMBAT, 10, Visibility.PRIVATE, Set.of(), ADMIN)
                .get(TIMEOUT, TimeUnit.SECONDS), "profil privé par défaut (#55) : absent");
    }

    private java.util.Optional<StatisticsRepository.Standing> liveStanding(UUID player, Set<UUID> excluded)
            throws Exception {
        return statistics.liveStanding(SEASON_1, COMBAT, player, excluded, ADMIN).get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void personalStandingCountsEveryRankedPlayerWithCompetitionTies() throws Exception {
        grant(alice, SkillType.COMBAT, 50, "combat_kill", "2026-11-08T10:00:00Z");
        grant(bob, SkillType.COMBAT, 50, "combat_kill", "2026-11-08T10:00:00Z");
        grant(carol, SkillType.COMBAT, 20, "combat_kill", "2026-11-08T10:00:00Z");
        grant(carol, SkillType.COMBAT, 999, "admin_grant", "2026-11-08T10:00:00Z");

        assertEquals(new StatisticsRepository.Standing(50, 1, 3), liveStanding(alice, Set.of()).orElseThrow());
        assertEquals(new StatisticsRepository.Standing(50, 1, 3), liveStanding(bob, Set.of()).orElseThrow());
        assertEquals(new StatisticsRepository.Standing(20, 3, 3), liveStanding(carol, Set.of()).orElseThrow(),
                "ex aequo au rang 1, le suivant est 3e ; l'octroi admin est ignoré");
        assertEquals(new StatisticsRepository.Standing(20, 2, 2), liveStanding(carol, Set.of(bob)).orElseThrow(),
                "un joueur exclu ne compte plus");
        assertTrue(liveStanding(alice, Set.of(alice)).isEmpty(), "un joueur exclu n'a pas de place");
    }

    @Test
    void personalStandingIncludesPrivateProfilesButIsEmptyWithoutValue() throws Exception {
        grant(alice, SkillType.COMBAT, 5, "combat_kill", "2026-11-08T10:00:00Z");

        // Profil privé par défaut (#55) : absent du classement public, mais sa propre place existe.
        assertEquals(List.of(), statistics.liveTop(SEASON_1, COMBAT, 10, Visibility.PRIVATE, Set.of(), ADMIN)
                .get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(new StatisticsRepository.Standing(5, 1, 1), liveStanding(alice, Set.of()).orElseThrow());
        assertTrue(liveStanding(bob, Set.of()).isEmpty(), "aucune valeur : aucune place inventée");
    }

    @Test
    void archivedStandingReadsFrozenResults() throws Exception {
        grant(alice, SkillType.COMBAT, 30, "combat_kill", "2026-11-08T10:00:00Z");
        grant(bob, SkillType.COMBAT, 40, "combat_kill", "2026-11-08T10:00:00Z");
        statistics.archive(SEASON_1, ADMIN, Instant.parse("2026-12-01T00:00:05Z")).get(TIMEOUT, TimeUnit.SECONDS);
        progression.resetPlayer(alice).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(new StatisticsRepository.Standing(30, 2, 2),
                statistics.archivedStanding(SEASON_1, COMBAT, alice, Set.of()).get(TIMEOUT, TimeUnit.SECONDS).orElseThrow());
        assertEquals(new StatisticsRepository.Standing(30, 1, 1),
                statistics.archivedStanding(SEASON_1, COMBAT, alice, Set.of(bob)).get(TIMEOUT, TimeUnit.SECONDS).orElseThrow());
    }

    private void quest(UUID player, String questId, String state) throws Exception {
        database.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO quest_progress (player_uuid, quest_id, state, updated_at) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, player.toString());
                statement.setString(2, questId);
                statement.setString(3, state);
                statement.setString(4, "2026-11-08T10:00:00Z");
                return statement.executeUpdate();
            }
        }).get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void personalQuestStandingCountsOnlyCompletedQuests() throws Exception {
        QuestStatisticsRepository quests = new QuestStatisticsRepository(database);
        quest(alice, "q1", "COMPLETED");
        quest(alice, "q2", "ACTIVE");
        quest(bob, "q1", "COMPLETED");
        quest(bob, "q2", "COMPLETED");

        assertEquals(new StatisticsRepository.Standing(1, 2, 2),
                quests.standing(alice, List.of()).get(TIMEOUT, TimeUnit.SECONDS).orElseThrow());
        assertEquals(new StatisticsRepository.Standing(1, 1, 1),
                quests.standing(alice, List.of(bob)).get(TIMEOUT, TimeUnit.SECONDS).orElseThrow());
        assertTrue(quests.standing(carol, List.of()).get(TIMEOUT, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void aRealisticJournalIsRankedQuickly() throws Exception {
        int players = 500;
        int grantsPerPlayer = 100;
        database.execute(connection -> {
            connection.setAutoCommit(false);
            try (PreparedStatement profile = connection.prepareStatement(
                    "INSERT INTO player_profiles (uuid, last_name, created_at, updated_at) VALUES (?, ?, ?, ?)");
                 PreparedStatement grant = connection.prepareStatement(
                    "INSERT INTO xp_grants (player_uuid, skill, event_id, amount, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                Instant base = Instant.parse("2026-11-01T00:00:00Z");
                for (int p = 0; p < players; p++) {
                    String uuid = new UUID(42L, p).toString();
                    profile.setString(1, uuid);
                    profile.setString(2, "P" + p);
                    profile.setString(3, base.toString());
                    profile.setString(4, base.toString());
                    profile.addBatch();
                    for (int g = 0; g < grantsPerPlayer; g++) {
                        grant.setString(1, uuid);
                        grant.setString(2, g % 2 == 0 ? "COMBAT" : "MINING");
                        grant.setString(3, "bulk:" + p + ":" + g);
                        grant.setLong(4, 1 + (p % 7));
                        grant.setString(5, "combat_kill");
                        grant.setString(6, base.plusSeconds(3600L * g + p).toString());
                        grant.addBatch();
                    }
                }
                profile.executeBatch();
                grant.executeBatch();
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
            return null;
        }).get(60, TimeUnit.SECONDS);

        long started = System.nanoTime();
        var top = statistics.liveTop(SEASON_1, COMBAT, 10, PUBLIC, Set.of(), ADMIN).get(TIMEOUT, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(10, top.size());
        assertTrue(elapsedMillis < 2_000, "50 000 octrois classés en " + elapsedMillis + " ms");
    }
}
