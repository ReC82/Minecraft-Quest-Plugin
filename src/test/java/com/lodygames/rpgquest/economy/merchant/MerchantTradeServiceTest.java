package com.lodygames.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
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

class MerchantTradeServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey MERCHANT_ID = new NamespacedKey("rpgquest", "test_merchant");
    private static final NamespacedKey SPIDER_FANG_ID = new NamespacedKey("rpgquest", "spider_fang");

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
        NpcIdentityService npcIdentityService = new NpcIdentityService(plugin, new NpcIdRepository(database));
        questProgressEngine = new QuestProgressEngine(
                plugin, questEngine, progressRepository, variableRepository, messagesService, npcIdentityService);
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
        tradeService.handleClick(player, 1); // offre BUY_FROM_PLAYER spider_fang x3 / 7
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
        tradeService.handleClick(player, 1);
        awaitAsyncWork();

        assertEquals(0L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, countCustomItem(player, SPIDER_FANG_ID));
    }

    @Test
    void offerRequiringMissingPermissionIsDenied() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 2); // offre DIAMOND, requiert rpgquest.vip
        awaitAsyncWork();

        assertEquals(10L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, countMaterial(player, Material.DIAMOND));
    }

    @Test
    void offerRequiringUnmetQuestStateIsDenied() throws Exception {
        PlayerMock player = addPlayer();
        walletRepository.credit(player.getUniqueId(), 10, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, 3); // offre EMERALD, requiert first_steps COMPLETED
        awaitAsyncWork();

        assertEquals(10L, walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, countMaterial(player, Material.EMERALD));
    }

    @Test
    void openingUnknownMerchantSendsAnErrorMessageWithoutCrashing() throws Exception {
        PlayerMock player = addPlayer();

        tradeService.openShop(player, new NamespacedKey("rpgquest", "does_not_exist"));

        assertTrue(player.nextMessage() != null, "un message d'erreur doit être envoyé au joueur");
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
