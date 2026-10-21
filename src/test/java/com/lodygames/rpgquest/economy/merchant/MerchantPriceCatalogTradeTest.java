package com.lodygames.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.lodygames.rpgquest.economy.price.PriceCatalogService;
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

/** Issue #336 : offres {@code price-ref} d'un marchand, résolues dans le catalogue {@code prices.yml}. */
class MerchantPriceCatalogTradeTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey MERCHANT_ID = new NamespacedKey("rpgquest", "atelier");
    private static final NamespacedKey SPIDER_FANG_ID = new NamespacedKey("rpgquest", "spider_fang");
    // Catalogue mixte (issue #17) : onglet Acheter = pain, epee, inexistant ; onglet Vendre = crocs.
    private static final int SLOT_SELL = 0;
    private static final int SLOT_CRAFT = 1;
    private static final int SLOT_MISSING = 2;
    private static final int SLOT_BUY_IN_SELL_TAB = 0;
    private static final int SELL_TAB_SLOT = 19;
    private static final String PRICES = """
            prices:
              pain:
                label: Pain
                type: SELL_TO_PLAYER
                item: BREAD
                amount: 2
                coins: 5
              crocs:
                label: Crocs
                type: BUY_FROM_PLAYER
                item: rpgquest:spider_fang
                amount: 3
                coins: 7
              epee:
                label: Épée
                type: CRAFT
                item: IRON_SWORD
                amount: 1
                coins: 10
                ingredients:
                  - {item: IRON_INGOT, amount: 2}
                  - {item: rpgquest:spider_fang, amount: 1}
            """;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private DatabaseManager database;
    private WalletRepository walletRepository;
    private YamlCustomItemRegistry customItemRegistry;
    private QuestProgressEngine questProgressEngine;
    private MerchantTradeService tradeService;
    private PlayerProfileRepository profileRepository;
    private PriceCatalogService prices;
    private Path pricesFile;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        RPGQuestPlugin plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        walletRepository = new WalletRepository(database);
        EconomyService economyService = new EconomyService(walletRepository);

        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        Path questsDir = Files.createDirectories(tempDir.resolve("quests"));
        YamlQuestEngine questEngine = new YamlQuestEngine(questsDir, plugin.getSLF4JLogger());
        questEngine.reload();
        QuestMessagesService messagesService = new QuestMessagesService(plugin);
        messagesService.start();
        questProgressEngine = new QuestProgressEngine(plugin, questEngine, new QuestProgressRepository(database),
                new PlayerVariableRepository(database), messagesService,
                new NpcIdentityService(plugin, new NpcIdRepository(database), new NpcBindingRepository(database)),
                economyService);
        questProgressEngine.start();

        Path merchantsDir = Files.createDirectories(tempDir.resolve("merchants"));
        Files.writeString(merchantsDir.resolve("atelier.yml"), """
                id: rpgquest:atelier
                title: "Atelier"
                offers:
                  - price-ref: pain
                  - price-ref: crocs
                  - price-ref: epee
                  - price-ref: inexistant
                """);
        YamlMerchantRegistry merchantRegistry = new YamlMerchantRegistry(merchantsDir, plugin.getSLF4JLogger());
        merchantRegistry.start();

        pricesFile = tempDir.resolve("prices.yml");
        Files.writeString(pricesFile, PRICES);
        prices = new PriceCatalogService(pricesFile, plugin.getSLF4JLogger());
        prices.start();

        tradeService = new MerchantTradeService(plugin, merchantRegistry, economyService, customItemRegistry,
                questProgressEngine, prices);
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
    void sellsAtTheCatalogPrice() throws Exception {
        PlayerMock player = playerWith(10);

        click(player, SLOT_SELL);

        assertEquals(5L, balance(player));
        assertEquals(2, countMaterial(player, Material.BREAD));
    }

    @Test
    void buysBackAtTheCatalogPrice() throws Exception {
        PlayerMock player = playerWith(0);
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 4).orElseThrow());

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, SELL_TAB_SLOT);
        awaitAsyncWork();
        tradeService.handleClick(player, SLOT_BUY_IN_SELL_TAB);
        awaitAsyncWork();

        assertEquals(7L, balance(player));
        assertEquals(1, countCustomItem(player, SPIDER_FANG_ID));
    }

    @Test
    void craftTakesCoinsAndExactIngredientsThenGivesTheResult() throws Exception {
        PlayerMock player = playerWith(15);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 3));
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 1).orElseThrow());

        click(player, SLOT_CRAFT);

        assertEquals(5L, balance(player));
        assertEquals(1, countMaterial(player, Material.IRON_INGOT));
        assertEquals(0, countCustomItem(player, SPIDER_FANG_ID));
        assertEquals(1, countMaterial(player, Material.IRON_SWORD));
    }

    @Test
    void craftWithoutEnoughCoinsTakesNothing() throws Exception {
        PlayerMock player = playerWith(9);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 2));
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 1).orElseThrow());

        click(player, SLOT_CRAFT);

        assertEquals(9L, balance(player));
        assertEquals(2, countMaterial(player, Material.IRON_INGOT));
        assertEquals(1, countCustomItem(player, SPIDER_FANG_ID));
        assertEquals(0, countMaterial(player, Material.IRON_SWORD));
    }

    @Test
    void craftWithMissingIngredientDebitsNothing() throws Exception {
        PlayerMock player = playerWith(15);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 2));
        // Un simple fil vanilla n'est pas un croc d'araignée : seul le PDC identifie l'objet RPGQuest.
        player.getInventory().addItem(new ItemStack(Material.STRING, 1));

        click(player, SLOT_CRAFT);

        assertEquals(15L, balance(player));
        assertEquals(2, countMaterial(player, Material.IRON_INGOT));
        assertEquals(0, countMaterial(player, Material.IRON_SWORD));
    }

    @Test
    void doubleClickCraftsOnceAndRefundsTheSecondDebit() throws Exception {
        PlayerMock player = playerWith(30);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 2));
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 1).orElseThrow());

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, SLOT_CRAFT);
        tradeService.handleClick(player, SLOT_CRAFT);
        awaitAsyncWork();

        assertEquals(1, countMaterial(player, Material.IRON_SWORD));
        assertEquals(0, countMaterial(player, Material.IRON_INGOT));
        assertEquals(20L, balance(player), "le second débit est remboursé : une seule fabrication payée");
    }

    @Test
    void craftWithAFullInventoryIsRefusedBeforeAnyDebit() throws Exception {
        PlayerMock player = playerWith(15);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 2));
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 1).orElseThrow());
        // Ingrédients pris en compte : on remplit d'abord tout le reste, puis on garde les lingots dans
        // une pile de 64 pour qu'aucune case ne se libère.
        player.getInventory().clear();
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 64));
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 2).orElseThrow());
        while (player.getInventory().firstEmpty() >= 0) {
            player.getInventory().addItem(new ItemStack(Material.DIRT, 64));
        }

        click(player, SLOT_CRAFT);

        assertEquals(15L, balance(player));
        assertEquals(64, countMaterial(player, Material.IRON_INGOT));
        assertEquals(0, countMaterial(player, Material.IRON_SWORD));
    }

    @Test
    void anUnknownOrDisabledPriceNeverTrades() throws Exception {
        PlayerMock player = playerWith(10);

        click(player, SLOT_MISSING);
        assertEquals(10L, balance(player));

        Files.writeString(pricesFile, PRICES.replace("coins: 5\n", "coins: 5\n    enabled: false\n"));
        prices.reloadNow();
        click(player, SLOT_SELL);

        assertEquals(10L, balance(player));
        assertEquals(0, countMaterial(player, Material.BREAD));
    }

    @Test
    void aPriceChangeWhileTheShopIsOpenRefusesTheClick() throws Exception {
        PlayerMock player = playerWith(10);
        tradeService.openShop(player, MERCHANT_ID);

        Files.writeString(pricesFile, PRICES.replace("coins: 5\n", "coins: 8\n"));
        prices.reloadNow();
        tradeService.handleClick(player, SLOT_SELL);
        awaitAsyncWork();
        assertEquals(10L, balance(player), "le joueur n'a jamais payé un prix qu'il n'a pas vu");

        tradeService.handleClick(player, SLOT_SELL); // vitrine réaffichée : nouveau prix visible
        awaitAsyncWork();
        assertEquals(2L, balance(player));
        assertEquals(2, countMaterial(player, Material.BREAD));
    }

    @Test
    void aRefusedCatalogMakesEveryLinkedOfferUnavailable() throws Exception {
        PlayerMock player = playerWith(10);
        Files.writeString(pricesFile, "prices:\n  pain:\n    label: Pain\n");
        prices.reloadNow();

        click(player, SLOT_SELL);

        assertTrue(!prices.lastErrors().isEmpty());
        assertEquals(10L, balance(player));
    }

    private void click(PlayerMock player, int slot) throws InterruptedException {
        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, slot);
        awaitAsyncWork();
    }

    private PlayerMock playerWith(long coins) throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (coins > 0) {
            walletRepository.credit(player.getUniqueId(), coins, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        return player;
    }

    private long balance(PlayerMock player) throws Exception {
        return walletRepository.balance(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private int countMaterial(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material && customItemRegistry.identify(stack).isEmpty()) {
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

    private void awaitAsyncWork() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }
}
