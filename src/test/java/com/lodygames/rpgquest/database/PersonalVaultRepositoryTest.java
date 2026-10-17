package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #104 — clé (joueur, vault_id), isolation et survie à un redémarrage (schéma V43). */
class PersonalVaultRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PersonalVaultRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = open();
        repository = new PersonalVaultRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private DatabaseManager open() throws Exception {
        DatabaseManager manager = new DatabaseManager(tempDir.resolve("data.db"));
        manager.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return manager;
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void sameVaultIdIsIndependentPerPlayer() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        await(repository.save(alice, "vault_01", 1, new byte[] {1, 2}));
        await(repository.save(bob, "vault_01", 1, new byte[] {9}));

        assertArrayEquals(new byte[] {1, 2}, await(repository.find(alice, "vault_01")).orElseThrow().contents());
        assertArrayEquals(new byte[] {9}, await(repository.find(bob, "vault_01")).orElseThrow().contents());
    }

    @Test
    void severalVaultsPerPlayerAndUpsertReplacesContents() throws Exception {
        UUID player = UUID.randomUUID();
        await(repository.save(player, "vault_02", 1, new byte[] {2}));
        await(repository.save(player, "vault_01", 1, new byte[] {1}));
        await(repository.save(player, "vault_01", 1, new byte[] {3}));

        assertEquals(List.of("vault_01", "vault_02"), await(repository.vaultIds(player)));
        assertArrayEquals(new byte[] {3}, await(repository.find(player, "vault_01")).orElseThrow().contents());
        assertTrue(await(repository.find(player, "vault_03")).isEmpty());
    }

    @Test
    void contentsSurviveARestart() throws Exception {
        UUID player = UUID.randomUUID();
        await(repository.save(player, "vault_01", 1, new byte[] {7, 7}));
        database.shutdown();

        database = open();
        PersonalVaultRepository reopened = new PersonalVaultRepository(database);
        PersonalVaultRepository.StoredVault stored = await(reopened.find(player, "vault_01")).orElseThrow();
        assertEquals(1, stored.schemaVersion());
        assertArrayEquals(new byte[] {7, 7}, stored.contents());
    }
}
