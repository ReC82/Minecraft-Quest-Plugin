package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.dialogue.DialogueLoadReport;
import com.lodygames.rpgquest.dialogue.DialogueLoader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Issue #66 : validation et tab-complétion de l'id passé à {@code /rpgadmin npc tag}. JUnit pur. */
class NpcTagIdPolicyTest {

    private static final List<String> CANONICAL = List.of("guard", "guide", "jo", "woodcutter_bob");

    @Test
    void aKnownIdIsAcceptedWithoutForce() {
        NpcTagIdPolicy.Verdict v = NpcTagIdPolicy.evaluate("guard", CANONICAL, false);

        assertTrue(v.accepted());
        assertTrue(v.known());
        assertNull(v.suggestion());
    }

    @Test
    void theLocalisedNameIsRefusedAndTheCanonicalIdSuggested() {
        NpcTagIdPolicy.Verdict v = NpcTagIdPolicy.evaluate("garde", CANONICAL, false);

        assertFalse(v.accepted());
        assertFalse(v.known());
        assertEquals("guard", v.suggestion());
    }

    @Test
    void anUnrelatedIdIsRefusedWithoutSuggestion() {
        NpcTagIdPolicy.Verdict v = NpcTagIdPolicy.evaluate("forgeron", CANONICAL, false);

        assertFalse(v.accepted());
        assertNull(v.suggestion());
    }

    @Test
    void forceAcceptsAnUnknownIdButKeepsItFlaggedUnknown() {
        NpcTagIdPolicy.Verdict v = NpcTagIdPolicy.evaluate("garde", CANONICAL, true);

        assertTrue(v.accepted());
        assertFalse(v.known(), "--force ne doit pas faire passer l'id pour référencé");
    }

    @Test
    void comparisonIgnoresCaseAndBlankEntries() {
        assertTrue(NpcTagIdPolicy.evaluate("guard", List.of(" Guard ", ""), false).known());
    }

    @Test
    void completionFiltersByPrefixAndSorts() {
        assertEquals(List.of("guard", "guide"), NpcTagIdPolicy.complete("gu", List.of("guide", "guard", "jo")));
        assertEquals(List.of("guard", "guide", "jo", "woodcutter_bob"), NpcTagIdPolicy.complete("", CANONICAL));
        assertEquals(List.of(), NpcTagIdPolicy.complete("x", CANONICAL));
    }

    @Test
    void theBundledGuardDialogueMakesGuardCanonical() throws Exception {
        // Le cas réel du ticket : « guard » vient du dialogue livré, sans définition npcs/ ni quête.
        YamlConfiguration yaml;
        try (InputStream in = getClass().getResourceAsStream("/dialogues/guard.yml")) {
            yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        DialogueLoadReport report = new DialogueLoader(List.of("give", "xp", "customitem", "claim"))
                .load(Map.<String, ConfigurationSection>of("guard.yml", yaml));

        List<String> canonical = NpcCatalogSources.canonicalIds(List.of(), report.loaded(), List.of());

        assertTrue(canonical.contains("guard"), canonical::toString);
        assertEquals("guard", NpcTagIdPolicy.evaluate("garde", canonical, false).suggestion());
    }
}
