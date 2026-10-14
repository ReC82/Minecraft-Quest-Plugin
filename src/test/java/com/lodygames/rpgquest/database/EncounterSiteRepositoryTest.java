package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.encounter.model.EncounterSite;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #13 — persistance des occurrences de PNJ récurrents : une seule par instance de biome. */
class EncounterSiteRepositoryTest {

    private static final long TIMEOUT = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private EncounterSiteRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        repository = new EncounterSiteRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void anInstanceNeverHoldsTwoEncounters() throws Exception {
        assertTrue(repository.insertIfAbsent(site("enc_a", "minecraft:forest@1,2", "forest_bandit"))
                .get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(repository.insertIfAbsent(site("enc_b", "minecraft:forest@1,2", "other"))
                .get(TIMEOUT, TimeUnit.SECONDS), "même (monde, instance) : refusé même avec un autre id");
        assertTrue(repository.insertIfAbsent(site("enc_c", "minecraft:forest@1,3", "forest_bandit"))
                .get(TIMEOUT, TimeUnit.SECONDS), "une autre instance du même biome reçoit sa rencontre");

        assertEquals(2, repository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    @Test
    void theCitizensEntityStatusAndErrorSurviveARestart() throws Exception {
        repository.insertIfAbsent(site("enc_a", "minecraft:forest@1,2", "forest_bandit")).get(TIMEOUT, TimeUnit.SECONDS);
        UUID citizens = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-14T01:00:00Z");
        repository.updateCitizens("enc_a", citizens, 42, at).get(TIMEOUT, TimeUnit.SECONDS);
        repository.updateError("enc_a", "Citizens absent", at).get(TIMEOUT, TimeUnit.SECONDS);
        repository.updateStatus("enc_a", EncounterSite.Status.DISABLED, at).get(TIMEOUT, TimeUnit.SECONDS);

        database.shutdown();
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        repository = new EncounterSiteRepository(database);
        EncounterSite reloaded = repository.findByInstance("wild", "minecraft:forest@1,2")
                .get(TIMEOUT, TimeUnit.SECONDS).orElseThrow();

        assertEquals(citizens, reloaded.citizensUuid());
        assertEquals(42, reloaded.citizensId());
        assertEquals(EncounterSite.Status.DISABLED, reloaded.status());
        assertEquals("Citizens absent", reloaded.lastError());
        assertEquals("encounter_bandit", reloaded.npcId());
        assertEquals(at, reloaded.updatedAt());
    }

    @Test
    void clearingTheCitizensEntityAndDeletingWork() throws Exception {
        repository.insertIfAbsent(site("enc_a", "minecraft:forest@1,2", "forest_bandit")).get(TIMEOUT, TimeUnit.SECONDS);
        repository.updateCitizens("enc_a", UUID.randomUUID(), 3, Instant.now()).get(TIMEOUT, TimeUnit.SECONDS);
        repository.updateCitizens("enc_a", null, 3, Instant.now()).get(TIMEOUT, TimeUnit.SECONDS);

        EncounterSite cleared = repository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).get(0);
        assertNull(cleared.citizensUuid());
        assertEquals(-1, cleared.citizensId());

        assertTrue(repository.delete("enc_a").get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of(), repository.loadAll().get(TIMEOUT, TimeUnit.SECONDS));
    }

    private static EncounterSite site(String id, String instance, String template) {
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        return new EncounterSite(id, "wild", instance, "minecraft:forest", 1, 2, template, "encounter_bandit",
                300, 70, 600, null, -1, EncounterSite.Status.ACTIVE, "", now, now);
    }
}
