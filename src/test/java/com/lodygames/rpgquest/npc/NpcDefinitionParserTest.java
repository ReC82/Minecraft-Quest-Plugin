package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

/** Validation d'une définition PNJ à partir d'une section YAML — JUnit pur (pas de MockBukkit). */
class NpcDefinitionParserTest {

    private final NpcDefinitionParser parser = new NpcDefinitionParser();

    private static MemoryConfiguration section(Object... kv) {
        MemoryConfiguration c = new MemoryConfiguration();
        for (int i = 0; i < kv.length; i += 2) {
            c.set((String) kv[i], kv[i + 1]);
        }
        return c;
    }

    @Test
    void minimalValidDefinition() {
        NpcDefinitionParser.ParseResult r = parser.parse("bob.yml",
                section("id", "woodcutter_bob", "display_name", "Bûcheron Bob"));
        assertTrue(r.isSuccess(), r.issues().toString());
        NpcDefinition d = r.definition();
        assertEquals("woodcutter_bob", d.id());
        assertEquals("Bûcheron Bob", d.displayName());
        assertTrue(d.enabled());
        assertNull(d.dialogueId());
        assertNull(d.role());
    }

    @Test
    void fullDefinitionWithNormalisedDialogueAndRole() {
        NpcDefinition d = parser.parse("g.yml", section(
                "id", "guard", "display_name", "<yellow>Garde</yellow>", "description", "Le garde",
                "dialogue", "guard", "role", "Quest_Giver", "enabled", false)).definition();
        assertEquals("rpgquest:guard", d.dialogueId(), "clé simple normalisée en rpgquest:clé");
        assertEquals("quest_giver", d.role());
        assertEquals("Le garde", d.description());
        assertFalse(d.enabled());
    }

    @Test
    void namespacedDialogueKept() {
        assertEquals("other:thing", parser.parse("x.yml", section(
                "id", "x", "display_name", "X", "dialogue", "other:thing")).definition().dialogueId());
    }

    @Test
    void missingIdAndDisplayNameAreReported() {
        assertFalse(parser.parse("x.yml", section("display_name", "X")).isSuccess());
        assertFalse(parser.parse("x.yml", section("id", "x")).isSuccess());
    }

    @Test
    void invalidIdIsRejected() {
        assertFalse(parser.parse("x.yml", section("id", "Bad Id", "display_name", "X")).isSuccess());
        assertFalse(parser.parse("x.yml", section("id", "UPPER", "display_name", "X")).isSuccess());
    }

    @Test
    void badDialogueOrRoleOrEnabledAreReported() {
        assertFalse(parser.parse("x.yml", section("id", "x", "display_name", "X", "dialogue", "Bad Dialogue")).isSuccess());
        assertFalse(parser.parse("x.yml", section("id", "x", "display_name", "X", "role", "way too long a role name here")).isSuccess());
        assertFalse(parser.parse("x.yml", section("id", "x", "display_name", "X", "enabled", "yes")).isSuccess());
    }
}
