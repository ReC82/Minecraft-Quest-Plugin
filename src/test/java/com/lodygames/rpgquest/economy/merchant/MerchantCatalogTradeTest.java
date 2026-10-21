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
import com.lodygames.rpgquest.economy.price.PriceCatalogService;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
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

/**
 * Issue #17 : catalogues typés (vente / rachat), boutique fermée, étal modifié vitrine ouverte et
 * solde affiché.
 */
class MerchantCatalogTradeTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey SHOP = new NamespacedKey("rpgquest", "boutique");
    private static final NamespacedKey COUNTER = new NamespacedKey("rpgquest", "comptoir");
    private static final NamespacedKey CLOSED = new NamespacedKey("rpgquest", "ferme");
    private static final String SHOP_YAML = """
            id: rpgquest:boutique
            title: "Boutique"
            type: SELL
            offers:
              - direction: SELL_TO_PLAYER
                material: BREAD
                quantity: 2
                price: %d
            """;
    private static final String PRICES = """
            prices:
              pain:
                label: Pain
                type: SELL_TO_PLAYER
                item: BREAD
                amount: 2
                coins: 5
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
    private Path merchantsDir;
    private YamlMerchantRegistry merchantRegistry;

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

        merchantsDir = Files.createDirectories(tempDir.resolve("merchants"));
        Files.writeString(merchantsDir.resolve("boutique.yml"), SHOP_YAML.formatted(3));
        Files.writeString(merchantsDir.resolve("comptoir.yml"), """
                id: rpgquest:comptoir
                title: "Comptoir"
                type: BUY
                offers:
                  - price-ref: pain
                  - direction: BUY_FROM_PLAYER
                    material: COBBLESTONE
                    quantity: 8
                    price: 2
                """);
        Files.writeString(merchantsDir.resolve("ferme.yml"), """
                id: rpgquest:ferme
                title: "Fermé"
                enabled: false
                offers:
                  - direction: SELL_TO_PLAYER
                    material: BREAD
                    quantity: 1
                    price: 1
                """);
        merchantRegistry = new YamlMerchantRegistry(merchantsDir, plugin.getSLF4JLogger());
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
    void aClosedShopNeverOpens() throws Exception {
        PlayerMock player = playerWith(10);

        tradeService.openShop(player, CLOSED);
        tradeService.handleClick(player, 0);
        awaitAsyncWork();

        Inventory top = player.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof MerchantShopInventoryHolder);
        assertEquals(10L, balance(player));
        assertEquals(0, countMaterial(player, Material.BREAD));
    }

    @Test
    void aBuyCatalogNeverSellsEvenThroughASalePrice() throws Exception {
        PlayerMock player = playerWith(10);
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 8));

        tradeService.openShop(player, COUNTER);
        assertEquals(Material.BARRIER, player.getOpenInventory().getTopInventory().getItem(0).getType(),
                "un prix de vente cité par un catalogue de rachat est affiché indisponible");
        tradeService.handleClick(player, 0);
        awaitAsyncWork();
        assertEquals(10L, balance(player));
        assertEquals(0, countMaterial(player, Material.BREAD));

        tradeService.handleClick(player, 1);
        awaitAsyncWork();
        assertEquals(12L, balance(player));
        assertEquals(0, countMaterial(player, Material.COBBLESTONE));
    }

    @Test
    void anEditedStallRefusesTheClickThenTradesAtTheNewPrice() throws Exception {
        PlayerMock player = playerWith(20);
        tradeService.openShop(player, SHOP);

        Files.writeString(merchantsDir.resolve("boutique.yml"), SHOP_YAML.formatted(7));
        merchantRegistry.reload();
        tradeService.handleClick(player, 0);
        awaitAsyncWork();

        assertEquals(20L, balance(player), "l'offre affichée n'est plus celle du fichier : rien n'est échangé");
        assertEquals(0, countMaterial(player, Material.BREAD));

        tradeService.handleClick(player, 0);
        awaitAsyncWork();
        assertEquals(13L, balance(player));
        assertEquals(2, countMaterial(player, Material.BREAD));
    }

    @Test
    void aShopClosedWhileOpenTradesNothing() throws Exception {
        PlayerMock player = playerWith(20);
        tradeService.openShop(player, SHOP);

        new MerchantCatalogEditor(merchantRegistry, org.slf4j.LoggerFactory.getLogger("test")).setEnabled(SHOP, false);
        tradeService.handleClick(player, 0);
        awaitAsyncWork();

        assertEquals(20L, balance(player));
        assertEquals(0, countMaterial(player, Material.BREAD));
    }

    @Test
    void theShopShowsTheBalanceAndRefreshesItAfterATrade() throws Exception {
        PlayerMock player = playerWith(20);
        tradeService.openShop(player, SHOP);
        awaitAsyncWork();
        Inventory top = player.getOpenInventory().getTopInventory();
        assertTrue(balanceText(top).contains("20"), balanceText(top));

        tradeService.handleClick(player, 0);
        awaitAsyncWork();
        assertTrue(balanceText(top).contains("17"), balanceText(top));
    }

    @Test
    void aSingleDirectionCatalogHasOneViewAnnouncedByAnIndicator() throws Exception {
        PlayerMock player = playerWith(20);
        tradeService.openShop(player, SHOP);
        Inventory top = player.getOpenInventory().getTopInventory();
        int indicator = top.getSize() - 9;
        assertEquals(Material.EMERALD, top.getItem(indicator).getType(), "vente seule : vue « Acheter »");
        assertNull(top.getItem(indicator + 1), "pas d'onglet « Vendre » sur un catalogue de vente");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(player.getOpenInventory().title()).endsWith("Acheter"));

        tradeService.handleClick(player, indicator);
        awaitAsyncWork();
        assertEquals(20L, balance(player), "l'indicateur n'échange rien");

        tradeService.openShop(player, COUNTER);
        top = player.getOpenInventory().getTopInventory();
        assertEquals(Material.HOPPER, top.getItem(top.getSize() - 9).getType(), "rachat seul : vue « Vendre »");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(player.getOpenInventory().title()).endsWith("Vendre"));
    }

    private static String balanceText(Inventory top) {
        ItemStack icon = top.getItem(top.getSize() - 5);
        return icon == null || icon.getItemMeta().lore() == null ? ""
                : PlainTextComponentSerializer.plainText().serialize(icon.getItemMeta().lore().get(0));
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

    private void awaitAsyncWork() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }
}
