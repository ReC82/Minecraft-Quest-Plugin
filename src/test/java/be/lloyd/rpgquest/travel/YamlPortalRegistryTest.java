package be.lloyd.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.travel.model.PortalDefinition;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class YamlPortalRegistryTest {

    @TempDir
    Path tempDir;

    private YamlPortalRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new YamlPortalRegistry(tempDir.resolve("portals"), NOPLogger.NOP_LOGGER);
        registry.start();
    }

    private PortalDefinition portal(String id, int minX, int minZ, int maxX, int maxZ) {
        return new PortalDefinition(id, "world", minX, 60, minZ, maxX, 63, maxZ,
                null, 3, 5, null, null, null, null, null);
    }

    @Test
    void createPersistsAPortalFileAndReloadsIt() {
        PortalDefinition portal = portal("gate", 0, 0, 2, 2);

        assertEquals(YamlPortalRegistry.CreateOutcome.CREATED, registry.create(portal));
        assertTrue(registry.find("gate").isPresent());

        YamlPortalRegistry second = new YamlPortalRegistry(tempDir.resolve("portals"), NOPLogger.NOP_LOGGER);
        second.reload();
        assertTrue(second.find("gate").isPresent());
    }

    @Test
    void createRejectsDuplicateId() {
        registry.create(portal("gate", 0, 0, 2, 2));
        assertEquals(YamlPortalRegistry.CreateOutcome.DUPLICATE_ID, registry.create(portal("gate", 100, 100, 102, 102)));
    }

    @Test
    void createRejectsOverlapInTheSameWorld() {
        registry.create(portal("gate", 0, 0, 10, 10));
        assertEquals(YamlPortalRegistry.CreateOutcome.OVERLAPS, registry.create(portal("gate2", 5, 5, 15, 15)));
    }

    @Test
    void deleteRemovesThePortalAndItsFile() {
        registry.create(portal("gate", 0, 0, 2, 2));
        assertTrue(registry.delete("gate"));
        assertFalse(registry.find("gate").isPresent());
        assertFalse(registry.delete("gate"), "une seconde suppression ne doit rien trouver");
    }

    @Test
    void setDestinationUpdatesOnlyTheDestinationField() {
        registry.create(portal("gate", 0, 0, 2, 2));

        assertEquals(YamlPortalRegistry.SetDestinationOutcome.UPDATED, registry.setDestination("gate", "village"));

        PortalDefinition updated = registry.find("gate").orElseThrow();
        assertEquals("village", updated.destinationId());
        assertEquals(3, updated.channelSeconds(), "les autres champs ne doivent pas être modifiés");
    }

    @Test
    void setDestinationOnUnknownPortalFails() {
        assertEquals(YamlPortalRegistry.SetDestinationOutcome.PORTAL_NOT_FOUND, registry.setDestination("missing", "village"));
    }

    @Test
    void portalAtFindsTheContainingPortal() {
        registry.create(portal("gate", 0, 0, 10, 10));

        assertTrue(registry.portalAt("world", 5, 61, 5).isPresent());
        assertTrue(registry.portalAt("world", 1000, 61, 1000).isEmpty());
    }

    @Test
    void newlyCreatedPortalHasNoDestinationYet() {
        registry.create(portal("gate", 0, 0, 2, 2));
        assertNull(registry.find("gate").orElseThrow().destinationId());
    }
}
