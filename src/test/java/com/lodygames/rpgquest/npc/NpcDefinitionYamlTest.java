package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.model.NpcDefinition;
import java.io.StringReader;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Le texte YAML produit se re-parse à l'identique. */
class NpcDefinitionYamlTest {

    private NpcDefinition roundTrip(NpcDefinition in) throws Exception {
        String yaml = NpcDefinitionYaml.render(in);
        YamlConfiguration section = new YamlConfiguration();
        section.load(new StringReader(yaml));
        NpcDefinitionParser.ParseResult r = new NpcDefinitionParser().parse("x.yml", section);
        assertTrue(r.isSuccess(), r.issues().toString());
        return r.definition();
    }

    @Test
    void fullDefinitionRoundTrips() throws Exception {
        NpcDefinition in = new NpcDefinition("guard", "<yellow>Garde \"le vieux\"</yellow>",
                "Description avec : deux points", "rpgquest:guard", "quest_giver", false);
        assertEquals(in, roundTrip(in));
    }

    @Test
    void minimalDefinitionRoundTrips() throws Exception {
        NpcDefinition in = new NpcDefinition("bob", "Bob", null, null, null, true);
        assertEquals(in, roundTrip(in));
    }

    @Test
    void renderCarriesAHeaderComment() {
        assertTrue(NpcDefinitionYaml.render(new NpcDefinition("x", "X", null, null, null, true))
                .startsWith("# "));
    }
}
