package com.lodygames.rpgquest.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #13 — format des modèles de PNJ récurrents ({@code encounters/*.yml}). */
class EncounterTemplateParserTest {

    private final EncounterTemplateParser parser = new EncounterTemplateParser();

    @Test
    void aMinimalTemplateGetsSafeDefaults() {
        var result = parser.parse("a.yml", yaml("""
                id: forest_bandit
                display_name: "Brigand des bois"
                biomes: [forest, minecraft:birch_forest]
                """));

        assertTrue(result.isSuccess(), () -> result.issues().toString());
        EncounterTemplate t = result.template();
        assertEquals("forest_bandit", t.npcId(), "npc_id vaut l'id du modèle par défaut");
        assertEquals(Set.of("minecraft:forest", "minecraft:birch_forest"), t.biomes(),
                "un biome sans espace de noms est lu comme minecraft:…");
        assertEquals(EncounterTemplateParser.DEFAULT_WEIGHT, t.weight());
        assertEquals(0, t.maxSites());
        assertTrue(t.worlds().isEmpty());
        assertTrue(t.enabled());
        assertNull(t.skinUrl());
    }

    @Test
    void everyFieldIsRead() {
        var result = parser.parse("a.yml", yaml("""
                id: desert_merchant
                display_name: "Marchand égaré"
                description: "Perdu"
                npc_id: encounter_merchant
                role: marchand
                family: desert
                biomes: [minecraft:desert]
                worlds: [wild, wild2, wild]
                weight: 3
                max_sites: 5
                enabled: false
                """));

        assertTrue(result.isSuccess(), () -> result.issues().toString());
        EncounterTemplate t = result.template();
        assertEquals("encounter_merchant", t.npcId());
        assertEquals("marchand", t.role());
        assertEquals("desert", t.family());
        assertEquals(List.of("wild", "wild2"), t.worlds(), "doublons de monde ignorés");
        assertEquals(3, t.weight());
        assertEquals(5, t.maxSites());
        assertFalse(t.enabled());
        assertTrue(t.allowsWorld("wild2"));
        assertFalse(t.allowsWorld("hub"));
    }

    @Test
    void allErrorsAreReportedTogether() {
        var result = parser.parse("bad.yml", yaml("""
                id: "Bad Id"
                display_name: "<red>Brigand</red>"
                biomes: ["not a biome"]
                weight: 0
                max_sites: -1
                skin_url: "https://example.com/skin.png"
                enabled: "oui"
                """));

        assertFalse(result.isSuccess());
        assertEquals(7, result.issues().size(), () -> result.issues().toString());
        assertTrue(result.issues().stream().allMatch(i -> i.file().equals("bad.yml")));
    }

    @Test
    void biomesAreMandatory() {
        var result = parser.parse("a.yml", yaml("""
                id: x
                display_name: "X"
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().get(0).message().contains("biomes"));
    }

    @Test
    void duplicateIdsAcrossFilesAreRejected() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        files.put("a.yml", yaml("id: same\ndisplay_name: A\nbiomes: [forest]\n"));
        files.put("b.yml", yaml("id: same\ndisplay_name: B\nbiomes: [desert]\n"));
        files.put("c.yml", yaml("id: other\ndisplay_name: C\nbiomes: [desert]\n"));

        EncounterLoadReport report = new EncounterTemplateLoader().load(files);

        assertEquals(1, report.loaded().size());
        assertEquals("other", report.loaded().get(0).id());
        assertEquals(2, report.issues().size());
    }

    @Test
    void theBundledTemplatesAreValid() {
        Map<String, ConfigurationSection> files = new LinkedHashMap<>();
        for (String name : YamlEncounterEngine.BUNDLED_EXAMPLES) {
            files.put(name, resource("/encounters/" + name));
        }

        EncounterLoadReport report = new EncounterTemplateLoader().load(files);

        assertTrue(report.issues().isEmpty(), () -> report.issues().toString());
        assertEquals(Set.of("encounter_bandit", "encounter_merchant", "encounter_smith"),
                Set.copyOf(report.loaded().stream().map(EncounterTemplate::npcId).toList()));
    }

    private static ConfigurationSection yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return yaml;
    }

    private static ConfigurationSection resource(String path) {
        InputStream in = EncounterTemplateParserTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("ressource absente : " + path);
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
