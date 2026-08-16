package com.lodygames.rpgquest.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class StoryDefinitionParserTest {

    private final StoryDefinitionParser parser = new StoryDefinitionParser();

    @Test
    void aValidStoryIsParsedWithDefaultNamespaceAppliedToBareQuestIds() {
        ConfigurationSection section = load("""
                id: main_story
                name: "Histoire principale"
                quests:
                  - premiers_pas
                  - rpgquest:first_steps
                """);

        StoryDefinitionParser.ParseResult result = parser.parse("main_story.yml", section);

        assertTrue(result.isSuccess());
        assertEquals("main_story", result.story().id());
        assertEquals("Histoire principale", result.story().name().base());
        assertEquals(
                java.util.List.of(new NamespacedKey("rpgquest", "premiers_pas"), new NamespacedKey("rpgquest", "first_steps")),
                result.story().questIds());
    }

    @Test
    void missingIdIsRejected() {
        ConfigurationSection section = load("""
                name: "Histoire principale"
                quests:
                  - premiers_pas
                """);

        StoryDefinitionParser.ParseResult result = parser.parse("broken.yml", section);

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("id")));
    }

    @Test
    void missingNameIsRejected() {
        ConfigurationSection section = load("""
                id: main_story
                quests:
                  - premiers_pas
                """);

        StoryDefinitionParser.ParseResult result = parser.parse("broken.yml", section);

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("name")));
    }

    @Test
    void missingQuestsListIsRejected() {
        ConfigurationSection section = load("""
                id: main_story
                name: "Histoire principale"
                """);

        StoryDefinitionParser.ParseResult result = parser.parse("broken.yml", section);

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("quests")));
    }

    @Test
    void emptyQuestsListIsRejected() {
        ConfigurationSection section = load("""
                id: main_story
                name: "Histoire principale"
                quests: []
                """);

        StoryDefinitionParser.ParseResult result = parser.parse("broken.yml", section);

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("quests")));
    }

    @Test
    void invalidIdFormatIsRejected() {
        ConfigurationSection section = load("""
                id: "Main Story!"
                name: "Histoire principale"
                quests:
                  - premiers_pas
                """);

        StoryDefinitionParser.ParseResult result = parser.parse("broken.yml", section);

        assertFalse(result.isSuccess());
    }

    private ConfigurationSection load(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(new StringReader(yaml));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return configuration;
    }
}
