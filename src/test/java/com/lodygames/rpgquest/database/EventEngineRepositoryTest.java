package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.player.PublicProfilePolicy.Visibility;
import com.lodygames.rpgquest.stats.EventLifecycle;
import com.lodygames.rpgquest.stats.EventRanking;
import com.lodygames.rpgquest.stats.EventRules;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatScope;
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
 * Issue #57 — moteur d'événements générique : journal d'activité, filtres de cible, règles de
 * classement, objectif commun, archivage et audit. Vraie base SQLite.
 */
class EventEngineRepositoryTest {

    private static final long TIMEOUT = 10;
    private static final StatMetric KILLS = StatMetric.find(StatMetric.ENTITY_KILL).orElseThrow();
    private static final StatMetric FISH = StatMetric.find(StatMetric.FISH_CAUGHT).orElseThrow();
    private static final String START = "2026-11-07T18:00:00Z";
    private static final String END = "2026-11-09T22:00:00Z";

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private StatisticsRepository statistics;
    private ActivityLogRepository journal;

    private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private final UUID bob = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private final UUID carol = UUID.fromString("00000000-0000-0000-0000-0000000ca201");
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
        profiles.findOrCreate(carol, "Carol").get(TIMEOUT, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static StatPeriod event(String id, String metric, EventRules rules) {
        return new StatPeriod(id, StatScope.EVENT, id, Instant.parse(START), Instant.parse(END), List.of(metric),
                StatPeriod.State.PLANNED, rules);
    }

    private static EventRules rules(EventRanking ranking, Map<String, Set<String>> filter, long goal) {
        return new EventRules(ranking, filter, goal, List.of());
    }

    private static final EventRules ZOMBIES = rules(EventRanking.HIGHEST_TOTAL, Map.of("entity", Set.of("ZOMBIE")), 0);

    private boolean kill(UUID player, String entity, String variant, String at) throws Exception {
        return kill("kill:" + (++sequence), player, entity, variant, at);
    }

    private boolean kill(String key, UUID player, String entity, String variant, String at) throws Exception {
        return journal.record(new ActivityLogRepository.Activity(key, player, StatMetric.ENTITY_KILL, entity, variant, 1,
                Instant.parse(at))).get(TIMEOUT, TimeUnit.SECONDS);
    }

    private List<StatisticsRepository.RankedRow> live(StatPeriod period, StatMetric metric) throws Exception {
        return statistics.liveTop(period, metric, 10, Visibility.PUBLIC, Set.of(), Set.of()).get(TIMEOUT, TimeUnit.SECONDS);
    }

    private static StatisticsRepository.RankedRow row(String name, long value, int rank) {
        return new StatisticsRepository.RankedRow(name, value, rank);
    }

    @Test
    void theEntityFilterOnlyCountsTheTargetedMobs() throws Exception {
        kill(alice, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        kill(alice, "ZOMBIE", "rpgquest:rotting_brute", "2026-11-08T10:01:00Z");
        kill(alice, "SKELETON", null, "2026-11-08T10:02:00Z");
        kill(bob, "SKELETON", null, "2026-11-08T10:03:00Z");

        assertEquals(List.of(row("Alice", 2, 1)), live(event("zombie_weekend", StatMetric.ENTITY_KILL, ZOMBIES), KILLS));
        assertEquals(List.of(row("Alice", 3, 1), row("Bob", 1, 2)),
                live(event("all_mobs", StatMetric.ENTITY_KILL, EventRules.DEFAULT), KILLS), "sans filtre : tout compte");
        EventRules brutes = rules(EventRanking.HIGHEST_TOTAL, Map.of("special-mob", Set.of("rpgquest:rotting_brute")), 0);
        assertEquals(List.of(row("Alice", 1, 1)), live(event("brutes", StatMetric.ENTITY_KILL, brutes), KILLS));
    }

    @Test
    void aRetryIsNeverCountedTwice() throws Exception {
        assertTrue(kill("kill:same-entity", alice, "ZOMBIE", null, "2026-11-08T10:00:00Z"));
        assertFalse(kill("kill:same-entity", alice, "ZOMBIE", null, "2026-11-08T10:00:01Z"), "même entité, même clé");

        assertEquals(List.of(row("Alice", 1, 1)), live(event("zombie_weekend", StatMetric.ENTITY_KILL, ZOMBIES), KILLS));
    }

    @Test
    void simultaneousEventsEachCountTheSameKillOnce() throws Exception {
        kill(alice, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        StatPeriod weekend = event("zombie_weekend", StatMetric.ENTITY_KILL, ZOMBIES);
        StatPeriod hunt = event("hunt", StatMetric.ENTITY_KILL, EventRules.DEFAULT);

        assertEquals(List.of(row("Alice", 1, 1)), live(weekend, KILLS));
        assertEquals(List.of(row("Alice", 1, 1)), live(hunt, KILLS));
    }

    @Test
    void killsOutsideTheWindowDoNotCount() throws Exception {
        kill(alice, "ZOMBIE", null, "2026-11-07T17:59:59Z");
        kill(alice, "ZOMBIE", null, END);

        assertEquals(List.of(), live(event("zombie_weekend", StatMetric.ENTITY_KILL, ZOMBIES), KILLS));
    }

    @Test
    void tiesAreDeterministic() throws Exception {
        kill(carol, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        kill(bob, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        kill(alice, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        kill(alice, "ZOMBIE", null, "2026-11-08T10:00:01Z");

        assertEquals(List.of(row("Alice", 2, 1), row("Bob", 1, 2), row("Carol", 1, 2)),
                live(event("zombie_weekend", StatMetric.ENTITY_KILL, ZOMBIES), KILLS));
    }

    @Test
    void bestSingleRanksTheLargestActionNotTheSum() throws Exception {
        for (int i = 0; i < 3; i++) {
            journal.record(new ActivityLogRepository.Activity("f" + i, alice, StatMetric.FISH_CAUGHT, "COD", null, 2,
                    Instant.parse("2026-11-08T10:00:0" + i + "Z"))).get(TIMEOUT, TimeUnit.SECONDS);
        }
        journal.record(new ActivityLogRepository.Activity("fb", bob, StatMetric.FISH_CAUGHT, "COD", null, 5,
                Instant.parse("2026-11-08T11:00:00Z"))).get(TIMEOUT, TimeUnit.SECONDS);

        StatPeriod best = event("biggest_catch", StatMetric.FISH_CAUGHT, rules(EventRanking.BEST_SINGLE, Map.of(), 0));
        assertEquals(List.of(row("Bob", 5, 1), row("Alice", 2, 2)), live(best, FISH));
        StatPeriod total = event("most_catches", StatMetric.FISH_CAUGHT, EventRules.DEFAULT);
        assertEquals(List.of(row("Alice", 6, 1), row("Bob", 5, 2)), live(total, FISH));
    }

    @Test
    void theCommunityTotalCountsPrivateProfilesButNotExcludedPlayers() throws Exception {
        hide(alice);
        kill(alice, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        kill(bob, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        kill(carol, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        StatPeriod goal = event("community", StatMetric.ENTITY_KILL,
                rules(EventRanking.COMMUNITY_GOAL, Map.of("entity", Set.of("ZOMBIE")), 100));

        assertEquals(2L, statistics.communityTotal(goal, KILLS, Set.of(carol), Set.of()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of(row("Bob", 1, 1), row("Carol", 1, 1)), live(goal, KILLS), "le profil privé n'est jamais nommé");
    }

    /** {@code /profile web prive} (#55). */
    private void hide(UUID player) throws Exception {
        new PlayerVariableRepository(database).set(player, com.lodygames.rpgquest.player.PublicProfilePolicy.HIDDEN_KEY,
                com.lodygames.rpgquest.player.PublicProfilePolicy.HIDDEN_VALUE).get(TIMEOUT, TimeUnit.SECONDS);
    }

    @Test
    void theArchiveFreezesFilteredResultsAndIsAudited() throws Exception {
        StatPeriod weekend = event("zombie_weekend", StatMetric.ENTITY_KILL, ZOMBIES);
        kill(alice, "ZOMBIE", null, "2026-11-08T10:00:00Z");
        kill(alice, "SKELETON", null, "2026-11-08T10:00:00Z");
        statistics.recordTransition(weekend.id(), EventLifecycle.ACTIVE, Instant.parse(START)).get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(statistics.archive(weekend, Set.of(), Instant.parse(END)).get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(statistics.archive(weekend, Set.of(), Instant.parse(END)).get(TIMEOUT, TimeUnit.SECONDS));
        // Un kill tardif horodaté dans la fenêtre (rejeu) ne change plus le résultat figé.
        kill(alice, "ZOMBIE", null, "2026-11-08T11:00:00Z");

        assertEquals(List.of(row("Alice", 1, 1)),
                statistics.archivedTop(weekend, KILLS, 10, Visibility.PUBLIC, Set.of()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1L, statistics.archivedCommunityTotal(weekend.id(), KILLS, Set.of()).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of("ACTIVE", "ARCHIVED"), statistics.auditTrail(weekend.id()).get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void aTransitionIsRecordedOnlyOnce() throws Exception {
        assertTrue(statistics.recordTransition("e", EventLifecycle.CANCELLED, Instant.parse(START)).get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(statistics.recordTransition("e", EventLifecycle.CANCELLED, Instant.parse(END)).get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of("CANCELLED"), statistics.auditTrail("e").get(TIMEOUT, TimeUnit.SECONDS));
    }
}
