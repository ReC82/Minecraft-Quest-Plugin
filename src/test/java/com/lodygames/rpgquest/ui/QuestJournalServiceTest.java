package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
    private WalletRepository walletRepository;
    private EconomyService economyService;
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
        walletRepository = new WalletRepository(database);
        economyService = new EconomyService(walletRepository);
        progressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService,
                economyService);
        progressEngine.start();

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        service = new QuestJournalService(
                plugin, questEngine, progressEngine, variableRepository, customItemRegistry, economyService,
                new JournalConfig(true));
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
    void bidirectionalTabNavigationWithoutClosingKeepsWorkingAndCloseButtonStillWorks() throws Exception {
        // Reproduit le bug remonté en validation manuelle DEV : IN_PROGRESS -> COMPLETED marche,
        // mais COMPLETED -> IN_PROGRESS (sans fermer) « ne rafraîchit pas », et le bouton Fermer
        // devient inerte. Cause : openInventory (qui remplace le menu ouvert) déclenche un
        // InventoryCloseEvent synchrone -> handleClose -> openSessions.remove, ce qui effaçait la
        // session tout juste posée. Il FAUT donc enregistrer le listener ici pour que le close
        // event soit réellement traité.
        writeQuests(2);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey active = new NamespacedKey("rpgquest", "quest_0");
        NamespacedKey done = new NamespacedKey("rpgquest", "quest_1");
        setState(player, active, QuestState.ACTIVE);
        setState(player, done, QuestState.COMPLETED);

        server.getPluginManager().registerEvents(listener, plugin);

        service.open(player);
        waitUntil(() -> listTabOf(player) == JournalTab.IN_PROGRESS);
        assertTrue(service.sessionOf(player).pageQuests().contains(active));

        // 1) IN_PROGRESS -> COMPLETED, via un vrai clic sur l'onglet (slot 2)
        clickTopSlot(player, QuestJournalService.TAB_COMPLETED_SLOT, ClickType.LEFT);
        waitUntil(() -> listTabOf(player) == JournalTab.COMPLETED);
        assertTrue(service.sessionOf(player).pageQuests().contains(done), "onglet terminées affiché");

        // 2) COMPLETED -> IN_PROGRESS, via un vrai clic sur l'onglet (slot 0), SANS fermer le menu
        clickTopSlot(player, QuestJournalService.TAB_IN_PROGRESS_SLOT, ClickType.LEFT);
        waitUntil(() -> listTabOf(player) == JournalTab.IN_PROGRESS);
        assertEquals(JournalTab.IN_PROGRESS, listTabOf(player),
                "le retour sur « en cours » doit rafraîchir la vue, pas rester bloqué sur « terminées »");
        assertTrue(service.sessionOf(player).pageQuests().contains(active));
        assertFalse(service.sessionOf(player).pageQuests().contains(done));

        // 3) le bouton Fermer fonctionne encore après cette navigation bidirectionnelle
        clickTopSlot(player, QuestJournalService.CLOSE_SLOT, ClickType.LEFT);
        assertTrue(isJournalStillOpen(player), "fermeture différée d'un tick");
        server.getScheduler().performTicks(2);
        assertFalse(isJournalStillOpen(player), "le bouton Fermer doit bien fermer le journal");
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

    // ---- Page « Ma progression RPG » (issue #183, retour du 20/10) ------------------------------

    /** Courbe réelle de la progression (100 XP puis ×2), niveau max 4 : tout calcul vient d'elle. */
    private com.lodygames.rpgquest.progression.ProgressionService progressionWithCurve() {
        var config = new com.lodygames.rpgquest.config.ProgressionConfig(100L, 2.0, 4, 0.0, 1000,
                com.lodygames.rpgquest.config.DisplayMode.OFF, false, 50, 15, 5, 4, 10, 100, 0);
        var progression = new com.lodygames.rpgquest.progression.ProgressionService(plugin,
                new com.lodygames.rpgquest.database.ProgressionRepository(database), () -> config,
                plugin.getSLF4JLogger());
        service.setProgression(progression);
        return progression;
    }

    private void setXp(com.lodygames.rpgquest.progression.ProgressionService progression, PlayerMock player,
                       com.lodygames.rpgquest.progression.model.SkillType skill, long totalXp) throws Exception {
        progression.setTotalXp(player.getUniqueId(), skill, totalXp).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    private void openProgressionFromPurse(PlayerMock player) throws InterruptedException {
        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        clickTopSlot(player, QuestJournalService.BALANCE_SLOT, ClickType.LEFT);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).isProgression());
    }

    @Test
    void thePurseShowsTheGlobalRpgLevelAndInvitesToOpenTheProgression() throws Exception {
        var progression = progressionWithCurve();
        PlayerMock player = addPlayer();
        setXp(progression, player, com.lodygames.rpgquest.progression.model.SkillType.GLOBAL, 190L);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);

        var purse = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.BALANCE_SLOT);
        String lore = String.join("\n", loreText(purse));
        assertTrue(plain(purse.getItemMeta().displayName()).contains("0"), "la bourse reste la bourse");
        assertTrue(lore.contains("Niveau RPG 2"), lore);
        assertTrue(lore.contains("45 %"), lore);
        assertTrue(lore.contains("Clic : voir ma progression"), lore);
    }

    @Test
    void clickingThePurseOpensOneGaugePerTrackGlobalFirst() throws Exception {
        var progression = progressionWithCurve();
        PlayerMock player = addPlayer();
        setXp(progression, player, com.lodygames.rpgquest.progression.model.SkillType.GLOBAL, 190L);
        setXp(progression, player, com.lodygames.rpgquest.progression.model.SkillType.MINING, 700L); // niveau max

        openProgressionFromPurse(player);

        Inventory page = player.getOpenInventory().getTopInventory();
        assertTrue(page.getHolder() instanceof JournalInventoryHolder holder && holder.kind() == JournalKind.PROGRESSION);
        var tracks = com.lodygames.rpgquest.progression.model.SkillType.values();
        for (int i = 0; i < tracks.length; i++) {
            ItemStack icon = page.getItem(QuestJournalService.PROGRESSION_FIRST_SLOT + i);
            int index = i;
            assertNotNull(icon, () -> "piste absente : " + tracks[index]);
            String title = plain(icon.getItemMeta().displayName());
            assertTrue(title.startsWith(tracks[i].label()), title);
        }
        String global = String.join("\n", loreText(page.getItem(QuestJournalService.PROGRESSION_FIRST_SLOT)));
        assertTrue(global.contains("XP : 90 / 200 pour le niveau 3"), global);
        String mining = String.join("\n", loreText(page.getItem(QuestJournalService.PROGRESSION_FIRST_SLOT
                + com.lodygames.rpgquest.progression.model.SkillType.MINING.ordinal())));
        assertTrue(mining.contains("Niveau maximal atteint"), mining);
        assertTrue(mining.contains("100 %"), mining);
    }

    @Test
    void aPlayerWithoutProgressionSeesEveryTrackAtLevelOneAndZeroPercent() throws Exception {
        progressionWithCurve();
        PlayerMock player = addPlayer();

        openProgressionFromPurse(player);

        Inventory page = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < com.lodygames.rpgquest.progression.model.SkillType.values().length; i++) {
            ItemStack icon = page.getItem(QuestJournalService.PROGRESSION_FIRST_SLOT + i);
            assertTrue(plain(icon.getItemMeta().displayName()).endsWith("niveau 1"));
            String lore = String.join("\n", loreText(icon));
            assertTrue(lore.contains("0 %") && lore.contains("XP : 0 / 100"), lore);
            assertTrue(loreText(icon).size() <= QuestJournalService.MAX_DETAIL_LORE_LINES, lore);
        }
    }

    @Test
    void theProgressionPageIsReadOnlyAndBackReturnsToTheSameTab() throws Exception {
        var progression = progressionWithCurve();
        PlayerMock player = addPlayer();
        setXp(progression, player, com.lodygames.rpgquest.progression.model.SkillType.COMBAT, 150L);
        service.open(player);
        showAndAwait(player, JournalTab.COMPLETED);
        clickTopSlot(player, QuestJournalService.BALANCE_SLOT, ClickType.LEFT);
        waitUntil(() -> service.sessionOf(player) != null && service.sessionOf(player).isProgression());

        InventoryView view = player.getOpenInventory();
        for (ClickType click : new ClickType[] {ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY}) {
            InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                    QuestJournalService.PROGRESSION_FIRST_SLOT, click, InventoryAction.PICKUP_ALL);
            listener.onInventoryClick(event);
            assertTrue(event.isCancelled(), () -> click + " doit être annulé");
        }
        assertEquals(150L, progression.totalXp(player.getUniqueId(),
                com.lodygames.rpgquest.progression.model.SkillType.COMBAT), "consulter ne modifie aucune XP");

        clickTopSlot(player, QuestJournalService.PROGRESSION_BACK_SLOT, ClickType.LEFT);
        waitUntil(() -> listTabOf(player) == JournalTab.COMPLETED && !service.sessionOf(player).isProgression());
    }

    @Test
    void theProgressionPageCloseButtonDefersClosingToTheNextTick() throws Exception {
        progressionWithCurve();
        PlayerMock player = addPlayer();
        openProgressionFromPurse(player);

        clickTopSlot(player, QuestJournalService.PROGRESSION_CLOSE_SLOT, ClickType.LEFT);

        assertTrue(isJournalStillOpen(player));
        server.getScheduler().performTicks(1);
        assertFalse(isJournalStillOpen(player));
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

    /** Onglet de la session liste courante, ou {@code null} (pas de session, ou vue détail). */
    private JournalTab listTabOf(PlayerMock player) {
        JournalSession session = service.sessionOf(player);
        return session != null && !session.isDetail() ? session.tab() : null;
    }

    /** Clic réel sur un slot du haut du menu, routé par le listener (comme sur un serveur Paper). */
    private void clickTopSlot(PlayerMock player, int rawSlot, ClickType click) {
        InventoryView view = player.getOpenInventory();
        InventoryClickEvent event = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, rawSlot, click, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);
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

    // ---- Infobulles : compacte en liste, complète en détails ----------------------------------

    /**
     * Issue #475 : la vue détail est une rangée d'objets (quête, description, objectifs,
     * récompenses, prérequis) — son texte complet est celui de toute la rangée.
     */
    static List<String> detailText(Inventory detail) {
        List<String> lines = new java.util.ArrayList<>();
        for (int slot = QuestJournalService.DETAIL_ICON_SLOT; slot <= QuestJournalService.DETAIL_LAST_SECTION_SLOT; slot++) {
            ItemStack stack = detail.getItem(slot);
            if (stack != null) {
                lines.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(stack.getItemMeta().displayName()));
                lines.addAll(loreText(stack));
            }
        }
        return lines;
    }

    /**
     * Issue #475 (retour du 17/10, capture 131135) : l'infobulle détaillée était trop HAUTE pour
     * une fenêtre 854×480 — son début sortait de l'écran. Une longue description et de nombreux
     * objectifs sont répartis sur plusieurs objets, chacun borné, et rien n'est perdu.
     */
    @Test
    void aLongDetailIsSplitIntoBoundedTooltipsWithoutLosingAnything() throws Exception {
        StringBuilder description = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            description.append("Phrase").append(i).append(" de la longue histoire. ");
        }
        StringBuilder objectives = new StringBuilder();
        for (String entity : List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "WITCH", "SLIME", "HUSK",
                "STRAY", "DROWNED", "PILLAGER", "VINDICATOR", "EVOKER")) {
            objectives.append("      - type: KILL_ENTITY\n        entity: ").append(entity).append("\n        amount: 2\n");
        }
        Files.writeString(questsDir.resolve("long.yml"), """
                id: rpgquest:long
                title: "Longue quête"
                description: "%s"
                category: test
                steps:
                  - id: step_one
                    objectives:
                %s
                rewards:
                  - type: EXPERIENCE
                    amount: 50
                """.formatted(description.toString().strip(), objectives.toString().stripTrailing()));
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "long"), QuestState.ACTIVE);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], false);
        waitUntil(() -> player.getOpenInventory().getTopInventory()
                .getItem(QuestJournalService.DETAIL_ICON_SLOT) != null);

        Inventory detail = player.getOpenInventory().getTopInventory();
        int items = 0;
        for (int slot = QuestJournalService.DETAIL_ICON_SLOT; slot <= QuestJournalService.DETAIL_LAST_SECTION_SLOT; slot++) {
            ItemStack stack = detail.getItem(slot);
            if (stack != null) {
                items++;
                assertTrue(loreText(stack).size() <= QuestJournalService.MAX_DETAIL_LORE_LINES,
                        () -> "infobulle trop haute : " + loreText(stack));
            }
        }
        assertTrue(items >= 5, "quête + description et objectifs en plusieurs pages + récompenses : " + items);
        String joined = String.join("\n", detailText(detail));
        assertTrue(joined.contains("Phrase1 ") && joined.contains("Phrase40 "), "toute la description : " + joined);
        assertTrue(joined.contains("Description (1/") && joined.contains("Objectifs (2/2)"), joined);
        assertTrue(joined.contains("50 XP"), joined);
        assertEquals(12, java.util.regex.Pattern.compile(" 0/2").matcher(joined).results().count(),
                () -> "les douze objectifs : " + joined);
        assertNotNull(detail.getItem(QuestJournalService.DETAIL_BACK_SLOT), "navigation conservée");
        assertNotNull(detail.getItem(QuestJournalService.DETAIL_TRACK_SLOT));
    }

    /** Texte brut (sans couleur ni balise) de la lore d'un objet, une ligne par entrée. */
    private static List<String> loreText(org.bukkit.inventory.ItemStack stack) {
        var lore = stack.getItemMeta().lore();
        if (lore == null) {
            return List.of();
        }
        return lore.stream()
                .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(c))
                .toList();
    }

    @Test
    void theListTooltipStaysCompactAndFreeOfTechnicalIdentifiers() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "quest_0");
        setState(player, questId, QuestState.ACTIVE);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        var icon = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.CONTENT_SLOTS[0]);
        assertNotNull(icon, "la quête active doit avoir une icône");
        String joined = String.join("\n", loreText(icon));

        assertTrue(joined.contains("État"), "l'état doit rester dans l'infobulle compacte");
        assertTrue(joined.contains("Clic gauche") && joined.contains("Clic droit"),
                "les indications de clic doivent rester : " + joined);
        assertTrue(joined.contains("0/1"), "le compteur de l'objectif doit apparaître : " + joined);

        // Les identifiants techniques ne doivent plus fuiter dans une infobulle joueur.
        assertFalse(joined.contains("step_one"), "l'id technique d'étape ne doit plus être affiché : " + joined);
        assertFalse(joined.contains("ZOMBIE"), "l'entité doit être nommée, pas affichée en id brut : " + joined);
        assertFalse(joined.contains("rpgquest:"), "aucun id namespacé dans l'infobulle : " + joined);

        // Allégée : description et récompenses passent dans les détails.
        assertFalse(joined.contains("Description"), "la description appartient aux détails : " + joined);
        assertFalse(joined.contains("Récompenses"), "les récompenses appartiennent aux détails : " + joined);

        assertTrue(icon.getItemMeta().hasItemFlag(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES),
                "les attributs vanilla de l'icône (dégâts d'attaque…) doivent être masqués");
    }

    @Test
    void theDetailTooltipCarriesDescriptionAndRewardsButNeverTheInternalVariable() throws Exception {
        Files.writeString(questsDir.resolve("rewarded.yml"), """
                id: rpgquest:rewarded
                title: "Quête récompensée"
                description: "Description longue"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: EXPERIENCE
                    amount: 50
                  - type: VARIABLE
                    key: CLAIM_TIER_1
                    value: "true"
                """);
        questEngine.reload();
        PlayerMock player = addPlayer();
        NamespacedKey questId = new NamespacedKey("rpgquest", "rewarded");
        setState(player, questId, QuestState.ACTIVE);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], false);
        waitUntil(() -> player.getOpenInventory().getTopInventory()
                .getItem(QuestJournalService.DETAIL_ICON_SLOT) != null);

        String joined = String.join("\n", detailText(player.getOpenInventory().getTopInventory()));

        assertTrue(joined.contains("Description longue"), "la description est dans les détails : " + joined);
        assertTrue(joined.contains("50 XP"), "la récompense d'XP est dans les détails : " + joined);
        // Une récompense VARIABLE est un état interne : son nom de clé ne doit jamais être montré.
        assertFalse(joined.contains("CLAIM_TIER_1"), "la variable interne ne doit pas fuiter : " + joined);
        assertFalse(joined.contains("step_one"), "pas d'id technique non plus dans les détails : " + joined);
    }

    // ---- Bourse et récompense monétaire (issue #16) --------------------------------------------

    @Test
    void theJournalShowsTheRealWalletBalance() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 1234, "ADMIN_GRANT", "test")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);

        var purse = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.BALANCE_SLOT);
        assertNotNull(purse, "le solde doit être visible dans une interface déjà utilisée, pas seulement via une commande");
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(purse.getItemMeta().displayName());
        // 1234 vient du PORTEFEUILLE, pas d'un objet d'inventaire : aucun item n'est de la monnaie.
        assertTrue(name.contains("1234"), () -> "le solde réel doit s'afficher : " + name);
    }

    @Test
    void theBalanceIsAlsoVisibleInTheDetailView() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 77, "ADMIN_GRANT", "test")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.ACTIVE);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], false);
        // Attendre l'ICÔNE DE DÉTAIL, pas le slot de bourse : le slot 4 porte l'indicateur de page
        // dans la vue LISTE, donc l'attendre rendrait le test vert sans avoir changé de vue.
        waitUntil(() -> player.getOpenInventory().getTopInventory()
                .getItem(QuestJournalService.DETAIL_ICON_SLOT) != null);

        var purse = player.getOpenInventory().getTopInventory()
                .getItem(QuestJournalService.DETAIL_BALANCE_SLOT);
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(purse.getItemMeta().displayName());
        assertTrue(name.contains("77"), () -> name);
    }

    @Test
    void anUntouchedWalletShowsZeroAndNotAnError() throws Exception {
        PlayerMock player = addPlayer();

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);

        var purse = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.BALANCE_SLOT);
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(purse.getItemMeta().displayName());
        assertTrue(name.contains("0"), () -> "un portefeuille jamais touché vaut 0, ce n'est pas une panne : " + name);
        assertFalse(name.toLowerCase(java.util.Locale.ROOT).contains("indisponible"), name);
    }

    @Test
    void theBalanceSlotIsInertAndDoesNotCloseOrNavigate() throws Exception {
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.ACTIVE);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        JournalSession before = service.sessionOf(player);

        service.handleListClick(player, before, QuestJournalService.BALANCE_SLOT, false);
        server.getScheduler().performTicks(5);

        JournalSession after = service.sessionOf(player);
        assertNotNull(after, "cliquer un indicateur ne doit rien fermer");
        assertEquals(before.tab(), after.tab());
        assertFalse(after.isDetail(), "l'indicateur de bourse n'est pas un bouton de navigation");
    }

    @Test
    void aMoneyRewardAppearsInTheDetailTooltipAsPlannedCoins() throws Exception {
        Files.writeString(questsDir.resolve("paid.yml"), """
                id: rpgquest:paid
                title: "Quête payée"
                description: "Description"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                rewards:
                  - type: MONEY
                    amount: 250
                """);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "paid"), QuestState.ACTIVE);

        service.open(player);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        service.handleListClick(player, service.sessionOf(player), QuestJournalService.CONTENT_SLOTS[0], false);
        waitUntil(() -> player.getOpenInventory().getTopInventory()
                .getItem(QuestJournalService.DETAIL_ICON_SLOT) != null);

        String joined = String.join("\n", detailText(player.getOpenInventory().getTopInventory()));
        assertTrue(joined.contains("250 LodyCoins"), () -> "la récompense prévue doit être lisible : " + joined);
    }

    // ---- Issue #13 : quêtes de rencontre renouvelables ------------------------------------------

    @Test
    void aCompletedEncounterQuestShowsItsTagAndTheRemainingCooldown() throws Exception {
        Files.writeString(questsDir.resolve("encounter_demo.yml"), questYaml("rpgquest:encounter_demo", "Rencontre démo")
                .replace("category: test", "category: encounter\nrepeatable: true\ncooldown: 20h"));
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "encounter_demo"), QuestState.COMPLETED);

        showAndAwait(player, JournalTab.COMPLETED);
        var icon = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.CONTENT_SLOTS[0]);
        assertNotNull(icon);
        String joined = String.join("\n", loreText(icon));
        assertTrue(joined.contains("Rencontre — quête secondaire"), joined);
        assertTrue(joined.contains("À refaire dans 20 h") || joined.contains("À refaire dans 19 h"), joined);
        assertFalse(joined.contains("Peut être refaite"), joined);
    }

    @Test
    void aCompletedRepeatableQuestWithoutDelayIsAnnouncedAsAvailableAgain() throws Exception {
        Files.writeString(questsDir.resolve("again.yml"), questYaml("rpgquest:again", "Encore")
                .replace("category: test", "category: test\nrepeatable: true"));
        writeQuests(1);
        questEngine.reload();
        PlayerMock player = addPlayer();
        setState(player, new NamespacedKey("rpgquest", "again"), QuestState.COMPLETED);
        setState(player, new NamespacedKey("rpgquest", "quest_0"), QuestState.COMPLETED);

        showAndAwait(player, JournalTab.COMPLETED);
        var inventory = player.getOpenInventory().getTopInventory();
        // Tri par titre : « Encore » avant « Quête 0 ».
        String repeatable = String.join("\n", loreText(inventory.getItem(QuestJournalService.CONTENT_SLOTS[0])));
        String oneShot = String.join("\n", loreText(inventory.getItem(QuestJournalService.CONTENT_SLOTS[1])));
        assertTrue(repeatable.contains("Peut être refaite"), repeatable);
        assertFalse(repeatable.contains("Rencontre"), repeatable);
        assertFalse(oneShot.contains("refaire") || oneShot.contains("refaite"), oneShot);
    }

    // ---- Issue #15 : onglets quotidien / hebdomadaire --------------------------------------------

    @Test
    void periodicQuestsLiveInTheirOwnTabOnceTheJournalIsOpened() throws Exception {
        Files.writeString(questsDir.resolve("daily_a.yml"), questYaml("rpgquest:daily_a", "Corvée du jour")
                .replace("category: test", "category: daily\nperiod: daily"));
        questEngine.reload();
        var periodic = new com.lodygames.rpgquest.quest.periodic.PeriodicQuestService(plugin, questEngine,
                progressEngine, new com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository(database),
                variableRepository, com.lodygames.rpgquest.config.PeriodicQuestConfig::defaults,
                java.time.Instant::now, Runnable::run, plugin.getSLF4JLogger());
        PlayerMock player = addPlayer();

        showAndAwait(player, JournalTab.IN_PROGRESS);
        assertNull(player.getOpenInventory().getTopInventory().getItem(QuestJournalService.TAB_DAILY_SLOT),
                "sans service branché, pas d'onglet périodique");

        service.setPeriodicQuests(periodic);
        NamespacedKey daily = new NamespacedKey("rpgquest", "daily_a");
        service.open(player);
        waitUntil(() -> progressRepository.find(player.getUniqueId(), daily).join()
                .map(r -> r.state() == QuestState.ACTIVE).orElse(false));

        showAndAwait(player, JournalTab.IN_PROGRESS);
        var inventory = player.getOpenInventory().getTopInventory();
        assertNotNull(inventory.getItem(QuestJournalService.TAB_DAILY_SLOT));
        assertNotNull(inventory.getItem(QuestJournalService.TAB_WEEKLY_SLOT));
        assertNull(inventory.getItem(QuestJournalService.CONTENT_SLOTS[0]),
                "une quotidienne active n'encombre pas « Quêtes en cours »");

        clickTopSlot(player, QuestJournalService.TAB_DAILY_SLOT, ClickType.LEFT);
        waitUntil(() -> listTabOf(player) == JournalTab.DAILY);
        String lore = String.join("\n", loreText(player.getOpenInventory().getTopInventory()
                .getItem(QuestJournalService.CONTENT_SLOTS[1])));
        assertTrue(lore.contains("À faire aujourd'hui"), lore);
        assertTrue(lore.contains("Nouvelles quêtes dans"), lore);

        clickTopSlot(player, QuestJournalService.TAB_WEEKLY_SLOT, ClickType.LEFT);
        waitUntil(() -> listTabOf(player) == JournalTab.WEEKLY);
        var empty = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.CONTENT_SLOTS[0]);
        assertNotNull(empty, "aucune hebdomadaire publiée : l'onglet le dit au lieu de rester vide");
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(empty.getItemMeta().displayName())
                .contains("Aucune quête"));
    }

    @Test
    void periodicTabShowsItsProgressWithoutHoverAndKeepsCompletedQuestsApart() throws Exception {
        for (String id : List.of("daily_a", "daily_b")) {
            Files.writeString(questsDir.resolve(id + ".yml"), questYaml("rpgquest:" + id, "Corvée " + id)
                    .replace("category: test", "category: daily\nperiod: daily"));
        }
        questEngine.reload();
        service.setPeriodicQuests(new com.lodygames.rpgquest.quest.periodic.PeriodicQuestService(plugin, questEngine,
                progressEngine, new com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository(database),
                variableRepository, com.lodygames.rpgquest.config.PeriodicQuestConfig::defaults,
                java.time.Instant::now, Runnable::run, plugin.getSLF4JLogger()));
        PlayerMock player = addPlayer();
        NamespacedKey done = new NamespacedKey("rpgquest", "daily_a");
        NamespacedKey toDo = new NamespacedKey("rpgquest", "daily_b");
        service.open(player);
        waitUntil(() -> java.util.stream.Stream.of(done, toDo).allMatch(id -> progressRepository
                .find(player.getUniqueId(), id).join().map(r -> r.state() == QuestState.ACTIVE).orElse(false)));
        setState(player, done, QuestState.COMPLETED);
        showAndAwait(player, JournalTab.IN_PROGRESS);

        clickTopSlot(player, QuestJournalService.TAB_DAILY_SLOT, ClickType.LEFT);
        waitUntil(() -> listTabOf(player) == JournalTab.DAILY);
        var view = player.getOpenInventory();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        assertEquals("Quotidiennes : 1/2 terminée", plain.serialize(view.title()), "résumé dans le titre, sans survol");
        var inventory = view.getTopInventory();
        assertTrue(plain.serialize(inventory.getItem(5).getItemMeta().displayName())
                .contains("1/2 terminée — 1 restante"), "résumé complet à la place de l'indicateur de page");

        assertEquals(PeriodicJournalEntries.TO_DO_HEADER, inventory.getItem(QuestJournalService.CONTENT_SLOTS[0]).getType());
        var pending = inventory.getItem(QuestJournalService.CONTENT_SLOTS[1]);
        assertTrue(plain.serialize(pending.getItemMeta().displayName()).contains("Corvée daily_b"));
        assertNotEquals(Boolean.TRUE, pending.getItemMeta().getEnchantmentGlintOverride());
        assertNull(inventory.getItem(QuestJournalService.CONTENT_SLOTS[2]));

        assertEquals(PeriodicJournalEntries.COMPLETED_HEADER, inventory.getItem(QuestJournalService.CONTENT_SLOTS[9]).getType(),
                "les terminées ont leur propre rangée");
        var completed = inventory.getItem(QuestJournalService.CONTENT_SLOTS[10]);
        assertTrue(plain.serialize(completed.getItemMeta().displayName()).contains("Corvée daily_a"));
        assertEquals(Boolean.TRUE, completed.getItemMeta().getEnchantmentGlintOverride(), "une terminée brille");
        assertTrue(String.join("\n", loreText(inventory.getItem(QuestJournalService.TAB_DAILY_SLOT)))
                .contains("1/2 terminée — 1 restante"), "l'onglet du haut porte aussi le résumé");

        clickTopSlot(player, QuestJournalService.CONTENT_SLOTS[0], ClickType.LEFT);
        assertEquals(JournalTab.DAILY, listTabOf(player), "un en-tête de section n'ouvre rien");

        showAndAwait(player, JournalTab.COMPLETED);
        assertNull(player.getOpenInventory().getTopInventory().getItem(QuestJournalService.CONTENT_SLOTS[0]),
                "une quotidienne terminée ne se mélange pas aux quêtes classiques terminées");
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
