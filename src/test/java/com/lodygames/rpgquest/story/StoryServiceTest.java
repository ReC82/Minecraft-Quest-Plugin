package com.lodygames.rpgquest.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.story.model.StoryState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Couvre l'orchestration {@code info}/{@code start}/{@code reset} — délibérément sans
 * dépendance au moteur de quête (aucune référence à {@code QuestProgressEngine}/{@code
 * YamlQuestEngine} dans tout ce test), conformément à l'indépendance demandée pour le moteur de
 * Storyline.
 */
class StoryServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private StoryRegistry registry;
    private StoryService service;
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path storiesDirectory = tempDir.resolve("stories");
        Files.createDirectories(storiesDirectory);
        Files.writeString(storiesDirectory.resolve("main_story.yml"), """
                id: main_story
                name: "Histoire principale"
                quests:
                  - premiers_pas
                  - first_steps
                """);
        Files.writeString(storiesDirectory.resolve("side_story.yml"), """
                id: side_story
                name: "Histoire secondaire"
                quests:
                  - woodcutters_request
                """);
        registry = new StoryRegistry(storiesDirectory, NOPLogger.NOP_LOGGER);
        registry.start();

        StoryProgressRepository progressRepository = new StoryProgressRepository(database);
        PlayerProfileRepository profileRepository = new PlayerProfileRepository(database);
        service = new StoryService(registry, progressRepository, profileRepository, NOPLogger.NOP_LOGGER);
        playerId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void infoReportsNotStartedForEveryKnownStoryBeforeAnythingHappens() throws Exception {
        var infos = service.info(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, infos.size());
        assertTrue(infos.stream().allMatch(i -> i.state() == StoryState.NOT_STARTED));
    }

    @Test
    void startingAnUnknownStoryIsRejected() throws Exception {
        var outcome = service.start(playerId, "Steve", "does_not_exist").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.StartOutcome.UNKNOWN_STORY, outcome);
    }

    @Test
    void startingAKnownStoryActivatesItAndCreatesTheOfflinePlayersProfile() throws Exception {
        var outcome = service.start(playerId, "Steve", "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.StartOutcome.STARTED, outcome);
        var infos = service.info(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(infos.stream().anyMatch(i -> i.story().id().equals("main_story") && i.state() == StoryState.ACTIVE));
        // "Steve" n'a jamais rejoint le serveur (mission : joueur potentiellement hors ligne) : le
        // profil doit malgré tout exister, sinon la FK de story_progress aurait empêché l'écriture.
        assertTrue(new PlayerProfileRepository(database).find(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isPresent());
    }

    @Test
    void startingAnAlreadyActiveStoryIsReportedWithoutChangingAnything() throws Exception {
        service.start(playerId, "Steve", "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var outcome = service.start(playerId, "Steve", "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.StartOutcome.ALREADY_ACTIVE, outcome);
    }

    @Test
    void startingAnAlreadyCompletedStoryRequiresAResetFirst() throws Exception {
        StoryProgressRepository progressRepository = new StoryProgressRepository(database);
        new PlayerProfileRepository(database).findOrCreate(playerId, "Steve").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressRepository.upsertState(playerId, "main_story", StoryState.COMPLETED).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var outcome = service.start(playerId, "Steve", "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.StartOutcome.ALREADY_COMPLETED, outcome);
    }

    @Test
    void resettingAnUnknownStoryIsRejected() throws Exception {
        var outcome = service.reset(playerId, "does_not_exist").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.ResetOutcome.UNKNOWN_STORY, outcome);
    }

    @Test
    void resettingOneStoryNeverTouchesTheOthers() throws Exception {
        service.start(playerId, "Steve", "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.start(playerId, "Steve", "side_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var outcome = service.reset(playerId, "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.ResetOutcome.RESET_ONE, outcome);
        var infos = service.info(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var mainStory = infos.stream().filter(i -> i.story().id().equals("main_story")).findFirst().orElseThrow();
        var sideStory = infos.stream().filter(i -> i.story().id().equals("side_story")).findFirst().orElseThrow();
        assertEquals(StoryState.NOT_STARTED, mainStory.state(), "recommencer proprement : plus aucune trace de l'ancienne progression");
        assertEquals(StoryState.ACTIVE, sideStory.state(), "une autre story du même joueur ne doit jamais être affectée");
    }

    @Test
    void resettingAllClearsEveryStoryForThatPlayerOnly() throws Exception {
        service.start(playerId, "Steve", "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.start(playerId, "Steve", "side_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        UUID otherPlayer = UUID.randomUUID();
        service.start(otherPlayer, "Alex", "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var outcome = service.reset(playerId, "all").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.ResetOutcome.RESET_ALL, outcome);
        var infos = service.info(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(infos.stream().allMatch(i -> i.state() == StoryState.NOT_STARTED));

        var otherInfos = service.info(otherPlayer).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(otherInfos.stream().anyMatch(i -> i.story().id().equals("main_story") && i.state() == StoryState.ACTIVE),
                "reset all d'un joueur ne doit jamais affecter la progression d'un autre joueur");
    }

    @Test
    void markCompletedIsAvailableForFutureStepsEvenThoughNoCommandExposesItYet() throws Exception {
        // markCompleted ne crée pas le profil joueur lui-même (contrairement à start) : réservé à un
        // futur appelant qui aura toujours un joueur déjà profilé (en ligne), jamais une cible
        // hors ligne comme les commandes admin de cette étape.
        new PlayerProfileRepository(database).findOrCreate(playerId, "Steve").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service.markCompleted(playerId, "main_story").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var infos = service.info(playerId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(infos.stream().anyMatch(i -> i.story().id().equals("main_story") && i.state() == StoryState.COMPLETED));
    }
}
