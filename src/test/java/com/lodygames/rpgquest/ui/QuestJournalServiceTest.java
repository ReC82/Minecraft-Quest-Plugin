package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.JournalConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class QuestJournalServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final String TRACKED_KEY = "__tracked_quest__";

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private Path questsDir;
    private YamlQuestEngine questEngine;
    private QuestProgressEngine progressEngine;
    private QuestProgressRepository progressRepository;
    private PlayerVariableRepository variableRepository;
    private PlayerProfileRepository profileRepository;
    private YamlCustomItemRegistry customItemRegistry;
    private QuestJournalService service;
    private QuestJournalListener listener;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        variableRepository = new PlayerVariableRepository(database);

        questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        progressRepository = new QuestProgressRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        progressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService);
        progressEngine.start();

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        service = new QuestJournalService(
                plugin, questEngine, progressEngine, variableRepository, customItemRegistry, new JournalConfig(true));
        service.start();
        listener = new QuestJournalListener(service);
    }

    @AfterEach
    void tearDown() {
        service.stop();
        progressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    // ---- Reconnaissance de l'item journal (identité RPGQuest / PDC) ------------------------------

    @Test
    void theJournalItemIsRecognisedByItsRpgQuestIdentityNotItsName() {
        ItemStack realJournal = customItemRegistry.create(RpgItemKeys.JOURNAL_QUETES, 1).orElseThrow();
        assertTrue(service.isJournalItem(realJournal));

        ItemStack plainBook = new ItemStack(Material.BOOK);
        assertFalse(service.isJournalItem(plainBook), "un simple livre vanilla n'est pas le journal");
        assertFalse(service.isJournalItem(null));
    }

    // ---- Onglets ------------------------------------------------------------------------------------

    @Test
    void playerWithNoQuestsSeesTwoEmptyTabs() throws Exception {
        PlayerMock player = addPlayer();

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        JournalSession inProgress = service.sessionOf(player);
        assertEquals(0, inProgress.page());
        assertTrue(inProgress.pageQuests().isEmpty());
        assertNotNull(player.getOpenInventory());

        showAndAwait(player, JournalTab.COMPLETED);
        assertTrue(service.sessionOf(player).pageQuests().isEmpty());
    }

    @Test
    void anAcceptedActiveQuestShowsInTheInProgressTabOnly() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "quest_0");
        setState(player, questId, QuestState.ACTIVE);

        showAndAwait(player, JournalTab.IN_PROGRESS);
        assertTrue(service.sessionOf(player).pageQuests().contains(questId), "quête active attendue dans « en cours »");

        showAndAwait(player, JournalTab.COMPLETED);
        assertFalse(service.sessionOf(player).pageQuests().contains(questId), "quête active absente de « terminées »");
    }

    @Test
    void aCompletedQuestShowsInTheCompletedTabOnly() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "quest_0");
        setState(player, questId, QuestState.COMPLETED);

        showAndAwait(player, JournalTab.COMPLETED);
        assertTrue(service.sessionOf(player).pageQuests().contains(questId), "quête terminée attendue dans « terminées »");

        showAndAwait(player, JournalTab.IN_PROGRESS);
        assertFalse(service.sessionOf(player).pageQuests().contains(questId), "quête terminée absente de « en cours »");
    }

    @Test
    void anUndiscoveredQuestNeverShowsInEitherTab() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "quest_0");
        // Aucune progression enregistrée : la quête n'a jamais été acceptée.

        showAndAwait(player, JournalTab.IN_PROGRESS);
        assertFalse(service.sessionOf(player).pageQuests().contains(questId));

        showAndAwait(player, JournalTab.COMPLETED);
        assertFalse(service.sessionOf(player).pageQuests().contains(questId),
                "une quête jamais découverte ne doit apparaître dans aucun onglet (pas de catalogue)");
    }

    // ---- Pagination -------------------------------------------------------------------------------

    @Test
    void pagination45ActiveQuestsFitOnASinglePage() throws Exception {
        writeQuests(45);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setAllStates(player, 45, QuestState.ACTIVE);

        openInProgress(player);

        assertEquals(45, service.sessionOf(player).pageQuests().size());
        assertNull(player.getOpenInventory().getTopInventory().getItem(QuestJournalService.NEXT_PAGE_SLOT),
                "une seule page : pas de bouton page suivante");
    }

    @Test
    void pagination46ActiveQuestsSpillToASecondPage() throws Exception {
        writeQuests(46);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setAllStates(player, 46, QuestState.ACTIVE);

        openInProgress(player);

        assertEquals(45, service.sessionOf(player).pageQuests().size());
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(QuestJournalService.NEXT_PAGE_SLOT),
                "46 quêtes doivent déborder sur une deuxième page");

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.NEXT_PAGE_SLOT, false);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).page() == 1);
        assertEquals(1, service.sessionOf(player).pageQuests().size());
    }

    @Test
    void leftClickOnAQuestOpensDetailView() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "quest_0");
        setState(player, questId, QuestState.ACTIVE);

        openInProgress(player);
        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], false);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).isDetail());

        assertEquals(questId, service.sessionOf(player).detailQuestId());
    }

    @Test
    void closeButtonInTheListViewDefersClosingToTheNextTick() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.ACTIVE);
        openInProgress(player);

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CLOSE_SLOT, false);

        assertTrue(isJournalStillOpen(player), "le clic ne doit pas fermer la fenêtre immédiatement");
        server.getScheduler().performTicks(1);
        assertFalse(isJournalStillOpen(player), "la fenêtre doit être fermée au tick suivant le clic");
    }

    @Test
    void closeButtonInTheDetailViewDefersClosingToTheNextTick() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.ACTIVE);
        openInProgress(player);
        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], false);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).isDetail());

        service.handleDetailClick(player, service.sessionOf(player), QuestJournalService.DETAIL_CLOSE_SLOT);

        assertTrue(isJournalStillOpen(player), "le clic ne doit pas fermer la fenêtre immédiatement");
        server.getScheduler().performTicks(1);
        assertFalse(isJournalStillOpen(player), "la fenêtre doit être fermée au tick suivant le clic");
    }

    @Test
    void rightClickTogglesTracking() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "quest_0");
        setState(player, questId, QuestState.ACTIVE);

        openInProgress(player);
        assertTrue(service.trackedQuestOf(player.getUniqueId()).isEmpty());

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], true);
        waitUntil(() -> service.trackedQuestOf(player.getUniqueId()).isPresent());
        assertEquals(questId, service.trackedQuestOf(player.getUniqueId()).orElseThrow());

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], true);
        waitUntil(() -> service.trackedQuestOf(player.getUniqueId()).isEmpty());
    }

    // ---- UX / sécurité : rien de récupérable ni duplicable ---------------------------------------

    @Test
    void everyClickTypeInsideTheMenuIsCancelled() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.ACTIVE);
        openInProgress(player);

        InventoryView view = player.getOpenInventory();
        assertCancelled(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertCancelled(view, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        assertCancelled(view, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP);
        assertCancelled(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertCancelled(view, ClickType.RIGHT, InventoryAction.PICKUP_HALF);
    }

    @Test
    void draggingIntoTheMenuIsCancelled() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.ACTIVE);
        openInProgress(player);

        InventoryView view = player.getOpenInventory();
        Map<Integer, ItemStack> newItems = new LinkedHashMap<>();
        newItems.put(QuestJournalService.CONTENT_SLOTS[0], new ItemStack(Material.DIRT));
        InventoryDragEvent event = new InventoryDragEvent(view, null, new ItemStack(Material.DIRT), false, newItems);

        listener.onInventoryDrag(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void dragEntirelyInPlayerInventoryIsNotAffected() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.ACTIVE);
        openInProgress(player);

        InventoryView view = player.getOpenInventory();
        int topSize = view.getTopInventory().getSize();
        Map<Integer, ItemStack> newItems = new LinkedHashMap<>();
        newItems.put(topSize + 1, new ItemStack(Material.DIRT));
        InventoryDragEvent event = new InventoryDragEvent(view, null, new ItemStack(Material.DIRT), false, newItems);

        listener.onInventoryDrag(event);
        assertFalse(event.isCancelled(), "un drag entièrement dans l'inventaire du joueur n'a pas besoin d'être bloqué");
    }

    @Test
    void questRemovedDuringReloadFallsBackGracefullyFromDetailView() throws Exception {
        Files.writeString(questsDir.resolve("temp.yml"), questYaml("rpgquest:temp", "Temporaire"));
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "temp");

        service.showDetail(player, JournalTab.IN_PROGRESS, 0, questId);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).isDetail());

        Files.delete(questsDir.resolve("temp.yml"));
        questEngine.reload();

        assertDoesNotThrow(() -> service.showDetail(player, JournalTab.IN_PROGRESS, 0, questId));
        waitUntil(() -> service.sessionOf(player) != null && !service.sessionOf(player).isDetail());
    }

    @Test
    void trackedQuestSurvivesAReconnection() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = questEngine.quests().get(0).id();

        variableRepository.set(player.getUniqueId(), TRACKED_KEY, questId.toString())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service.handleQuit(player);
        assertTrue(service.trackedQuestOf(player.getUniqueId()).isEmpty());

        service.handleJoin(player);
        waitUntil(() -> service.trackedQuestOf(player.getUniqueId()).isPresent());
        assertEquals(questId, service.trackedQuestOf(player.getUniqueId()).orElseThrow());
    }

    // ---- Utilitaires ----------------------------------------------------

    private void assertCancelled(InventoryView view, ClickType click, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                QuestJournalService.CONTENT_SLOTS[0], click, action);
        listener.onInventoryClick(event);
        assertTrue(event.isCancelled(), () -> click + "/" + action + " doit être annulé");
    }

    private boolean isJournalStillOpen(PlayerMock player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof JournalInventoryHolder;
    }

    private void openInProgress(PlayerMock player) throws InterruptedException {
        showAndAwait(player, JournalTab.IN_PROGRESS);
    }

    /** Ouvre {@code tab} puis attend que la session reflète bien cet onglet (pas une session périmée). */
    private void showAndAwait(PlayerMock player, JournalTab tab) throws InterruptedException {
        service.showList(player, tab, 0);
        waitUntil(() -> {
            JournalSession session = service.sessionOf(player);
            return session != null && !session.isDetail() && session.tab() == tab;
        });
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void writeQuests(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            Files.writeString(questsDir.resolve("quest_" + i + ".yml"),
                    questYaml("rpgquest:quest_" + i, "Quête " + i));
        }
    }

    private void setState(PlayerMock player, NamespacedKey questId, QuestState state) throws Exception {
        progressRepository.upsertState(player.getUniqueId(), questId, state, "step_one")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void setAllStates(PlayerMock player, int count, QuestState state) throws Exception {
        for (int i = 0; i < count; i++) {
            setState(player, new NamespacedKey("rpgquest", "quest_" + i), state);
        }
    }

    private String questYaml(String id, String title) {
        return """
                id: %s
                title: "%s"
                description: "Description"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                """.formatted(id, title);
    }

    private void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition non atteinte avant le délai");
    }
}
