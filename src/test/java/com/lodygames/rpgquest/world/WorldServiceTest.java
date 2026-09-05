package com.lodygames.rpgquest.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.nio.file.Path;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Couvre la logique de {@link WorldService} : validation du nom, création/chargement idempotent
 * via l'API Bukkit ({@code Server#createWorld(WorldCreator)}, que MockBukkit implémente
 * réellement), et persistance de la petite liste de mondes gérés entre deux instances (redémarrage
 * simulé) — voir docs-site/worlds.html.
 */
class WorldServiceTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private WorldService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        service = newService();
        service.start();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private WorldService newService() {
        return new WorldService(plugin, tempDir.resolve("worlds.yml"), plugin.getSLF4JLogger());
    }

    // ---- Validation du nom --------------------------------------------------

    @Test
    void rejectsBlankName() {
        assertEquals(WorldService.CreateOutcome.INVALID_NAME, service.createOrLoad(""));
    }

    @Test
    void rejectsUppercaseAndSpecialCharacters() {
        assertEquals(WorldService.CreateOutcome.INVALID_NAME, service.createOrLoad("Wild World!"));
        assertEquals(WorldService.CreateOutcome.INVALID_NAME, service.createOrLoad("../evil"));
    }

    @Test
    void acceptsLowercaseDigitsUnderscoreAndDash() {
        assertTrue(WorldService.isValidName("wild-2_test"));
    }

    // ---- Création / chargement -----------------------------------------------

    @Test
    void createsAndLoadsANewWorld() {
        assertEquals(WorldService.CreateOutcome.CREATED, service.createOrLoad("wild"));

        World world = service.find("wild").orElseThrow();
        assertEquals(World.Environment.NORMAL, world.getEnvironment());
        assertTrue(service.isManaged("wild"));
    }

    @Test
    void creatingAnAlreadyLoadedWorldIsIdempotent() {
        service.createOrLoad("wild");

        assertEquals(WorldService.CreateOutcome.ALREADY_LOADED, service.createOrLoad("wild"));
        assertTrue(service.isManaged("wild"), "un monde déjà chargé doit tout de même être mémorisé comme géré");
    }

    @Test
    void findIsEmptyForAnUnknownWorld() {
        assertTrue(service.find("does-not-exist").isEmpty());
        assertFalse(service.isManaged("does-not-exist"));
    }

    @Test
    void loadedWorldsIncludesTheDefaultAndAnyCreatedWorld() {
        server.addSimpleWorld("world");
        service.createOrLoad("wild");

        var names = service.loadedWorlds().stream().map(World::getName).toList();
        assertTrue(names.contains("world"));
        assertTrue(names.contains("wild"));
    }

    // ---- Persistance entre redémarrages ---------------------------------------

    @Test
    void managedWorldSurvivesARestartViaAFreshServiceInstance() {
        service.createOrLoad("wild");

        WorldService restarted = newService();
        restarted.start();

        assertTrue(restarted.isManaged("wild"));
        // Le monde "wild" est resté chargé en mémoire (MockBukkit ne redémarre pas le process) :
        // start() ne doit pas re-déclencher d'erreur en tentant de le recharger.
        assertTrue(restarted.find("wild").isPresent());
    }

    @Test
    void unmanagedWorldIsNotPersisted() {
        server.addSimpleWorld("scratch");

        WorldService restarted = newService();
        restarted.start();

        assertFalse(restarted.isManaged("scratch"), "un monde jamais créé via createOrLoad ne doit pas être mémorisé");
    }
}
