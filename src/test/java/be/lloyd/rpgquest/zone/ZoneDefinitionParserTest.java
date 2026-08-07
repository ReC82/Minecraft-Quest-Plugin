package be.lloyd.rpgquest.zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.zone.model.ZoneDefinition;
import java.io.StringReader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ZoneDefinitionParserTest {

    private final ZoneDefinitionParser parser = new ZoneDefinitionParser();

    @Test
    void validFileParsesSuccessfully() {
        ZoneDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: central_village
                world: world
                min: {x: -10, y: 0, z: -10}
                max: {x: 10, y: 255, z: 10}
                flags:
                  pvp: false
                  block-break: false
                  doors: true
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        ZoneDefinition zone = result.zone();
        assertEquals("central_village", zone.id());
        assertEquals("world", zone.world());
        assertEquals(-10, zone.minX());
        assertEquals(255, zone.maxY());
        assertFalse(zone.flags().allowPvp());
        assertTrue(zone.flags().allowDoors());
    }

    @Test
    void missingFieldsAreAllReportedTogether() {
        ZoneDefinitionParser.ParseResult result = parser.parse("bad.yml", load(""));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(ZoneLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("world"), combined);
        assertTrue(combined.contains("min"), combined);
        assertTrue(combined.contains("max"), combined);
    }

    @Test
    void invertedBoundsAreRejected() {
        ZoneDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: bad_zone
                world: world
                min: {x: 10, y: 0, z: 0}
                max: {x: -10, y: 10, z: 10}
                """));

        assertFalse(result.isSuccess());
    }

    @Test
    void flagsDefaultWhenSectionIsAbsent() {
        ZoneDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: no_flags
                world: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertFalse(result.zone().flags().allowPvp(), "faux par défaut");
        assertTrue(result.zone().flags().allowDoors(), "vrai par défaut");
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
