package be.lloyd.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.quest.model.QuestState;
import be.lloyd.rpgquest.travel.model.PortalDefinition;
import java.io.StringReader;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PortalDefinitionParserTest {

    private final PortalDefinitionParser parser = new PortalDefinitionParser();

    @Test
    void validFileParsesSuccessfullyWithDefaults() {
        PortalDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: forest_gate
                world: world
                min: {x: 10, y: 60, z: 10}
                max: {x: 12, y: 63, z: 12}
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        PortalDefinition portal = result.portal();
        assertEquals("forest_gate", portal.id());
        assertNull(portal.destinationId());
        assertEquals(3, portal.channelSeconds());
        assertEquals(5, portal.cooldownSeconds());
    }

    @Test
    void fullFileWithAllOptionalFieldsParsesSuccessfully() {
        PortalDefinitionParser.ParseResult result = parser.parse("valid.yml", load("""
                id: forest_gate
                world: world
                min: {x: 10, y: 60, z: 10}
                max: {x: 12, y: 63, z: 12}
                destination: village
                channel-seconds: 5
                cooldown-seconds: 30
                required-permission: rpgquest.portal.forest
                required-quest: rpgquest:first_steps
                required-quest-state: COMPLETED
                required-level: 5
                cost: 10
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        PortalDefinition portal = result.portal();
        assertEquals("village", portal.destinationId());
        assertEquals(5, portal.channelSeconds());
        assertEquals(30, portal.cooldownSeconds());
        assertEquals("rpgquest.portal.forest", portal.requiredPermission());
        assertEquals("rpgquest:first_steps", portal.requiredQuestId().toString());
        assertEquals(QuestState.COMPLETED, portal.requiredQuestState());
        assertEquals(5, portal.requiredLevel());
        assertEquals(10L, portal.cost());
    }

    @Test
    void missingRequiredFieldsAreAllReportedTogether() {
        PortalDefinitionParser.ParseResult result = parser.parse("incomplete.yml", load("world: world\n"));

        assertFalse(result.isSuccess());
        String combined = String.join(" | ", result.issues().stream().map(PortalLoadIssue::message).toList());
        assertTrue(combined.contains("id"), combined);
        assertTrue(combined.contains("min"), combined);
        assertTrue(combined.contains("max"), combined);
    }

    @Test
    void zeroCostIsRejected() {
        PortalDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: gate
                world: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                cost: 0
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("cost")));
    }

    @Test
    void negativeChannelSecondsIsRejected() {
        PortalDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: gate
                world: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                channel-seconds: -1
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("channel-seconds")));
    }

    @Test
    void questStateWithoutQuestIsRejected() {
        PortalDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                id: gate
                world: world
                min: {x: 0, y: 0, z: 0}
                max: {x: 1, y: 1, z: 1}
                required-quest-state: COMPLETED
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("required-quest-state")));
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
