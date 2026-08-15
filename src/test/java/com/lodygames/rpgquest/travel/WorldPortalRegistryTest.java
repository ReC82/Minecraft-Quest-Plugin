package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.model.DestinationStrategy;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Couvre la persistance des portails simples (voir docs-site/worlds.html, section « Portail Hub →
 * wild »), même conception que {@code zone.ZoneRegistryTest}.
 */
class WorldPortalRegistryTest {

    @TempDir
    Path tempDir;

    private WorldPortalRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        registry.start();
    }

    private WorldPortalDefinition portal(String id, String world, String destinationWorld) {
        return new WorldPortalDefinition(id, world, -5, 60, -5, 5, 70, 5, destinationWorld, true);
    }

    @Test
    void startsEmptyWithNoPortalsOnDisk() {
        assertTrue(registry.portals().isEmpty());
        assertTrue(registry.find("hub_to_wild").isEmpty());
    }

    @Test
    void createPersistsAndReloadsThePortal() {
        assertEquals(WorldPortalRegistry.CreateOutcome.CREATED, registry.create(portal("hub_to_wild", "world", "wild")));

        WorldPortalDefinition found = registry.find("hub_to_wild").orElseThrow();
        assertEquals("world", found.world());
        assertEquals("wild", found.destinationWorld());
        assertTrue(found.enabled());
        assertEquals(DestinationStrategy.WORLD_SPAWN, found.destinationStrategy(), "stratégie par défaut sans argument explicite");
    }

    @Test
    void randomSafeStrategySurvivesARestartViaAFreshRegistryInstance() {
        WorldPortalDefinition randomPortal = new WorldPortalDefinition(
                "hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild", true, DestinationStrategy.RANDOM_SAFE);
        registry.create(randomPortal);

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();

        assertEquals(DestinationStrategy.RANDOM_SAFE, restarted.find("hub_to_wild").orElseThrow().destinationStrategy());
    }

    @Test
    void portalSurvivesARestartViaAFreshRegistryInstanceOnTheSameDirectory() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();

        WorldPortalDefinition found = restarted.find("hub_to_wild").orElseThrow();
        assertEquals("wild", found.destinationWorld());
    }

    @Test
    void duplicateIdIsRejected() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertEquals(WorldPortalRegistry.CreateOutcome.DUPLICATE_ID,
                registry.create(portal("hub_to_wild", "world", "wild")));
    }

    @Test
    void overlappingPortalInTheSameWorldIsRejected() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        WorldPortalDefinition overlapping = new WorldPortalDefinition(
                "hub_to_other", "world", 0, 60, 0, 10, 70, 10, "other", true);

        assertEquals(WorldPortalRegistry.CreateOutcome.OVERLAPS, registry.create(overlapping));
    }

    @Test
    void nonOverlappingPortalInAnotherWorldIsAccepted() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertEquals(WorldPortalRegistry.CreateOutcome.CREATED,
                registry.create(portal("elsewhere_to_wild", "elsewhere", "wild")));
    }

    @Test
    void portalAtFindsThePortalOnlyInTheCorrectWorldAndBounds() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertTrue(registry.portalAt("world", 0, 65, 0).isPresent());
        assertTrue(registry.portalAt("world", -5, 60, -5).isPresent(), "borne min incluse");
        assertTrue(registry.portalAt("world", 5, 70, 5).isPresent(), "borne max incluse");
        assertFalse(registry.portalAt("world", 100, 65, 100).isPresent(), "hors bornes");
        assertFalse(registry.portalAt("wild", 0, 65, 0).isPresent(), "mêmes coordonnées, mauvais monde");
    }

    // ---- enable / disable ------------------------------------------------------------------------

    @Test
    void disableTurnsEnabledOffAndPersistsIt() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UPDATED, registry.setEnabled("hub_to_wild", false));
        assertFalse(registry.find("hub_to_wild").orElseThrow().enabled());

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();
        assertFalse(restarted.find("hub_to_wild").orElseThrow().enabled(), "désactivation persistée après redémarrage");
    }

    @Test
    void enableTurnsEnabledBackOnAndPersistsIt() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        registry.setEnabled("hub_to_wild", false);

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UPDATED, registry.setEnabled("hub_to_wild", true));
        assertTrue(registry.find("hub_to_wild").orElseThrow().enabled());
    }

    @Test
    void disablingAnAlreadyDisabledPortalIsReportedAsUnchanged() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        registry.setEnabled("hub_to_wild", false);

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UNCHANGED, registry.setEnabled("hub_to_wild", false));
    }

    @Test
    void enablingAnAlreadyEnabledPortalIsReportedAsUnchanged() {
        registry.create(portal("hub_to_wild", "world", "wild")); // enabled=true dès la création

        assertEquals(WorldPortalRegistry.SetEnabledOutcome.UNCHANGED, registry.setEnabled("hub_to_wild", true));
    }

    @Test
    void setEnabledOnAnUnknownPortalIsReportedAsNotFound() {
        assertEquals(WorldPortalRegistry.SetEnabledOutcome.NOT_FOUND, registry.setEnabled("does_not_exist", false));
    }

    @Test
    void setEnabledKeepsTheRestOfTheConfigurationUnchanged() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        registry.setEnabled("hub_to_wild", false);

        WorldPortalDefinition found = registry.find("hub_to_wild").orElseThrow();
        assertEquals("world", found.world());
        assertEquals("wild", found.destinationWorld());
        assertEquals(-5, found.minX());
        assertEquals(5, found.maxX());
    }

    // ---- delete ------------------------------------------------------------------------------------

    @Test
    void deleteRemovesThePortalFromMemoryAndFromDisk() {
        registry.create(portal("hub_to_wild", "world", "wild"));

        assertTrue(registry.delete("hub_to_wild"));
        assertTrue(registry.find("hub_to_wild").isEmpty());

        WorldPortalRegistry restarted = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        restarted.start();
        assertTrue(restarted.find("hub_to_wild").isEmpty(), "suppression persistée après redémarrage");
    }

    @Test
    void deletingAnUnknownPortalReturnsFalse() {
        assertFalse(registry.delete("does_not_exist"));
    }

    @Test
    void deleteNeverAffectsOtherPortals() {
        registry.create(portal("hub_to_wild", "world", "wild"));
        registry.create(portal("elsewhere_to_wild", "elsewhere", "wild"));

        registry.delete("hub_to_wild");

        assertTrue(registry.find("hub_to_wild").isEmpty());
        assertTrue(registry.find("elsewhere_to_wild").isPresent(), "un autre portail ne doit jamais être affecté");
    }

    @Test
    void corruptFileIsIgnoredRatherThanCrashingReload() throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve("world-portals"));
        java.nio.file.Files.writeString(tempDir.resolve("world-portals/broken.yml"), "not: a valid portal\n");

        WorldPortalRegistry broken = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(broken::start);
        assertTrue(broken.portals().isEmpty());
    }
}
