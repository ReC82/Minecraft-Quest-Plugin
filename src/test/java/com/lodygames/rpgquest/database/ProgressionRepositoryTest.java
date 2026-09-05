package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.progression.model.SkillType;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressionRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private ProgressionRepository repository;
    private PlayerProfileRepository profiles;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new ProgressionRepository(database);
        profiles = new PlayerProfileRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private UUID newPlayer(String name) throws Exception {
        UUID uuid = UUID.randomUUID();
        profiles.findOrCreate(uuid, name).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return uuid;
    }

    @Test
    void topPlayersReturnsHighestXpFirst() throws Exception {
        UUID alice = newPlayer("Alice");
        UUID bob = newPlayer("Bob");
        UUID carol = newPlayer("Carol");

        repository.grantXp(alice, SkillType.MINING, 500, "test", "e1", Long.MAX_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.grantXp(bob, SkillType.MINING, 1500, "test", "e2", Long.MAX_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.grantXp(carol, SkillType.MINING, 900, "test", "e3", Long.MAX_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<ProgressionRepository.LeaderboardRow> top =
                repository.topPlayers(SkillType.MINING, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(3, top.size());
        assertEquals("Bob", top.get(0).name());
        assertEquals(1500L, top.get(0).totalXp());
        assertEquals("Carol", top.get(1).name());
        assertEquals("Alice", top.get(2).name());
    }

    @Test
    void topPlayersRespectsLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            UUID player = newPlayer("Player" + i);
            repository.grantXp(player, SkillType.COMBAT, 10L * (i + 1), "test", "e" + i, Long.MAX_VALUE)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        List<ProgressionRepository.LeaderboardRow> top =
                repository.topPlayers(SkillType.COMBAT, 2).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, top.size());
        assertTrue(top.get(0).totalXp() >= top.get(1).totalXp());
    }

    @Test
    void topPlayersExcludesPlayersWithZeroXp() throws Exception {
        newPlayer("Zero");
        UUID scorer = newPlayer("Scorer");
        repository.grantXp(scorer, SkillType.FISHING, 5, "test", "e1", Long.MAX_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        List<ProgressionRepository.LeaderboardRow> top =
                repository.topPlayers(SkillType.FISHING, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, top.size());
        assertEquals("Scorer", top.get(0).name());
    }

    @Test
    void topPlayersOnUnusedSkillIsEmpty() throws Exception {
        List<ProgressionRepository.LeaderboardRow> top =
                repository.topPlayers(SkillType.EXPLORATION, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(top.isEmpty());
    }
}
