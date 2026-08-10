package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class QuestProgressEngineTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey KILL_QUEST = new NamespacedKey("rpgquest", "kill_quest");
    private static final NamespacedKey KILL_QUEST_TWO = new NamespacedKey("rpgquest", "kill_quest_two");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private YamlQuestEngine questEngine;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private QuestProgressEngine engine;
    private Path questsDir;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        writeKillQuest(KILL_QUEST, "kill_quest.yml", 2, false, null);

        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        progressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(plugin, new NpcIdRepository(database));

        engine = new QuestProgressEngine(plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService);
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void acceptProgressAndTurnInCycle() throws Exception {
        PlayerMock player = addPlayer();

        AcceptOutcome accept = engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, accept.result());

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST));

        engine.handleKillEntity(player, EntityType.ZOMBIE);

        var record = progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(record.isPresent());
        assertEquals(QuestState.COMPLETED, record.get().state());
    }

    @Test
    void reconnectionDuringAStepPreservesCounters() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE); // 1/2

        // Déconnexion puis reconnexion : le compteur doit être rechargé depuis la base, pas remis à zéro.
        engine.unloadForPlayer(player.getUniqueId());
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE); // 2/2 -> devrait suffire si le compteur a été conservé

        var record = progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(record.isPresent());
        assertEquals(QuestState.COMPLETED, record.get().state(),
                "un seul kill après reconnexion doit suffire : le compteur (1/2) doit avoir survécu à la reconnexion");
    }

    @Test
    void twoIdenticalObjectivesInTwoQuestsProgressIndependently() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "kill_quest_two.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // KILL_QUEST a besoin de 2 ; KILL_QUEST_TWO n'en a besoin que d'1

        var stateOne = progressRepository.find(player.getUniqueId(), KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var stateTwo = progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.COMPLETED, stateOne.orElseThrow().state());
        assertEquals(QuestState.COMPLETED, stateTwo.orElseThrow().state());
    }

    @Test
    void irrelevantEventDoesNothing() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        engine.handleKillEntity(player, EntityType.SKELETON); // la quête attend ZOMBIE
        engine.handleBreakBlock(player, org.bukkit.Material.STONE); // aucun objectif BREAK_BLOCK chargé

        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST));
    }

    @Test
    void abandonThenAcceptAgainRestartsCleanly() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // 1/2

        AbandonOutcome abandon = engine.abandon(player, KILL_QUEST);
        assertEquals(AbandonOutcome.ABANDONED, abandon);
        assertEquals(AbandonOutcome.NOTHING_TO_ABANDON, engine.abandon(player, KILL_QUEST));

        AcceptOutcome reaccept = engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.ACCEPTED, reaccept.result());

        // Un seul kill après la ré-acceptation ne doit pas suffire : le compteur est reparti de zéro.
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        assertEquals(QuestState.ACTIVE, activeState(player, KILL_QUEST));
    }

    @Test
    void nonRepeatableQuestAlreadyCompletedCannotBeAcceptedAgain() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "not_repeatable.yml", 1, false, null);
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        var record = progressRepository.find(player.getUniqueId(), KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.COMPLETED, record.orElseThrow().state());

        AcceptOutcome secondAccept = engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(AcceptOutcome.Result.NOT_REPEATABLE, secondAccept.result());
    }

    @Test
    void preventsDoubleTurnIn() throws Exception {
        PlayerMock player = addPlayer();
        engine.accept(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int expBefore = player.getTotalExperience();

        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE); // complète la quête, octroie la récompense

        int expAfterFirstCompletion = player.getTotalExperience();
        assertTrue(expAfterFirstCompletion > expBefore, "l'expérience doit avoir été accordée une fois");

        // D'autres événements après la fin ne doivent plus rien faire : la quête n'est plus active en mémoire.
        engine.handleKillEntity(player, EntityType.ZOMBIE);
        engine.handleKillEntity(player, EntityType.ZOMBIE);

        assertEquals(expAfterFirstCompletion, player.getTotalExperience(),
                "l'expérience ne doit pas être accordée deux fois");

        // La voie admin (bypass) doit elle aussi refuser une double remise.
        assertEquals(CompleteOutcome.ALREADY_COMPLETED,
                engine.forceComplete(player, KILL_QUEST).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(expAfterFirstCompletion, player.getTotalExperience());
    }

    @Test
    void acceptRejectsWhenPrerequisiteNotCompleted() throws Exception {
        writeKillQuest(KILL_QUEST_TWO, "with_prereq.yml", 1, false, KILL_QUEST.toString());
        engine.reloadQuestDefinitions();

        PlayerMock player = addPlayer();
        AcceptOutcome outcome = engine.accept(player, KILL_QUEST_TWO).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(AcceptOutcome.Result.MISSING_PREREQUISITES, outcome.result());
        assertEquals(List.of(KILL_QUEST), outcome.missingPrerequisites());
    }

    /** Ajoute un joueur MockBukkit et crée son profil dans la base de test (requis par la FK de quest_progress). */
    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private QuestState activeState(PlayerMock player, NamespacedKey questId) throws Exception {
        return progressRepository.find(player.getUniqueId(), questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseThrow()
                .state();
    }

    private void writeKillQuest(NamespacedKey id, String fileName, int amount, boolean repeatable, String prerequisite) throws Exception {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("title: \"Titre\"\n");
        yaml.append("description: \"Description\"\n");
        yaml.append("category: test\n");
        yaml.append("repeatable: ").append(repeatable).append('\n');
        if (prerequisite != null) {
            yaml.append("prerequisites:\n  - ").append(prerequisite).append('\n');
        }
        yaml.append("""
                steps:
                  - id: kill_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: %d

                rewards:
                  - type: EXPERIENCE
                    amount: 10
                """.formatted(amount));
        Files.writeString(questsDir.resolve(fileName), yaml.toString());
    }
}
