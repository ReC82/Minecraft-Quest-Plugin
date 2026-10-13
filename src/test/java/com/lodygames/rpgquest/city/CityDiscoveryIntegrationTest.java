package com.lodygames.rpgquest.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.CityConfig;
import com.lodygames.rpgquest.database.CityDiscoveryRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.VillageCenterRepository;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.travel.beacon.model.VillageCenter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #178 — découverte des villes de bout en bout, avec une vraie base SQLite : registre, index,
 * écouteurs (marche, téléportation, connexion) et persistance. Le rendu du titre chez le client
 * reste une validation manuelle ; l'annonce est observée par l'abonnement aux premières découvertes,
 * appelé juste après le titre.
 */
class CityDiscoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private CityRegistry registry;
    private CityDiscoveryRepository discoveries;
    private CityDiscoveryService service;
    private final List<String> announced = new CopyOnWriteArrayList<>();
    private World hub;
    private World wild;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("world_hub");
        wild = server.addSimpleWorld("wild");
        database = new DatabaseManager(tempDir.resolve("cities.db"));
        database.initialize().get(5, TimeUnit.SECONDS);
        registry = new CityRegistry(new VillageCenterRepository(database),
                task -> server.getScheduler().runTask(plugin, task), plugin.getSLF4JLogger());
        await(registry.load());
        discoveries = new CityDiscoveryRepository(database);
        QuestMessagesService messages = new QuestMessagesService(plugin);
        messages.start();
        service = new CityDiscoveryService(plugin, registry, discoveries, messages, CityConfig::defaults);
        service.onFirstDiscovery((player, city) -> announced.add(player.getName() + ":" + city.id()));
        server.getPluginManager().registerEvents(service.listener(), plugin);
        service.start();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        for (int i = 0; i < 200 && !future.isDone(); i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(5);
        }
        return future.get(1, TimeUnit.SECONDS);
    }

    private void settle(BooleanSupplier until) throws Exception {
        for (int i = 0; i < 200 && !until.getAsBoolean(); i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(5);
        }
    }

    /** Laisse passer les allers-retours base → thread principal sans condition précise. */
    private void drain() throws Exception {
        for (int i = 0; i < 30; i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(5);
        }
    }

    private VillageCenter create(String name, World world, double x, double z) throws Exception {
        CityRegistry.Result r = await(registry.create(name, world.getName(), x, 64, z, 0f, 0f));
        assertTrue(r.ok(), r.error());
        return r.city();
    }

    private PlayerMock joinAt(Location at) throws Exception {
        PlayerMock player = server.addPlayer();
        player.teleport(at);
        drain();
        return player;
    }

    private Set<String> stored(PlayerMock player) throws Exception {
        return await(discoveries.discoveredBy(player.getUniqueId()));
    }

    @Test
    void firstEntryOnFootDiscoversOnceAndReentryDoesNot() throws Exception {
        VillageCenter city = create("Bourg-Neuf", hub, 1000.5, 1000.5);
        PlayerMock player = joinAt(new Location(hub, 1100.5, 64, 1000.5));
        assertTrue(announced.isEmpty());

        player.simulatePlayerMove(new Location(hub, 1051.5, 64, 1000.5));
        drain();
        assertTrue(announced.isEmpty(), "51 blocs à l'est : hors de la ville");

        player.simulatePlayerMove(new Location(hub, 1050.5, 64, 1000.5));
        settle(() -> !announced.isEmpty());
        assertEquals(List.of(player.getName() + ":" + city.id()), announced);
        assertEquals(Set.of(city.id()), stored(player));

        player.simulatePlayerMove(new Location(hub, 1060.5, 64, 1000.5));
        player.simulatePlayerMove(new Location(hub, 1040.5, 64, 1000.5));
        player.simulatePlayerMove(new Location(hub, 1000.5, 64, 1000.5));
        drain();
        assertEquals(1, announced.size(), "sortir puis revenir ne redécouvre rien");
    }

    @Test
    void teleportIntoACityDiscoversIt() throws Exception {
        VillageCenter city = create("Port-Lily", hub, -500.5, 200.5);
        PlayerMock player = joinAt(new Location(wild, 0.5, 64, 0.5));
        player.teleport(new Location(hub, -540.5, 64, 245.5)); // coin quasi exact du carré
        settle(() -> !announced.isEmpty());
        assertEquals(List.of(player.getName() + ":" + city.id()), announced);
    }

    @Test
    void joiningWhileAlreadyInsideDiscoversAfterTheRecordIsLoaded() throws Exception {
        PlayerMock player = joinAt(new Location(hub, 300.5, 64, 300.5));
        player.disconnect();
        drain();
        VillageCenter city = create("Hautecour", hub, 300.5, 300.5);
        drain();
        assertTrue(announced.isEmpty(), "joueur déconnecté : rien");

        player.reconnect();
        settle(() -> !announced.isEmpty());
        assertEquals(List.of(player.getName() + ":" + city.id()), announced);
    }

    @Test
    void discoveryPersistsAcrossReconnectionAndRestartAndRename() throws Exception {
        VillageCenter city = create("Vieux-Port", hub, 0.5, 0.5);
        PlayerMock player = joinAt(new Location(hub, 5.5, 64, 5.5));
        settle(() -> !announced.isEmpty());
        assertEquals(1, announced.size());

        // « Redémarrage » : nouveau service, nouvelle mémoire, même base.
        service.stop();
        service.forget(player);
        assertEquals(1, await(registry.rename(city.id(), "Port-Neuf")).ok() ? 1 : 0);
        service.load(player);
        drain();
        player.simulatePlayerMove(new Location(hub, 6.5, 64, 6.5));
        drain();
        assertEquals(1, announced.size(), "ni la reconnexion, ni le redémarrage, ni le renommage");
        assertEquals(city.id(), registry.find(city.id()).orElseThrow().id(), "identifiant stable");
        assertEquals(1, service.discoveredCount(player.getUniqueId(), null));
        assertEquals(1, service.discoveredCount(player.getUniqueId(), city.id()));
    }

    @Test
    void inactiveCityIsNotDiscoveredUntilReactivated() throws Exception {
        VillageCenter city = create("Les Halles", hub, 2000.5, 2000.5);
        await(registry.setActive(city.id(), false));
        PlayerMock player = joinAt(new Location(hub, 2000.5, 64, 2000.5));
        player.simulatePlayerMove(new Location(hub, 2001.5, 64, 2000.5));
        drain();
        assertTrue(announced.isEmpty(), "ville inactive : aucune découverte");

        await(registry.setActive(city.id(), true));
        settle(() -> !announced.isEmpty());
        assertEquals(1, announced.size(), "réactivée sous ses pieds : découverte sans bouger");
    }

    @Test
    void anotherWorldAtTheSameCoordinatesDiscoversNothing() throws Exception {
        create("Hub-Centre", hub, 0.5, 0.5);
        PlayerMock player = joinAt(new Location(wild, 0.5, 64, 0.5));
        player.simulatePlayerMove(new Location(wild, 1.5, 64, 1.5));
        drain();
        assertTrue(announced.isEmpty());
    }

    @Test
    void overlappingCitiesAnnounceOneAtATime() throws Exception {
        VillageCenter big = create("Grande-Ville", hub, 5000.5, 5000.5);
        await(registry.resize(big.id(), 100));
        VillageCenter small = create("Faubourg", hub, 5060.5, 5000.5);
        await(registry.resize(small.id(), 20));
        PlayerMock player = joinAt(new Location(hub, 5300.5, 64, 5000.5));

        player.teleport(new Location(hub, 5055.5, 64, 5000.5)); // dans les deux, plus près du Faubourg
        settle(() -> !announced.isEmpty());
        drain();
        assertEquals(List.of(player.getName() + ":" + small.id()), announced, "un seul titre");

        player.simulatePlayerMove(new Location(hub, 5030.5, 64, 5000.5)); // Grande-Ville seulement
        settle(() -> announced.size() == 2);
        assertEquals(player.getName() + ":" + big.id(), announced.get(1));
    }

    @Test
    void registryValidatesAndDeleteRemovesDiscoveries() throws Exception {
        VillageCenter city = create("Brisval", hub, 0.5, 0.5);
        assertFalse(await(registry.create("brisval", "world_hub", 10, 64, 10, 0f, 0f)).ok(), "nom en double");
        assertFalse(await(registry.resize(city.id(), 4)).ok());
        assertFalse(await(registry.resize(city.id(), 501)).ok());
        assertEquals(25, await(registry.resize(city.id(), 25)).city().detectionDistance());
        assertFalse(await(registry.rename("inconnue", "X")).ok());

        PlayerMock player = joinAt(new Location(hub, 1.5, 64, 1.5));
        settle(() -> !announced.isEmpty());
        Map<String, Integer> counts = await(discoveries.countByCity());
        assertEquals(1, counts.get(city.id()));

        assertTrue(await(registry.delete(city.id())).ok());
        assertTrue(registry.find(city.id()).isEmpty());
        assertTrue(stored(player).isEmpty(), "les découvertes partent avec la ville");
        assertEquals(0, service.discoveredCount(player.getUniqueId(), null), "une ville supprimée ne compte plus");

        // Recréée sous le même nom (donc le même identifiant) : elle se redécouvre sans reconnexion.
        VillageCenter again = create("Brisval", hub, 0.5, 0.5);
        assertEquals(city.id(), again.id());
        settle(() -> announced.size() == 2);
        assertEquals(2, announced.size(), "la mémoire du joueur a oublié la ville supprimée");
    }

    @Test
    void reloadedRegistryKeepsDistanceAndState() throws Exception {
        VillageCenter city = create("Rochefort", hub, 10.5, 10.5);
        await(registry.resize(city.id(), 75));
        await(registry.setActive(city.id(), false));
        CityRegistry reloaded = new CityRegistry(new VillageCenterRepository(database),
                task -> server.getScheduler().runTask(plugin, task), plugin.getSLF4JLogger());
        await(reloaded.load());
        VillageCenter back = reloaded.find(city.id()).orElseThrow();
        assertEquals(75, back.detectionDistance());
        assertFalse(back.active());
        assertEquals("Rochefort", back.name());
        assertEquals(0, reloaded.index().size(), "inactive : hors index");
    }
}
