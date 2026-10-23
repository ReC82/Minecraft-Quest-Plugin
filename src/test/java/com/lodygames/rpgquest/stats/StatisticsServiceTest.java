package com.lodygames.rpgquest.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.StatisticsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Issue #54 — chargement de {@code statistics.yml} et archivage automatique des périodes terminées. */
class StatisticsServiceTest {

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private StatisticsRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(10, TimeUnit.SECONDS);
        repository = new StatisticsRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private StatisticsService service(Path file, String now) {
        return new StatisticsService(null, file, repository, LoggerFactory.getLogger("test"),
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    @Test
    void withoutAFileNothingIsDefined() throws Exception {
        StatisticsService service = service(tempDir.resolve("statistics.yml"), "2026-11-15T00:00:00Z");

        service.refresh().get(10, TimeUnit.SECONDS);

        assertTrue(service.definition().periods().isEmpty());
    }

    @Test
    void onlyEndedPeriodsAreArchivedAndOnlyOnce() throws Exception {
        Path file = tempDir.resolve("statistics.yml");
        Files.writeString(file, """
                leaderboards: []
                seasons:
                  - {id: s1, start: "2026-10-01T00:00:00Z", end: "2026-11-01T00:00:00Z", metrics: [RPG_XP_GLOBAL]}
                  - {id: s2, start: "2026-11-01T00:00:00Z", end: "2026-12-01T00:00:00Z", metrics: [RPG_XP_GLOBAL]}
                events:
                  - {id: later, start: "2027-01-01T00:00:00Z", end: "2027-01-02T00:00:00Z", metrics: [RPG_XP_COMBAT]}
                """);
        StatisticsService service = service(file, "2026-11-15T00:00:00Z");

        service.refresh().get(10, TimeUnit.SECONDS);
        service.refresh().get(10, TimeUnit.SECONDS);

        assertEquals(3, service.definition().periods().size());
        assertTrue(service.isArchived("s1"));
        assertFalse(service.isArchived("s2"), "saison en cours : jamais figée");
        assertFalse(service.isArchived("later"), "événement programmé : jamais figé");
        assertEquals(Set.of("s1"), repository.archivedPeriodIds().get(10, TimeUnit.SECONDS));
        assertEquals("s2", service.definition().currentSeason(service.now()).orElseThrow().id());
    }

    @Test
    void withoutAFileTheBundledCatalogueFreezesOnlyTheLastEndedCalendarWindowsOnce() throws Exception {
        StatisticsService service = service(tempDir.resolve("absent.yml"), "2026-11-15T00:00:00Z");

        service.refresh().get(10, TimeUnit.SECONDS);
        service.refresh().get(10, TimeUnit.SECONDS);

        assertTrue(service.definition().periods().isEmpty());
        assertFalse(service.definition().boards().isEmpty(), "catalogue par défaut du JAR (#734)");
        Set<String> archived = repository.archivedPeriodIds().get(10, TimeUnit.SECONDS);
        assertTrue(archived.containsAll(Set.of("board_zombies_week_2026w45", "board_mobs_day_20261114",
                "board_ores_month_202610")), archived.toString());
        assertTrue(archived.stream().noneMatch(id -> id.startsWith("board_mobs_total")), "« depuis toujours » ne se fige pas");
        assertTrue(archived.stream().noneMatch(id -> id.contains("w44")), "jamais d'historique plus ancien");
        assertTrue(service.isArchived("board_zombies_week_2026w45"));
    }

    @Test
    void anInvalidFilePublishesNoPeriod() throws Exception {
        Path file = tempDir.resolve("statistics.yml");
        Files.writeString(file, """
                seasons:
                  - {id: s1, start: "2026-10-01T00:00:00Z", end: "2026-11-01T00:00:00Z", metrics: [NOPE]}
                """);
        StatisticsService service = service(file, "2026-11-15T00:00:00Z");

        service.refresh().get(10, TimeUnit.SECONDS);

        assertTrue(service.definition().periods().isEmpty());
        assertEquals(Set.of(), repository.archivedPeriodIds().get(10, TimeUnit.SECONDS));
    }

    @Test
    void draftAndCancelledEventsAreNeverArchivedAndTransitionsAreAuditedOnce() throws Exception {
        Path file = tempDir.resolve("statistics.yml");
        Files.writeString(file, """
                leaderboards: []
                events:
                  - {id: done, start: "2026-11-01T00:00:00Z", end: "2026-11-02T00:00:00Z", metrics: [ENTITY_KILL]}
                  - {id: live, start: "2026-11-10T00:00:00Z", end: "2026-11-20T00:00:00Z", metrics: [ENTITY_KILL]}
                  - {id: soon, start: "2026-12-01T00:00:00Z", end: "2026-12-02T00:00:00Z", metrics: [ENTITY_KILL]}
                  - {id: draft, status: draft, start: "2026-11-01T00:00:00Z", end: "2026-11-02T00:00:00Z", metrics: [ENTITY_KILL]}
                  - {id: called_off, status: cancelled, start: "2026-11-01T00:00:00Z", end: "2026-11-02T00:00:00Z", metrics: [ENTITY_KILL]}
                """);
        StatisticsService service = service(file, "2026-11-15T00:00:00Z");

        service.refresh().get(10, TimeUnit.SECONDS);
        service.refresh().get(10, TimeUnit.SECONDS);

        assertEquals(Set.of("done"), repository.archivedPeriodIds().get(10, TimeUnit.SECONDS));
        assertEquals(java.util.List.of("ARCHIVED"), repository.auditTrail("done").get(10, TimeUnit.SECONDS));
        assertEquals(java.util.List.of("ACTIVE"), repository.auditTrail("live").get(10, TimeUnit.SECONDS));
        assertEquals(java.util.List.of("SCHEDULED"), repository.auditTrail("soon").get(10, TimeUnit.SECONDS));
        assertEquals(java.util.List.of(), repository.auditTrail("draft").get(10, TimeUnit.SECONDS), "un brouillon n'existe pas encore");
        assertEquals(java.util.List.of("CANCELLED"), repository.auditTrail("called_off").get(10, TimeUnit.SECONDS));
    }

    @Test
    void aTickDuringShutdownIsSkippedInsteadOfFailing() throws Exception {
        DatabaseManager closing = new DatabaseManager(tempDir.resolve("closing.db"));
        closing.initialize().get(10, TimeUnit.SECONDS);
        StatisticsService service = new StatisticsService(null, tempDir.resolve("statistics.yml"),
                new StatisticsRepository(closing), LoggerFactory.getLogger("test"),
                Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC));
        closing.shutdown();

        service.tick(); // base fermée : RejectedExecutionException avalée, aucune exception
        service.stop();
        service.tick(); // service arrêté : rien n'est tenté
    }
}
