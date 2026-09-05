package com.lodygames.rpgquest.story;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class StoryLoaderTest {

    private final StoryLoader loader = new StoryLoader();

    @Test
    void duplicateIdAcrossFilesRejectsBoth() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", story("duplicate"));
        files.put("b.yml", story("duplicate"));

        StoryLoadReport report = loader.load(files);

        assertEquals(0, report.loaded().size());
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream().allMatch(i -> i.message().contains("dupliqué")));
    }

    @Test
    void distinctIdsAreBothLoaded() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", story("first"));
        files.put("b.yml", story("second"));

        StoryLoadReport report = loader.load(files);

        assertEquals(2, report.loaded().size());
        assertEquals(0, report.issues().size());
    }

    @Test
    void anInvalidFileIsReportedWithoutBlockingOthers() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("broken.yml", load("id: broken\nname: \"Broken\"\n"));
        files.put("ok.yml", story("ok"));

        StoryLoadReport report = loader.load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("ok", report.loaded().get(0).id());
        assertEquals(1, report.issues().size());
    }

    private ConfigurationSection story(String id) {
        return load("""
                id: %s
                name: "Story %s"
                quests:
                  - premiers_pas
                """.formatted(id, id));
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
