package com.lodygames.rpgquest.waystone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.TravelConfig;
import com.lodygames.rpgquest.config.TravelConfig.RuneConfig;
import com.lodygames.rpgquest.config.TravelConfig.WaystoneConfig;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.WaystoneRepository;
import com.lodygames.rpgquest.spawn.SpawnService;
import com.lodygames.rpgquest.waystone.model.Waystone;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Mission « Waystones Wild » : couvre {@link WaystoneService} — génération idempotente / unicité par
 * cellule, espacement minimal, surface sûre, persistance, découverte individuelle, retour au Hub.
 */
class WaystoneServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private World wild;
    private WaystoneRepository repository;
    private SpawnService spawnService;
    private WaystoneService service;

    private TravelConfig travelConfig(int minSpacing) {
        return new TravelConfig("wild", new RuneConfig(10, 1800),
                new WaystoneConfig(1000L, 1.0, minSpacing, 16, 1));
    }

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        wild = server.addSimpleWorld("wild");

        database = new DatabaseManager(tempDir.resolve("test.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        repository = new WaystoneRepository(database);

        spawnService = new SpawnService(plugin, tempDir.resolve("spawn.yml"), plugin.getSLF4JLogger());
        spawnService.start();
        World hub = server.addSimpleWorld("world_hub");
        buildPlatform(hub, 0, 0);
        spawnService.set(new Location(hub, 0.5, 65, 0.5));

        service = new WaystoneService(plugin, repository, new WaystoneCellPlanner(),
                new SimpleWaystoneStructurePlacer(), spawnService, () -> travelConfig(300));
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
        database.shutdown();
        MockBukkit.unmock();
    }

    /** Petite plate-forme de pierre pour que RandomSafeLocationFinder trouve une surface sûre. */
    private void buildPlatform(World world, int centerX, int centerZ) {
        for (int x = centerX - 4; x <= centerX + 4; x++) {
            for (int z = centerZ - 4; z <= centerZ + 4; z++) {
                world.getBlockAt(x, 64, z).setType(Material.STONE);
                world.getBlockAt(x, 65, z).setType(Material.AIR);
                world.getBlockAt(x, 66, z).setType(Material.AIR);
            }
        }
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            server.getScheduler().performTicks(1);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertTrue(condition.getAsBoolean(), "condition non atteinte avant le délai");
    }

    private Waystone generateAt(int x, int z) {
        buildPlatform(wild, x, z);
        Optional<Waystone> result = service.generateAt(new Location(wild, x, 65, z)).join();
        await(() -> !service.all().isEmpty() || result.isEmpty());
        return result.orElse(null);
    }

    @Test
    void generatingTwiceInTheSameCellNeverDuplicates() throws Exception {
        Waystone first = generateAt(100, 100);
        assertTrue(first != null, "la première génération doit réussir sur une plate-forme sûre");

        Optional<Waystone> second = service.generateAt(new Location(wild, 120, 65, 120)).join();
        assertTrue(second.isPresent());
        assertEquals(first.id(), second.get().id(), "la cellule ne peut contenir qu'une Waystone");

        assertEquals(1, service.all().size());
        assertEquals(1, repository.loadAll().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size(),
                "une seule ligne persistée : jamais de doublon");
    }

    @Test
    void aFreshServiceReloadsPersistedWaystones() throws Exception {
        Waystone w = generateAt(100, 100);
        assertTrue(w != null);

        WaystoneService reloaded = new WaystoneService(plugin, repository, new WaystoneCellPlanner(),
                new SimpleWaystoneStructurePlacer(), spawnService, () -> travelConfig(300));
        reloaded.start();
        await(() -> !reloaded.all().isEmpty());
        assertEquals(w.id(), reloaded.all().get(0).id(), "les Waystones persistées sont rechargées au démarrage");
        reloaded.stop();
    }

    @Test
    void spacingRejectsPositionsTooCloseToAnExistingWaystone() {
        Waystone w = generateAt(100, 100);
        assertTrue(w != null);

        assertTrue(service.violatesSpacing("wild", w.x() + 100, w.z(), 300), "100 blocs < 300 : refusé");
        assertFalse(service.violatesSpacing("wild", w.x() + 400, w.z(), 300), "400 blocs >= 300 : autorisé");
    }

    @Test
    void theWaystoneIsPlacedOnASafeSolidSurface() {
        buildPlatform(wild, 200, 200);
        Optional<Location> spot = service.findSafeSurface(wild, 200, 200, 16);
        assertTrue(spot.isPresent(), "une surface sûre doit être trouvée sur la plate-forme");
        Location safe = spot.get();
        assertTrue(wild.getBlockAt(safe.getBlockX(), safe.getBlockY() - 1, safe.getBlockZ()).getType().isSolid(),
                "le bloc sous la Waystone doit être solide");
        assertFalse(wild.getBlockAt(safe.getBlockX(), safe.getBlockY(), safe.getBlockZ()).getType().isSolid(),
                "les pieds doivent être dans du vide");
    }

    @Test
    void discoveryIsIndividualPerPlayer() throws Exception {
        Waystone w = generateAt(100, 100);
        assertTrue(w != null);

        PlayerMock a = addPlayer();
        PlayerMock b = addPlayer();
        service.handleJoin(a);
        service.handleJoin(b);

        service.handleClick(a, w);
        await(() -> {
            try {
                return repository.discoveriesFor(a.getUniqueId()).get(2, TimeUnit.SECONDS).contains(w.id());
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(repository.discoveriesFor(a.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).contains(w.id()));
        assertFalse(repository.discoveriesFor(b.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).contains(w.id()),
                "la découverte est propre à chaque joueur");
    }

    @Test
    void resetDiscoveriesClearsThemForThatPlayer() throws Exception {
        Waystone w = generateAt(100, 100);
        PlayerMock a = addPlayer();
        service.handleJoin(a);
        service.handleClick(a, w);
        await(() -> {
            try {
                return repository.discoveriesFor(a.getUniqueId()).get(2, TimeUnit.SECONDS).contains(w.id());
            } catch (Exception e) {
                return false;
            }
        });

        service.resetDiscoveries(a.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(repository.discoveriesFor(a.getUniqueId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void theReturnChannelTeleportsToTheHubAndIsCancelledByMovement() throws Exception {
        PlayerMock player = addPlayer();
        player.teleport(new Location(wild, 100.5, 65, 100.5));
        buildPlatform(wild, 100, 100);

        service.beginReturnChannel(player);
        assertTrue(service.isChanneling(player.getUniqueId()));

        player.teleport(new Location(wild, 120.5, 65, 120.5));
        await(() -> !service.isChanneling(player.getUniqueId()));
        assertNotEquals("world_hub", player.getWorld().getName(), "un déplacement doit annuler le retour");
    }

    private PlayerMock addPlayer() throws Exception {
        PlayerMock player = server.addPlayer();
        new PlayerProfileRepository(database).findOrCreate(player.getUniqueId(), player.getName())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return player;
    }
}
