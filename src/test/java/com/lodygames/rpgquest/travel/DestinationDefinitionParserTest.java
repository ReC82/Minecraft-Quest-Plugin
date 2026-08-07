package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.travel.model.Destination;
import java.io.StringReader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DestinationDefinitionParserTest {

    private final DestinationDefinitionParser parser = new DestinationDefinitionParser();

    @Test
    void validFileParsesSuccessfully() {
        DestinationDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: village
                world: world
                x: 0.5
                y: 65.0
                z: 0.5
                yaw: 90.0
                pitch: 0.0
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        Destination destination = result.destination();
        assertEquals("village", destination.id());
        assertEquals("world", destination.world());
        assertEquals(0.5, destination.x());
        assertEquals(65.0, destination.y());
        assertEquals(90.0f, destination.yaw());
    }

    @Test
    void yawAndPitchDefaultToZero() {
        DestinationDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: village
                world: world
                x: 0
                y: 65
                z: 0
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        assertEquals(0f, result.destination().yaw());
        assertEquals(0f, result.destination().pitch());
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        DestinationDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("world: world\n"));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(DestinationLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("x"), combined);
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
