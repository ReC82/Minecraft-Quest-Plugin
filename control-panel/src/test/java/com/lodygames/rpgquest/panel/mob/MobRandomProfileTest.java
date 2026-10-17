package com.lodygames.rpgquest.panel.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Issue #214 — brouillons aléatoires bornés, cohérents, jamais en collision ; différences par clé. */
class MobRandomProfileTest {

    /** Clés du contrat plugin ({@code SpecialMobSchema}) : le générateur n'en invente aucune. */
    private static final Set<String> CONTRACT = Set.of("id", "category", "enabled", "boss-mode", "community-weight",
            "participation-rewards", "entity-type", "name", "spawn-chance", "worlds", "biomes", "zones", "health",
            "damage", "speed", "armor", "knockback-resistance", "scale", "creeper-explosion-radius", "particle", "sound",
            "abilities", "drops", "xp-reward", "max-population");

    private static final MobImportContext CTX = new MobImportContext(true, Map.of(), List.of("hub", "wild"), "wild",
            List.of("plains"), List.of(), false, Map.of(),
            List.of(new MobImportContext.Profile("rpgquest:boss_zombie_0001", "Z", "BOSS", "QUEST", "")));

    @Test
    void draftsAreBoundedCoherentAndUseOnlyContractKeys() {
        Random rnd = new Random(214);
        for (String kind : MobImportContext.KINDS) {
            for (int i = 0; i < 300; i++) {
                String yaml = MobRandomProfile.generate(kind, CTX, rnd);
                Map<String, String> blocks = MobProfileDiff.blocks(yaml);
                assertTrue(CONTRACT.containsAll(blocks.keySet()), blocks.keySet().toString());
                boolean boss = !kind.equals("special");
                assertEquals(boss ? "BOSS" : "SPECIAL", blocks.get("category"));
                assertEquals("[wild]", blocks.get("worlds"));
                assertTrue(Double.parseDouble(blocks.get("speed")) <= 0.32);
                assertTrue(Double.parseDouble(blocks.get("health")) > 0);
                if (boss) {
                    assertEquals("1", blocks.get("max-population"));
                    assertEquals(kind.equals("boss-community") ? "COMMUNITY" : "QUEST", blocks.get("boss-mode"));
                    assertEquals("0.0", blocks.get("spawn-chance"));
                }
                String abilities = blocks.getOrDefault("abilities", "");
                boolean creeper = blocks.get("entity-type").equals("CREEPER");
                assertFalse(!creeper && (abilities.contains("STRONGER_EXPLOSION") || abilities.contains("EXPLOSIVE_ON_ATTACK")),
                        "capacité explosive hors creeper : " + yaml);
                if (abilities.contains("SUMMON_ON_DAMAGE")) {
                    Matcher m = Pattern.compile("max-alive: (\\d+)").matcher(abilities);
                    assertTrue(m.find() && Integer.parseInt(m.group(1)) <= 4, "invocation plafonnée");
                }
                assertTrue(blocks.get("name").startsWith("\"<"), "nom affiché coloré");
            }
        }
    }

    @Test
    void successiveDraftsNeverReuseATakenId() {
        Random rnd = new Random(1);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String id = MobProfileDiff.blocks(MobRandomProfile.generate("boss-quest", CTX, rnd)).get("id");
            assertFalse(id.equals("boss_zombie_0001"));
            assertTrue(id.matches("(boss|mob)_[a-z_]+_[0-9a-f]{4}"), id);
            seen.add(id);
        }
        assertTrue(seen.size() > 150, "brouillons variés");
    }

    @Test
    void diffListsChangedAddedAndRemovedKeys() {
        String before = "id: a\nhealth: 20.0\nabilities:\n  - type: ENRAGED\n    health-fraction: 0.3\nxp-reward: 5\n";
        String after = "# commentaire ignoré\nid: a\nhealth: 40.0\nxp-reward: 5\nscale: 1.2\n";
        List<MobProfileDiff.Change> changes = MobProfileDiff.changes(before, after);
        assertEquals(3, changes.size(), changes.toString());
        assertEquals(new MobProfileDiff.Change("health", "20.0", "40.0"), changes.get(0));
        assertTrue(changes.get(1).removed() && changes.get(1).key().equals("abilities")
                && changes.get(1).before().contains("ENRAGED"));
        assertTrue(changes.get(2).added() && changes.get(2).key().equals("scale"));
        assertTrue(MobProfileDiff.changes(before, before).isEmpty());
    }
}
