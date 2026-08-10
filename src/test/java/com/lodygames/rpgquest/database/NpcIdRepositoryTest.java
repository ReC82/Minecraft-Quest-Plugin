package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NpcIdRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private NpcIdRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new NpcIdRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void allocatedIdsAreStrictlyIncreasingAndNeverReused() throws Exception {
        int first = repository.allocateId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int second = repository.allocateId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int third = repository.allocateId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertEquals(first + 1, second);
        assertEquals(second + 1, third);
    }
}
