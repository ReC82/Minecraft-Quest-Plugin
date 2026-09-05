package com.lodygames.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.StoryProgressRepository.StoryProgressRecord;
import com.lodygames.rpgquest.story.model.StoryState;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoryProgressRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final String STORY_ID = "main_story";

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private StoryProgressRepository repository;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new StoryProgressRepository(database);

        // story_progress a une FK vers player_profiles : il faut un profil existant.
        playerUuid = UUID.randomUUID();
        new PlayerProfileRepository(database).findOrCreate(playerUuid, "Steve").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void aStoryNeverStartedIsAbsent() throws Exception {
        assertTrue(repository.find(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(),
                "NOT_STARTED n'est jamais persisté, même convention que quest_progress");
    }

    @Test
    void upsertsAndReadsStateAndCurrentIndex() throws Exception {
        repository.upsertProgress(playerUuid, STORY_ID, StoryState.ACTIVE, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        StoryProgressRecord record = repository.find(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(StoryState.ACTIVE, record.state());
        assertEquals(0, record.currentIndex());

        repository.upsertProgress(playerUuid, STORY_ID, StoryState.ACTIVE, 1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        StoryProgressRecord updated = repository.find(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(1, updated.currentIndex(), "un upsert doit remplacer l'index, pas l'accumuler");

        repository.upsertProgress(playerUuid, STORY_ID, StoryState.COMPLETED, 3).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        StoryProgressRecord completed = repository.find(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseThrow();
        assertEquals(StoryState.COMPLETED, completed.state());
        assertEquals(3, completed.currentIndex());
    }

    @Test
    void findAllReturnsEveryStoryForPlayer() throws Exception {
        repository.upsertProgress(playerUuid, STORY_ID, StoryState.ACTIVE, 1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertProgress(playerUuid, "side_story", StoryState.COMPLETED, 2).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Map<String, StoryProgressRecord> all = repository.findAll(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, all.size());
        assertEquals(StoryState.ACTIVE, all.get(STORY_ID).state());
        assertEquals(1, all.get(STORY_ID).currentIndex());
        assertEquals(StoryState.COMPLETED, all.get("side_story").state());
        assertEquals(2, all.get("side_story").currentIndex());
    }

    @Test
    void deleteStoryRemovesOnlyThatStoryForThePlayer() throws Exception {
        repository.upsertProgress(playerUuid, STORY_ID, StoryState.ACTIVE, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertProgress(playerUuid, "side_story", StoryState.ACTIVE, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.deleteStory(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.find(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        assertTrue(repository.find(playerUuid, "side_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isPresent(),
                "les autres stories du joueur ne doivent jamais être affectées par un reset ciblé");
    }

    @Test
    void deleteAllForPlayerRemovesEveryStory() throws Exception {
        repository.upsertProgress(playerUuid, STORY_ID, StoryState.ACTIVE, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertProgress(playerUuid, "side_story", StoryState.COMPLETED, 2).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.deleteAllForPlayer(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.findAll(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void deletingAnUntrackedStoryIsANoOp() throws Exception {
        repository.deleteStory(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.find(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void aSecondPlayersProgressIsNeverAffectedByAnotherPlayersReset() throws Exception {
        UUID other = UUID.randomUUID();
        new PlayerProfileRepository(database).findOrCreate(other, "Alex").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertProgress(playerUuid, STORY_ID, StoryState.ACTIVE, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository.upsertProgress(other, STORY_ID, StoryState.ACTIVE, 0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        repository.deleteAllForPlayer(playerUuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(repository.find(playerUuid, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
        assertTrue(repository.find(other, STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isPresent(),
                "chaque joueur a une progression indépendante par Story");
    }
}
