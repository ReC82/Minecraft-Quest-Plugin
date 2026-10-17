package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository.Assignment;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #15 — attribution idempotente et transitions de statut uniques (schéma V42). */
class PeriodicQuestAssignmentRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PeriodicQuestAssignmentRepository repository;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new PeriodicQuestAssignmentRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void aSecondAssignmentForTheSamePeriodNeverOverwritesTheFirst() throws Exception {
        List<Assignment> first = await(repository.assign(player, "daily", "2026-10-17", List.of("q:a", "q:b")));
        List<Assignment> second = await(repository.assign(player, "daily", "2026-10-17", List.of("q:c", "q:d")));

        assertEquals(List.of("q:a", "q:b"), first.stream().map(Assignment::questId).toList());
        assertEquals(first, second, "l'attribution existante est rendue telle quelle");
        assertTrue(first.stream().allMatch(a -> PeriodicQuestAssignmentRepository.ASSIGNED.equals(a.status())));
    }

    @Test
    void concurrentAssignmentsProduceASingleSelection() throws Exception {
        CompletableFuture<List<Assignment>> a = repository.assign(player, "weekly", "2026-10-12", List.of("q:a"));
        CompletableFuture<List<Assignment>> b = repository.assign(player, "weekly", "2026-10-12", List.of("q:b"));
        assertEquals(await(a).size(), 1);
        assertEquals(await(a), await(b));
        assertEquals(1, await(repository.findPeriod(player, "weekly", "2026-10-12")).size());
    }

    @Test
    void statusTransitionsHappenOnce() throws Exception {
        Assignment row = await(repository.assign(player, "daily", "2026-10-17", List.of("q:a"))).get(0);

        assertTrue(await(repository.markStarted(player, row)));
        assertFalse(await(repository.markStarted(player, row)), "double démarrage refusé");

        Assignment started = await(repository.findPeriod(player, "daily", "2026-10-17")).get(0);
        assertEquals(PeriodicQuestAssignmentRepository.STARTED, started.status());
        assertEquals(List.of(started), await(repository.findOpen(player)));

        assertTrue(await(repository.close(player, started)));
        assertFalse(await(repository.close(player, started)), "double expiration refusée");
        assertTrue(await(repository.findOpen(player)).isEmpty());
    }

    @Test
    void previousQuestIdsReadTheLatestEarlierPeriodOnly() throws Exception {
        await(repository.assign(player, "daily", "2026-10-15", List.of("q:old")));
        await(repository.assign(player, "daily", "2026-10-16", List.of("q:a", "q:b")));
        await(repository.assign(player, "weekly", "2026-10-12", List.of("q:w")));

        assertEquals(List.of("q:a", "q:b"), await(repository.previousQuestIds(player, "daily", "2026-10-17")));
        assertEquals(List.of("q:old"), await(repository.previousQuestIds(player, "daily", "2026-10-16")));
        assertTrue(await(repository.previousQuestIds(player, "daily", "2026-10-15")).isEmpty());
        assertTrue(await(repository.previousQuestIds(UUID.randomUUID(), "daily", "2026-10-17")).isEmpty());
    }
}
