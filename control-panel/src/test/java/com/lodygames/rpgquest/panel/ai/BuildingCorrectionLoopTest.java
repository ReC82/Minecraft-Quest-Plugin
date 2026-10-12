package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #94, lot F — la boucle de correction, et surtout son budget.
 *
 * <p>Tout est hors ligne : aucune clé d'API, aucun serveur, aucun jeton dépensé. C'est le sens même
 * des deux fonctions injectées — la pièce dont une erreur coûte de l'argent réel est celle qui doit
 * pouvoir être éprouvée gratuitement.</p>
 */
class BuildingCorrectionLoopTest {

    /** Un modèle scripté : on lui dicte ce qu'il « répond », passe après passe. */
    private static final class ScriptedModel implements BuildingCorrectionLoop.Asker {
        private final List<String> plans;
        private final List<String> seenRefusals = new ArrayList<>();
        private final List<String> seenMetrics = new ArrayList<>();
        private int calls;

        ScriptedModel(String... plans) {
            this.plans = List.of(plans);
        }

        @Override
        public AiBuildingStudio.Proposal ask(String previousPlan, String refusal, String metrics,
                                             int attempt, int maxAttempts) {
            seenRefusals.add(refusal);
            seenMetrics.add(metrics);
            String plan = calls < plans.size() ? plans.get(calls) : plans.get(plans.size() - 1);
            calls++;
            return new AiBuildingStudio.Proposal("anthropic", "Anthropic", "modele", "usage",
                    plan, plan, null, null);
        }
    }

    private static BuildingCorrectionLoop.Audit refused(String message) {
        return new BuildingCorrectionLoop.Audit(true, false, "ARCHITECTURE_REFUSED", message,
                List.of("NO_ENTRANCE"), "- cellules vitrées : 0");
    }

    private static BuildingCorrectionLoop.Audit accepted() {
        return new BuildingCorrectionLoop.Audit(true, true, "OK", "Plan conforme.", List.of(), "");
    }

    @Test
    @DisplayName("Sans mode automatique, la boucle s'arrête au premier refus et rend la main")
    void withoutAutomaticModeTheLoopStopsAtTheFirstRefusalAndHandsBack() {
        ScriptedModel model = new ScriptedModel("plan A", "plan B", "plan C");
        var loop = new BuildingCorrectionLoop(model, plan -> refused("Aucune entrée."));

        var run = loop.run(new BuildingCorrectionLoop.Budget(4, false), null, null, null);

        assertEquals(BuildingCorrectionLoop.Outcome.AWAITING_DECISION, run.outcome());
        assertEquals(1, run.spent(), "un seul appel payé : personne ne découvre une dépense");
        assertEquals(1, model.calls);
        assertNull(run.acceptedPlan());
    }

    @Test
    @DisplayName("Le mode automatique enchaîne, et s'arrête exactement au budget")
    void automaticModeChainsAndStopsExactlyAtTheBudget() {
        ScriptedModel model = new ScriptedModel("plan A", "plan B", "plan C", "plan D", "plan E");
        var loop = new BuildingCorrectionLoop(model, plan -> refused("Aucune entrée."));

        var run = loop.run(new BuildingCorrectionLoop.Budget(3, true), null, null, null);

        assertEquals(BuildingCorrectionLoop.Outcome.BUDGET_EXHAUSTED, run.outcome());
        assertEquals(3, run.spent());
        assertEquals(3, model.calls, "pas un appel de plus que le budget");
        assertEquals(3, run.attempts().size());
    }

    /**
     * Le garde-fou central : un formulaire ou un appelant distrait ne peut pas demander vingt
     * tentatives payantes.
     */
    @Test
    @DisplayName("Le budget est borné à la construction, pas à l'usage")
    void thebudgetIsBoundedAtConstructionNotAtUse() {
        assertEquals(BuildingCorrectionLoop.MAX_ATTEMPTS_CEILING,
                new BuildingCorrectionLoop.Budget(50, true).maxAttempts());
        assertEquals(1, new BuildingCorrectionLoop.Budget(0, true).maxAttempts());
        assertEquals(1, new BuildingCorrectionLoop.Budget(-7, true).maxAttempts());

        ScriptedModel model = new ScriptedModel("a", "b", "c", "d", "e", "f", "g", "h");
        var loop = new BuildingCorrectionLoop(model, plan -> refused("Aucune entrée."));

        var run = loop.run(new BuildingCorrectionLoop.Budget(99, true), null, null, null);

        assertEquals(BuildingCorrectionLoop.MAX_ATTEMPTS_CEILING, run.spent());
    }

    @Test
    @DisplayName("Le budget par défaut est une seule proposition, sans enchaînement")
    void thedefaultBudgetIsASingleProposal() {
        var manual = BuildingCorrectionLoop.Budget.manual();

        assertEquals(1, manual.maxAttempts());
        assertFalse(manual.automatic(), "personne ne doit découvrir une dépense");
    }

    @Test
    @DisplayName("Un plan accepté arrête la boucle immédiatement")
    void anacceptedPlanStopsTheLoopAtOnce() {
        ScriptedModel model = new ScriptedModel("plan A", "plan B");
        var loop = new BuildingCorrectionLoop(model, plan -> accepted());

        var run = loop.run(new BuildingCorrectionLoop.Budget(5, true), null, null, null);

        assertEquals(BuildingCorrectionLoop.Outcome.ACCEPTED, run.outcome());
        assertEquals(1, run.spent(), "le budget restant n'est pas consommé pour rien");
        assertEquals("plan A", run.acceptedPlan());
    }

    /**
     * Un plan accepté n'est pas un bâtiment. La boucle ne génère rien, et son issue le dit : c'est
     * la discipline que tout l'atelier respecte, un second geste humain reste nécessaire.
     */
    @Test
    @DisplayName("Un plan accepté n'est jamais annoncé comme un bâtiment construit")
    void anacceptedPlanIsNeverAnnouncedAsABuiltBuilding() {
        var loop = new BuildingCorrectionLoop(new ScriptedModel("plan A"), plan -> accepted());

        var run = loop.run(BuildingCorrectionLoop.Budget.manual(), null, null, null);

        assertEquals("Plan accepté par le serveur", run.outcome().label());
        assertFalse(run.outcome().label().toLowerCase(java.util.Locale.ROOT).contains("génér"),
                "le vocabulaire ne doit pas laisser croire qu'un artefact existe : "
                        + run.outcome().label());
    }

    @Test
    @DisplayName("Le motif réel et les mesures sont transmis au modèle à la passe suivante")
    void therealRefusalAndTheMeasurementsReachTheModelOnTheNextPass() {
        ScriptedModel model = new ScriptedModel("plan A", "plan B");
        var loop = new BuildingCorrectionLoop(model,
                plan -> "plan A".equals(plan) ? refused("Aucune entrée dans la façade OUEST.")
                        : accepted());

        loop.run(new BuildingCorrectionLoop.Budget(2, true), null, null, null);

        assertNull(model.seenRefusals.get(0), "la première passe n'a aucun refus à montrer");
        assertEquals("Aucune entrée dans la façade OUEST.", model.seenRefusals.get(1));
        assertEquals("- cellules vitrées : 0", model.seenMetrics.get(1),
                "les mesures sont l'argument le plus efficace de la correction");
    }

    @Test
    @DisplayName("Un modèle qui ressert le même plan interrompt la boucle au lieu d'être repayé")
    void amodelRepeatingItsPlanStopsTheLoopInsteadOfBeingPaidAgain() {
        ScriptedModel model = new ScriptedModel("plan identique", "plan identique");
        var loop = new BuildingCorrectionLoop(model, plan -> refused("Aucune entrée."));

        var run = loop.run(new BuildingCorrectionLoop.Budget(5, true), null, null, null);

        assertEquals(BuildingCorrectionLoop.Outcome.STALLED, run.outcome());
        assertEquals(2, run.spent(), "la répétition est constatée au deuxième appel, pas au cinquième");
    }

    /**
     * Un agent hors ligne n'est pas un refus. Relancer le modèle parce que le serveur n'a pas
     * répondu serait payer pour une information qu'on n'a pas obtenue.
     */
    @Test
    @DisplayName("Un serveur injoignable arrête la boucle sans relancer le modèle")
    void anunreachableServerStopsTheLoopWithoutCallingTheModelAgain() {
        ScriptedModel model = new ScriptedModel("plan A", "plan B", "plan C");
        var loop = new BuildingCorrectionLoop(model,
                plan -> BuildingCorrectionLoop.Audit.unreachable("Agent hors ligne."));

        var run = loop.run(new BuildingCorrectionLoop.Budget(5, true), null, null, null);

        assertEquals(BuildingCorrectionLoop.Outcome.AGENT_UNREACHABLE, run.outcome());
        assertEquals(1, model.calls);
    }

    @Test
    @DisplayName("Un appel qui échoue est tout de même compté comme dépensé")
    void afailedCallIsStillCountedAsSpent() {
        BuildingCorrectionLoop.Asker failing = (plan, refusal, metrics, attempt, max) ->
                new AiBuildingStudio.Proposal("anthropic", "Anthropic", null, null, null, null,
                        null, "502 Bad Gateway");
        var loop = new BuildingCorrectionLoop(failing, plan -> accepted());

        var run = loop.run(new BuildingCorrectionLoop.Budget(3, true), null, null, null);

        assertEquals(BuildingCorrectionLoop.Outcome.CALL_FAILED, run.outcome());
        assertEquals(1, run.spent(), "un appel émis est un appel payé, même refusé en route");
    }

    @Test
    @DisplayName("Une réponse sans plan exploitable arrête la boucle plutôt que de la relancer à vide")
    void aresponseWithNoUsablePlanStopsTheLoop() {
        BuildingCorrectionLoop.Asker prose = (plan, refusal, metrics, attempt, max) ->
                new AiBuildingStudio.Proposal("anthropic", "Anthropic", "modele", "usage",
                        "Bien sûr ! Voici mes idées…", null, "aucun YAML trouvé", null);
        var loop = new BuildingCorrectionLoop(prose, plan -> accepted());

        var run = loop.run(new BuildingCorrectionLoop.Budget(3, true), null, null, null);

        assertEquals(BuildingCorrectionLoop.Outcome.NO_PLAN, run.outcome());
        assertEquals(1, run.spent());
    }

    @Test
    @DisplayName("Les mesures du serveur sont mises en phrases, et une clé absente reste muette")
    void theserverMeasurementsAreTurnedIntoSentencesAndAMissingKeyStaysSilent() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("solidCells", 2368);
        metrics.put("glazedCells", 0);
        metrics.put("topMaterial", "stone_bricks");
        metrics.put("topMaterialShare", 0.6271870794078062);

        String text = BuildingCorrectionLoop.formatMetrics(metrics);

        assertTrue(text.contains("blocs de matière posés : 2368"), text);
        assertTrue(text.contains("cellules vitrées : 0"), text);
        assertTrue(text.contains("stone_bricks à 63 %"), "arrondi lisible : " + text);
        assertFalse(text.contains("null"), "une clé absente ne devient pas une fausse mesure : "
                + text);
        assertFalse(text.contains("sources de lumière"), "non mesuré, donc non affirmé : " + text);
        assertEquals("", BuildingCorrectionLoop.formatMetrics(null));
        assertEquals("", BuildingCorrectionLoop.formatMetrics(Map.of()));
    }

    @Test
    @DisplayName("Le prompt de correction porte le rang de la tentative et les mesures")
    void thecorrectionPromptCarriesTheAttemptRankAndTheMeasurements() {
        var demand = new BuildingPromptBuilder.Demand("buildsite_0012", "Bibliothèque du Hub",
                "WEST", "Une bibliothèque sur deux niveaux.", 19, 14);

        String prompt = BuildingPromptBuilder.correctionPrompt(demand, "plan refusé",
                "Aucune entrée.", "- cellules vitrées : 0", 2, 3);

        assertTrue(prompt.contains("Tentative 2 sur 3"), prompt);
        assertTrue(prompt.contains("Il en restera 1"), prompt);
        assertTrue(prompt.contains("cellules vitrées : 0"), prompt);
        assertTrue(prompt.contains("Aucune entrée."), prompt);

        String last = BuildingPromptBuilder.correctionPrompt(demand, "plan refusé",
                "Aucune entrée.", "", 3, 3);
        assertTrue(last.contains("DERNIÈRE"), "la dernière tentative doit être annoncée : " + last);
    }

    /** Le prompt doit enseigner les primitives du lot B, sinon le modèle continue sans elles. */
    @Test
    @DisplayName("Le prompt enseigne les primitives d'architecture et la convention de façade")
    void thepromptTeachesTheArchitecturalPrimitivesAndTheFacadeConvention() {
        String system = BuildingPromptBuilder.systemPrompt();

        for (String primitive : List.of("op: door", "op: window", "op: light", "op: staircase",
                "op: ladder", "op: pillars", "op: gable")) {
            assertTrue(system.contains(primitive), "primitive non enseignée : " + primitive);
        }
        assertTrue(system.contains("FRONT, BACK, LEFT ou RIGHT"),
                "la convention de façade doit être explicite");
        assertTrue(system.contains("JAMAIS une coordonnée"),
                "c'est l'erreur exacte qui a scellé la V1");
        assertTrue(system.contains("kind: habitable"), "le type de structure doit être déclarable");
        assertTrue(system.contains("fill:"), "sans « fill », les pignons restent creux");
    }

    /**
     * Le contrat énoncé au modèle doit être <strong>celui que le serveur mesure</strong>. Deux
     * contrats divergents produiraient des plans refusés pour des raisons que le prompt n'annonçait
     * pas — et c'est exactement ce qui rend une boucle de correction frustrante et coûteuse.
     */
    @Test
    @DisplayName("Le prompt énonce les huit points du contrat architectural réellement contrôlé")
    void thepromptStatesTheEightActuallyCheckedContractPoints() {
        String system = BuildingPromptBuilder.systemPrompt();

        for (String point : List.of("UNE ENTRÉE", "DES VITRAGES", "DE LA LUMIÈRE",
                "UNE ENVELOPPE CLOSE", "ÉTAGES ACCESSIBLES", "ÉTAGES PRATICABLES", "TROIS MATÉRIAUX",
                "DU MOBILIER")) {
            assertTrue(system.contains(point), "point du contrat non énoncé : " + point);
        }
        // #282 : « étages praticables » (LEVEL_CLUTTERED) est désormais mesuré par le serveur.
        assertEquals(8, system.lines().filter(l -> l.trim().startsWith("[ ]")).count(),
                "la liste de contrôle doit avoir exactement huit cases");
    }

    @Test
    @DisplayName("Le prompt contient un exemple complet, et cet exemple respecte son propre contrat")
    void thepromptContainsACompleteExampleThatRespectsItsOwnContract() {
        String system = BuildingPromptBuilder.systemPrompt();
        int example = system.indexOf("UN EXEMPLE COMPLET ET VALIDE");

        assertTrue(example > 0, "un exemple complet est attendu");
        String body = system.substring(example);
        // Un exemple qui enfreindrait le contrat qu'il illustre serait la pire des consignes.
        assertTrue(body.contains("op: door"), "l'exemple doit montrer une entrée");
        assertTrue(body.contains("op: light"), "l'exemple doit montrer une source de lumière");
        assertEquals(4, body.lines().filter(l -> l.contains("op: window")).count(),
                "au moins deux façades vitrées, aux deux étages : l'exemple en montre quatre");
        assertTrue(body.contains("fill: minecraft:"), "l'exemple doit fermer ses pignons");
        // #282 : l'exemple a deux étages, reliés par la primitive qui calcule la trémie.
        assertTrue(body.contains("op: staircase") && body.contains("floor: 5"),
                "l'exemple doit montrer un escalier entre niveaux");
    }
}
