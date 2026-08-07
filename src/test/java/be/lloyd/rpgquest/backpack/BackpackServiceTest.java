package be.lloyd.rpgquest.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.backpack.model.BackpackSize;
import be.lloyd.rpgquest.config.BackpackConfig;
import be.lloyd.rpgquest.database.BackpackRepository;
import be.lloyd.rpgquest.database.DatabaseManager;
import be.lloyd.rpgquest.database.EntitlementRepository;
import be.lloyd.rpgquest.database.PlayerProfileRepository;
import be.lloyd.rpgquest.entitlement.EntitlementService;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

class BackpackServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private BackpackRepository repository;
    private EntitlementService entitlementService;
    private PlayerProfileRepository profiles;
    private BackpackConfig config;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new BackpackRepository(database);
        entitlementService = new EntitlementRepository(database);
        profiles = new PlayerProfileRepository(database);

        config = new BackpackConfig(1, 3, 6, Set.of(), BackpackSize.SMALL, Material.BUNDLE);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private BackpackService newService() {
        BackpackService service = new BackpackService(plugin, repository, entitlementService, () -> config, plugin.getSLF4JLogger());
        service.start(); // enregistre BackpackListener : sans lui, InventoryCloseEvent n'atteint jamais handleClose.
        return service;
    }

    private PlayerMock newPlayer(boolean fallbackPermission) throws Exception {
        PlayerMock player = server.addPlayer();
        profiles.findOrCreate(player.getUniqueId(), player.getName()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        player.addAttachment(plugin, BackpackService.FALLBACK_PERMISSION, fallbackPermission);
        return player;
    }

    /**
     * L'octroi/l'ouverture d'un backpack traverse le thread DB (réel, séparé) puis revient sur le
     * thread principal via {@code Bukkit.getScheduler().runTask} : on ne peut pas bloquer sur le
     * futur avant d'avoir laissé une chance au thread DB de programmer cette tâche, donc on
     * alterne petites attentes réelles et exécution des tâches en attente jusqu'à complétion.
     */
    private <T> T await(CompletableFuture<T> future) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!future.isDone() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ---- Création --------------------------------------------------------------------------

    @Test
    void openingForTheFirstTimeCreatesAnEmptyBackpackAtTheFallbackSize() throws Exception {
        BackpackService service = newService();
        PlayerMock player = newPlayer(true);

        BackpackService.OpenOutcome outcome = await(service.open(player));

        assertEquals(BackpackService.OpenOutcome.OPENED, outcome);
        assertEquals(1, player.getOpenInventory().getTopInventory().getSize() / 9); // SMALL = 1 ligne
        for (ItemStack item : player.getOpenInventory().getTopInventory().getContents()) {
            assertEquals(null, item);
        }
    }

    // ---- Accès sans droit --------------------------------------------------------------------

    @Test
    void openingWithoutAnyEntitlementOrFallbackPermissionIsDenied() throws Exception {
        BackpackService service = newService();
        PlayerMock player = newPlayer(false);

        BackpackService.OpenOutcome outcome = await(service.open(player));

        assertEquals(BackpackService.OpenOutcome.NO_ACCESS, outcome);
    }

    // ---- Persistance -------------------------------------------------------------------------

    @Test
    void closingSavesContentsAndReopeningRestoresThem() throws Exception {
        BackpackService service = newService();
        PlayerMock player = newPlayer(true);

        await(service.open(player));
        player.getOpenInventory().getTopInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.closeInventory();
        server.getScheduler().performTicks(2);
        awaitDatabaseIdle();

        BackpackService.OpenOutcome outcome = await(service.open(player));
        assertEquals(BackpackService.OpenOutcome.OPENED, outcome);
        assertEquals(new ItemStack(Material.DIAMOND, 3), player.getOpenInventory().getTopInventory().getItem(0));
    }

    @Test
    void crashSimulationSurvivesAFreshServiceReadingTheSameDatabase() throws Exception {
        BackpackService first = newService();
        PlayerMock player = newPlayer(true);
        await(first.open(player));
        player.getOpenInventory().getTopInventory().setItem(0, new ItemStack(Material.GOLD_INGOT, 7));
        player.closeInventory();
        server.getScheduler().performTicks(2);
        awaitDatabaseIdle();

        // Simule un redémarrage brutal : nouvelle instance de service, jamais stop() proprement appelé
        // sur "first" (pas de flush explicite requis puisque la fermeture a déjà tout sauvegardé).
        BackpackService restarted = newService();
        BackpackService.OpenOutcome outcome = await(restarted.open(player));

        assertEquals(BackpackService.OpenOutcome.OPENED, outcome);
        assertEquals(new ItemStack(Material.GOLD_INGOT, 7), player.getOpenInventory().getTopInventory().getItem(0));
    }

    // ---- Ouvertures simultanées ----------------------------------------------------------

    @Test
    void openingTwiceBeforeTheFirstLoadCompletesNeverCreatesTwoInstances() throws Exception {
        BackpackService service = newService();
        PlayerMock player = newPlayer(true);

        CompletableFuture<BackpackService.OpenOutcome> firstCall = service.open(player);
        CompletableFuture<BackpackService.OpenOutcome> secondCall = service.open(player);

        BackpackService.OpenOutcome firstOutcome = await(firstCall);
        BackpackService.OpenOutcome secondOutcome = await(secondCall);

        assertTrue((firstOutcome == BackpackService.OpenOutcome.OPENED && secondOutcome == BackpackService.OpenOutcome.ALREADY_OPEN)
                || (secondOutcome == BackpackService.OpenOutcome.OPENED && firstOutcome == BackpackService.OpenOutcome.ALREADY_OPEN));
    }

    // ---- Upgrade / downgrade ------------------------------------------------------------------

    @Test
    void upgradePreservesAllExistingItems() throws Exception {
        BackpackService service = newService();
        PlayerMock player = newPlayer(true);
        await(service.open(player));
        player.getOpenInventory().getTopInventory().setItem(0, new ItemStack(Material.EMERALD, 4));
        player.closeInventory();
        server.getScheduler().performTicks(2);
        awaitDatabaseIdle();

        entitlementService.grant(player.getUniqueId(), BackpackService.ENTITLEMENT_KEY, BackpackSize.LARGE.name(), "test")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        await(service.applySizeChange(player.getUniqueId(), BackpackSize.LARGE));

        BackpackService.OpenOutcome outcome = await(service.open(player));
        assertEquals(BackpackService.OpenOutcome.OPENED, outcome);
        assertEquals(6, player.getOpenInventory().getTopInventory().getSize() / 9);
        assertEquals(new ItemStack(Material.EMERALD, 4), player.getOpenInventory().getTopInventory().getItem(0));
    }

    @Test
    void downgradeMovesOverflowingItemsToTheRecoveryBoxWithoutLosingAny() throws Exception {
        BackpackService service = newService();
        PlayerMock player = newPlayer(true);

        entitlementService.grant(player.getUniqueId(), BackpackService.ENTITLEMENT_KEY, BackpackSize.LARGE.name(), "test")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        await(service.open(player));
        for (int i = 0; i < 54; i++) {
            player.getOpenInventory().getTopInventory().setItem(i, new ItemStack(Material.STONE, 1));
        }
        player.closeInventory();
        server.getScheduler().performTicks(2);
        awaitDatabaseIdle();

        entitlementService.grant(player.getUniqueId(), BackpackService.ENTITLEMENT_KEY, BackpackSize.SMALL.name(), "test")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        await(service.applySizeChange(player.getUniqueId(), BackpackSize.SMALL));

        List<BackpackRepository.OverflowEntry> overflow =
                service.unclaimedOverflow(player.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(overflow.isEmpty(), "les 45 objets en trop (54 - 9) doivent être récupérables, jamais perdus");

        BackpackService.OpenOutcome outcome = await(service.open(player));
        assertEquals(BackpackService.OpenOutcome.OPENED, outcome);
        for (ItemStack item : player.getOpenInventory().getTopInventory().getContents()) {
            assertEquals(new ItemStack(Material.STONE, 1), item);
        }
    }

    private void awaitDatabaseIdle() throws Exception {
        database.execute(connection -> null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
