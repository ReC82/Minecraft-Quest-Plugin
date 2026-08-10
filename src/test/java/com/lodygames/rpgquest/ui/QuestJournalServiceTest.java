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
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
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
    private PlayerVariableRepository variableRepository;
    private PlayerProfileRepository profileRepository;
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

        QuestProgressRepository progressRepository = new QuestProgressRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(plugin, new NpcIdRepository(database));
        progressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService);
        progressEngine.start();

        service = new QuestJournalService(plugin, questEngine, progressEngine, variableRepository, new JournalConfig(true));
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

    @Test
    void playerWithNoQuestsSeesAnEmptyFirstPage() throws Exception {
        PlayerMock player = addPlayer();

        service.open(player);
        awaitList(player);

        JournalSession session = service.sessionOf(player);
        assertEquals(0, session.page());
        assertTrue(session.pageQuests().isEmpty());
        assertNotNull(player.getOpenInventory());
    }

    @Test
    void pagination45QuestsFitsOnASinglePage() throws Exception {
        writeAvailableQuests(45);
        questEngine.reload();
        PlayerMock player = addPlayer();

        openAvailable(player);

        assertEquals(45, service.sessionOf(player).pageQuests().size());
        assertNull(player.getOpenInventory().getTopInventory().getItem(QuestJournalService.NEXT_PAGE_SLOT),
                "une seule page : pas de bouton page suivante");
    }

    @Test
    void pagination46QuestsSpillsToASecondPage() throws Exception {
        writeAvailableQuests(46);
        questEngine.reload();
        PlayerMock player = addPlayer();

        openAvailable(player);

        assertEquals(45, service.sessionOf(player).pageQuests().size());
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(QuestJournalService.NEXT_PAGE_SLOT),
                "46 quêtes doivent déborder sur une deuxième page");

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.NEXT_PAGE_SLOT, false);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).page() == 1);
        assertEquals(1, service.sessionOf(player).pageQuests().size());
    }

    @Test
    void leftClickOnAQuestOpensDetailView() throws Exception {
        writeAvailableQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();

        openAvailable(player);
        NamespacedKey questId = service.sessionOf(player).pageQuests().get(0);

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], false);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).isDetail());

        assertEquals(questId, service.sessionOf(player).detailQuestId());
    }

    @Test
    void rightClickTogglesTracking() throws Exception {
        writeAvailableQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();

        openAvailable(player);
        NamespacedKey questId = service.sessionOf(player).pageQuests().get(0);
        assertTrue(service.trackedQuestOf(player.getUniqueId()).isEmpty());

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], true);
        waitUntil(() -> service.trackedQuestOf(player.getUniqueId()).isPresent());
        assertEquals(questId, service.trackedQuestOf(player.getUniqueId()).orElseThrow());

        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], true);
        waitUntil(() -> service.trackedQuestOf(player.getUniqueId()).isEmpty());
    }

    @Test
    void everyClickTypeInsideTheMenuIsCancelled() throws Exception {
        writeAvailableQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        openAvailable(player);

        InventoryView view = player.getOpenInventory();

        assertCancelled(view, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertCancelled(view, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        assertCancelled(view, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP);
        assertCancelled(view, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertCancelled(view, ClickType.RIGHT, InventoryAction.PICKUP_HALF);
    }

    @Test
    void draggingIntoTheMenuIsCancelled() throws Exception {
        writeAvailableQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        openAvailable(player);

        InventoryView view = player.getOpenInventory();
        Map<Integer, ItemStack> newItems = new LinkedHashMap<>();
        newItems.put(QuestJournalService.CONTENT_SLOTS[0], new ItemStack(Material.DIRT));
        InventoryDragEvent event = new InventoryDragEvent(view, null, new ItemStack(Material.DIRT), false, newItems);

        listener.onInventoryDrag(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void dragEntirelyInPlayerInventoryIsNotAffected() throws Exception {
        writeAvailableQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        openAvailable(player);

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

        service.showDetail(player, JournalTab.AVAILABLE, 0, questId);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).isDetail());

        Files.delete(questsDir.resolve("temp.yml"));
        questEngine.reload();

        assertDoesNotThrow(() -> service.showDetail(player, JournalTab.AVAILABLE, 0, questId));
        waitUntil(() -> service.sessionOf(player) != null && !service.sessionOf(player).isDetail());
    }

    @Test
    void trackedQuestSurvivesAReconnection() throws Exception {
        writeAvailableQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = questEngine.quests().get(0).id();

        variableRepository.set(player.getUniqueId(), TRACKED_KEY, questId.toString())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Déconnexion : le cache mémoire est vidé (la donnée persistée en base n'est pas touchée).
        service.handleQuit(player);
        assertTrue(service.trackedQuestOf(player.getUniqueId()).isEmpty());

        // Reconnexion : le suivi doit être rechargé depuis la base.
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

    private void openAvailable(PlayerMock player) throws InterruptedException {
        service.showList(player, JournalTab.AVAILABLE, 0);
        awaitList(player);
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void writeAvailableQuests(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            Files.writeString(questsDir.resolve("quest_" + i + ".yml"),
                    questYaml("rpgquest:quest_" + i, "Quête " + i));
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

    private void awaitList(PlayerMock player) throws InterruptedException {
        waitUntil(() -> service.sessionOf(player) != null && !service.sessionOf(player).isDetail());
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
