package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #163 : sélection des prérequis — origine source/runtime de chaque quête et refus des
 * cycles (direct comme indirect) avant l'enregistrement.
 */
class QuestPrereqGraphTest {

    /** Graphe : {@code c} exige {@code b}, {@code b} exige {@code a}, {@code a} n'exige rien. */
    private static RefData graph() {
        return new RefData(
                List.of("a", "b", "c", "solo"), List.of(), List.of(),
                true, false, false,
                Map.of(), Map.of("a", "Quête A", "b", "Quête B"),
                Map.of("a", RefData.ORIGIN_BOTH, "b", RefData.ORIGIN_SOURCE, "c", RefData.ORIGIN_RUNTIME),
                Map.of("c", List.of("b"), "b", List.of("a"), "a", List.of()));
    }

    @Test
    void originIsReportedWithoutGuessing() {
        RefData ref = graph();
        assertEquals(RefData.ORIGIN_BOTH, ref.questOrigin("a"));
        assertEquals("source uniquement", ref.questOriginLabel("b"));
        assertEquals("serveur uniquement", ref.questOriginLabel("c"));
        // Jamais d'invention : une quête hors catalogue n'a pas d'origine.
        assertEquals("", ref.questOrigin("inconnue"));
        assertEquals("", ref.questOriginLabel("inconnue"));
    }

    @Test
    void originIsFoundRegardlessOfNamespaceForm() {
        assertEquals(RefData.ORIGIN_BOTH, graph().questOrigin("rpgquest:a"));
    }

    @Test
    void noCycleWhenPrerequisiteChainStaysAcyclic() {
        // d (nouvelle quête) exige c : c -> b -> a, aucune boucle.
        assertNull(graph().findPrereqCycle("d", List.of("c")));
    }

    @Test
    void indirectCycleIsDetectedAcrossTheWholeChain() {
        // Donner c comme prérequis de a fermerait la boucle a -> c -> b -> a.
        String cycle = graph().findPrereqCycle("a", List.of("c"));
        assertNotNull(cycle, "le cycle indirect doit être détecté");
        assertTrue(cycle.startsWith("a") && cycle.endsWith("a"), "cycle rendu lisible : " + cycle);
        assertTrue(cycle.contains("c") && cycle.contains("b"), "le chemin complet est montré : " + cycle);
    }

    @Test
    void directCycleOfTwoQuestsIsDetected() {
        // b exige déjà a ; donner b comme prérequis de a boucle immédiatement.
        assertNotNull(graph().findPrereqCycle("a", List.of("b")));
    }

    @Test
    void selfReferenceIsLeftToItsOwnDiagnosticAndNeverReportedAsACycle() {
        // L'auto-référence a un message dédié, plus explicite, dans QuestValidator.
        assertNull(graph().findPrereqCycle("a", List.of("a")));
    }

    @Test
    void unknownPrerequisiteIsNeverTurnedIntoAnInventedCycle() {
        assertNull(graph().findPrereqCycle("a", List.of("jamais_vue")));
    }

    @Test
    void preexistingCycleInTheKnownGraphDoesNotBlockAnUnrelatedQuest() {
        // x <-> y déjà bouclés dans le graphe connu : éditer « solo » ne doit pas être bloqué
        // par une anomalie qui ne le concerne pas (et surtout ne doit pas boucler à l'infini).
        RefData ref = new RefData(
                List.of("x", "y", "solo"), List.of(), List.of(), true, false, false,
                Map.of(), Map.of(), Map.of(),
                Map.of("x", List.of("y"), "y", List.of("x")));
        assertNull(ref.findPrereqCycle("solo", List.of("x")));
    }

    @Test
    void validatorBlocksAnIndirectCycleWithAnExplicitError() {
        QuestDraft d = new QuestDraft();
        d.id = "a";
        d.title = "Quête A";
        d.category = "story";
        d.prerequisites.add("c");
        QuestDraft.Step step = new QuestDraft.Step();
        step.id = "etape";
        Map<String, String> obj = new java.util.LinkedHashMap<>();
        obj.put("kind", "KILL_ENTITY");
        obj.put("entity", "ZOMBIE");
        obj.put("amount", "1");
        step.objectives.add(obj);
        d.steps.add(step);

        List<Diagnostic> diags = QuestValidator.validate(d, graph());
        assertTrue(Diagnostic.hasError(diags), "un cycle doit bloquer l'enregistrement");
        assertTrue(diags.stream().anyMatch(x -> x.message().contains("Cycle de prérequis")),
                "le message doit nommer le cycle : " + diags);
    }
}
