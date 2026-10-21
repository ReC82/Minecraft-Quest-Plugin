package com.lodygames.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
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
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class MerchantTradeServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey MERCHANT_ID = new NamespacedKey("rpgquest", "test_merchant");
    private static final NamespacedKey SPIDER_FANG_ID = new NamespacedKey("rpgquest", "spider_fang");
    /** Vitrine de 5 offres = 3 lignes : onglets Acheter / Vendre en début de dernière ligne. */
    private static final int BUY_TAB_SLOT = 18;
    private static final int SELL_TAB_SLOT = 19;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private WalletRepository walletRepository;
    private EconomyService economyService;
    private YamlCustomItemRegistry customItemRegistry;
    private QuestProgressEngine questProgressEngine;
    private MerchantTradeService tradeService;
    private PlayerProfileRepository profileRepository;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        walletRepository = new WalletRepository(database);
        economyService = new EconomyService(walletRepository);

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

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
        NpcIdentityService npcIdentityService = new NpcIdentityService(
                plugin, new NpcIdRepository(database), new NpcBindingRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService,
                economyService);
        questProgressEngine.start();

        Path merchantsDir = tempDir.resolve("merchants");
        Files.createDirectories(merchantsDir);
        Files.writeString(merchantsDir.resolve("test_merchant.yml"), """
                id: rpgquest:test_merchant
                title: "<gold>Marchand de test</gold>"

                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 2
                    price: 5
                  - direction: BUY_FROM_PLAYER
                    custom-item: rpgquest:spider_fang
                    quantity: 3
                    price: 7
                  - direction: SELL_TO_PLAYER
                    material: DIAMOND
                    quantity: 1
                    price: 1
                    required-permission: rpgquest.vip
                  - direction: SELL_TO_PLAYER
                    material: EMERALD
                    quantity: 1
                    price: 1
                    required-quest: rpgquest:first_steps
                    required-quest-state: COMPLETED
                  - direction: SELL_TO_PLAYER
                    material: GOLD_INGOT
                    quantity: 1
                    price: 1
                    required-level: 2
                """);
        YamlMerchantRegistry merchantRegistry = new YamlMerchantRegistry(merchantsDir, plugin.getSLF4JLogger());
        merchantRegistry.start();

        tradeService = new MerchantTradeService(plugin, merchantRegistry, economyService, customItemRegistry, questProgressEngine);
        tradeService.start();
    }

    @AfterEach
    void tearDown() {
        tradeService.stop();
        questProgressEngine.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void buyingWithSufficientFundsDebitsAndGivesTheItem() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 0); // offre SELL_TO_PLAYER BREAD x2 / 5
        awaitAsyncWork();

        assertEquals(5L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(2, countMaterial(player, Material.BREAD));
    }

    @Test
    void buyingWithInsufficientFundsChangesNothing() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 2, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 0);
        awaitAsyncWork();

        assertEquals(2L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, countMaterial(player, Material.BREAD));
    }

    @Test
    void sellingEnoughItemsRemovesThemAndCreditsTheWallet() throws Exception {
        PlayerMock player = addPlayer();
        ItemStack spiderFangs = customItemRegistry.create(SPIDER_FANG_ID, 5).orElseThrow();
        player.getInventory().addItem(spiderFangs);

        tradeService.openShop(player, MERCHANT_ID);
        showTab(player, SELL_TAB_SLOT);
        tradeService.handleClick(player, 0); // offre BUY_FROM_PLAYER spider_fang x3 / 7, seule de l'onglet Vendre
        awaitAsyncWork();

        assertEquals(7L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(2, countCustomItem(player, SPIDER_FANG_ID));
    }

    @Test
    void sellingWithoutEnoughItemsChangesNothing() throws Exception {
        PlayerMock player = addPlayer();
        ItemStack spiderFangs = customItemRegistry.create(SPIDER_FANG_ID, 1).orElseThrow();
        player.getInventory().addItem(spiderFangs);

        tradeService.openShop(player, MERCHANT_ID);
        showTab(player, SELL_TAB_SLOT);
        tradeService.handleClick(player, 0);
        awaitAsyncWork();

        assertEquals(0L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, countCustomItem(player, SPIDER_FANG_ID));
    }

    @Test
    void offerRequiringMissingPermissionIsDenied() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 1); // onglet Acheter : offre DIAMOND, requiert rpgquest.vip
        awaitAsyncWork();

        assertEquals(10L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, countMaterial(player, Material.DIAMOND));
    }

    @Test
    void offerRequiringUnmetQuestStateIsDenied() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 2); // onglet Acheter : offre EMERALD, requiert first_steps COMPLETED
        awaitAsyncWork();

        assertEquals(10L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, countMaterial(player, Material.EMERALD));
    }

    @Test
    void offerRequiredLevelReadsTheSharedLevelRuleNotTheFrozenVanillaLevel() throws Exception {
        PlayerMock player = addPlayer();
        player.setLevel(0); // depuis #183 le niveau vanilla ne progresse plus
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        AtomicInteger rpgLevel = new AtomicInteger(1);
        tradeService.setRequiredLevelRule(p -> rpgLevel.get());

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 3); // onglet Acheter : offre GOLD_INGOT, required-level: 2
        awaitAsyncWork();
        assertEquals(0, countMaterial(player, Material.GOLD_INGOT), "niveau RPG 1 < 2 : refusé");

        rpgLevel.set(2);
        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 3);
        awaitAsyncWork();
        assertEquals(1, countMaterial(player, Material.GOLD_INGOT), "niveau RPG 2 : accepté malgré le niveau vanilla 0");
        assertEquals(9L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void openingUnknownMerchantSendsAnErrorMessageWithoutCrashing() throws Exception {
        PlayerMock player = addPlayer();

        tradeService.openShop(player, new NamespacedKey("rpgquest", "does_not_exist"));

        assertTrue(player.nextMessage() != null, "un message d'erreur doit être envoyé au joueur");
    }

    @Test
    void aMixedShopOpensOnBuyAndShowsOnlyWhatThePlayerCanBuy() throws Exception {
        PlayerMock player = addPlayer();

        tradeService.openShop(player, MERCHANT_ID);

        Inventory top = player.getOpenInventory().getTopInventory();
        assertEquals(Material.BREAD, top.getItem(0).getType());
        assertEquals(Material.DIAMOND, top.getItem(1).getType());
        assertEquals(Material.EMERALD, top.getItem(2).getType());
        assertEquals(Material.GOLD_INGOT, top.getItem(3).getType());
        assertNull(top.getItem(4), "l'offre de rachat n'est pas dans l'onglet Acheter");
        assertTrue(plain(player.getOpenInventory().title()).endsWith("Acheter"));
        assertTrue(plain(top.getItem(0).getItemMeta().lore().get(0)).startsWith("Tu achètes"));
        assertEquals(Material.EMERALD, top.getItem(BUY_TAB_SLOT).getType());
        assertTrue(top.getItem(BUY_TAB_SLOT).getItemMeta().getEnchantmentGlintOverride(), "onglet affiché en surbrillance");
        assertEquals(Material.HOPPER, top.getItem(SELL_TAB_SLOT).getType());
        assertFalse(top.getItem(SELL_TAB_SLOT).getItemMeta().getEnchantmentGlintOverride());
    }

    @Test
    void theSellTabShowsOnlyBuyBacksAndSwitchingTabsNeverTrades() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 5).orElseThrow());

        tradeService.openShop(player, MERCHANT_ID);
        showTab(player, SELL_TAB_SLOT);

        Inventory top = player.getOpenInventory().getTopInventory();
        assertEquals(SPIDER_FANG_ID, customItemRegistry.identify(top.getItem(0)).orElseThrow());
        assertNull(top.getItem(1));
        assertTrue(plain(player.getOpenInventory().title()).endsWith("Vendre"));
        assertTrue(plain(top.getItem(0).getItemMeta().lore().get(0)).startsWith("Tu vends"));

        showTab(player, SELL_TAB_SLOT); // déjà affiché : rien
        showTab(player, BUY_TAB_SLOT);
        assertEquals(Material.BREAD, player.getOpenInventory().getTopInventory().getItem(0).getType());
        assertEquals(10L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(5, countCustomItem(player, SPIDER_FANG_ID));
        assertEquals(0, countMaterial(player, Material.BREAD));
    }

    @Test
    void aMixedShopWithoutBuyBacksSaysSoInItsSellTab() throws Exception {
        Path merchantsDir = tempDir.resolve("merchants");
        Files.writeString(merchantsDir.resolve("bakery.yml"), """
                id: rpgquest:bakery
                title: "<gold>Boulangerie</gold>"

                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """);
        YamlMerchantRegistry registry = new YamlMerchantRegistry(merchantsDir, plugin.getSLF4JLogger());
        registry.start();
        MerchantTradeService service = new MerchantTradeService(plugin, registry, economyService, customItemRegistry,
                questProgressEngine);
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service.openShop(player, new NamespacedKey("rpgquest", "bakery"));
        service.handleClick(player, SELL_TAB_SLOT);
        awaitAsyncWork();

        Inventory top = player.getOpenInventory().getTopInventory();
        assertEquals(Material.PAPER, top.getItem(MerchantTradeService.EMPTY_TAB_SLOT).getType());
        assertTrue(plain(top.getItem(MerchantTradeService.EMPTY_TAB_SLOT).getItemMeta().displayName())
                .contains("ne rachète rien"));
        service.handleClick(player, MerchantTradeService.EMPTY_TAB_SLOT);
        service.handleClick(player, 0);
        awaitAsyncWork();
        assertEquals(10L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, countMaterial(player, Material.BREAD));
        service.stop();
    }

    private void showTab(PlayerMock player, int tabSlot) throws InterruptedException {
        tradeService.handleClick(player, tabSlot);
        awaitAsyncWork();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private int countMaterial(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private int countCustomItem(PlayerMock player, NamespacedKey id) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && customItemRegistry.identify(stack).map(id::equals).orElse(false)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Les transactions passent par des CompletableFuture réels (thread DB) : on laisse le temps à l'effet de se produire. */
    private void awaitAsyncWork() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }
}
