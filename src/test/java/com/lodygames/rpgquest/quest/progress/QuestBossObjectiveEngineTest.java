package com.lodygames.rpgquest.quest.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #171 — coutures du moteur pour les boss de quête : objectif en cours exposé seulement à
 * l'étape courante, crédit unique, rien après abandon.
 */
class QuestBossObjectiveEngineTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey HUNT = new NamespacedKey("rpgquest", "chasse_ogre");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerProfileRepository profileRepository;
    private QuestProgressEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path questsDirectory = Files.createDirectories(tempDir.resolve("quests"));
        Files.writeString(questsDirectory.resolve("chasse_ogre.yml"), """
                id: rpgquest:chasse_ogre
                title: "Chasse"
                description: "d"
                category: test
                steps:
                  - id: chasse
                    objectives:
                      - type: KILL_QUEST_BOSS
                        mob: ogre
                  - id: retour
                    objectives:
                      - type: TALK_TO_NPC
                        npc: guide
                """);
        YamlQuestEngine questEngine = new YamlQuestEngine(questsDirectory, plugin.getSLF4JLogger());
        questEngine.reload();
        profileRepository = new PlayerProfileRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        engine = new QuestProgressEngine(plugin, questEngine, new QuestProgressRepository(database),
                new PlayerVariableRepository(database), messagesService,
                new NpcIdentityService(plugin, new NpcIdRepository(database), new NpcBindingRepository(database)),
                QuestRewardPayer.unavailable());
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private PlayerMock acceptedPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        engine.accept(player, HUNT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        server.getScheduler().performTicks(5);
        return player;
    }

    @Test
    void acceptedQuestExposesItsBossObjectiveOnce() throws Exception {
        PlayerMock player = acceptedPlayer();
        assertTrue(engine.isLoaded(player.getUniqueId()));
        List<ObjectiveRef> pending = engine.pendingQuestBosses(player.getUniqueId());
        assertEquals(1, pending.size());
        assertEquals("chasse", pending.get(0).stepId());
    }

    @Test
    void creditAdvancesTheStepExactlyOnce() throws Exception {
        PlayerMock player = acceptedPlayer();
        assertTrue(engine.creditQuestBoss(player, HUNT, "chasse", 0));
        assertTrue(engine.pendingQuestBosses(player.getUniqueId()).isEmpty());
        assertEquals("retour", engine.activeStepView(player.getUniqueId(), HUNT).orElseThrow().stepId());
        assertFalse(engine.creditQuestBoss(player, HUNT, "chasse", 0), "jamais deux crédits");
        assertFalse(engine.creditQuestBoss(player, HUNT, "retour", 0), "pas un objectif de boss");
    }

    @Test
    void abandonedQuestNoLongerWantsItsBoss() throws Exception {
        PlayerMock player = acceptedPlayer();
        engine.abandon(player, HUNT);
        assertTrue(engine.pendingQuestBosses(player.getUniqueId()).isEmpty());
        assertFalse(engine.creditQuestBoss(player, HUNT, "chasse", 0));
        assertEquals(QuestState.ABANDONED,
                engine.stateOf(player.getUniqueId(), HUNT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
}
