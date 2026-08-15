package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Couvre la persistance de la liaison PNJ Citizens <-> id RPGQuest — le cœur
 * du correctif : cette table (contrairement à un {@code PersistentDataContainer}
 * posé sur l'entité Bukkit éphémère que Citizens recrée à chaque redémarrage)
 * doit survivre à une réinstanciation complète du repository, simulant un
 * redémarrage du serveur.
 */
class NpcBindingRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private NpcBindingRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new NpcBindingRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void findReturnsEmptyForAnUnknownCitizensNpc() throws Exception {
        Optional<String> found = repository.find(UUID.randomUUID()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(found.isEmpty());
    }

    @Test
    void upsertThenFindReturnsTheBoundNpcId() throws Exception {
        UUID citizensUuid = UUID.randomUUID();

        repository.upsert(citizensUuid, 4, "libraire").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("libraire", repository.find(citizensUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow());
    }

    @Test
    void upsertOnAnAlreadyBoundCitizensUuidReplacesTheNpcId() throws Exception {
        UUID citizensUuid = UUID.randomUUID();
        repository.upsert(citizensUuid, 4, "libraire").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.upsert(citizensUuid, 4, "bookseller").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("bookseller", repository.find(citizensUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow());
    }

    @Test
    void deleteRemovesTheBinding() throws Exception {
        UUID citizensUuid = UUID.randomUUID();
        repository.upsert(citizensUuid, 4, "libraire").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.delete(citizensUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.find(citizensUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void bindingSurvivesAFreshRepositoryInstanceOverTheSameDatabase() throws Exception {
        // Simule un redémarrage : le mapping vit dans data.db, jamais dans un objet Java en mémoire
        // ni sur l'entité Bukkit éphémère recréée par Citizens à chaque (re)spawn.
        UUID citizensUuid = UUID.randomUUID();
        repository.upsert(citizensUuid, 3, "guide").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        NpcBindingRepository freshRepository = new NpcBindingRepository(database);

        List<NpcBindingRepository.Binding> all = freshRepository.loadAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, all.size());
        assertEquals(citizensUuid, all.get(0).citizensUuid());
        assertEquals(3, all.get(0).citizensNumericId());
        assertEquals("guide", all.get(0).npcId());
    }
}
