package com.lodygames.rpgquest.travel.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.config.TravelConfig.RuneConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaypointConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.TravelBeaconRepository;
import com.lodygames.rpgquest.database.WaypointRepository;
import com.lodygames.rpgquest.waypoint.WaypointGenerationPlanner;
import com.lodygames.rpgquest.waypoint.WaypointIdentityResolver;
import com.lodygames.rpgquest.waypoint.WaypointPlacementGuard;
import com.lodygames.rpgquest.waypoint.WaypointService;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import com.lodygames.rpgquest.waypoint.render.BlockOffset;
import com.lodygames.rpgquest.waypoint.render.WaypointModelRegistry;
import com.lodygames.rpgquest.waypoint.render.WaypointModelV1;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issues #132/#150 : parcours « waypoint découvert dans le Wild -> borne du Hub -> menu -> retour
 * sûr au même waypoint », isolation des découvertes entre joueurs, revalidation stricte, pagination
 * et recherche normalisée. Découverte simulée via le vrai bus d'événements Bukkit (même technique
 * que {@code dialogue.session.DialogueSessionEngineTest}) : {@link TravelBeaconServiceTest} est
 * dans un paquet différent de {@code waypoint}, donc jamais d'appel direct à ses méthodes de paquet.
 */
class TravelBeaconServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private World wild;
    private WaypointRepository waypointRepository;
    private WaypointService waypointService;
    private TravelBeaconRepository beaconRepository;
    private TravelBeaconService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        wild = server.addSimpleWorld("wild");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        waypointRepository = new WaypointRepository(database);
        waypointService = new WaypointService(plugin, waypointRepository, new WaypointIdentityResolver(),
                new WaypointGenerationPlanner(), new WaypointModelRegistry(1, new WaypointModelV1()),
                WaypointPlacementGuard.ALLOW_ALL, this::travelConfig);

        beaconRepository = new TravelBeaconRepository(database);
        service = new TravelBeaconService(plugin, beaconRepository, waypointService);
    }

    @AfterEach
    void tearDown() {
        service.stop();
        waypointService.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private TravelConfig travelConfig() {
        return new TravelConfig("wild", new RuneConfig(10, 1800), new WaystoneConfig(1000L, 1.0, 300, 16, 1),
                new WaypointConfig(true, 256L, 16, 40, 24, 0L, 8, 1));
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(2);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(condition.getAsBoolean(), "condition non atteinte avant le délai");
    }

    /** {@code biome_instance} doit être unique par (world, biome_instance) — jamais le même pour deux appels. */
    private Waypoint seedWaypoint(String id, String biomeKey, int x, int y, int z) throws Exception {
        Waypoint waypoint = new Waypoint(id, "wild", biomeKey + "@" + x + "," + z, biomeKey, x, z, x, y, z,
                "NORTH", 1, true, Instant.now());
        boolean inserted = waypointRepository.insertIfAbsent(waypoint).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(inserted, () -> "pré-requis du test : l'insertion de " + id + " a échoué (doublon ?)");
        return waypoint;
    }

    /** {@code waypointService.start()} recharge de façon ASYNCHRONE : attendre l'indexation avant d'interagir. */
    private void reloadAndAwaitIndexed(Waypoint... waypoints) {
        waypointService.start();
        for (Waypoint waypoint : waypoints) {
            await(() -> waypointService.byId(waypoint.id()).isPresent());
        }
    }

    private Block interactorBlockOf(Waypoint wp) {
        BlockOffset off = new WaypointModelV1().interactor(BlockFace.valueOf(wp.facing()));
        return wild.getBlockAt(wp.x() + off.dx(), wp.y() + off.dy(), wp.z() + off.dz());
    }

    /** Simule un clic droit réel sur le bouton d'un waypoint, via le vrai bus d'événements. */
    private void discover(PlayerMock player, Waypoint waypoint) {
        Block button = interactorBlockOf(waypoint);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.AIR), button, BlockFace.valueOf(waypoint.facing()), EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        await(() -> waypointService.hasActivelyDiscovered(player.getUniqueId(), waypoint.id()));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void placingABeaconBuildsTheDiamondStructureAndIsIdempotent() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        server.getPluginManager().registerEvents(service.listener(), plugin);
        server.getPluginManager().registerEvents(service.protectionListener(), plugin);
        service.start();

        PlayerMock admin = addPlayer();
        admin.teleport(new Location(wild, 100.5, 65, 100.5, 0f, 0f));
        wild.getBlockAt(100, 64, 100).setType(Material.STONE);
        wild.getBlockAt(100, 65, 100).setType(Material.AIR);
        wild.getBlockAt(100, 66, 100).setType(Material.AIR);
        wild.getBlockAt(100, 67, 100).setType(Material.AIR);

        var error = service.placeAt(admin, admin.getLocation());
        assertTrue(error.isEmpty(), () -> "échec inattendu : " + error);
        await(() -> !service.all().isEmpty());

        var beacon = service.all().get(0);
        assertEquals(Material.DIAMOND_BLOCK, wild.getBlockAt(beacon.x(), beacon.y() + 1, beacon.z()).getType());
        assertEquals(Material.COBBLESTONE_WALL, wild.getBlockAt(beacon.x(), beacon.y(), beacon.z()).getType());
        assertEquals(Material.OAK_BUTTON, wild.getBlockAt(
                beacon.x() + BlockFace.valueOf(beacon.facing()).getModX(), beacon.y() + 1,
                beacon.z() + BlockFace.valueOf(beacon.facing()).getModZ()).getType());

        // Rejouer au même endroit : aucune deuxième borne.
        var second = service.placeAt(admin, admin.getLocation());
        assertTrue(second.isPresent(), "une deuxième borne au même endroit doit être refusée");
        assertEquals(1, service.all().size());
    }

    @Test
    void breakingABeaconBlockWithoutBypassIsBlocked() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        server.getPluginManager().registerEvents(service.listener(), plugin);
        server.getPluginManager().registerEvents(service.protectionListener(), plugin);
        service.start();

        PlayerMock admin = addPlayer();
        admin.teleport(new Location(wild, 100.5, 65, 100.5, 0f, 0f));
        wild.getBlockAt(100, 64, 100).setType(Material.STONE);
        service.placeAt(admin, admin.getLocation());
        await(() -> !service.all().isEmpty());
        var beacon = service.all().get(0);

        assertTrue(service.isProtectedBlock("wild", beacon.x(), beacon.y() + 1, beacon.z()));
    }

    @Test
    void buttonInteractionOpensTheRootMenu() throws Exception {
        waypointService.start();
        service.start();
        PlayerMock admin = addPlayer();
        admin.teleport(new Location(wild, 200.5, 65, 200.5, 0f, 0f));
        wild.getBlockAt(200, 64, 200).setType(Material.STONE);
        service.placeAt(admin, admin.getLocation());
        await(() -> !service.all().isEmpty());

        boolean cancelled = service.handleButtonInteract(admin,
                interactorBlockOfBeacon(service.all().get(0)));
        assertTrue(cancelled);
        assertTrue(admin.getOpenInventory().getTopInventory().getHolder() instanceof BeaconMenuHolder holder
                && holder.kind() == BeaconMenuHolder.Kind.ROOT);
    }

    private Block interactorBlockOfBeacon(com.lodygames.rpgquest.travel.beacon.model.TravelBeacon beacon) {
        BlockFace f = BlockFace.valueOf(beacon.facing());
        return wild.getBlockAt(beacon.x() + f.getModX(), beacon.y() + 1, beacon.z() + f.getModZ());
    }

    @Test
    void waypointsMenuShowsEmptyStateWhenNothingDiscovered() throws Exception {
        waypointService.start();
        service.start();
        PlayerMock player = addPlayer();

        service.openWaypoints(player, 0, "");

        assertEquals(Material.BARRIER, player.getOpenInventory().getTopInventory().getItem(22).getType());
    }

    @Test
    void discoveriesAreIsolatedBetweenPlayers() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        service.start();

        Waypoint wp = seedWaypoint("wp_forest_0_0", "minecraft:forest", 10, 65, 10);
        reloadAndAwaitIndexed(wp);

        PlayerMock a = addPlayer();
        PlayerMock b = addPlayer();
        discover(a, wp);

        assertEquals(1, waypointService.discoveredBy(a.getUniqueId()).size());
        assertTrue(waypointService.discoveredBy(b.getUniqueId()).isEmpty());
    }

    @Test
    void fullJourneyDiscoverBeaconMenuAndSafeReturnToTheSameWaypoint() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        Waypoint wp = seedWaypoint("wp_forest_1_1", "minecraft:forest", 300, 65, 300);
        reloadAndAwaitIndexed(wp);
        for (int x = 295; x <= 305; x++) {
            for (int z = 295; z <= 305; z++) {
                wild.getBlockAt(x, 64, z).setType(Material.STONE);
                wild.getBlockAt(x, 65, z).setType(Material.AIR);
                wild.getBlockAt(x, 66, z).setType(Material.AIR);
            }
        }

        service.start();
        PlayerMock player = addPlayer();
        discover(player, wp); // découverte dans le Wild

        player.teleport(new Location(wild, 0.5, 65, 0.5)); // "mort -> retour au Hub" simulé par un déplacement
        service.openWaypoints(player, 0, "");
        BeaconMenuSession session = service.sessionOf(player);
        service.handleWaypointsClick(player, 0, session); // choisit l'unique waypoint découvert

        assertEquals("wild", player.getLocation().getWorld().getName());
        double distance = Math.hypot(player.getLocation().getX() - wp.x(), player.getLocation().getZ() - wp.z());
        assertTrue(distance < 2.0, "arrivée attendue tout près du waypoint, obtenu à " + distance + " blocs");
    }

    @Test
    void stalePickOfAnUndiscoveredOrUnknownWaypointIsRejectedWithoutTeleporting() throws Exception {
        waypointService.start();
        service.start();
        PlayerMock player = addPlayer();
        Location before = player.getLocation().clone();

        service.travelTo(player, "does_not_exist");

        assertEquals(before.getWorld(), player.getLocation().getWorld());
        assertEquals(before.getBlockX(), player.getLocation().getBlockX());
    }

    @Test
    void paginationSplitsMoreThanFortyFiveDiscoveriesAcrossPages() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        java.util.List<Waypoint> seeded = new java.util.ArrayList<>();
        for (int i = 0; i < 46; i++) {
            seeded.add(seedWaypoint("wp_test_" + i, "minecraft:plains", i, 65, 0));
        }
        reloadAndAwaitIndexed(seeded.toArray(new Waypoint[0]));
        service.start();

        PlayerMock player = addPlayer();
        for (Waypoint wp : seeded) {
            discover(player, wp);
        }

        service.openWaypoints(player, 0, "");
        assertEquals(45, countFilledMaps(player));

        service.openWaypoints(player, 1, "");
        assertEquals(1, countFilledMaps(player));
    }

    private int countFilledMaps(PlayerMock player) {
        int count = 0;
        for (var item : player.getOpenInventory().getTopInventory().getContents()) {
            if (item != null && item.getType() == Material.FILLED_MAP) {
                count++;
            }
        }
        return count;
    }

    @Test
    void normalizeIsCaseAndAccentInsensitive() {
        assertEquals(TravelBeaconService.normalize("forest"), TravelBeaconService.normalize("FOREST"));
        assertEquals(TravelBeaconService.normalize("foret"), TravelBeaconService.normalize("Forêt"));
    }
}
