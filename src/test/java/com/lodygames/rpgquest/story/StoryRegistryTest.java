package com.lodygames.rpgquest.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class StoryRegistryTest {

    @TempDir
    Path tempDir;

    private Path storiesDirectory;
    private StoryRegistry registry;

    @BeforeEach
    void setUp() {
        storiesDirectory = tempDir.resolve("stories");
        registry = new StoryRegistry(storiesDirectory, NOPLogger.NOP_LOGGER);
    }

    @Test
    void startGeneratesTheBundledMainStoryExampleOnAFreshInstall() {
        registry.start();

        assertTrue(Files.exists(storiesDirectory.resolve("main_story.yml")));
        assertTrue(registry.find("main_story").isPresent(), "l'exemple embarqué doit être chargé");
        assertEquals(3, registry.find("main_story").orElseThrow().questIds().size());
    }

    @Test
    void startNeverOverwritesAnAlreadyCustomizedExample() throws Exception {
        Files.createDirectories(storiesDirectory);
        Files.writeString(storiesDirectory.resolve("main_story.yml"), """
                id: main_story
                name: "Personnalisée par l'admin"
                quests:
                  - premiers_pas
                """);

        registry.start();

        assertEquals("Personnalisée par l'admin", registry.find("main_story").orElseThrow().name().base());
    }

    @Test
    void reloadPicksUpManuallyAddedStoryFiles() throws Exception {
        registry.start();
        Files.writeString(storiesDirectory.resolve("side_story.yml"), """
                id: side_story
                name: "Histoire secondaire"
                quests:
                  - woodcutters_request
                """);

        StoryLoadReport report = registry.reload();

        assertEquals(0, report.issues().size());
        assertTrue(registry.find("side_story").isPresent());
        assertEquals(2, registry.stories().size(), "main_story (exemple) + side_story");
    }

    @Test
    void unknownStoryIdIsAbsent() {
        registry.start();

        assertTrue(registry.find("does_not_exist").isEmpty());
    }
}
