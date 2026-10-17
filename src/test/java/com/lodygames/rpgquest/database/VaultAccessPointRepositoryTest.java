package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.VaultAccessPointRepository.StoredAccessPoint;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #105 — clé (monde, x, y, z), relier, délier, plusieurs points par vault_id (schéma V44). */
class VaultAccessPointRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final UUID ADMIN = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private VaultAccessPointRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new VaultAccessPointRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void severalPositionsMayTargetTheSameVaultAndRebindReplacesInPlace() throws Exception {
        await(repository.save(new StoredAccessPoint("hub_a", 1, 64, 2, "vault_01"), ADMIN));
        await(repository.save(new StoredAccessPoint("hub_b", -5, 70, 9, "vault_01"), ADMIN));
        await(repository.save(new StoredAccessPoint("hub_a", 1, 64, 2, "vault_02"), ADMIN));

        assertEquals(List.of(new StoredAccessPoint("hub_b", -5, 70, 9, "vault_01"),
                new StoredAccessPoint("hub_a", 1, 64, 2, "vault_02")), await(repository.findAll()));
    }

    @Test
    void deleteRemovesOnlyThatPosition() throws Exception {
        await(repository.save(new StoredAccessPoint("hub_a", 1, 64, 2, "vault_01"), ADMIN));
        await(repository.save(new StoredAccessPoint("hub_a", 2, 64, 2, "vault_01"), ADMIN));

        assertTrue(await(repository.delete("hub_a", 1, 64, 2)));
        assertFalse(await(repository.delete("hub_a", 1, 64, 2)));
        assertEquals(List.of(new StoredAccessPoint("hub_a", 2, 64, 2, "vault_01")), await(repository.findAll()));
    }
}
