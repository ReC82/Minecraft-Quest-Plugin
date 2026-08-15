package com.lodygames.rpgquest.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Location;
import org.bukkit.World;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre la persistance du spawn (fichier plat, jamais de coordonnées codées en dur) et son
 * application aux événements de connexion/réapparition — voir {@code docs-site/hub-safe-zone.html}
 * pour la checklist manuelle en jeu.
 */
@SuppressWarnings("removal") // org.spigotmc.event.player.PlayerSpawnLocationEvent — voir SpawnService#handleFirstJoin.
class SpawnServiceTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private SpawnService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        service = newService();
        service.start();
        plugin.getServer().getPluginManager().registerEvents(service.listener(), plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SpawnService newService() {
        return new SpawnService(plugin, tempDir.resolve("spawn.yml"), plugin.getSLF4JLogger());
    }

    // ---- Persistance ------------------------------------------------------

    @Test
    void noSpawnConfiguredYieldsEmptyCurrentAndResolve() {
        assertTrue(service.current().isEmpty());
        assertTrue(service.resolve().isEmpty());
    }

    @Test
    void setPersistsAndResolveReturnsALiveLocation() {
        Location captured = new Location(world, 12.5, 70.0, -8.5, 90.0f, 15.0f);

        assertTrue(service.set(captured));

        SpawnPoint point = service.current().orElseThrow();
        assertEquals("world", point.world());
        assertEquals(12.5, point.x());
        assertEquals(70.0, point.y());
        assertEquals(-8.5, point.z());
        assertEquals(90.0f, point.yaw());
        assertEquals(15.0f, point.pitch());

        Location resolved = service.resolve().orElseThrow();
        assertEquals(world, resolved.getWorld());
        assertEquals(12.5, resolved.getX());
        assertEquals(70.0, resolved.getY());
        assertEquals(-8.5, resolved.getZ());
    }

    @Test
    void setOverwritesAPreviouslyConfiguredSpawn() {
        service.set(new Location(world, 0, 65, 0, 0, 0));
        service.set(new Location(world, 100, 80, -100, 180, 0));

        SpawnPoint point = service.current().orElseThrow();
        assertEquals(100.0, point.x());
        assertEquals(80.0, point.y());
        assertEquals(-100.0, point.z());
    }

    @Test
    void spawnSurvivesARestartViaAFreshServiceInstanceOnTheSameFile() {
        service.set(new Location(world, 5.5, 66.0, 5.5, 45.0f, 0.0f));

        SpawnService restarted = newService();
        restarted.start();

        SpawnPoint point = restarted.current().orElseThrow();
        assertEquals(5.5, point.x());
        assertEquals(66.0, point.y());
        assertEquals(45.0f, point.yaw());
    }

    @Test
    void resolveIsEmptyWhenTheConfiguredWorldIsNotLoaded() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("ghost.yml"),
                "world: ghost_world\nx: 0.0\ny: 65.0\nz: 0.0\nyaw: 0.0\npitch: 0.0\n");

        SpawnService ghost = new SpawnService(plugin, tempDir.resolve("ghost.yml"), plugin.getSLF4JLogger());
        ghost.start();

        assertTrue(ghost.current().isPresent(), "le fichier est valide, la position doit être chargée en mémoire");
        assertTrue(ghost.resolve().isEmpty(), "mais aucun monde « ghost_world » n'existe sur ce serveur");
    }

    @Test
    void invalidSpawnFileIsIgnoredRatherThanCrashing() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("broken.yml"), "not: a valid spawn\n");

        SpawnService broken = new SpawnService(plugin, tempDir.resolve("broken.yml"), plugin.getSLF4JLogger());
        broken.start();

        assertFalse(broken.current().isPresent());
    }

    // ---- Connexion (nouveau joueur) ---------------------------------------

    @Test
    void firstJoinSetsSpawnLocationWhenOneIsConfigured() {
        service.set(new Location(world, 3.5, 70.0, 3.5, 180.0f, 0.0f));

        // server.addPlayer() simule la séquence de connexion complète, PlayerSpawnLocationEvent
        // inclus, pour un joueur tout juste créé (hasPlayedBefore() == false).
        PlayerMock player = server.addPlayer();

        assertEquals(world, player.getLocation().getWorld());
        assertEquals(3.5, player.getLocation().getX());
        assertEquals(70.0, player.getLocation().getY());
        assertEquals(3.5, player.getLocation().getZ());
    }

    @Test
    void firstJoinLeavesSpawnLocationUnchangedWhenNoneIsConfigured() {
        PlayerMock player = server.addPlayer();
        Location expected = new Location(world, 500, 65, 500);
        PlayerSpawnLocationEvent event = new PlayerSpawnLocationEvent(player, expected);

        server.getPluginManager().callEvent(event);

        assertEquals(expected, event.getSpawnLocation(), "sans spawn configuré, la position d'origine n'est jamais modifiée");
    }

    @Test
    void returningPlayerSpawnLocationIsNeverOverridden() {
        service.set(new Location(world, 3.5, 70.0, 3.5, 180.0f, 0.0f));
        PlayerMock player = server.addPlayer(); // consomme la « première connexion »
        server.getPlayerList().setFirstPlayed(player.getUniqueId(), 1L); // simule une venue précédente

        Location elsewhere = new Location(world, 500, 65, 500);
        PlayerSpawnLocationEvent event = new PlayerSpawnLocationEvent(player, elsewhere);
        server.getPluginManager().callEvent(event);

        assertEquals(elsewhere, event.getSpawnLocation(), "une reconnexion normale ne doit jamais être redirigée vers le spawn");
    }

    // ---- Réapparition après la mort ----------------------------------------

    @Test
    void respawnAlwaysOverridesToTheConfiguredSpawn() {
        Location spawn = new Location(world, 8.5, 72.0, -4.5, 270.0f, 0.0f);
        service.set(spawn);

        PlayerMock player = server.addPlayer();
        player.setBedSpawnLocation(new Location(world, 999, 65, 999), true); // même avec un lit ailleurs...
        player.setHealth(0.0); // déclenche la mort

        var respawnEvent = player.respawn();

        assertEquals(world, respawnEvent.getRespawnLocation().getWorld());
        assertEquals(8.5, respawnEvent.getRespawnLocation().getX());
        assertEquals(72.0, respawnEvent.getRespawnLocation().getY());
        assertEquals(-4.5, respawnEvent.getRespawnLocation().getZ());
    }

    @Test
    void respawnFallsBackToVanillaBehaviourWhenNoSpawnIsConfigured() {
        PlayerMock player = server.addPlayer();
        player.setBedSpawnLocation(new Location(world, 999, 65, 999), true);
        player.setHealth(0.0);

        var respawnEvent = player.respawn();

        assertEquals(999.0, respawnEvent.getRespawnLocation().getX(),
                "sans spawn configuré, le comportement vanilla (lit) n'est pas altéré");
    }
}
