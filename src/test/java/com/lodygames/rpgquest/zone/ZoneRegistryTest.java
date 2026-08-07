package com.lodygames.rpgquest.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class ZoneRegistryTest {

    @TempDir
    Path tempDir;

    private ZoneRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ZoneRegistry(tempDir.resolve("zones"), NOPLogger.NOP_LOGGER);
    }

    @Test
    void startGeneratesTheBundledCentralVillageExample() {
        registry.start();

        assertEquals(0, registry.lastReport().issues().size(), () -> "issues: " + registry.lastReport().issues());
        assertTrue(registry.find("central_village").isPresent());
    }

    @Test
    void createPersistsAZoneFileAndReloadsIt() {
        registry.start();
        ZoneDefinition zone = new ZoneDefinition("shop", "world", 200, 0, 200, 220, 255, 220, ZoneFlags.defaults());

        assertEquals(ZoneRegistry.CreateOutcome.CREATED, registry.create(zone));
        assertTrue(registry.find("shop").isPresent());

        // Un second registre pointant sur le même dossier doit retrouver la zone créée : preuve
        // qu'elle a bien été écrite sur disque, pas seulement gardée en mémoire.
        ZoneRegistry secondRegistry = new ZoneRegistry(tempDir.resolve("zones"), NOPLogger.NOP_LOGGER);
        secondRegistry.reload();
        assertTrue(secondRegistry.find("shop").isPresent());
    }

    @Test
    void createRejectsDuplicateId() {
        registry.start();
        ZoneDefinition first = new ZoneDefinition("shop", "world", 200, 0, 200, 220, 255, 220, ZoneFlags.defaults());
        ZoneDefinition duplicate = new ZoneDefinition("shop", "world", 300, 0, 300, 320, 255, 320, ZoneFlags.defaults());

        assertEquals(ZoneRegistry.CreateOutcome.CREATED, registry.create(first));
        assertEquals(ZoneRegistry.CreateOutcome.DUPLICATE_ID, registry.create(duplicate));
    }

    @Test
    void createRejectsOverlapWithAnExistingZoneInTheSameWorld() {
        registry.start(); // central_village occupe déjà -48..48 sur X/Z dans "world"
        ZoneDefinition overlapping = new ZoneDefinition("inner", "world", 0, 0, 0, 10, 255, 10, ZoneFlags.defaults());

        assertEquals(ZoneRegistry.CreateOutcome.OVERLAPS, registry.create(overlapping));
    }

    @Test
    void createAllowsOverlapInADifferentWorld() {
        registry.start();
        ZoneDefinition sameCoordsDifferentWorld =
                new ZoneDefinition("nether_hub", "world_nether", 0, 0, 0, 10, 255, 10, ZoneFlags.defaults());

        assertEquals(ZoneRegistry.CreateOutcome.CREATED, registry.create(sameCoordsDifferentWorld));
    }

    @Test
    void deleteRemovesTheZoneAndItsFile() {
        registry.start();
        ZoneDefinition zone = new ZoneDefinition("shop", "world", 200, 0, 200, 220, 255, 220, ZoneFlags.defaults());
        registry.create(zone);

        assertTrue(registry.delete("shop"));
        assertFalse(registry.find("shop").isPresent());
        assertFalse(registry.delete("shop"), "une seconde suppression ne doit rien trouver");
    }

    @Test
    void zoneAtReturnsEmptyForAWorldWithNoZones() {
        registry.start();
        assertTrue(registry.zoneAt("some_missing_world", 0, 64, 0).isEmpty());
    }

    @Test
    void zoneAtFindsTheContainingZone() {
        registry.start();
        assertTrue(registry.zoneAt("world", 0, 64, 0).isPresent());
        assertTrue(registry.zoneAt("world", 1000, 64, 1000).isEmpty());
    }

    @Test
    void reloadPicksUpManuallyAddedFiles() throws Exception {
        registry.start();
        assertEquals(1, registry.zones().size());

        java.nio.file.Files.writeString(tempDir.resolve("zones/extra.yml"), """
                id: extra
                world: world_the_end
                min: {x: 0, y: 0, z: 0}
                max: {x: 10, y: 10, z: 10}
                """);

        registry.reload();
        assertEquals(2, registry.zones().size());
        assertTrue(registry.find("extra").isPresent());
    }
}
