package com.lodygames.rpgquest.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.encounter.model.EncounterTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Issue #13 — choix du modèle d'une instance de biome. */
class EncounterTemplateSelectorTest {

    private final EncounterTemplateSelector selector = new EncounterTemplateSelector();

    @Test
    void onlyTemplatesOfThatBiomeCompete() {
        List<EncounterTemplate> all = List.of(template("bandit", "minecraft:forest", 10, true, List.of(), 0),
                template("merchant", "minecraft:desert", 10, true, List.of(), 0));

        assertEquals("bandit", selector.select(all, "wild", "minecraft:forest", 1L, id -> 0).orElseThrow().id());
        assertTrue(selector.select(all, "wild", "minecraft:plains", 1L, id -> 0).isEmpty(),
                "aucun modèle pour ce biome : aucune rencontre, jamais un modèle d'un autre biome");
    }

    @Test
    void disabledTemplatesWorldRestrictionsAndCapsAreHonoured() {
        List<EncounterTemplate> all = List.of(
                template("off", "minecraft:forest", 10, false, List.of(), 0),
                template("elsewhere", "minecraft:forest", 10, true, List.of("wild2"), 0),
                template("full", "minecraft:forest", 10, true, List.of(), 2));

        assertTrue(selector.select(all, "wild", "minecraft:forest", 7L, id -> 2).isEmpty());
        assertEquals("full", selector.select(all, "wild", "minecraft:forest", 7L, id -> 1).orElseThrow().id());
        assertEquals(Set.of("elsewhere", "full"), Set.copyOf(
                selector.eligible(all, "wild2", "minecraft:forest", id -> 0).stream().map(EncounterTemplate::id).toList()));
    }

    @Test
    void theDrawIsDeterministicAndWeighted() {
        List<EncounterTemplate> all = List.of(template("rare", "minecraft:forest", 1, true, List.of(), 0),
                template("common", "minecraft:forest", 9, true, List.of(), 0));

        assertEquals(selector.select(all, "wild", "minecraft:forest", 42L, id -> 0),
                selector.select(all, "wild", "minecraft:forest", 42L, id -> 0), "même graine, même modèle");

        Map<String, Integer> counts = new HashMap<>();
        for (long seed = 0; seed < 2000; seed++) {
            counts.merge(selector.select(all, "wild", "minecraft:forest", seed, id -> 0).orElseThrow().id(), 1,
                    Integer::sum);
        }
        assertTrue(counts.get("common") > 1600 && counts.get("rare") > 100, counts::toString);
    }

    private static EncounterTemplate template(String id, String biome, int weight, boolean enabled,
                                              List<String> worlds, int maxSites) {
        return new EncounterTemplate(id, id, null, id, null, null, Set.of(biome), worlds, weight, maxSites, null,
                enabled);
    }
}
