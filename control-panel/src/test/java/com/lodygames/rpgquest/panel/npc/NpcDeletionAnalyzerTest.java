package com.lodygames.rpgquest.panel.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #226 — ce qu'une suppression de PNJ propose, et surtout ce qu'elle refuse.
 *
 * <p>Les cas sont construits sur la forme réelle du relevé {@code npc.list}, et le PNJ central est
 * un <strong>PNJ de test</strong> : aucun test ne met en scène la suppression d'un PNJ de gameplay,
 * exactement comme le ticket le demande pour la validation manuelle.</p>
 */
class NpcDeletionAnalyzerTest {

    private static Map<String, Object> row(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("displayName", "PNJ de test");
        m.put("logicalDefinitionPresent", true);
        m.put("citizensBindingPresent", false);
        m.put("citizensNumericId", null);
        m.put("bindingCount", 0);
        m.put("enabled", true);
        m.put("sources", List.of("DEFINITION"));
        m.put("state", "NOT_LINKED");
        m.put("warnings", List.of());
        return m;
    }

    private static Map<String, Object> bound(String id, int citizens) {
        Map<String, Object> m = row(id);
        m.put("citizensBindingPresent", true);
        m.put("citizensNumericId", citizens);
        m.put("bindingCount", 1);
        m.put("sources", List.of("DEFINITION", "BINDING"));
        m.put("state", "LINKED");
        return m;
    }

    @SafeVarargs
    private static NpcDirectory dir(Map<String, Object>... rows) {
        return NpcDirectory.from(Map.of("npcs", List.of((Object[]) rows)));
    }

    // ---- Sans relevé : rien n'est proposé ------------------------------------------------------

    /**
     * L'exigence la plus importante du lot. « Aucune dépendance trouvée » et « on n'a pas regardé »
     * se ressemblent à l'écran, et devant un bouton de suppression la confusion détruit du contenu.
     */
    @Test
    void withoutASurveyNothingIsOffered() {
        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(NpcDirectory.unavailable(), "pnj_de_test");

        assertFalse(plan.surveyAvailable());
        assertTrue(plan.operations().isEmpty(), "aucune opération ne doit être proposée");
        assertFalse(plan.anyAvailable());
        assertTrue(plan.notes().get(0).contains("Aucun relevé"), plan.notes().toString());
    }

    @Test
    void anUnknownNpcOffersNothingEither() {
        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(row("autre_pnj")), "pnj_de_test");

        assertTrue(plan.surveyAvailable());
        assertTrue(plan.npc().isEmpty());
        assertTrue(plan.operations().isEmpty());
    }

    // ---- Le PNJ de test, sans dépendance ------------------------------------------------------

    @Test
    void aDefinitionWithoutDependenciesCanBeDeletedAlone() {
        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(row("pnj_de_test")), "pnj_de_test");

        NpcDeletionPlan.Operation a = plan.operation(Op.DEFINITION_ONLY).orElseThrow();
        assertTrue(a.available(), a.blockers().toString());
        assertTrue(a.effects().stream().anyMatch(e -> e.contains("npc-backups")),
                "la sauvegarde doit être annoncée : " + a.effects());
        assertTrue(a.effects().stream().anyMatch(e -> e.contains("progression")),
                "et le fait qu'aucune progression n'est effacée");
        assertFalse(a.reversible());
    }

    /** Sans Citizens lié, délier et détruire n'ont rien à faire : les deux sont bloquées. */
    @Test
    void withoutACitizensLinkTheCitizensOperationsAreBlocked() {
        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(row("pnj_de_test")), "pnj_de_test");

        assertFalse(plan.operation(Op.UNLINK_CITIZENS).orElseThrow().available());
        assertFalse(plan.operation(Op.DELETE_CITIZENS).orElseThrow().available());
        assertTrue(plan.operation(Op.DELETE_CITIZENS).orElseThrow().blockers().get(0)
                .contains("rien à détruire"), plan.operation(Op.DELETE_CITIZENS).orElseThrow()
                .blockers().toString());
    }

    @Test
    void aBoundNpcOffersUnlinkAndCitizensDeletionAndFullCleanup() {
        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(bound("pnj_de_test", 42)),
                "pnj_de_test");

        assertEquals(Integer.valueOf(42), plan.citizensNumericId());
        assertTrue(plan.operation(Op.UNLINK_CITIZENS).orElseThrow().available());
        assertTrue(plan.operation(Op.DELETE_CITIZENS).orElseThrow().available());
        assertTrue(plan.operation(Op.FULL_CLEANUP).orElseThrow().available());
        assertTrue(plan.operation(Op.UNLINK_CITIZENS).orElseThrow().reversible(),
                "délier se défait en reliant");
        assertFalse(plan.operation(Op.DELETE_CITIZENS).orElseThrow().reversible());
    }

    /** Supprimer la définition seule laisse le Citizens : il faut le dire, pas le découvrir après. */
    @Test
    void deletingTheDefinitionAloneWarnsThatCitizensSurvives() {
        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(bound("pnj_de_test", 42)),
                "pnj_de_test");

        NpcDeletionPlan.Operation a = plan.operation(Op.DEFINITION_ONLY).orElseThrow();

        assertTrue(a.available());
        assertTrue(a.effects().stream().anyMatch(e -> e.contains("orphelin")), a.effects().toString());
        assertTrue(a.effects().stream().anyMatch(e -> e.contains("NE touche PAS")), a.effects().toString());
    }

    // ---- Les dépendances bloquantes ----------------------------------------------------------

    @Test
    void aQuestGiverCannotLoseItsDefinition() {
        Map<String, Object> m = row("pnj_de_test");
        m.put("questsGiven", List.of("rpgquest:crystal_hunt"));
        m.put("sources", List.of("DEFINITION", "QUEST_GIVER"));

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(m), "pnj_de_test");

        NpcDeletionPlan.Operation a = plan.operation(Op.DEFINITION_ONLY).orElseThrow();
        assertFalse(a.available());
        assertTrue(a.blockers().get(0).contains("rpgquest:crystal_hunt"), a.blockers().toString());
        assertFalse(plan.operation(Op.FULL_CLEANUP).orElseThrow().available(),
                "le nettoyage complet hérite du même blocage");
        // Délier reste possible : ça ne casse aucune référence de contenu.
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("pas corrigées automatiquement")),
                plan.notes().toString());
    }

    @Test
    void aTalkObjectiveTargetIsAlsoBlocking() {
        Map<String, Object> m = row("pnj_de_test");
        m.put("questsReferenced", List.of("rpgquest:st0_meet_people"));

        var a = NpcDeletionAnalyzer.analyze(dir(m), "pnj_de_test")
                .operation(Op.DEFINITION_ONLY).orElseThrow();

        assertFalse(a.available());
        assertTrue(a.blockers().get(0).contains("parler à"), a.blockers().toString());
    }

    /**
     * La remise, invisible dans le catalogue avant ce lot. Un PNJ destinataire supprimé rend la
     * quête <em>infinissable</em> : c'est la dépendance la plus grave, et c'était la seule qu'aucun
     * écran ne montrait.
     */
    @Test
    void aDeliveryRecipientIsBlockingAndSaysWhy() {
        Map<String, Object> m = row("pnj_de_test");
        m.put("questsDelivering", List.of("rpgquest:test_remise"));

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(m), "pnj_de_test");
        var a = plan.operation(Op.DEFINITION_ONLY).orElseThrow();

        assertFalse(a.available());
        assertTrue(a.blockers().get(0).contains("rpgquest:test_remise"), a.blockers().toString());
        assertTrue(a.blockers().get(0).contains("infinissables"), a.blockers().toString());
        assertTrue(plan.layers().stream()
                        .anyMatch(l -> l.name().contains("Remises") && l.present()),
                "la couche « remises » doit apparaître présente");
    }

    // ---- Le dialogue, jamais supprimé ---------------------------------------------------------

    @Test
    void noOperationEverDeletesTheDialogue() {
        Map<String, Object> m = bound("pnj_de_test", 42);
        m.put("definedDialogueId", "rpgquest:pnj_de_test");
        m.put("dialogueId", "rpgquest:pnj_de_test");
        m.put("dialogueNodes", 3);

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(m), "pnj_de_test");

        for (Op op : List.of(Op.DEFINITION_ONLY, Op.FULL_CLEANUP)) {
            assertTrue(plan.operation(op).orElseThrow().effects().stream()
                            .anyMatch(e -> e.contains("NE supprime PAS le dialogue")),
                    op + " : " + plan.operation(op).orElseThrow().effects());
        }
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("ne supprime un dialogue")),
                plan.notes().toString());
    }

    /** Un dialogue partagé est signalé nommément : il reste utilisé après la suppression. */
    @Test
    void aSharedDialogueIsNamedInTheNotes() {
        Map<String, Object> mine = bound("pnj_de_test", 42);
        mine.put("definedDialogueId", "rpgquest:dialogue_commun");
        Map<String, Object> other = row("autre_pnj");
        other.put("definedDialogueId", "rpgquest:dialogue_commun");

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(mine, other), "pnj_de_test");

        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("PARTAGÉ")
                && n.contains("autre_pnj")), plan.notes().toString());
        assertTrue(plan.layers().stream().anyMatch(l -> l.name().equals("Dialogue lié")
                && l.detail().contains("PARTAGÉ")), plan.layers().toString());
    }

    // ---- Le cas Mira, sans jamais la supprimer ------------------------------------------------

    /**
     * Le dialogue lié ne porte pas le nom du PNJ : la page doit annoncer que supprimer la
     * définition laissera ce dialogue sans porteur — donc une entrée fantôme, explicable.
     */
    @Test
    void aDialogueNamedDifferentlyIsAnnouncedAsFutureOrphan() {
        Map<String, Object> m = bound("pnj_de_test", 42);
        m.put("definedDialogueId", "rpgquest:autre_nom");

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(m), "pnj_de_test");

        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("ne porte pas le nom du PNJ")
                && n.contains("rpgquest:autre_nom")), plan.notes().toString());
    }

    /** Une entrée sans définition n'a pas de définition à supprimer, et sa provenance est dite. */
    @Test
    void anOrphanEntryHasNothingToDeleteButIsExplained() {
        Map<String, Object> ghost = new LinkedHashMap<>();
        ghost.put("id", "pnj_de_test_dialogue");
        ghost.put("displayName", "Test");
        ghost.put("logicalDefinitionPresent", false);
        ghost.put("dialogueId", "rpgquest:pnj_de_test_dialogue");
        ghost.put("sources", List.of("DIALOGUE"));
        ghost.put("state", "UNDEFINED_REFERENCE");

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(ghost), "pnj_de_test_dialogue");

        assertFalse(plan.operation(Op.DEFINITION_ONLY).orElseThrow().available());
        assertTrue(plan.operation(Op.DEFINITION_ONLY).orElseThrow().blockers().get(0)
                .contains("pas de définition"), plan.operation(Op.DEFINITION_ONLY).orElseThrow()
                .blockers().toString());
        assertFalse(plan.operation(Op.FULL_CLEANUP).orElseThrow().available());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("Remédiations possibles")),
                plan.notes().toString());
    }

    // ---- Liaisons multiples ------------------------------------------------------------------

    /** Deux Citizens tagués pareil : on ne prétend pas traiter les deux. */
    @Test
    void severalBindingsAreFlaggedAsPartiallyHandled() {
        Map<String, Object> m = bound("pnj_de_test", 42);
        m.put("bindingCount", 2);

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(m), "pnj_de_test");

        assertTrue(plan.notes().stream().anyMatch(n -> n.startsWith("ATTENTION")
                && n.contains("2 PNJ Citizens")), plan.notes().toString());
    }

    /** Sans identifiant numérique, détruire est impossible : on ne devine pas une cible. */
    @Test
    void aBindingWithoutANumericIdCannotBeDeleted() {
        Map<String, Object> m = row("pnj_de_test");
        m.put("citizensBindingPresent", true);
        m.put("citizensNumericId", null);
        m.put("bindingCount", 1);

        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(m), "pnj_de_test");

        assertFalse(plan.operation(Op.DELETE_CITIZENS).orElseThrow().available());
        assertFalse(plan.operation(Op.UNLINK_CITIZENS).orElseThrow().available());
        assertTrue(plan.operation(Op.DELETE_CITIZENS).orElseThrow().blockers().get(0)
                .contains("sans cible explicite"));
    }

    // ---- Asynchronisme ------------------------------------------------------------------------

    /** La page ne doit jamais laisser croire qu'un aperçu vaut une garantie. */
    @Test
    void theAsynchronousRevalidationIsAnnounced() {
        NpcDeletionPlan plan = NpcDeletionAnalyzer.analyze(dir(row("pnj_de_test")), "pnj_de_test");

        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("ASYNCHRONES")
                && n.contains("revérifie")), plan.notes().toString());
    }
}
