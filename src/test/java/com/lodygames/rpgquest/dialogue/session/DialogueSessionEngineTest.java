package com.lodygames.rpgquest.dialogue.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.render.DialogueRenderer;
import com.lodygames.rpgquest.dialogue.render.VisibleChoice;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.merchant.MerchantTradeService;
import com.lodygames.rpgquest.economy.merchant.YamlMerchantRegistry;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.npc.NpcIdentityService;
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
    private ClaimService claimService;
    private RecordingRenderer renderer;
    private PlayerProfileRepository profileRepository;
    private YamlDialogueEngine dialogueEngine;
    private Path dialoguesDir;
    private PlayerVariableRepository variableRepository;
    private YamlCustomItemRegistry customItemRegistry;

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
        variableRepository = new PlayerVariableRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService);
        questProgressEngine.start();

        dialoguesDir = tempDir.resolve("dialogues");
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
        dialogueEngine = new YamlDialogueEngine(dialoguesDir, plugin.getSLF4JLogger(), List.of("give", "xp"));
        dialogueEngine.start();

        WalletRepository walletRepository = new WalletRepository(database);
        EconomyService economyService = new EconomyService(walletRepository);
        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();
        YamlMerchantRegistry merchantRegistry = new YamlMerchantRegistry(tempDir.resolve("merchants"), plugin.getSLF4JLogger());
        merchantRegistry.start();
        merchantTradeService = new MerchantTradeService(
                plugin, merchantRegistry, economyService, customItemRegistry, questProgressEngine);
        merchantTradeService.start();

        ClaimRepository claimRepository = new ClaimRepository(database);
        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zoneRegistry.start();
        YamlPortalRegistry portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        ConfigService configService = new ConfigService(plugin);
        configService.start();
        ProgressionService progressionService = new ProgressionService(
                plugin, new ProgressionRepository(database), () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();
        claimService = new ClaimService(plugin, claimRepository, zoneRegistry, portalRegistry, configService,
                progressionService, variableRepository);
        claimService.start();

        sessionEngine = new DialogueSessionEngine(
                plugin, dialogueEngine, questProgressEngine, variableRepository, merchantTradeService, npcIdentityService,
                claimService, customItemRegistry);
        sessionEngine.start();
        renderer = new RecordingRenderer();
        sessionEngine.setRenderer(renderer);
    }

    @AfterEach
    void tearDown() {
        sessionEngine.stop();
        merchantTradeService.stop();
        claimService.stop();
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

    /**
     * Couvre {@code NO_MAIN_CLAIM} (mission « Jo » — option visible seulement si le joueur n'a
     * encore aucun claim principal), utilisé par le dialogue Jo pour ne proposer l'Acte de propriété
     * que tant qu'aucun claim n'existe déjà.
     */
    @Test
    void noMainClaimConditionHidesTheChoiceOnceAClaimExists() throws Exception {
        Files.writeString(dialoguesDir.resolve("jo.yml"), """
                id: rpgquest:jo
                start: greeting
                nodes:
                  greeting:
                    speaker: "Jo"
                    text: "Bonjour."
                    choices:
                      - text: "Je viens réclamer mon acte de propriété"
                        conditions:
                          - type: NO_MAIN_CLAIM
                        actions:
                          - type: CLOSE
                      - text: "Rien pour le moment"
                        actions:
                          - type: CLOSE
                """);
        dialogueEngine.reload();
        NamespacedKey joId = new NamespacedKey("rpgquest", "jo");
        PlayerMock player = addPlayer();

        sessionEngine.open(player, joId);
        awaitRendered();
        assertTrue(renderer.lastVisibleChoices.stream().anyMatch(c -> c.label().contains("acte de propriété")),
                "aucun claim : l'option doit être visible");

        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        org.bukkit.World world = server.addSimpleWorld("world_for_claim_test");
        var outcome = claimService.create(player, "main_" + player.getUniqueId(),
                        new org.bukkit.Location(world, 0, 60, 0), new org.bukkit.Location(world, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome, "pré-requis du test : le claim doit vraiment être créé");
        server.getScheduler().performTicks(2);

        renderer.lastNode = null;
        sessionEngine.open(player, joId);
        awaitRendered();
        assertTrue(renderer.lastVisibleChoices.stream().noneMatch(c -> c.label().contains("acte de propriété")),
                () -> "claim déjà créé : l'option doit disparaître, obtenu : " + renderer.lastVisibleChoices);
    }

    /**
     * Couvre {@code HAS_MAIN_CLAIM} (mission « Jo : retourner à son claim ») — strict opposé de
     * {@code NO_MAIN_CLAIM} : l'option « Me rendre sur ma propriété » ne doit apparaître qu'une fois
     * qu'un claim principal existe réellement, jamais avant.
     */
    @Test
    void hasMainClaimConditionShowsTheChoiceOnlyOnceAClaimExists() throws Exception {
        Files.writeString(dialoguesDir.resolve("jo.yml"), """
                id: rpgquest:jo
                start: greeting
                nodes:
                  greeting:
                    speaker: "Jo"
                    text: "Bonjour."
                    choices:
                      - text: "Me rendre sur ma propriété"
                        conditions:
                          - type: HAS_MAIN_CLAIM
                        actions:
                          - type: CLOSE
                      - text: "Rien pour le moment"
                        actions:
                          - type: CLOSE
                """);
        dialogueEngine.reload();
        NamespacedKey joId = new NamespacedKey("rpgquest", "jo");
        PlayerMock player = addPlayer();

        sessionEngine.open(player, joId);
        awaitRendered();
        assertTrue(renderer.lastVisibleChoices.stream().noneMatch(c -> c.label().contains("propriété")),
                "aucun claim : l'option ne doit pas être visible");

        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        org.bukkit.World world = server.addSimpleWorld("world_for_has_main_claim_test");
        var outcome = claimService.create(player, "main_" + player.getUniqueId(),
                        new org.bukkit.Location(world, 0, 60, 0), new org.bukkit.Location(world, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome, "pré-requis du test : le claim doit vraiment être créé");
        server.getScheduler().performTicks(2);

        renderer.lastNode = null;
        sessionEngine.open(player, joId);
        awaitRendered();
        assertTrue(renderer.lastVisibleChoices.stream().anyMatch(c -> c.label().contains("propriété")),
                () -> "claim créé : l'option doit devenir visible, obtenu : " + renderer.lastVisibleChoices);
    }

    /**
     * Couvre {@code LACKS_CUSTOM_ITEM} (mission « ne pas permettre de farmer inutilement » — Pierre
     * de retour / Acte réutilisé comme visualiseur) : l'option ne doit être visible que tant que le
     * joueur ne détient <strong>aucun</strong> exemplaire de l'objet identifié par PDC — jamais un
     * simple match par matériau (voir {@code HasItemCondition}, volontairement différent).
     */
    @Test
    void lacksCustomItemConditionHidesTheChoiceOnceTheItemIsHeld() throws Exception {
        Files.writeString(dialoguesDir.resolve("jo.yml"), """
                id: rpgquest:jo
                start: greeting
                nodes:
                  greeting:
                    speaker: "Jo"
                    text: "Bonjour."
                    choices:
                      - text: "Obtenir une Pierre de retour"
                        conditions:
                          - type: LACKS_CUSTOM_ITEM
                            item: rpgquest:pierre_retour
                        actions:
                          - type: CLOSE
                      - text: "Rien pour le moment"
                        actions:
                          - type: CLOSE
                """);
        dialogueEngine.reload();
        NamespacedKey joId = new NamespacedKey("rpgquest", "jo");
        PlayerMock player = addPlayer();

        sessionEngine.open(player, joId);
        awaitRendered();
        assertTrue(renderer.lastVisibleChoices.stream().anyMatch(c -> c.label().contains("Pierre de retour")),
                "aucune Pierre en poche : l'option doit être visible");

        player.getInventory().addItem(customItemRegistry.create(
                new NamespacedKey("rpgquest", "pierre_retour"), 1).orElseThrow());

        renderer.lastNode = null;
        sessionEngine.open(player, joId);
        awaitRendered();
        assertTrue(renderer.lastVisibleChoices.stream().noneMatch(c -> c.label().contains("Pierre de retour")),
                () -> "Pierre déjà en poche : l'option doit disparaître, obtenu : " + renderer.lastVisibleChoices);
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
