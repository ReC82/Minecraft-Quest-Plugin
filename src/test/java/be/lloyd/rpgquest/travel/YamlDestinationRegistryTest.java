package be.lloyd.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.travel.model.Destination;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class YamlDestinationRegistryTest {

    @TempDir
    Path tempDir;

    private YamlDestinationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new YamlDestinationRegistry(tempDir.resolve("destinations"), NOPLogger.NOP_LOGGER);
    }

    @Test
    void startWithNoFilesLoadsNothingWithoutError() {
        registry.start();
        assertEquals(0, registry.destinations().size());
        assertEquals(0, registry.lastReport().issues().size());
    }

    @Test
    void createOrUpdatePersistsAndReloadsInAFreshRegistry() {
        registry.start();
        Destination destination = new Destination("village", "world", 0.5, 65.0, 0.5, 90.0f, 0.0f);

        assertTrue(registry.createOrUpdate(destination));
        assertTrue(registry.find("village").isPresent());

        YamlDestinationRegistry second = new YamlDestinationRegistry(tempDir.resolve("destinations"), NOPLogger.NOP_LOGGER);
        second.reload();
        assertTrue(second.find("village").isPresent());
        assertEquals(0.5, second.find("village").get().x());
    }

    @Test
    void createOrUpdateOverwritesAnExistingDestination() {
        registry.start();
        registry.createOrUpdate(new Destination("village", "world", 0, 65, 0, 0, 0));
        registry.createOrUpdate(new Destination("village", "world", 10, 70, 10, 45, 0));

        Destination updated = registry.find("village").orElseThrow();
        assertEquals(10.0, updated.x());
        assertEquals(70.0, updated.y());
    }
}
