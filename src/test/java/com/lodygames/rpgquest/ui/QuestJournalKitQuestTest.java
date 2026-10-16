package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.JournalConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcIdRepository;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
 * Issue #475 — parcours du journal alimenté par les <strong>vraies</strong> quêtes de palier du kit
 * ({@code kit_tier2.yml}, {@code kit_tier3.yml} embarquées) et le compteur {@code DELIVER_ITEM_TO_NPC}
 * (#123) : acceptée → visible dans « en cours » à 0/N → remise partielle → compteurs à jour, y compris
 * après une reconnexion → dernière remise → « terminées » → palier suivant visible à son tour.
 */
class QuestJournalKitQuestTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey KIT_TIER_2 = new NamespacedKey("rpgquest", "kit_tier2");
    private static final NamespacedKey KIT_TIER_3 = new NamespacedKey("rpgquest", "kit_tier3");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private DatabaseManager database;
    private Path questsDir;
    private YamlQuestEngine questEngine;
    private QuestProgressEngine progressEngine;
    private QuestProgressRepository progressRepository;
    private PlayerProfileRepository profileRepository;
    private QuestJournalService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        PlayerVariableRepository variableRepository = new PlayerVariableRepository(database);

        questsDir = tempDir.resolve("quests");
        Files.createDirectories(questsDir);
        copyBundledQuest("kit_tier2.yml");
        copyBundledQuest("kit_tier3.yml");
        questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();

        progressRepository = new QuestProgressRepository(database);
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        EconomyService economyService = new EconomyService(new WalletRepository(database));
        progressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService,
                economyService);
        progressEngine.setNpcNameResolver(id -> "guide".equals(id) ? Optional.of("Guide") : Optional.empty());
        progressEngine.start();

        YamlCustomItemRegistry customItemRegistry =
                new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        service = new QuestJournalService(
                plugin, questEngine, progressEngine, variableRepository, customItemRegistry, economyService,
                new JournalConfig(true));
        service.setContextLabels(id -> id.equals(KIT_TIER_2) ? Optional.of("Amélioration du kit — palier 2")
                : id.equals(KIT_TIER_3) ? Optional.of("Amélioration du kit — palier 3") : Optional.empty());
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
        progressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void theBundledKitQuestsLoadUnderTheirRealIds() {
        assertTrue(questEngine.find(KIT_TIER_2).isPresent(), "kit_tier2 embarquée doit se charger");
        assertTrue(questEngine.find(KIT_TIER_3).isPresent(), "kit_tier3 embarquée doit se charger");
    }

    @Test
    void notAcceptedKitQuestStaysInvisible() throws Exception {
        PlayerMock player = addPlayer();

        showAndAwait(player, JournalTab.IN_PROGRESS);
        assertTrue(service.sessionOf(player).pageQuests().isEmpty(), "aucun catalogue de quêtes non acceptées");
    }

    @Test
    void fullKitParcoursFromAcceptanceToCompletedTabAndNextTier() throws Exception {
        PlayerMock player = addPlayer();
        progressEngine.accept(player, KIT_TIER_2).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 1. Acceptée → visible dans « en cours », à 0/N, signalée comme amélioration du kit.
        String lore = inProgressLoreOf(player, KIT_TIER_2);
        assertTrue(lore.contains("Amélioration du kit — palier 2"), lore);
        assertTrue(lore.contains("à remettre à Guide"), "le PNJ destinataire doit être nommé : " + lore);
        assertTrue(lore.contains("0/1") && lore.contains("0/2") && lore.contains("0/4") && lore.contains("0/3"), lore);
        assertFalse(lore.contains("STICK") || lore.contains("WHEAT_SEEDS"), "aucun id brut d'objet : " + lore);
        assertFalse(lore.contains("rpgquest:"), lore);

        // 2. Remise partielle → compteurs DÉPOSÉS (pas la possession) mis à jour dans le menu ouvert.
        player.getInventory().addItem(new ItemStack(Material.STICK, 1), new ItemStack(Material.COBBLESTONE, 1),
                new ItemStack(Material.LEATHER, 2));
        progressEngine.deliverTo(player, "guide");
        waitUntil(() -> openLore(player).contains("1/2"));
        lore = openLore(player);
        assertTrue(lore.contains("1/1") && lore.contains("1/2") && lore.contains("2/4") && lore.contains("0/3"), lore);

        // Posséder sans remettre ne compte pas.
        player.getInventory().addItem(new ItemStack(Material.WHEAT_SEEDS, 3));
        assertTrue(inProgressLoreOf(player, KIT_TIER_2).contains("0/3"), "la possession ne compte jamais");

        // 3. Reconnexion → les dépôts persistés sont relus, rien n'est perdu.
        awaitStoredCounter(player, 2, 2);
        progressEngine.unloadForPlayer(player.getUniqueId());
        progressEngine.loadForPlayer(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        lore = inProgressLoreOf(player, KIT_TIER_2);
        assertTrue(lore.contains("1/1") && lore.contains("1/2") && lore.contains("2/4"), lore);

        // 4. Dernière remise → la quête quitte « en cours » et passe dans « terminées ».
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 1), new ItemStack(Material.LEATHER, 2));
        progressEngine.deliverTo(player, "guide");
        waitUntil(() -> stateOf(player, KIT_TIER_2) == QuestState.COMPLETED);
        showAndAwait(player, JournalTab.IN_PROGRESS);
        assertFalse(service.sessionOf(player).pageQuests().contains(KIT_TIER_2));
        showAndAwait(player, JournalTab.COMPLETED);
        assertTrue(service.sessionOf(player).pageQuests().contains(KIT_TIER_2), "palier 2 attendu dans « terminées »");

        // 5. Palier suivant accepté → visible à son tour, avec ses propres compteurs.
        progressEngine.accept(player, KIT_TIER_3).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        lore = inProgressLoreOf(player, KIT_TIER_3);
        assertTrue(lore.contains("Amélioration du kit — palier 3"), lore);
        assertTrue(lore.contains("0/4"), lore);
    }

    @Test
    void severalActiveQuestsAllShowIncludingTheKitQuest() throws Exception {
        Files.writeString(questsDir.resolve("other.yml"), """
                id: rpgquest:other
                title: "Autre quête"
                description: "Description"
                category: test
                steps:
                  - id: step_one
                    objectives:
                      - type: KILL_ENTITY
                        entity: ZOMBIE
                        amount: 1
                """);
        progressEngine.reloadQuestDefinitions();
        PlayerMock player = addPlayer();
        progressEngine.accept(player, KIT_TIER_2).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        progressEngine.accept(player, new NamespacedKey("rpgquest", "other")).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        showAndAwait(player, JournalTab.IN_PROGRESS);
        List<NamespacedKey> shown = service.sessionOf(player).pageQuests();
        assertEquals(2, shown.size(), "les deux quêtes actives : " + shown);
        assertTrue(shown.contains(KIT_TIER_2));
    }

    @Test
    void anActiveQuestWhoseDefinitionIsMissingStaysVisibleInsteadOfVanishing() throws Exception {
        PlayerMock player = addPlayer();
        progressEngine.accept(player, KIT_TIER_2).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Publication ratée / fichier retiré : la définition disparaît, la progression reste.
        Files.delete(questsDir.resolve("kit_tier2.yml"));
        progressEngine.reloadQuestDefinitions();

        showAndAwait(player, JournalTab.IN_PROGRESS);
        assertTrue(service.sessionOf(player).pageQuests().contains(KIT_TIER_2),
                "une quête en cours ne doit jamais disparaître silencieusement du journal");
        ItemStack icon = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.CONTENT_SLOTS[0]);
        assertNotNull(icon);
        assertTrue(plain(icon).contains("progression est conservée"), plain(icon));
        assertFalse(plain(icon).contains("rpgquest:"), plain(icon));
        assertEquals(QuestState.ACTIVE, stateOf(player, KIT_TIER_2), "aucune écriture : l'état reste ACTIVE");
    }

    // ---- Outils --------------------------------------------------------------------------------

    private void copyBundledQuest(String fileName) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/quests/" + fileName)) {
            assertNotNull(in, "ressource embarquée introuvable : " + fileName);
            Files.copy(in, questsDir.resolve(fileName));
        }
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private QuestState stateOf(PlayerMock player, NamespacedKey questId) {
        try {
            Map<NamespacedKey, QuestState> states =
                    progressEngine.allStates(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return states.getOrDefault(questId, QuestState.NOT_STARTED);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String inProgressLoreOf(PlayerMock player, NamespacedKey questId) throws InterruptedException {
        showAndAwait(player, JournalTab.IN_PROGRESS);
        List<NamespacedKey> shown = service.sessionOf(player).pageQuests();
        int index = shown.indexOf(questId);
        assertTrue(index >= 0, questId + " absente de « en cours » : " + shown);
        return plain(player.getOpenInventory().getTopInventory().getItem(QuestJournalService.CONTENT_SLOTS[index]));
    }

    private String openLore(PlayerMock player) {
        ItemStack icon = player.getOpenInventory().getTopInventory().getItem(QuestJournalService.CONTENT_SLOTS[0]);
        return icon == null ? "" : plain(icon);
    }

    private static String plain(ItemStack stack) {
        var meta = stack.getItemMeta();
        StringBuilder sb = new StringBuilder();
        if (meta.displayName() != null) {
            sb.append(PlainTextComponentSerializer.plainText().serialize(meta.displayName())).append('\n');
        }
        if (meta.lore() != null) {
            meta.lore().forEach(line -> sb.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n'));
        }
        return sb.toString();
    }

    private void awaitStoredCounter(PlayerMock player, int objectiveIndex, int expected) throws InterruptedException {
        waitUntil(() -> {
            try {
                Integer value = progressRepository.findObjectiveProgress(player.getUniqueId(), KIT_TIER_2,
                        "rapporter_les_materiaux").get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(objectiveIndex);
                return value != null && value == expected;
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** Session effacée d'abord : on attend le menu RECONSTRUIT, jamais l'ancien déjà ouvert. */
    private void showAndAwait(PlayerMock player, JournalTab tab) throws InterruptedException {
        service.handleClose(player);
        service.showList(player, tab, 0);
        waitUntil(() -> {
            JournalSession session = service.sessionOf(player);
            return session != null && !session.isDetail() && session.tab() == tab;
        });
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
