package com.lodygames.rpgquest.economy.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #18 : atelier d'artisan ({@code type: CRAFT}) — recettes à ingrédients seuls, pièces seules ou
 * les deux, ce qui manque affiché au joueur, rien pris tant que tout n'est pas réuni.
 */
class ArtisanWorkshopTradeTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final NamespacedKey MERCHANT_ID = new NamespacedKey("rpgquest", "artisan");
    private static final NamespacedKey SPIDER_FANG_ID = new NamespacedKey("rpgquest", "spider_fang");
    private static final int SLOT_INGREDIENTS_ONLY = 0;
    private static final int SLOT_COINS_ONLY = 1;
    private static final int SLOT_MIXED = 2;
    private static final int SLOT_SALE = 3;
    private static final String PRICES = """
            prices:
              pain-cuit:
                label: Pain cuit
                type: CRAFT
                item: BREAD
                amount: 1
                coins: 0
                ingredients:
                  - {item: WHEAT, amount: 3}
              lanterne:
                label: Lanterne
                type: CRAFT
                item: LANTERN
                amount: 1
                coins: 12
              epee:
                label: Épée
                type: CRAFT
                item: IRON_SWORD
                amount: 1
                coins: 10
                ingredients:
                  - {item: IRON_INGOT, amount: 2}
                  - {item: rpgquest:spider_fang, amount: 1}
              vente:
                label: Pain vendu
                type: SELL_TO_PLAYER
                item: BREAD
                amount: 1
                coins: 1
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
        Files.writeString(merchantsDir.resolve("artisan.yml"), """
                id: rpgquest:artisan
                title: "Artisan"
                type: CRAFT
                offers:
                  - price-ref: pain-cuit
                  - price-ref: lanterne
                  - price-ref: epee
                  - price-ref: vente
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
    void ingredientsOnlyRecipeTakesNoCoin() throws Exception {
        PlayerMock player = playerWith(4);
        player.getInventory().addItem(new ItemStack(Material.WHEAT, 5));

        click(player, SLOT_INGREDIENTS_ONLY);

        assertEquals(4L, balance(player));
        assertEquals(2, countMaterial(player, Material.WHEAT));
        assertEquals(1, countMaterial(player, Material.BREAD));
    }

    @Test
    void coinsOnlyRecipeTakesCoinsOnceEvenOnDoubleClick() throws Exception {
        PlayerMock player = playerWith(20);

        tradeService.openShop(player, MERCHANT_ID);
        tradeService.handleClick(player, SLOT_COINS_ONLY);
        tradeService.handleClick(player, SLOT_COINS_ONLY);
        awaitAsyncWork();

        // 20 → 8 : le second clic ne trouve plus que 8 pièces, il est refusé sans rien prendre.
        assertEquals(8L, balance(player));
        assertEquals(1, countMaterial(player, Material.LANTERN));
    }

    @Test
    void mixedRecipeTakesBothIngredientsAndCoins() throws Exception {
        PlayerMock player = playerWith(10);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 2));
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 1).orElseThrow());

        click(player, SLOT_MIXED);

        assertEquals(0L, balance(player));
        assertEquals(0, countMaterial(player, Material.IRON_INGOT));
        assertEquals(0, countCustomItem(player, SPIDER_FANG_ID));
        assertEquals(1, countMaterial(player, Material.IRON_SWORD));
    }

    @Test
    void everyMissingIngredientIsListedAndNothingIsTaken() throws Exception {
        PlayerMock player = playerWith(50);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 1));

        click(player, SLOT_MIXED);

        assertEquals(50L, balance(player));
        assertEquals(1, countMaterial(player, Material.IRON_INGOT));
        String said = messages(player);
        assertTrue(said.contains("1 × IRON_INGOT (tu en as 1 sur 2)"), said);
        assertTrue(said.contains("1 × rpgquest:spider_fang (tu en as 0 sur 1)"), said);
        assertTrue(said.contains("Rien n'a été pris"), said);
    }

    @Test
    void missingCoinsAreToldWithTheExactShortfall() throws Exception {
        PlayerMock player = playerWith(7);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 2));
        player.getInventory().addItem(customItemRegistry.create(SPIDER_FANG_ID, 1).orElseThrow());

        click(player, SLOT_MIXED);

        assertEquals(7L, balance(player));
        assertEquals(2, countMaterial(player, Material.IRON_INGOT));
        assertEquals(1, countCustomItem(player, SPIDER_FANG_ID));
        String said = messages(player);
        assertTrue(said.contains("il te manque 3"), said);
        assertTrue(said.contains("coût 10, solde 7"), said);
    }

    @Test
    void recipeIconShowsWhatThePlayerHasAndLacks() throws Exception {
        PlayerMock player = playerWith(4);
        player.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 5));

        tradeService.openShop(player, MERCHANT_ID);
        awaitAsyncWork(); // solde lu : recettes redessinées

        String mixed = lore(player, SLOT_MIXED);
        assertTrue(mixed.contains("✔ 2 × IRON_INGOT (tu en as 5)"), mixed);
        assertTrue(mixed.contains("✘ 1 × rpgquest:spider_fang (tu en as 0)"), mixed);
        assertTrue(mixed.contains("Il te manque 6"), mixed);
        String coinsOnly = lore(player, SLOT_COINS_ONLY);
        assertTrue(coinsOnly.contains("Ingrédients : aucun"), coinsOnly);
        String ingredientsOnly = lore(player, SLOT_INGREDIENTS_ONLY);
        assertTrue(ingredientsOnly.contains("aucune pièce"), ingredientsOnly);
    }

    @Test
    void aSalePriceIsUnavailableInAWorkshop() throws Exception {
        PlayerMock player = playerWith(10);

        tradeService.openShop(player, MERCHANT_ID);
        ItemStack icon = player.getOpenInventory().getTopInventory().getItem(SLOT_SALE);
        assertNotNull(icon);
        assertEquals(Material.BARRIER, icon.getType());
        tradeService.handleClick(player, SLOT_SALE);
        awaitAsyncWork();

        assertEquals(10L, balance(player));
        assertEquals(0, countMaterial(player, Material.BREAD));
    }

    private String lore(PlayerMock player, int slot) {
        ItemStack icon = player.getOpenInventory().getTopInventory().getItem(slot);
        assertNotNull(icon);
        List<Component> lines = icon.getItemMeta().lore();
        StringBuilder sb = new StringBuilder();
        for (Component line : lines == null ? List.<Component>of() : lines) {
            sb.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return sb.toString();
    }

    private static String messages(PlayerMock player) {
        List<String> said = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            said.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return String.join("\n", said);
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
