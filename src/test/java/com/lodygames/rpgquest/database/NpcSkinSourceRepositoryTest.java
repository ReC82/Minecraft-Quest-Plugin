package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Source d'apparence d'un PNJ Citizens (migration V27).
 *
 * <p>Ce que cette table doit garantir : qu'un renommage puisse reconduire l'apparence exacte, et
 * que cette information <strong>survive à un redémarrage</strong> — c'est précisément ce qui
 * manquait, et pourquoi renommer changeait le skin.</p>
 */
class NpcSkinSourceRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private NpcSkinSourceRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new NpcSkinSourceRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void anUnknownNpcHasNoRecordedSource() throws Exception {
        assertTrue(repository.find(UUID.randomUUID()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "aucune source ne doit être inventée pour un PNJ jamais habillé");
    }

    @Test
    void anAppliedUrlIsStoredAndReadBack() throws Exception {
        UUID npc = UUID.randomUUID();
        repository.save(npc, NpcSkinSourceRepository.Kind.URL, "https://minesk.in/abcdefgh")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Optional<NpcSkinSourceRepository.SkinSource> found =
                repository.find(npc).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(found.isPresent());
        assertEquals(NpcSkinSourceRepository.Kind.URL, found.get().kind());
        assertEquals("https://minesk.in/abcdefgh", found.get().value());
    }

    @Test
    void aNewSourceReplacesThePreviousOneForTheSameNpc() throws Exception {
        UUID npc = UUID.randomUUID();
        repository.save(npc, NpcSkinSourceRepository.Kind.NAME, "Tan").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.save(npc, NpcSkinSourceRepository.Kind.URL, "https://minesk.in/zzzzzzzz")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        NpcSkinSourceRepository.SkinSource found =
                repository.find(npc).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(NpcSkinSourceRepository.Kind.URL, found.kind(), "la dernière application gagne");
        assertEquals("https://minesk.in/zzzzzzzz", found.value());
    }

    @Test
    void twoNpcsKeepTheirOwnSource() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        repository.save(first, NpcSkinSourceRepository.Kind.NAME, "Tan").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.save(second, NpcSkinSourceRepository.Kind.URL, "https://minesk.in/abcdefgh")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("Tan", repository.find(first).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().value());
        assertEquals("https://minesk.in/abcdefgh",
                repository.find(second).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().value());
    }

    @Test
    void theSourceSurvivesAReopenOfTheDatabase() throws Exception {
        UUID npc = UUID.randomUUID();
        repository.save(npc, NpcSkinSourceRepository.Kind.NAME, "Tan").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        database.shutdown();

        // Rouvrir = ce que fait un redémarrage du serveur : l'apparence doit rester reconductible.
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new NpcSkinSourceRepository(database);

        NpcSkinSourceRepository.SkinSource found =
                repository.find(npc).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(NpcSkinSourceRepository.Kind.NAME, found.kind());
        assertEquals("Tan", found.value());
    }

    @Test
    void deletingForgetsOnlyThatNpc() throws Exception {
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        repository.save(kept, NpcSkinSourceRepository.Kind.NAME, "Lily").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.save(removed, NpcSkinSourceRepository.Kind.NAME, "Jo").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.delete(removed).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.find(removed).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        assertEquals("Lily", repository.find(kept).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow().value());
    }
}
