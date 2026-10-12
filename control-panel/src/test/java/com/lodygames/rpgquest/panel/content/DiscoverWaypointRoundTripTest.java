package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #240 — une quête {@code DISCOVER_WAYPOINT} doit survivre au garde-fou round-trip.
 *
 * <p>Le défaut : {@link QuestYaml} écrivait {@code worlds: [wild]} (forme lue par le plugin), mais
 * {@link MiniYaml} relisait {@code [wild]} comme le <em>texte</em> « [wild] », que l'écriture suivante
 * transformait en {@code ["[wild]"]}. L'éditeur et le relecteur n'avaient jamais été confrontés.
 * Le garde-fou n'est pas affaibli : c'est le relecteur qui apprend la forme émise.</p>
 */
class DiscoverWaypointRoundTripTest {

    private static QuestDraft quest(Map<String, String> objective) {
        QuestDraft d = new QuestDraft();
        d.id = "test_discover_waypoints";
        d.title = "Explorateur";
        d.description = "Découvrir des waypoints.";
        d.category = "exploration";
        d.icon = "COMPASS";
        QuestDraft.Step step = new QuestDraft.Step("step1");
        step.objectives.add(new LinkedHashMap<>(objective));
        d.steps.add(step);
        return d;
    }

    private static Map<String, String> discover(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("kind", "DISCOVER_WAYPOINT");
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String roundTrips(Map<String, String> objective) {
        String yaml = QuestYaml.write(quest(objective));
        assertEquals(List.of(), QuestYaml.roundTripProblems(yaml), yaml);
        return yaml;
    }

    @Test
    void amountAlone() {
        String yaml = roundTrips(discover("amount", "3"));
        assertTrue(yaml.contains("amount: 3"), yaml);
    }

    @Test
    void theExactCaseFromTheTicket() {
        String yaml = roundTrips(discover("amount", "2", "worlds", "wild", "count-mode", "NEW_ONLY"));
        assertTrue(yaml.contains("worlds: [wild]"), "la forme lue par getStringList côté plugin");
        assertTrue(yaml.contains("count-mode: NEW_ONLY"), yaml);
    }

    @Test
    void twoWorldsAndIncludeExisting() {
        String yaml = roundTrips(discover("amount", "5", "worlds", "world_hub, wild",
                "count-mode", "INCLUDE_EXISTING"));
        assertTrue(yaml.contains("worlds: [world_hub, wild]"), yaml);
    }

    /** Ce que l'éditeur rouvre est exactement ce qu'il avait enregistré. */
    @Test
    void rereadingRestoresTheFormValues() {
        String yaml = QuestYaml.write(quest(discover("amount", "2", "worlds", "world_hub, wild",
                "count-mode", "NEW_ONLY")));
        Map<String, String> back = QuestYaml.read(yaml).draft().steps.get(0).objectives.get(0);
        assertEquals("world_hub, wild", back.get("worlds"), "forme du formulaire, jamais « [..] »");
        assertEquals("NEW_ONLY", back.get("count-mode"));
        assertEquals("2", back.get("amount"));
    }

    @Test
    void writeReadWriteIsStable() {
        String first = QuestYaml.write(quest(discover("amount", "2", "worlds", " wild ,world_hub,",
                "count-mode", "NEW_ONLY")));
        String second = QuestYaml.write(QuestYaml.read(first).draft());
        assertEquals(first, second);
        assertTrue(first.contains("worlds: [wild, world_hub]"), "entrée vide et espaces ignorés");
    }

    // ---- MiniYaml : la forme en ligne, et ses limites -----------------------------------------

    @Test
    void miniYamlReadsAFlowSequenceOfScalars() {
        Object root = MiniYaml.parse("a: [x, \"y, z\", 'w']\nb: []\n");
        Map<?, ?> m = (Map<?, ?>) root;
        assertEquals(List.of("x", "y, z", "w"), m.get("a"));
        assertEquals(List.of(), m.get("b"));
    }

    /** Ce que MiniYaml ne sait pas lire reste du texte : le garde-fou le signalera, sans deviner. */
    @Test
    void nestedOrBrokenSequencesStayText() {
        Map<?, ?> m = (Map<?, ?>) MiniYaml.parse("a: [x, [y]]\nb: [\"x]\nc: [x,,y]\n");
        assertEquals("[x, [y]]", m.get("a"));
        assertEquals("[\"x]", m.get("b"));
        assertEquals("[x,,y]", m.get("c"));
    }

    /** Un texte entre guillemets qui ressemble à une liste reste un texte. */
    @Test
    void aQuotedBracketIsStillText() {
        Map<?, ?> m = (Map<?, ?>) MiniYaml.parse("title: \"[Garde] Bonjour\"\n");
        assertEquals("[Garde] Bonjour", m.get("title"));
    }
}
