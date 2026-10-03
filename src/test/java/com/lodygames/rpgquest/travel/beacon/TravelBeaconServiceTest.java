package com.lodygames.rpgquest.travel.beacon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.claim.model.ClaimFlags;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.config.TravelConfig.BeaconConfig;
import com.lodygames.rpgquest.config.TravelConfig.RuneConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaypointConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import com.lodygames.rpgquest.database.ClaimRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.ProgressionRepository;
import com.lodygames.rpgquest.database.TravelBeaconRepository;
import com.lodygames.rpgquest.database.VillageCenterRepository;
import com.lodygames.rpgquest.database.WaypointRepository;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.travel.YamlPortalRegistry;
import com.lodygames.rpgquest.travel.beacon.model.TravelBeacon;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import com.lodygames.rpgquest.waypoint.WaypointGenerationPlanner;
import com.lodygames.rpgquest.waypoint.WaypointIdentityResolver;
import com.lodygames.rpgquest.waypoint.WaypointPlacementGuard;
import com.lodygames.rpgquest.waypoint.WaypointService;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import com.lodygames.rpgquest.waypoint.render.BlockOffset;
import com.lodygames.rpgquest.waypoint.render.WaypointModelRegistry;
import com.lodygames.rpgquest.waypoint.render.WaypointModelV1;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issues #132/#150/#151 : parcours « waypoint découvert dans le Wild -> borne du Hub -> menu ->
 * retour sûr au même waypoint », « Mon claim », « Villages », isolation des découvertes entre
 * joueurs, revalidation stricte, pagination et recherche normalisée. Découverte simulée via le vrai
 * bus d'événements Bukkit (même technique que {@code dialogue.session.DialogueSessionEngineTest}) :
 * {@link TravelBeaconServiceTest} est dans un paquet différent de {@code waypoint}, donc jamais
 * d'appel direct à ses méthodes de paquet.
 *
 * <p><strong>Limitation MockBukkit connue</strong> (même catégorie que les occurrences déjà
 * documentées depuis l'étape 18, nouvelle occurrence) : {@code EntityMock#teleportAsync(Location)}
 * (la surcharge à deux arguments sans {@code TeleportCause} explicite) lève
 * {@code UnimplementedOperationException} — JUnit traite ceci comme une exécution
 * <strong>ignorée</strong> (jamais un échec) dès qu'un scénario atteint réellement la
 * téléportation finale ({@code fullJourneyDiscoverBeaconMenuAndSafeReturnToTheSameWaypoint},
 * {@code travelToClaimRefusesWithoutAClaimThenArrivesInsideOnceOneExists},
 * {@code villageCentersAreDistinctByIdAndTravelArrivesAtTheExactStoredLocation}). Tout ce qui
 * précède la téléportation elle-même (découverte, revalidation, calcul de la position sûre) est
 * bien exercé et vérifié avant que l'appel ne soit atteint.</p>
 */
class TravelBeaconServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private World wild;
    private World hub;
    private WaypointRepository waypointRepository;
    private WaypointService waypointService;
    private TravelBeaconRepository beaconRepository;
    private VillageCenterRepository villageCenterRepository;
    private PlayerVariableRepository variableRepository;
    private ClaimService claimService;
    private TravelBeaconService service;
    /** Mutable : certains tests #149 modifient la configuration (gating) sans reconstruire les services. */
    private TravelConfig currentConfig;

    @BeforeEach
    void setUp() throws Exception {
        currentConfig = defaultTravelConfig();
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        wild = server.addSimpleWorld("wild");
        hub = server.addSimpleWorld("world_hub");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        waypointRepository = new WaypointRepository(database);
        waypointService = new WaypointService(plugin, waypointRepository, new WaypointIdentityResolver(),
                new WaypointGenerationPlanner(), new WaypointModelRegistry(1, new WaypointModelV1()),
                WaypointPlacementGuard.ALLOW_ALL, this::travelConfig);

        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        zoneRegistry.start();
        YamlPortalRegistry portalRegistry = new YamlPortalRegistry(tempDir.resolve("portals"), plugin.getSLF4JLogger());
        portalRegistry.start();
        ConfigService configService = new ConfigService(plugin);
        configService.start();
        ProgressionService progressionService = new ProgressionService(plugin, new ProgressionRepository(database),
                () -> configService.current().progression(), plugin.getSLF4JLogger());
        progressionService.start();
        variableRepository = new PlayerVariableRepository(database);
        claimService = new ClaimService(plugin, new ClaimRepository(database), zoneRegistry, portalRegistry,
                configService, progressionService, variableRepository);
        claimService.start();

        beaconRepository = new TravelBeaconRepository(database);
        villageCenterRepository = new VillageCenterRepository(database);
        service = new TravelBeaconService(plugin, beaconRepository, waypointService, claimService, villageCenterRepository,
                this::travelConfig, () -> "world_hub");
    }

    @AfterEach
    void tearDown() {
        service.stop();
        claimService.stop();
        waypointService.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    private TravelConfig travelConfig() {
        return currentConfig;
    }

    private TravelConfig defaultTravelConfig() {
        return new TravelConfig("wild", new RuneConfig(10, 1800), new WaystoneConfig(1000L, 1.0, 300, 16, 1),
                new WaypointConfig(true, 256L, 16, 40, 24, 0L, 8, 1, true),
                new BeaconConfig(Material.OAK_BUTTON, true, 6, 16));
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
        Waypoint waypoint = new Waypoint(id, "Nom " + id, "wild", biomeKey + "@" + x + "," + z, biomeKey, x, z, x, y, z,
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
        return interactorBlockOf(wp, wild);
    }

    private Block interactorBlockOf(Waypoint wp, World world) {
        BlockOffset off = new WaypointModelV1().interactor(BlockFace.valueOf(wp.facing()));
        return world.getBlockAt(wp.x() + off.dx(), wp.y() + off.dy(), wp.z() + off.dz());
    }

    /** Simule un clic droit réel sur le bouton d'un waypoint, via le vrai bus d'événements. */
    private void discover(PlayerMock player, Waypoint waypoint) {
        discover(player, waypoint, wild);
    }

    private void discover(PlayerMock player, Waypoint waypoint, World world) {
        Block button = interactorBlockOf(waypoint, world);
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

        service.openWaypoints(player, 0, "", "wild");

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
        service.openWaypoints(player, 0, "", "wild");
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

        service.openWaypoints(player, 0, "", "wild");
        assertEquals(45, countFilledMaps(player));

        service.openWaypoints(player, 1, "", "wild");
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

    // ---- Issue #151 : Mon claim --------------------------------------------------------------

    @Test
    void travelToClaimRefusesWithoutAClaimThenArrivesInsideOnceOneExists() throws Exception {
        waypointService.start();
        service.start();
        PlayerMock player = addPlayer();
        Location before = player.getLocation().clone();

        service.travelToClaim(player);
        assertEquals(before.getWorld(), player.getLocation().getWorld());

        // ClaimService#create refuse le monde Hub (claim à la baguette hors Hub uniquement) :
        // un monde dédié, comme pour les autres tests de claims du dépôt.
        World claimsWorld = server.addSimpleWorld("claims_test");
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                claimsWorld.getBlockAt(x, 59, z).setType(Material.STONE);
                claimsWorld.getBlockAt(x, 60, z).setType(Material.AIR);
                claimsWorld.getBlockAt(x, 61, z).setType(Material.AIR);
            }
        }
        variableRepository.set(player.getUniqueId(), ClaimService.CLAIM_TIER_1_KEY, ClaimService.CLAIM_TIER_1_VALUE)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var outcome = claimService.create(player, "main_" + player.getUniqueId(),
                new Location(claimsWorld, -4, 60, -4), new Location(claimsWorld, 4, 63, 4))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClaimService.CreateOutcome.CREATED, outcome, "pré-requis du test : le claim doit vraiment être créé");
        // La création termine sa future avant que le cache mémoire ne soit réappliqué sur le thread
        // principal (runOnMainThread) : attendre que mainClaimOf reflète vraiment le nouveau claim.
        await(() -> claimService.mainClaimOf(player.getUniqueId()).isPresent());

        service.travelToClaim(player);
        assertEquals("claims_test", player.getLocation().getWorld().getName());
        var claim = claimService.mainClaimOf(player.getUniqueId()).orElseThrow();
        assertTrue(claim.contains("claims_test", player.getLocation().getBlockX(),
                player.getLocation().getBlockY(), player.getLocation().getBlockZ()),
                "l'arrivée doit rester DANS le claim du joueur");
    }

    // ---- Issue #151 : Villages -------------------------------------------------------------

    @Test
    void villageCentersAreDistinctByIdAndTravelArrivesAtTheExactStoredLocation() throws Exception {
        waypointService.start();
        service.start();
        service.setVillage("hub_main", "Hub principal", new Location(hub, 10.5, 70, 10.5, 90f, 0f));
        service.setVillage("hub_east", "Quartier Est", new Location(hub, 50.5, 70, 50.5, 0f, 0f));
        await(() -> service.villages().size() == 2);

        PlayerMock player = addPlayer();
        service.travelToVillage(player, "hub_east");

        assertEquals("world_hub", player.getLocation().getWorld().getName());
        assertEquals(50, player.getLocation().getBlockX());
        assertEquals(50, player.getLocation().getBlockZ());

        service.travelToVillage(player, "hub_main");
        assertEquals(10, player.getLocation().getBlockX());
    }

    @Test
    void disabledOrRemovedVillageIsRejectedWithoutTeleporting() throws Exception {
        waypointService.start();
        service.start();
        service.setVillage("hub_main", "Hub principal", new Location(hub, 10.5, 70, 10.5, 0f, 0f));
        await(() -> service.villages().size() == 1);
        PlayerMock player = addPlayer();
        Location before = player.getLocation().clone();

        var disableError = service.setVillageActive("hub_main", false);
        assertTrue(disableError.isEmpty());
        await(() -> !service.villages().get(0).active());
        service.travelToVillage(player, "hub_main");
        assertEquals(before.getWorld(), player.getLocation().getWorld());

        var removeError = service.removeVillage("hub_main");
        assertTrue(removeError.isEmpty());
        service.travelToVillage(player, "hub_main");
        assertEquals(before.getWorld(), player.getLocation().getWorld());

        assertTrue(service.removeVillage("does_not_exist").isPresent());
    }

    // ---- Issue #149 : génération progressive waypoint + borne appariée dans le Hub -----------

    private static final int HUB_RADIUS = 64;

    /** Plateforme de pierre + biome imposé, assez large pour couvrir waypoint (40) + appariement borne (16). */
    private void prepareHubArea(int centerX, int centerZ, Biome biome) {
        for (int x = centerX - HUB_RADIUS; x <= centerX + HUB_RADIUS; x++) {
            for (int z = centerZ - HUB_RADIUS; z <= centerZ + HUB_RADIUS; z++) {
                hub.getBlockAt(x, 64, z).setType(Material.STONE);
                hub.getBlockAt(x, 65, z).setType(Material.AIR);
                hub.getBlockAt(x, 66, z).setType(Material.AIR);
                hub.getBlockAt(x, 67, z).setType(Material.AIR);
                hub.setBiome(x, z, biome);
            }
        }
    }

    private Optional<Waypoint> waypointNear(int centerX, int centerZ) {
        return waypointService.all().stream()
                .filter(w -> w.world().equals("world_hub"))
                .filter(w -> Math.abs(w.x() - centerX) <= HUB_RADIUS && Math.abs(w.z() - centerZ) <= HUB_RADIUS)
                .findFirst();
    }

    private Optional<TravelBeacon> beaconNear(int centerX, int centerZ) {
        return service.all().stream()
                .filter(b -> b.world().equals("world_hub"))
                .filter(b -> Math.abs(b.x() - centerX) <= HUB_RADIUS && Math.abs(b.z() - centerZ) <= HUB_RADIUS)
                .findFirst();
    }

    @Test
    void hubMovementProgressivelyGeneratesAWaypointThenAPairedBeaconAtDistinctPositionsForTwoPlayers() throws Exception {
        waypointService.start();
        service.start();
        prepareHubArea(1000, 1000, Biome.PLAINS);
        Location entry = new Location(hub, 1000.5, 65, 1000.5);

        PlayerMock first = addPlayer();
        service.handleHubMovement(first, entry); // déclenche la génération du waypoint (asynchrone).
        await(() -> waypointNear(1000, 1000).isPresent());
        assertTrue(beaconNear(1000, 1000).isEmpty(), "pas encore de borne : le waypoint vient tout juste d'apparaître");

        PlayerMock second = addPlayer();
        service.handleHubMovement(second, entry); // waypoint désormais prêt : la borne s'apparie.
        await(() -> beaconNear(1000, 1000).isPresent());

        Waypoint waypoint = waypointNear(1000, 1000).orElseThrow();
        TravelBeacon beacon = beaconNear(1000, 1000).orElseThrow();
        assertTrue(beacon.isAutoGenerated(), "une borne appariée au Hub porte une biome_instance");
        assertTrue(beacon.id().startsWith("beacon_auto_"));
        assertTrue(beacon.x() != waypoint.x() || beacon.z() != waypoint.z(),
                "la borne doit être à une position distincte du waypoint");

        // Rejouer (autres joueurs, mouvements répétés) ne crée jamais de doublon.
        PlayerMock third = addPlayer();
        service.handleHubMovement(third, entry);
        service.handleHubMovement(third, entry);
        server.getScheduler().performTicks(10);
        assertEquals(1, waypointService.all().size());
        assertEquals(1, service.all().size());
    }

    @Test
    void twoDistinctHubBiomeInstancesEachGetTheirOwnDistinctWaypointAndBeaconPair() throws Exception {
        waypointService.start();
        service.start();
        prepareHubArea(2000, 2000, Biome.PLAINS);
        prepareHubArea(2000 + 4 * 256, 2000, Biome.PLAINS);

        PlayerMock player = addPlayer();
        waypointService.ensureGenerated(hub, new Location(hub, 2000.5, 65, 2000.5));
        waypointService.ensureGenerated(hub, new Location(hub, 2000.5 + 4 * 256, 65, 2000.5));
        await(() -> waypointNear(2000, 2000).isPresent());
        await(() -> waypointNear(2000 + 4 * 256, 2000).isPresent());

        service.handleHubMovement(player, new Location(hub, 2000.5, 65, 2000.5));
        await(() -> beaconNear(2000, 2000).isPresent());
        service.handleHubMovement(player, new Location(hub, 2000.5 + 4 * 256, 65, 2000.5));
        await(() -> beaconNear(2000 + 4 * 256, 2000).isPresent());

        assertEquals(2, waypointService.all().size());
        assertEquals(2, service.all().size());
        Waypoint firstWaypoint = waypointNear(2000, 2000).orElseThrow();
        Waypoint secondWaypoint = waypointNear(2000 + 4 * 256, 2000).orElseThrow();
        assertFalse(firstWaypoint.biomeInstance().equals(secondWaypoint.biomeInstance()));
    }

    @Test
    void hubWaypointGenerationIsSkippedWhenWaypointHubEnabledIsFalse() throws Exception {
        currentConfig = new TravelConfig("wild", new RuneConfig(10, 1800), new WaystoneConfig(1000L, 1.0, 300, 16, 1),
                new WaypointConfig(true, 256L, 16, 40, 24, 0L, 8, 1, false),
                new BeaconConfig(Material.OAK_BUTTON, true, 6, 16));
        waypointService.start();
        service.start();
        prepareHubArea(3000, 3000, Biome.PLAINS);

        service.handleHubMovement(addPlayer(), new Location(hub, 3000.5, 65, 3000.5));
        server.getScheduler().performTicks(10);

        assertTrue(waypointService.all().isEmpty(), "hub-enabled=false : aucune génération, même dans le Hub");
        assertTrue(service.all().isEmpty());
    }

    @Test
    void beaconPairingIsSkippedWhenBeaconHubGenerationEnabledIsFalseButWaypointStillGenerates() throws Exception {
        currentConfig = new TravelConfig("wild", new RuneConfig(10, 1800), new WaystoneConfig(1000L, 1.0, 300, 16, 1),
                new WaypointConfig(true, 256L, 16, 40, 24, 0L, 8, 1, true),
                new BeaconConfig(Material.OAK_BUTTON, false, 6, 16));
        waypointService.start();
        service.start();
        prepareHubArea(4000, 4000, Biome.PLAINS);

        service.handleHubMovement(addPlayer(), new Location(hub, 4000.5, 65, 4000.5));
        await(() -> waypointNear(4000, 4000).isPresent());
        server.getScheduler().performTicks(10);

        assertTrue(service.all().isEmpty(), "borne appariée désactivée : le waypoint existe seul");
    }

    @Test
    void hubMovementHasNoEffectOutsideTheHubWorldAndAdministeredBeaconsAreNeverMarkedAutoGenerated() throws Exception {
        waypointService.start();
        service.start();
        PlayerMock player = addPlayer();
        player.teleport(new Location(wild, 5000.5, 65, 5000.5, 0f, 0f));
        wild.getBlockAt(5000, 64, 5000).setType(Material.STONE);

        service.handleHubMovement(player, new Location(wild, 5000.5, 65, 5000.5)); // monde Wild, pas Hub.
        server.getScheduler().performTicks(5);
        assertTrue(waypointService.all().isEmpty());
        assertTrue(service.all().isEmpty());

        var error = service.placeAt(player, player.getLocation());
        assertTrue(error.isEmpty());
        await(() -> !service.all().isEmpty());
        assertFalse(service.all().get(0).isAutoGenerated(), "placement administré : jamais marqué auto-généré");
    }

    @Test
    void bothAutoGeneratedStructuresAreProtectedAfterHubPairing() throws Exception {
        waypointService.start();
        service.start();
        prepareHubArea(6000, 6000, Biome.PLAINS);
        Location entry = new Location(hub, 6000.5, 65, 6000.5);
        waypointService.ensureGenerated(hub, entry);
        await(() -> waypointNear(6000, 6000).isPresent());

        service.handleHubMovement(addPlayer(), entry);
        await(() -> beaconNear(6000, 6000).isPresent());

        Waypoint waypoint = waypointNear(6000, 6000).orElseThrow();
        TravelBeacon beacon = beaconNear(6000, 6000).orElseThrow();
        assertTrue(waypointService.isProtectedBlock("world_hub", waypoint.x(), waypoint.y() + 1, waypoint.z()),
                "le waypoint auto-généré du Hub reste protégé");
        assertTrue(service.isProtectedBlock("world_hub", beacon.x(), beacon.y() + 1, beacon.z()),
                "la borne appariée du Hub reste protégée");
    }

    // ---- Régression : routage réel des clics (menu racine -> waypoints / villages) -----------

    /**
     * Clic réel sur un slot du haut du menu actuellement ouvert, routé par le vrai listener (comme
     * sur un serveur Paper) — jamais un appel direct à {@code handleWaypointsClick}/etc., qui
     * masquerait exactement le bug de perte de session rencontré en jeu (le {@code session} utilisé
     * par un appel direct est capturé AVANT la transition, jamais réellement relu par
     * {@code onInventoryClick}).
     */
    private void click(PlayerMock player, int rawSlot) {
        TravelBeaconListener listener = (TravelBeaconListener) service.listener();
        InventoryView view = player.getOpenInventory();
        InventoryClickEvent event = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onInventoryClick(event);
    }

    private BeaconMenuHolder.Kind openMenuKind(PlayerMock player) {
        return player.getOpenInventory().getTopInventory().getHolder() instanceof BeaconMenuHolder holder
                ? holder.kind() : null;
    }

    @Test
    void realClickFromRootThroughWorldsThroughWaypointsOntoADestinationActuallyTravels() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        Waypoint wp = seedWaypoint("wp_forest_click", "minecraft:forest", 400, 65, 400);
        reloadAndAwaitIndexed(wp);
        service.start();
        PlayerMock player = addPlayer();
        discover(player, wp);
        player.teleport(new Location(wild, 0.5, 65, 0.5));

        service.openRoot(player);
        click(player, 2); // "Waypoints découverts" — transition réelle qui écrasait la session
        assertEquals(BeaconMenuHolder.Kind.WORLDS, openMenuKind(player),
                "le choix du monde doit bien s'ouvrir après le clic sur la catégorie");

        click(player, 0); // "Wild" — toujours en première position
        assertEquals(BeaconMenuHolder.Kind.WAYPOINTS, openMenuKind(player),
                "choisir le monde doit réellement ouvrir sa liste de waypoints");

        click(player, 0); // seule destination listée
        assertEquals("wild", player.getLocation().getWorld().getName());
        double distance = Math.hypot(player.getLocation().getX() - wp.x(), player.getLocation().getZ() - wp.z());
        assertTrue(distance < 2.0, "le clic réel sur la destination doit réellement déclencher le voyage");
    }

    @Test
    void realClickOnBackButtonsNavigateWaypointsThenWorldsThenRoot() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        Waypoint wp = seedWaypoint("wp_forest_back", "minecraft:forest", 410, 65, 410);
        reloadAndAwaitIndexed(wp);
        service.start();
        PlayerMock player = addPlayer();
        discover(player, wp);

        service.openRoot(player);
        click(player, 2);
        click(player, 0); // "Wild"
        assertEquals(BeaconMenuHolder.Kind.WAYPOINTS, openMenuKind(player));

        click(player, 45); // "Retour" depuis Waypoints -> choix du monde
        assertEquals(BeaconMenuHolder.Kind.WORLDS, openMenuKind(player),
                "le bouton Retour depuis une liste de waypoints doit ramener au choix du monde");

        click(player, 45); // "Retour" depuis le choix du monde -> racine
        assertEquals(BeaconMenuHolder.Kind.ROOT, openMenuKind(player),
                "le bouton Retour depuis le choix du monde doit ramener au menu racine");
    }

    @Test
    void realClickOnSearchButtonOpensTheAnvilAndResultReopensTheSameWorld() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        Waypoint wp = seedWaypoint("wp_forest_search", "minecraft:forest", 420, 65, 420);
        reloadAndAwaitIndexed(wp);
        service.start();
        PlayerMock player = addPlayer();
        discover(player, wp);

        service.openRoot(player);
        click(player, 2);
        click(player, 0); // "Wild"
        click(player, 47); // "Rechercher"
        assertEquals(BeaconMenuHolder.Kind.SEARCH, openMenuKind(player),
                "le bouton Rechercher doit réellement ouvrir l'enclume de recherche");

        service.handleSearchResultClick(player, null); // équivaut à une saisie vide (aucun filtre)
        assertEquals(BeaconMenuHolder.Kind.WAYPOINTS, openMenuKind(player),
                "le résultat de recherche doit rouvrir la liste de waypoints du MÊME monde, jamais perdu");
        assertEquals(1, countFilledMaps(player));
    }

    @Test
    void realClickOnNextPageShowsTheRemainingDiscoveries() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        java.util.List<Waypoint> seeded = new java.util.ArrayList<>();
        for (int i = 0; i < 46; i++) {
            seeded.add(seedWaypoint("wp_page_" + i, "minecraft:plains", 1000 + i, 65, 0));
        }
        reloadAndAwaitIndexed(seeded.toArray(new Waypoint[0]));
        service.start();
        PlayerMock player = addPlayer();
        for (Waypoint wp : seeded) {
            discover(player, wp);
        }

        service.openRoot(player);
        click(player, 2);
        click(player, 0); // "Wild"
        assertEquals(45, countFilledMaps(player));

        click(player, 50); // page suivante
        assertEquals(BeaconMenuHolder.Kind.WAYPOINTS, openMenuKind(player));
        assertEquals(1, countFilledMaps(player), "le clic réel sur page suivante doit afficher le reste des découvertes");
    }

    @Test
    void realClickOnCloseButtonClosesTheMenuWithoutError() throws Exception {
        waypointService.start();
        service.start();
        PlayerMock player = addPlayer();

        service.openRoot(player);
        click(player, 2); // choix du monde
        click(player, 0); // "Wild" (vide)
        click(player, 53); // Fermer

        var top = player.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof BeaconMenuHolder,
                "le bouton Fermer doit réellement fermer le menu");
    }

    @Test
    void worldSelectionAlwaysOffersHubAndWildEvenWithoutAnyDiscoveryThere() throws Exception {
        waypointService.start();
        service.start();
        PlayerMock player = addPlayer();

        service.openRoot(player);
        click(player, 2);
        assertEquals(BeaconMenuHolder.Kind.WORLDS, openMenuKind(player));
        BeaconMenuSession session = service.sessionOf(player);
        assertEquals(java.util.List.of("wild", "world_hub"), session.worldsShown(),
                "Hub et Wild doivent toujours avoir leur propre page, même à 0 découverte");
    }

    @Test
    void worldSelectionListsAnExtraWorldOnlyWhenAWaypointIsDiscoveredThere() throws Exception {
        server.getPluginManager().registerEvents(waypointService.listener(), plugin);
        waypointService.start();
        World claimsExtra = server.addSimpleWorld("claims_extra");
        Waypoint extra = new Waypoint("wp_claims_extra", "Nom extra", "claims_extra",
                "minecraft:plains@0,0", "minecraft:plains", 0, 0, 5, 65, 5, "NORTH", 1, true, Instant.now());
        waypointRepository.insertIfAbsent(extra).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        reloadAndAwaitIndexed(extra);
        service.start();
        PlayerMock player = addPlayer();
        discover(player, extra, claimsExtra); // monde tiers (extensible), jamais pré-listé en dur

        service.openRoot(player);
        click(player, 2);
        BeaconMenuSession session = service.sessionOf(player);
        assertTrue(session.worldsShown().contains("claims_extra"),
                "un monde tiers doit apparaître dès qu'une découverte y existe (extensible, jamais codé en dur)");
    }

    @Test
    void realClickFromRootThroughVillagesOntoADestinationActuallyTravels() throws Exception {
        waypointService.start();
        service.start();
        service.setVillage("hub_click", "Village du clic", new Location(hub, 20.5, 70, 20.5, 0f, 0f));
        await(() -> service.villages().size() == 1);
        PlayerMock player = addPlayer();

        service.openRoot(player);
        click(player, 6); // "Villages" — même transition root -> catégorie que pour les waypoints
        assertEquals(BeaconMenuHolder.Kind.VILLAGES, openMenuKind(player));

        click(player, 0); // seul village listé
        assertEquals("world_hub", player.getLocation().getWorld().getName());
        assertEquals(20, player.getLocation().getBlockX(),
                "le clic réel sur la destination Villages doit réellement déclencher le voyage");
    }
}
