package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuestProgressRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey QUEST_ID = new NamespacedKey("rpgquest", "first_steps");

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private QuestProgressRepository repository;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new QuestProgressRepository(database);

        // quest_progress a une FK vers player_profiles : il faut un profil existant.
        playerUuid = UUID.randomUUID();
        new PlayerProfileRepository(database).findOrCreate(playerUuid, "Steve").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void upsertsAndReadsState() throws Exception {
        repository.upsertState(playerUuid, QUEST_ID, QuestState.ACTIVE, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var record = repository.find(playerUuid, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(QuestState.ACTIVE, record.state());
        assertEquals("step_one", record.currentStepId());

        repository.upsertState(playerUuid, QUEST_ID, QuestState.COMPLETED, null)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var updated = repository.find(playerUuid, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(QuestState.COMPLETED, updated.state());
    }

    @Test
    void findAllReturnsEveryQuestForPlayer() throws Exception {
        NamespacedKey second = new NamespacedKey("rpgquest", "second_quest");
        repository.upsertState(playerUuid, QUEST_ID, QuestState.ACTIVE, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertState(playerUuid, second, QuestState.COMPLETED, null)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var all = repository.findAll(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, all.size());
    }

    @Test
    void objectiveProgressCountersAreUpsertedAndReadBack() throws Exception {
        repository.setObjectiveProgress(playerUuid, QUEST_ID, "step_one", 0, 3)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.setObjectiveProgress(playerUuid, QUEST_ID, "step_one", 1, 1)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Map<Integer, Integer> progress = repository.findObjectiveProgress(playerUuid, QUEST_ID, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(3, progress.get(0));
        assertEquals(1, progress.get(1));

        // Ré-écriture (upsert) : la valeur doit être remplacée, pas dupliquée.
        repository.setObjectiveProgress(playerUuid, QUEST_ID, "step_one", 0, 5)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Map<Integer, Integer> updated = repository.findObjectiveProgress(playerUuid, QUEST_ID, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(2, updated.size());
        assertEquals(5, updated.get(0));
    }

    @Test
    void schemaIsCreatedOnTemporaryDatabase() {
        assertTrue(Files.exists(tempDir.resolve("data.db")));
    }

    @Test
    void deleteQuestRemovesStateAndObjectivesButNotOtherQuests() throws Exception {
        NamespacedKey other = new NamespacedKey("rpgquest", "other_quest");
        repository.upsertState(playerUuid, QUEST_ID, QuestState.COMPLETED, null)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.setObjectiveProgress(playerUuid, QUEST_ID, "step_one", 0, 3)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertState(playerUuid, other, QuestState.ACTIVE, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.setObjectiveProgress(playerUuid, other, "step_one", 0, 1)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.deleteQuest(playerUuid, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.find(playerUuid, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        assertTrue(repository.findObjectiveProgress(playerUuid, QUEST_ID, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());

        var otherRecord = repository.find(playerUuid, other).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(otherRecord.isPresent(), "les autres quêtes du joueur ne doivent pas être affectées");
        Map<Integer, Integer> otherProgress = repository.findObjectiveProgress(playerUuid, other, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, otherProgress.get(0));
    }

    @Test
    void deleteAllForPlayerRemovesEveryQuestAndObjective() throws Exception {
        NamespacedKey second = new NamespacedKey("rpgquest", "second_quest");
        repository.upsertState(playerUuid, QUEST_ID, QuestState.ACTIVE, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.setObjectiveProgress(playerUuid, QUEST_ID, "step_one", 0, 2)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertState(playerUuid, second, QuestState.COMPLETED, null)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.deleteAllForPlayer(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.findAll(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        assertTrue(repository.findObjectiveProgress(playerUuid, QUEST_ID, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void deleteQuestOnUntrackedQuestIsANoOp() throws Exception {
        repository.deleteQuest(playerUuid, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.find(playerUuid, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }
}
