package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceNodeRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private ResourceNodeRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new ResourceNodeRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void upsertsAndReadsBackAllNodes() throws Exception {
        repository.upsert(new ResourceNodeRecord("world", 1, 2, 3, "rpgquest:crystal_ore", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsert(new ResourceNodeRecord("world_nether", 4, 5, 6, "rpgquest:crystal_ore", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<ResourceNodeRecord> all = repository.findAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, all.size());
    }

    @Test
    void upsertOnSameCoordinatesReplacesTheType() throws Exception {
        repository.upsert(new ResourceNodeRecord("world", 1, 2, 3, "rpgquest:crystal_ore", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsert(new ResourceNodeRecord("world", 1, 2, 3, "rpgquest:other_type", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<ResourceNodeRecord> all = repository.findAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, all.size());
        assertEquals("rpgquest:other_type", all.get(0).typeId());
    }

    @Test
    void depletedAtSurvivesRoundTrip() throws Exception {
        Instant depletedAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.upsert(new ResourceNodeRecord("world", 1, 2, 3, "rpgquest:crystal_ore", depletedAt))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ResourceNodeRecord record = repository.findAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(0);
        assertEquals(depletedAt, record.depletedAt());
    }

    @Test
    void updateDepletedAtChangesOnlyThatColumn() throws Exception {
        repository.upsert(new ResourceNodeRecord("world", 1, 2, 3, "rpgquest:crystal_ore", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Instant depletedAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.updateDepletedAt("world", 1, 2, 3, depletedAt).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ResourceNodeRecord record = repository.findAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(0);
        assertEquals("rpgquest:crystal_ore", record.typeId());
        assertEquals(depletedAt, record.depletedAt());

        repository.updateDepletedAt("world", 1, 2, 3, null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ResourceNodeRecord cleared = repository.findAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(0);
        assertNull(cleared.depletedAt());
    }

    @Test
    void deleteRemovesTheNode() throws Exception {
        repository.upsert(new ResourceNodeRecord("world", 1, 2, 3, "rpgquest:crystal_ore", null))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.delete("world", 1, 2, 3).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.findAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }
}
