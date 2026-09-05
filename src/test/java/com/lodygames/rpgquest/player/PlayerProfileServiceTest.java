package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerProfileServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PlayerProfileService service;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service = new PlayerProfileService(new PlayerProfileRepository(database));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void joinPopulatesCacheAndQuitInvalidatesIt() throws Exception {
        UUID uuid = UUID.randomUUID();

        service.loadOnJoin(uuid, "Steve").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(service.cached(uuid).isPresent());

        service.invalidate(uuid);
        assertFalse(service.cached(uuid).isPresent());
    }

    @Test
    void getOrLoadReturnsCachedProfileWithoutHittingRepositoryAgain() throws Exception {
        UUID uuid = UUID.randomUUID();
        service.loadOnJoin(uuid, "Alex").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var cachedResult = service.getOrLoad(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(cachedResult.isPresent());
        assertEquals("Alex", cachedResult.get().lastName());

        service.invalidate(uuid);
        var reloaded = service.getOrLoad(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(reloaded.isPresent(), "le profil doit rester lisible en base après invalidation du cache");
    }
}
