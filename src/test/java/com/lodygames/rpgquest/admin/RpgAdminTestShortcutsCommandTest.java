package com.lodygames.rpgquest.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.StoryProgressRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.story.StoryRegistry;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.story.model.StoryState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #36 — raccourcis d'administration / test {@code /rpgadmin quest ...} / {@code /rpgadmin story
 * advance|complete} / {@code /rpgadmin player variable get|set}. Vérifie le comportement propre à la
 * couche commande (permissions, id invalide, messages, non-duplication des récompenses, limite du
 * reset) ; la progression Story fine est couverte par {@code StoryServiceTest}.
 */
class RpgAdminTestShortcutsCommandTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final String WORLD_PERM = "rpgquest.admin.world";
    private static final String DEBUG_PERM = "rpgquest.admin.debug";

    private static final NamespacedKey BASE_QUEST = new NamespacedKey("rpgquest", "shortcut_base");
    private static final NamespacedKey PREREQ_QUEST = new NamespacedKey("rpgquest", "shortcut_prereq");
    private static final NamespacedKey REWARD_QUEST = new NamespacedKey("rpgquest", "shortcut_reward");
    private static final String STORY_ID = "shortcut_story";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private YamlQuestEngine questEngine;
    private QuestProgressEngine questProgressEngine;
    private StoryService storyService;
    private PlayerProfileRepository profileRepository;
    private PlayerVariableRepository variableRepository;
    private RpgAdminCommand command;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        writeQuest(questsDir, BASE_QUEST, "base.yml", null, false);
        writeQuest(questsDir, PREREQ_QUEST, "prereq.yml", BASE_QUEST.toString(), false);
        writeRewardQuest(questsDir);
        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        QuestProgressRepository questProgressRepository = new QuestProgressRepository(database);
        profileRepository = new PlayerProfileRepository(database);
        variableRepository = new PlayerVariableRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, questProgressRepository, variableRepository, messagesService, npcIdentityService);
        questProgressEngine.start();

        Path storiesDir = tempDir.resolve("stories");
        Files.createDirectories(storiesDir);
        Files.writeString(storiesDir.resolve("story.yml"),
                "id: " + STORY_ID + "\nname: \"Story\"\nquests:\n  - " + BASE_QUEST + "\n  - " + REWARD_QUEST + "\n");
        StoryRegistry storyRegistry = new StoryRegistry(storiesDir, plugin.getSLF4JLogger());
        storyRegistry.start();
        storyService = new StoryService(plugin, storyRegistry, new StoryProgressRepository(database), profileRepository,
                questProgressEngine, questEngine, messagesService, plugin.getSLF4JLogger());
        storyService.start();
        questProgressEngine.onProgressChanged(storyService::onQuestProgressChanged);

        command = new RpgAdminCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                storyService, null, null, null,
                questProgressEngine, questEngine, variableRepository, plugin);
    }

    @AfterEach
    void tearDown() {
        storyService.stop();
        questProgressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    // ---- Permissions ---------------------------------------------------------------------------

    @Test
    void everySubcommandRequiresTheWorldAdminPermission() throws Exception {
        PlayerMock player = addPlayer(false, false);

        run(player, "quest", "complete", player.getName(), BASE_QUEST.toString());

        assertTrue(nextMessage(player).contains(WORLD_PERM), "sans rpgquest.admin.world : refus explicite");
        assertEquals(QuestState.NOT_STARTED, questState(player, BASE_QUEST), "rien ne doit se passer");
    }

    @Test
    void variableSetRequiresTheStricterDebugPermission() throws Exception {
        PlayerMock player = addPlayer(true, false); // admin.world mais pas admin.debug

        run(player, "player", "variable", "set", player.getName(), "CLAIM_TIER_1", "true");
        assertTrue(nextMessage(player).contains(DEBUG_PERM), "set exige la permission stricte");
        assertTrue(variableRepository.get(player.getUniqueId(), "CLAIM_TIER_1")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(), "rien écrit sans la permission");

        player.addAttachment(plugin, DEBUG_PERM, true);
        run(player, "player", "variable", "set", player.getName(), "CLAIM_TIER_1", "true");
        awaitUntil(() -> variableValue(player, "CLAIM_TIER_1").filter("true"::equals).isPresent());
        assertEquals(Optional.of("true"), variableValue(player, "CLAIM_TIER_1"));
    }

    // ---- quest start / complete / reset ------------------------------------------------------

    @Test
    void questStartRejectsAnUnknownIdCleanly() throws Exception {
        PlayerMock player = addPlayer(true, false);

        run(player, "quest", "start", player.getName(), "rpgquest:does_not_exist");

        assertTrue(awaitMessageContaining(player, "inconnue"));
    }

    @Test
    void questStartRespectsPrerequisitesButForceBypassesThem() throws Exception {
        PlayerMock player = addPlayer(true, false);

        run(player, "quest", "start", player.getName(), PREREQ_QUEST.toString());
        assertTrue(awaitMessageContaining(player, "Prérequis manquants"));
        assertEquals(QuestState.NOT_STARTED, questState(player, PREREQ_QUEST));

        run(player, "quest", "start", player.getName(), PREREQ_QUEST.toString(), "force");
        awaitUntil(() -> questState(player, PREREQ_QUEST) == QuestState.ACTIVE);
    }

    @Test
    void questCompleteAppliesTheVariableAndItemRewardExactlyOnce() throws Exception {
        PlayerMock player = addPlayer(true, false);

        run(player, "quest", "complete", player.getName(), REWARD_QUEST.toString());
        awaitUntil(() -> questState(player, REWARD_QUEST) == QuestState.COMPLETED);
        awaitUntil(() -> variableValue(player, "CLAIM_TIER_1").filter("true"::equals).isPresent());
        awaitUntil(() -> diamondCount(player) == 1);

        // Deuxième appel : ALREADY_COMPLETED, aucune récompense re-créditée.
        run(player, "quest", "complete", player.getName(), REWARD_QUEST.toString());
        assertTrue(awaitMessageContaining(player, "Déjà terminée"));
        server.getScheduler().performTicks(5);
        assertEquals(1, diamondCount(player), "aucun objet en double sur un second complete");
        assertEquals(Optional.of("true"), variableValue(player, "CLAIM_TIER_1"));
    }

    @Test
    void questResetMakesTheQuestReplayableButKeepsAlreadyGrantedRewards() throws Exception {
        PlayerMock player = addPlayer(true, false);
        run(player, "quest", "complete", player.getName(), REWARD_QUEST.toString());
        awaitUntil(() -> variableValue(player, "CLAIM_TIER_1").filter("true"::equals).isPresent());

        run(player, "quest", "reset", player.getName(), REWARD_QUEST.toString());
        assertTrue(awaitMessageContaining(player, "initialis"));

        assertEquals(QuestState.NOT_STARTED, questState(player, REWARD_QUEST), "la quête redevient rejouable");
        assertEquals(Optional.of("true"), variableValue(player, "CLAIM_TIER_1"),
                "limite documentée : un reset ciblé n'annule pas les récompenses déjà accordées");
    }

    // ---- story advance / complete ----------------------------------------------------------

    @Test
    void storyAdvanceStartsTheStoryCompletesTheCurrentQuestAndPointsToTheNext() throws Exception {
        PlayerMock player = addPlayer(true, false);

        run(player, "story", "advance", player.getName(), STORY_ID);

        awaitUntil(() -> questState(player, BASE_QUEST) == QuestState.COMPLETED);
        awaitUntil(() -> questState(player, REWARD_QUEST) == QuestState.ACTIVE);
        assertTrue(awaitMessageContaining(player, "étape 2/2"), "le message doit dire quelle étape tester maintenant");
    }

    @Test
    void storyCompleteFinishesTheWholeStoryAndAppliesEveryReward() throws Exception {
        PlayerMock player = addPlayer(true, false);

        run(player, "story", "complete", player.getName(), STORY_ID);

        awaitUntil(() -> storyState(player, STORY_ID) == StoryState.COMPLETED);
        assertEquals(QuestState.COMPLETED, questState(player, BASE_QUEST));
        assertEquals(QuestState.COMPLETED, questState(player, REWARD_QUEST));
        awaitUntil(() -> variableValue(player, "CLAIM_TIER_1").filter("true"::equals).isPresent());
    }

    @Test
    void storyAdvanceOnAnUnknownStoryReportsCleanly() throws Exception {
        PlayerMock player = addPlayer(true, false);

        run(player, "story", "advance", player.getName(), "nope");

        assertTrue(awaitMessageContaining(player, "inconnue"));
    }

    // ---- player variable get -------------------------------------------------------------

    @Test
    void variableGetReadsThePlayerVariable() throws Exception {
        PlayerMock player = addPlayer(true, false);
        variableRepository.set(player.getUniqueId(), "CLAIM_TIER_1", "true").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        run(player, "player", "variable", "get", player.getName(), "CLAIM_TIER_1");

        assertTrue(awaitMessageContaining(player, "true"));
    }

    @Test
    void variableGetOnAnAbsentKeysaysSo() throws Exception {
        PlayerMock player = addPlayer(true, false);

        run(player, "player", "variable", "get", player.getName(), "NEVER_SET");

        assertTrue(awaitMessageContaining(player, "absente"));
    }

    // ---- Helpers -----------------------------------------------------------------------

    private PlayerMock addPlayer(boolean worldPerm, boolean debugPerm) throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        questProgressEngine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (worldPerm) {
            player.addAttachment(plugin, WORLD_PERM, true);
        }
        if (debugPerm) {
            player.addAttachment(plugin, DEBUG_PERM, true);
        }
        return player;
    }

    private void run(PlayerMock player, String... args) {
        command.onCommand(player, plugin.getCommand("rpgadmin"), "rpgadmin", args);
    }

    private String nextMessage(PlayerMock player) {
        String message = player.nextMessage();
        assertNotNull(message, "un message était attendu");
        return message;
    }

    /** Pompe les ticks jusqu'à recevoir un message contenant {@code needle} (ou timeout), en vidant la file au passage. */
    private boolean awaitMessageContaining(PlayerMock player, String needle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            String message;
            while ((message = player.nextMessage()) != null) {
                if (message.contains(needle)) {
                    return true;
                }
            }
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        return false;
    }

    private QuestState questState(PlayerMock player, NamespacedKey questId) {
        try {
            return questProgressEngine.stateOf(player.getUniqueId(), questId).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StoryState storyState(PlayerMock player, String storyId) {
        try {
            return new StoryProgressRepository(database).find(player.getUniqueId(), storyId)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .map(StoryProgressRepository.StoryProgressRecord::state).orElse(StoryState.NOT_STARTED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<String> variableValue(PlayerMock player, String key) {
        try {
            return variableRepository.get(player.getUniqueId(), key).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int diamondCount(PlayerMock player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.DIAMOND) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition jamais atteinte dans le délai imparti");
    }

    private void writeQuest(Path dir, NamespacedKey id, String file, String prerequisite, boolean repeatable) throws Exception {
        StringBuilder yaml = new StringBuilder();
        yaml.append("id: ").append(id).append('\n');
        yaml.append("title: \"Titre\"\ndescription: \"Desc\"\ncategory: test\n");
        yaml.append("repeatable: ").append(repeatable).append('\n');
        if (prerequisite != null) {
            yaml.append("prerequisites:\n  - ").append(prerequisite).append('\n');
        }
        yaml.append("""
                steps:
                  - id: only_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: EXPERIENCE
                    amount: 5
                """);
        Files.writeString(dir.resolve(file), yaml.toString());
    }

    private void writeRewardQuest(Path dir) throws Exception {
        Files.writeString(dir.resolve("reward.yml"), """
                id: %s
                title: "Récompense"
                description: "Desc"
                category: test
                repeatable: false
                steps:
                  - id: only_step
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: ITEM
                    material: DIAMOND
                    amount: 1
                  - type: VARIABLE
                    key: CLAIM_TIER_1
                    value: "true"
                """.formatted(REWARD_QUEST));
    }
}
