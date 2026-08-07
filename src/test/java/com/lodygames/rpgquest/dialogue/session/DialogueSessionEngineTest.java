package com.lodygames.rpgquest.dialogue.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.render.DialogueRenderer;
import com.lodygames.rpgquest.dialogue.render.VisibleChoice;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.merchant.MerchantTradeService;
import com.lodygames.rpgquest.economy.merchant.YamlMerchantRegistry;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class DialogueSessionEngineTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey QUEST_ID = new NamespacedKey("rpgquest", "first_steps");
    private static final NamespacedKey DIALOGUE_ID = new NamespacedKey("rpgquest", "guard");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private QuestProgressEngine questProgressEngine;
    private DialogueSessionEngine sessionEngine;
    private MerchantTradeService merchantTradeService;
    private RecordingRenderer renderer;
    private PlayerProfileRepository profileRepository;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);

        Path questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        Files.writeString(questsDir.resolve("first_steps.yml"), """
                id: rpgquest:first_steps
                title: "Titre"
                description: "Description"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                """);
        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        QuestProgressRepository progressRepository = new QuestProgressRepository(database);
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        questProgressEngine = new QuestProgressEngine(plugin, questEngine, progressRepository, variableRepository, messagesService);
        questProgressEngine.start();

        Path dialoguesDir = tempDir.resolve("dialogues");
        Files.createDirectories(dialoguesDir);
        Files.writeString(dialoguesDir.resolve("guard.yml"), """
                id: rpgquest:guard
                start: greeting
                nodes:
                  greeting:
                    speaker: "Garde"
                    text: "Bienvenue."
                    choices:
                      - text: "Accepter"
                        conditions:
                          - type: QUEST_STATE
                            quest: rpgquest:first_steps
                            state: NOT_STARTED
                        actions:
                          - type: START_QUEST
                            quest: rpgquest:first_steps
                        next: accepted
                      - text: "Refuser"
                        next: refused
                      - text: "Voir la boutique"
                        actions:
                          - type: OPEN_MERCHANT
                            merchant: rpgquest:village_merchant
                  accepted:
                    speaker: "Garde"
                    text: "Merci !"
                    choices:
                      - text: "D'accord"
                        actions:
                          - type: CLOSE
                  refused:
                    speaker: "Garde"
                    text: "Dommage."
                    choices:
                      - text: "D'accord"
                        actions:
                          - type: CLOSE
                """);
        YamlDialogueEngine dialogueEngine = new YamlDialogueEngine(dialoguesDir, plugin.getSLF4JLogger(), List.of("give", "xp"));
        dialogueEngine.start();

        WalletRepository walletRepository = new WalletRepository(database);
        EconomyService economyService = new EconomyService(walletRepository);
        YamlCustomItemRegistry customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();
        YamlMerchantRegistry merchantRegistry = new YamlMerchantRegistry(tempDir.resolve("merchants"), plugin.getSLF4JLogger());
        merchantRegistry.start();
        merchantTradeService = new MerchantTradeService(
                plugin, merchantRegistry, economyService, customItemRegistry, questProgressEngine);
        merchantTradeService.start();

        sessionEngine = new DialogueSessionEngine(plugin, dialogueEngine, questProgressEngine, variableRepository, merchantTradeService);
        sessionEngine.start();
        renderer = new RecordingRenderer();
        sessionEngine.setRenderer(renderer);
    }

    @AfterEach
    void tearDown() {
        sessionEngine.stop();
        merchantTradeService.stop();
        questProgressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void conditionalChoiceIsVisibleWhenConditionIsMet() throws Exception {
        PlayerMock player = addPlayer();

        sessionEngine.open(player, DIALOGUE_ID);
        awaitRendered();

        assertTrue(renderer.lastVisibleChoices.stream().anyMatch(c -> c.label().equals("Accepter")));
    }

    @Test
    void conditionalChoiceIsInvisibleWhenConditionIsNotMet() throws Exception {
        PlayerMock player = addPlayer();
        questProgressEngine.accept(player, QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        sessionEngine.open(player, DIALOGUE_ID);
        awaitRendered();

        assertTrue(renderer.lastVisibleChoices.stream().noneMatch(c -> c.label().equals("Accepter")),
                () -> "choix visibles : " + renderer.lastVisibleChoices);
        assertTrue(renderer.lastVisibleChoices.stream().anyMatch(c -> c.label().equals("Refuser")));
    }

    @Test
    void startQuestActionActuallyStartsTheQuest() throws Exception {
        PlayerMock player = addPlayer();
        sessionEngine.open(player, DIALOGUE_ID);
        awaitRendered();

        int acceptIndex = renderer.lastVisibleChoices.stream()
                .filter(c -> c.label().equals("Accepter")).findFirst().orElseThrow().index();
        renderer.lastNode = null;
        sessionEngine.onChoiceSelected(player, DIALOGUE_ID, "greeting", acceptIndex);
        awaitRendered();

        QuestState state = questProgressEngine.stateOf(player.getUniqueId(), QUEST_ID).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(QuestState.ACTIVE, state);
        assertEquals("accepted", renderer.lastNode.id());
    }

    @Test
    void refusalBranchNavigatesToRefusedNode() throws Exception {
        PlayerMock player = addPlayer();
        sessionEngine.open(player, DIALOGUE_ID);
        awaitRendered();

        int refuseIndex = renderer.lastVisibleChoices.stream()
                .filter(c -> c.label().equals("Refuser")).findFirst().orElseThrow().index();
        renderer.lastNode = null;
        sessionEngine.onChoiceSelected(player, DIALOGUE_ID, "greeting", refuseIndex);
        awaitRendered();

        assertEquals("refused", renderer.lastNode.id());
    }

    @Test
    void openMerchantActionClosesTheDialogueAndOpensTheShop() throws Exception {
        PlayerMock player = addPlayer();
        sessionEngine.open(player, DIALOGUE_ID);
        awaitRendered();

        int shopIndex = renderer.lastVisibleChoices.stream()
                .filter(c -> c.label().equals("Voir la boutique")).findFirst().orElseThrow().index();
        renderer.lastNode = null;
        sessionEngine.onChoiceSelected(player, DIALOGUE_ID, "greeting", shopIndex);

        // La vitrine (5 offres dans village_merchant.yml) fait 27 slots, distinct de la vue par défaut (artisanat, 5 slots).
        int expectedShopSize = 27;
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (currentTopInventorySize(player) != expectedShopSize && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }

        assertNull(renderer.lastNode, "le dialogue ne doit pas se re-rendre après l'ouverture d'un marchand");
        assertEquals(expectedShopSize, currentTopInventorySize(player), "la vitrine du marchand doit être ouverte");
    }

    @Test
    void openingUnknownDialogueSendsAnErrorMessageWithoutCrashing() throws Exception {
        PlayerMock player = addPlayer();

        assertDoesNotThrow(() -> sessionEngine.open(player, new NamespacedKey("rpgquest", "does_not_exist")));
        assertNull(renderer.lastNode, "aucun rendu ne doit être déclenché pour un dialogue introuvable");
        assertTrue(player.nextMessage() != null, "un message d'erreur doit être envoyé au joueur");
    }

    private int currentTopInventorySize(PlayerMock player) {
        var view = player.getOpenInventory();
        return (view == null || view.getTopInventory() == null) ? 0 : view.getTopInventory().getSize();
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    /** Les conditions/actions passent par des CompletableFuture réels (thread DB) : on attend leur effet plutôt qu'un délai fixe. */
    private void awaitRendered() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (renderer.lastNode == null && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        assertTrue(renderer.lastNode != null, "le renderer n'a rien reçu avant le délai");
    }

    private static final class RecordingRenderer implements DialogueRenderer {
        private volatile DialogueNode lastNode;
        private volatile List<VisibleChoice> lastVisibleChoices;

        @Override
        public void render(Player player, DialogueDefinition dialogue, DialogueNode node, List<VisibleChoice> visibleChoices) {
            this.lastNode = node;
            this.lastVisibleChoices = visibleChoices;
        }
    }
}
