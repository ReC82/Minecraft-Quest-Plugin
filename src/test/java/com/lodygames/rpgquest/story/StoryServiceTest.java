package com.lodygames.rpgquest.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository.StoryProgressRecord;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.CompleteOutcome;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.story.model.StoryState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre la progression automatique de bout en bout : une Story {@code ACTIVE} avance toute seule
 * au fil des quêtes qu'elle référence, sans commande joueur — voir {@link StoryService}. Utilise
 * {@link QuestProgressEngine#forceComplete} plutôt que de simuler de vrais événements de jeu
 * (BREAK_BLOCK, etc.) : ce que ce test vérifie, c'est la réaction de la Story à une complétion de
 * quête, pas le déclenchement de cette complétion (déjà couvert par {@code QuestProgressEngineTest}).
 */
class StoryServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey QUEST_ONE = new NamespacedKey("rpgquest", "quest_one");
    private static final NamespacedKey QUEST_TWO = new NamespacedKey("rpgquest", "quest_two");
    private static final NamespacedKey QUEST_THREE = new NamespacedKey("rpgquest", "quest_three");
    private static final NamespacedKey STANDALONE_QUEST = new NamespacedKey("rpgquest", "standalone_quest");
    private static final String STORY_ID = "test_story";
    private static final String SIDE_STORY_ID = "side_story";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private YamlQuestEngine questEngine;
    private QuestProgressRepository questProgressRepository;
    private QuestProgressEngine questProgressEngine;
    private StoryRegistry storyRegistry;
    private StoryProgressRepository storyProgressRepository;
    private StoryService storyService;
    private PlayerProfileRepository profileRepository;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        writeSimpleQuest(questsDir, QUEST_ONE, "quest_one.yml");
        writeSimpleQuest(questsDir, QUEST_TWO, "quest_two.yml");
        writeSimpleQuest(questsDir, QUEST_THREE, "quest_three.yml");
        writeSimpleQuest(questsDir, STANDALONE_QUEST, "standalone_quest.yml");
        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        questProgressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, questProgressRepository, variableRepository, messagesService, npcIdentityService);
        questProgressEngine.start();

        Path storiesDir = tempDir.resolve("stories");
        Files.createDirectories(storiesDir);
        writeStory(storiesDir, STORY_ID, "test_story.yml", "quest_one", "quest_two", "quest_three");
        writeStory(storiesDir, SIDE_STORY_ID, "side_story.yml", "quest_one");
        storyRegistry = new StoryRegistry(storiesDir, plugin.getSLF4JLogger());
        storyRegistry.start();

        storyProgressRepository = new StoryProgressRepository(database);
        storyService = new StoryService(plugin, storyRegistry, storyProgressRepository, profileRepository,
                questProgressEngine, questEngine, messagesService, plugin.getSLF4JLogger());
        storyService.start();
        questProgressEngine.onProgressChanged(storyService::onQuestProgressChanged);
    }

    @AfterEach
    void tearDown() {
        storyService.stop();
        questProgressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    // ---- Démarrage et première quête ---------------------------------------------------------------

    @Test
    void startingAStoryActivatesItAtIndexZero() throws Exception {
        PlayerMock player = addPlayer();

        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryState.ACTIVE, stateOf(player, STORY_ID));
        assertEquals(0, currentIndexOf(player, STORY_ID));
    }

    @Test
    void startingAStoryAutomaticallyAcceptsItsFirstQuest() throws Exception {
        PlayerMock player = addPlayer();

        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);
        assertEquals(QuestState.ACTIVE, questState(player, QUEST_ONE), "la première quête doit démarrer automatiquement, sans commande joueur");
    }

    // ---- Progression automatique --------------------------------------------------------------------

    @Test
    void completingTheCurrentQuestAdvancesTheStoryAndStartsTheNextQuestAutomatically() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);

        forceComplete(player, QUEST_ONE);

        awaitUntil(() -> currentIndexOf(player, STORY_ID) == 1);
        assertEquals(StoryState.ACTIVE, stateOf(player, STORY_ID), "toujours en cours après la première quête, une deuxième reste à faire");
        awaitUntil(() -> questState(player, QUEST_TWO) == QuestState.ACTIVE);
        assertEquals(QuestState.ACTIVE, questState(player, QUEST_TWO), "la deuxième quête doit démarrer automatiquement, sans interaction PNJ entre les deux");
    }

    @Test
    void progressingThroughAllQuestsCompletesTheStory() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);

        forceComplete(player, QUEST_ONE);
        awaitUntil(() -> questState(player, QUEST_TWO) == QuestState.ACTIVE);
        forceComplete(player, QUEST_TWO);
        awaitUntil(() -> questState(player, QUEST_THREE) == QuestState.ACTIVE);
        forceComplete(player, QUEST_THREE);

        awaitUntil(() -> stateOf(player, STORY_ID) == StoryState.COMPLETED);
        assertEquals(StoryState.COMPLETED, stateOf(player, STORY_ID));
    }

    @Test
    void aCompletedStoryNeverAdvancesFurther() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);
        forceComplete(player, QUEST_ONE);
        awaitUntil(() -> questState(player, QUEST_TWO) == QuestState.ACTIVE);
        forceComplete(player, QUEST_TWO);
        awaitUntil(() -> questState(player, QUEST_THREE) == QuestState.ACTIVE);
        forceComplete(player, QUEST_THREE);
        awaitUntil(() -> stateOf(player, STORY_ID) == StoryState.COMPLETED);
        int indexAtCompletion = currentIndexOf(player, STORY_ID);

        // Notifications supplémentaires après la fin (ex. une autre quête sans rapport se termine) :
        // ne doivent jamais rien avancer de plus, la story n'est plus dans le cache actif.
        storyService.onQuestProgressChanged(player.getUniqueId());
        server.getScheduler().performTicks(3);

        assertEquals(StoryState.COMPLETED, stateOf(player, STORY_ID));
        assertEquals(indexAtCompletion, currentIndexOf(player, STORY_ID));
    }

    @Test
    void noDoubleProgressionEvenWithRepeatedNotifications() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);

        forceComplete(player, QUEST_ONE);
        awaitUntil(() -> currentIndexOf(player, STORY_ID) == 1);

        // Plusieurs notifications supplémentaires pour la même complétion (comportement réel de
        // QuestProgressEngine#onProgressChanged, notifié après CHAQUE mutation, pas seulement les
        // fins de quête) : ne doivent jamais faire avancer l'index une deuxième fois.
        storyService.onQuestProgressChanged(player.getUniqueId());
        storyService.onQuestProgressChanged(player.getUniqueId());
        server.getScheduler().performTicks(5);

        assertEquals(1, currentIndexOf(player, STORY_ID), "une seule complétion de quest_one ne doit jamais avancer l'index deux fois");
    }

    // ---- Reprise après déconnexion / redémarrage ---------------------------------------------------

    @Test
    void anActiveStoryResumesCorrectlyAfterReconnection() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);
        forceComplete(player, QUEST_ONE);
        awaitUntil(() -> currentIndexOf(player, STORY_ID) == 1);

        storyService.unloadForPlayer(player.getUniqueId());
        questProgressEngine.unloadForPlayer(player.getUniqueId());
        questProgressEngine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        storyService.loadForPlayer(player);
        server.getScheduler().performTicks(3);

        assertEquals(1, currentIndexOf(player, STORY_ID), "l'index persisté doit survivre à une reconnexion");
        assertEquals(QuestState.ACTIVE, questState(player, QUEST_TWO), "la quête courante doit rester suivie après reconnexion");
    }

    @Test
    void aQuestCompletedWhileOfflineIsCaughtUpOnReconnection() throws Exception {
        // Simule un crash/une anomalie entre la complétion de la quête et l'avancement de la story
        // (ou une complétion via un autre chemin pendant que le cache Story était déchargé) : au
        // moment de la reconnexion, la quête courante est déjà COMPLETED mais la story ne l'a pas
        // encore vu — loadForPlayer doit rattraper ça tout seul (mission point 3 : reprise correcte).
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);

        storyService.unloadForPlayer(player.getUniqueId());
        questProgressEngine.forceComplete(player, QUEST_ONE).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        storyService.loadForPlayer(player);
        server.getScheduler().performTicks(3);

        awaitUntil(() -> currentIndexOf(player, STORY_ID) == 1);
        awaitUntil(() -> questState(player, QUEST_TWO) == QuestState.ACTIVE);
        assertEquals(1, currentIndexOf(player, STORY_ID));
        assertEquals(QuestState.ACTIVE, questState(player, QUEST_TWO));
    }

    // ---- Reset ------------------------------------------------------------------------------------

    @Test
    void resetClearsStoryProgressButNeverTheQuestProgress() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);
        forceComplete(player, QUEST_ONE);
        awaitUntil(() -> currentIndexOf(player, STORY_ID) == 1);

        var outcome = storyService.reset(player.getUniqueId(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.ResetOutcome.RESET_ONE, outcome);
        assertEquals(StoryState.NOT_STARTED, stateOf(player, STORY_ID));
        assertEquals(QuestState.COMPLETED, questState(player, QUEST_ONE),
                "reset ne doit jamais toucher à la progression de quête normale (mission point 5)");
    }

    @Test
    void resetWithQuestsClearsTheStoryAndAllItsQuestsButNeverAnUnrelatedQuest() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);
        forceComplete(player, QUEST_ONE);
        awaitUntil(() -> questState(player, QUEST_TWO) == QuestState.ACTIVE);
        // accept() d'abord : forceComplete() sur une quête jamais acceptée passe par un runTask()
        // planifié (tick suivant), qui ne se résoudrait jamais sans avancer le scheduler en parallèle
        // du .get() bloquant ci-dessous — accepter d'abord la rend synchrone (déjà en cache).
        questProgressEngine.accept(player, STANDALONE_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompleteOutcome standaloneOutcome = questProgressEngine.forceComplete(player, STANDALONE_QUEST)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(CompleteOutcome.COMPLETED, standaloneOutcome);

        var outcome = storyService.resetWithQuests(player.getUniqueId(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.ResetWithQuestsOutcome.RESET, outcome);
        assertEquals(StoryState.NOT_STARTED, stateOf(player, STORY_ID));
        assertEquals(QuestState.NOT_STARTED, questState(player, QUEST_ONE), "la quête 1 de la story doit être remise à zéro");
        assertEquals(QuestState.NOT_STARTED, questState(player, QUEST_TWO), "la quête 2 de la story doit être remise à zéro");
        assertEquals(QuestState.COMPLETED, questState(player, STANDALONE_QUEST),
                "une quête hors story ne doit jamais être affectée par resetWithQuests");
    }

    @Test
    void afterResetWithQuestsTheStoryCanBeRestartedAndReplayedCleanly() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);
        forceComplete(player, QUEST_ONE);
        awaitUntil(() -> questState(player, QUEST_TWO) == QuestState.ACTIVE);
        storyService.resetWithQuests(player.getUniqueId(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        var startOutcome = storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(StoryService.StartOutcome.STARTED, startOutcome);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);
        assertEquals(0, currentIndexOf(player, STORY_ID));
    }

    // ---- Plusieurs Stories indépendantes ------------------------------------------------------------

    @Test
    void twoActiveStoriesForTheSamePlayerProgressIndependently() throws Exception {
        PlayerMock player = addPlayer();
        storyService.start(player.getUniqueId(), player.getName(), STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        storyService.start(player.getUniqueId(), player.getName(), SIDE_STORY_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitUntil(() -> questState(player, QUEST_ONE) == QuestState.ACTIVE);

        // quest_one est la quête courante des DEUX stories à la fois (side_story n'en a qu'une) :
        // sa complétion doit faire avancer les deux, chacune vers son propre état final.
        forceComplete(player, QUEST_ONE);

        awaitUntil(() -> stateOf(player, SIDE_STORY_ID) == StoryState.COMPLETED);
        awaitUntil(() -> currentIndexOf(player, STORY_ID) == 1);
        assertEquals(StoryState.COMPLETED, stateOf(player, SIDE_STORY_ID), "side_story n'a qu'une quête : terminée dès quest_one");
        assertEquals(StoryState.ACTIVE, stateOf(player, STORY_ID), "test_story a deux quêtes de plus à faire");
        assertEquals(1, currentIndexOf(player, STORY_ID));
    }

    // ---- Quête utilisée hors Story -------------------------------------------------------------------

    @Test
    void aQuestNeverReferencedByAnyActiveStoryWorksNormally() throws Exception {
        PlayerMock player = addPlayer();
        // Aucune story démarrée : standalone_quest doit s'accepter/se terminer normalement.
        questProgressEngine.accept(player, STANDALONE_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompleteOutcome outcome = questProgressEngine.forceComplete(player, STANDALONE_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(CompleteOutcome.COMPLETED, outcome);
        assertEquals(QuestState.COMPLETED, questState(player, STANDALONE_QUEST));
    }

    @Test
    void completingAQuestThatIsAStoryQuestButNotViaAnActiveStoryDoesNothingToTheStory() throws Exception {
        PlayerMock player = addPlayer();
        // quest_one appartient à test_story/side_story, mais aucune des deux n'est démarrée ici.
        questProgressEngine.accept(player, QUEST_ONE).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        forceComplete(player, QUEST_ONE);
        server.getScheduler().performTicks(3);

        assertEquals(StoryState.NOT_STARTED, stateOf(player, STORY_ID), "une story jamais démarrée ne doit jamais réagir");
        assertEquals(StoryState.NOT_STARTED, stateOf(player, SIDE_STORY_ID));
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        questProgressEngine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void forceComplete(PlayerMock player, NamespacedKey questId) throws Exception {
        questProgressEngine.forceComplete(player, questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private QuestState questState(PlayerMock player, NamespacedKey questId) {
        try {
            return questProgressEngine.stateOf(player.getUniqueId(), questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StoryState stateOf(PlayerMock player, String storyId) {
        try {
            return storyProgressRepository.find(player.getUniqueId(), storyId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .map(StoryProgressRecord::state).orElse(StoryState.NOT_STARTED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int currentIndexOf(PlayerMock player, String storyId) {
        try {
            return storyProgressRepository.find(player.getUniqueId(), storyId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .map(StoryProgressRecord::currentIndex).orElse(-1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }

    private void writeSimpleQuest(Path questsDir, NamespacedKey id, String fileName) throws Exception {
        String yaml = """
                id: %s
                title: "Titre"
                description: "Description"
                category: test
                repeatable: true

                steps:
                  - id: only_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1

                rewards:
                  - type: EXPERIENCE
                    amount: 5
                """.formatted(id);
        Files.writeString(questsDir.resolve(fileName), yaml);
    }

    private void writeStory(Path storiesDir, String id, String fileName, String... questIds) throws Exception {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("name: \"Story ").append(id).append("\"\n");
        yaml.append("quests:\n");
        for (String questId : questIds) {
            yaml.append("  - rpgquest:").append(questId).append('\n');
        }
        Files.writeString(storiesDir.resolve(fileName), yaml.toString());
    }
}
