package be.lloyd.rpgquest.economy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.database.DatabaseManager;
import be.lloyd.rpgquest.database.MarketListingRecord;
import be.lloyd.rpgquest.database.MarketRepository;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.database.WalletRepository;
import be.lloyd.rpgquest.economy.EconomyService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class MarketServiceTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final int FIRST_CONTENT_SLOT = 9;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private WalletRepository walletRepository;
    private MarketRepository marketRepository;
    private EconomyService economyService;
    private MarketService marketService;
    private PlayerProfileRepository profileRepository;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profileRepository = new PlayerProfileRepository(database);
        walletRepository = new WalletRepository(database);
        marketRepository = new MarketRepository(database);
        economyService = new EconomyService(walletRepository);

        marketService = new MarketService(plugin, marketRepository, economyService);
        marketService.start();
    }

    @AfterEach
    void tearDown() {
        marketService.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void sellingRemovesTheItemFromHandAndCreatesAnActiveListing() throws Exception {
        PlayerMock seller = addPlayer();
        seller.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 3));

        marketService.sell(seller, 100);
        awaitAsyncWork();

        assertTrue(seller.getInventory().getItemInMainHand().getType().isAir());
        List<MarketListingRecord> active = marketRepository.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, active.size());
        assertEquals(100L, active.get(0).price());
        assertEquals(seller.getUniqueId(), active.get(0).sellerUuid());
    }

    @Test
    void buyingWithSufficientFundsMovesMoneyAndItem() throws Exception {
        PlayerMock seller = addPlayer();
        PlayerMock buyer = addPlayer();
        walletRepository.credit(buyer.getUniqueId(), 50, "TEST", null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        marketRepository.createListing(seller.getUniqueId(), new ItemStack(Material.DIAMOND, 1).serializeAsBytes(), 30)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        marketService.open(buyer);
        awaitAsyncWork();
        marketService.handleClick(buyer, FIRST_CONTENT_SLOT);
        awaitAsyncWork();

        assertEquals(20L, walletRepository.balance(buyer.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(30L, walletRepository.balance(seller.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(containsMaterial(buyer, Material.DIAMOND));
        assertEquals(0, marketRepository.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
        assertEquals(0, marketRepository.myActiveListings(seller.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void buyingWithInsufficientFundsReactivatesTheListing() throws Exception {
        PlayerMock seller = addPlayer();
        PlayerMock buyer = addPlayer();
        // Aucun crédit pour l'acheteur : solde 0.
        marketRepository.createListing(seller.getUniqueId(), new ItemStack(Material.DIAMOND, 1).serializeAsBytes(), 30)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        marketService.open(buyer);
        awaitAsyncWork();
        marketService.handleClick(buyer, FIRST_CONTENT_SLOT);
        awaitAsyncWork();

        assertEquals(0L, walletRepository.balance(buyer.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0L, walletRepository.balance(seller.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(!containsMaterial(buyer, Material.DIAMOND));
        assertEquals(1, marketRepository.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size(),
                "l'offre doit être réactivée après un débit refusé");
    }

    @Test
    void clickingYourOwnListingCancelsItAndReturnsTheItem() throws Exception {
        PlayerMock seller = addPlayer();
        marketRepository.createListing(seller.getUniqueId(), new ItemStack(Material.DIAMOND, 1).serializeAsBytes(), 30)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        marketService.open(seller);
        awaitAsyncWork();
        marketService.handleClick(seller, FIRST_CONTENT_SLOT);
        awaitAsyncWork();

        assertTrue(containsMaterial(seller, Material.DIAMOND));
        assertEquals(0, marketRepository.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void cancelCommandBySellerReturnsTheItem() throws Exception {
        PlayerMock seller = addPlayer();
        long id = marketRepository.createListing(
                seller.getUniqueId(), new ItemStack(Material.DIAMOND, 1).serializeAsBytes(), 30)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        marketService.cancel(seller, id);
        awaitAsyncWork();

        assertTrue(containsMaterial(seller, Material.DIAMOND));
        assertEquals(0, marketRepository.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void cancelCommandByNonOwnerChangesNothing() throws Exception {
        PlayerMock seller = addPlayer();
        PlayerMock stranger = addPlayer();
        long id = marketRepository.createListing(
                seller.getUniqueId(), new ItemStack(Material.DIAMOND, 1).serializeAsBytes(), 30)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        marketService.cancel(stranger, id);
        awaitAsyncWork();

        assertTrue(!containsMaterial(stranger, Material.DIAMOND));
        assertEquals(1, marketRepository.activeListings().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        profileRepository.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private boolean containsMaterial(PlayerMock player, Material material) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                return true;
            }
        }
        return false;
    }

    /** Les transactions passent par des CompletableFuture réels (thread DB) : on laisse le temps à l'effet de se produire. */
    private void awaitAsyncWork() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }
}
