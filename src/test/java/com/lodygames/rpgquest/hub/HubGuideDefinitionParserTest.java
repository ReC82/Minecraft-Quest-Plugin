package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class HubGuideDefinitionParserTest {

    private final HubGuideDefinitionParser parser = new HubGuideDefinitionParser();

    @Test
    void parsesAFullDefinition() {
        HubGuideDefinitionParser.ParseResult result = parser.parse("hub_depart.yml", load("""
                hub-id: hub_depart
                worlds:
                  - world_hub
                guide-dialogue: rpgquest:guide
                help-node: help_menu
                welcome: "Bienvenue !"
                specialty: "Les bases."
                referrals:
                  - role: "Quêtes"
                    npc: "le Libraire"
                    note: "Il remet le journal."
                """));

        assertTrue(result.isSuccess(), () -> "issues: " + result.issues());
        HubGuideDefinition def = result.definition();
        assertEquals("hub_depart", def.hubId());
        assertEquals(java.util.List.of("world_hub"), def.worlds());
        assertEquals(new NamespacedKey("rpgquest", "guide"), def.guideDialogueId());
        assertEquals("help_menu", def.helpNodeId());
        assertEquals(1, def.referrals().size());
        assertEquals("le Libraire", def.referrals().get(0).npcName());
    }

    @Test
    void defaultsHelpNodeWhenAbsent() {
        HubGuideDefinitionParser.ParseResult result = parser.parse("h.yml", load("""
                hub-id: h
                guide-dialogue: rpgquest:guide
                """));

        assertTrue(result.isSuccess());
        assertEquals(HubGuideDefinition.DEFAULT_HELP_NODE, result.definition().helpNodeId());
        assertTrue(result.definition().worlds().isEmpty());
    }

    @Test
    void rejectsMissingHubIdAndDialogue() {
        HubGuideDefinitionParser.ParseResult result = parser.parse("bad.yml", load("welcome: \"x\"\n"));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("hub-id")));
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("guide-dialogue")));
    }

    @Test
    void rejectsAnInvalidHubId() {
        HubGuideDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                hub-id: "Hub Départ"
                guide-dialogue: rpgquest:guide
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("hub-id")));
    }

    @Test
    void rejectsAReferralWithoutRoleOrNpc() {
        HubGuideDefinitionParser.ParseResult result = parser.parse("bad.yml", load("""
                hub-id: h
                guide-dialogue: rpgquest:guide
                referrals:
                  - note: "orpheline"
                """));

        assertFalse(result.isSuccess());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("referrals[0]")));
    }

    private ConfigurationSection load(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml));
    }
}
