package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.RandomSafeArrivalConfig;
import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import com.lodygames.rpgquest.world.WorldService;
import java.nio.file.Path;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Couvre la détection d'entrée et la téléportation du premier portail simple Hub → wild — voir
 * docs-site/worlds.html, section « Portail Hub → wild ».
 */
class WorldPortalTeleportListenerTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World wild;
    private WorldService worldService;
    private WorldPortalRegistry registry;
    private RandomSafeArrivalConfig randomSafeArrivalConfig;
    private WorldPortalTeleportListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("world");
        wild = server.addSimpleWorld("wild");

        worldService = new WorldService(plugin, tempDir.resolve("worlds.yml"), plugin.getSLF4JLogger());
        worldService.start();

        registry = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        registry.start();
        registry.create(new WorldPortalDefinition("hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild", true));

        randomSafeArrivalConfig = new RandomSafeArrivalConfig(500, 5000, 20);
        listener = new WorldPortalTeleportListener(registry, worldService, () -> randomSafeArrivalConfig, plugin.getSLF4JLogger());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- Stratégie RANDOM_SAFE ------------------------------------------------------------------

    @Test
    void randomSafePortalTeleportsWithinTheConfiguredRadiusOfTheDestinationSpawn() throws Exception {
        registry.create(new WorldPortalDefinition("hub_to_wild_random", "world", 100, 60, 100, 110, 70, 110,
                "wild", true, DestinationStrategy.RANDOM_SAFE));
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location to = new Location(hub, 105.5, 65, 105.5);

        listener.onMove(new PlayerMoveEvent(player, from, to));

        assertEquals(wild, player.getWorld());
        double distance = wild.getSpawnLocation().distance(player.getLocation());
        assertTrue(distance >= randomSafeArrivalConfig.minRadius() - 1
                        && distance <= randomSafeArrivalConfig.maxRadius() + 1,
                "distance " + distance + " hors de la plage configurée");
        String message = player.nextMessage();
        assertTrue(message != null && message.contains("Téléportation réussie"));
    }

    @Test
    void randomSafePortalFallsBackToWorldSpawnWhenNoSafePositionIsFound() throws Exception {
        // Bordure du monde bien plus petite que le rayon minimum configuré : aucun candidat n'est
        // jamais valide, quel que soit le tirage aléatoire réel.
        wild.getWorldBorder().setCenter(wild.getSpawnLocation());
        wild.getWorldBorder().setSize(10);
        registry.create(new WorldPortalDefinition("hub_to_wild_random", "world", 100, 60, 100, 110, 70, 110,
                "wild", true, DestinationStrategy.RANDOM_SAFE));
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location to = new Location(hub, 105.5, 65, 105.5);

        listener.onMove(new PlayerMoveEvent(player, from, to));

        assertEquals(wild, player.getWorld());
        assertEquals(wild.getSpawnLocation().getBlockX(), player.getLocation().getBlockX());
        assertEquals(wild.getSpawnLocation().getBlockZ(), player.getLocation().getBlockZ());
        String message = player.nextMessage();
        assertTrue(message != null && message.contains("Téléportation réussie"), "le repli reste une téléportation propre, pas une erreur");
    }

    @Test
    void disabledPortalNeverTriggersTeleportation() throws Exception {
        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UPDATED, registry.setEnabled("hub_to_wild", false));
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location to = new Location(hub, 0.5, 65, 0.5); // à l'intérieur des bornes, mais portail désactivé

        listener.onMove(new PlayerMoveEvent(player, from, to));

        assertEquals(hub, player.getWorld(), "portail désactivé : aucune téléportation");
        assertNull(player.nextMessage());
    }

    @Test
    void reEnablingAPortalMakesItTriggerAgain() throws Exception {
        registry.setEnabled("hub_to_wild", false);
        registry.setEnabled("hub_to_wild", true);
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location to = new Location(hub, 0.5, 65, 0.5);

        listener.onMove(new PlayerMoveEvent(player, from, to));

        assertEquals(wild, player.getWorld(), "réactivé : le portail doit à nouveau se déclencher");
    }

    @Test
    void playerOutsideThePortalIsNeverTeleported() throws Exception {
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location to = new Location(hub, 1001.5, 64, 1000.5);

        listener.onMove(new PlayerMoveEvent(player, from, to));

        assertNull(player.nextMessage(), "aucun message : le joueur n'a jamais touché le portail");
    }

    @Test
    void enteringThePortalTeleportsToTheDestinationWorldSpawn() throws Exception {
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location to = new Location(hub, 0.5, 65, 0.5); // à l'intérieur des bornes -5..5 / 60..70

        listener.onMove(new PlayerMoveEvent(player, from, to));

        assertEquals(wild, player.getWorld());
        assertEquals(wild.getSpawnLocation().getBlockX(), player.getLocation().getBlockX());
        assertEquals(wild.getSpawnLocation().getBlockZ(), player.getLocation().getBlockZ());
        String message = player.nextMessage();
        assertTrue(message != null && message.contains("Téléportation réussie"));
    }

    @Test
    void sameCoordinatesInAnotherWorldNeverTrigger() throws Exception {
        PlayerMock player = server.addPlayer();
        // Mêmes coordonnées que la zone du portail, mais dans "wild" (la destination), pas "world".
        Location from = new Location(wild, 1000.5, 64, 1000.5);
        Location to = new Location(wild, 0.5, 65, 0.5);

        listener.onMove(new PlayerMoveEvent(player, from, to));

        assertNull(player.nextMessage(), "mêmes coordonnées mais mauvais monde : jamais déclenché");
    }

    @Test
    void unloadedDestinationWorldDoesNotCrashAndDoesNotTeleport() throws Exception {
        registry.create(new WorldPortalDefinition("hub_to_ghost", "world", 100, 60, 100, 110, 70, 110, "does_not_exist", true));
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location to = new Location(hub, 105.5, 65, 105.5);

        assertDoesNotThrow(() -> listener.onMove(new PlayerMoveEvent(player, from, to)));

        assertEquals(hub, player.getWorld(), "aucune téléportation : le monde de destination n'est pas chargé");
        String message = player.nextMessage();
        assertTrue(message != null && message.contains("n'est pas chargé"));
    }

    @Test
    void stayingInsideThePortalNeverTriggersASecondTeleport() throws Exception {
        PlayerMock player = server.addPlayer();
        Location from = new Location(hub, 1000.5, 64, 1000.5);
        Location firstInside = new Location(hub, 0.5, 65, 0.5);
        Location stillInside = new Location(hub, 1.5, 65, 0.5); // bloc différent, toujours dans les bornes

        listener.onMove(new PlayerMoveEvent(player, from, firstInside));
        assertTrue(player.nextMessage() != null, "première entrée : un message de téléportation");

        // Deuxième événement construit avec les mêmes coordonnées "world" que la première entrée
        // (le joueur réel est déjà dans "wild" après la téléportation ci-dessus, mais on vérifie ici
        // uniquement l'anti-boucle du listener lui-même : même portail détecté deux fois de suite).
        listener.onMove(new PlayerMoveEvent(player, firstInside, stillInside));

        assertNull(player.nextMessage(), "même portail que le mouvement précédent : aucune nouvelle tentative");
    }
}
