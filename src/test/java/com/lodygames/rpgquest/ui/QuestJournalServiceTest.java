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

        var detail = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.DETAIL_ICON_SLOT);
        String joined = String.join("\n", loreText(detail));

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

        var detail = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.DETAIL_ICON_SLOT);
        String joined = String.join("\n", loreText(detail));
        assertTrue(joined.contains("250 pièce(s)"), () -> "la récompense prévue doit être lisible : " + joined);
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
