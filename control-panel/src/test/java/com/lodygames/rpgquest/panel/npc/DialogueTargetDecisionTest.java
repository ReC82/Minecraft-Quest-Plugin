package com.lodygames.rpgquest.panel.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #225 — quel dialogue une génération doit viser, et dans quel cas il faut <strong>demander
 * avant d'appeler l'IA</strong>.
 */
class DialogueTargetDecisionTest {

    private static Map<String, Object> npc(String id, String declaredDialogue, Integer citizens) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("displayName", id);
        m.put("logicalDefinitionPresent", true);
        m.put("citizensBindingPresent", citizens != null);
        m.put("citizensNumericId", citizens);
        m.put("bindingCount", citizens == null ? 0 : 1);
        m.put("enabled", true);
        m.put("definedDialogueId", declaredDialogue);
        m.put("dialogueNodes", declaredDialogue == null ? 0 : 2);
        m.put("dialogueChoices", declaredDialogue == null ? 0 : 3);
        m.put("sources", List.of("DEFINITION"));
        m.put("state", "LINKED");
        m.put("warnings", List.of());
        return m;
    }

    /** Le catalogue réel : Mira la Cartographe, Citizens #9, dialogue « mira_first_map ». */
    private static NpcDirectory miraDirectory() {
        Map<String, Object> ghost = new LinkedHashMap<>();
        ghost.put("id", "mira_first_map");
        ghost.put("displayName", "Mira");
        ghost.put("logicalDefinitionPresent", false);
        ghost.put("dialogueId", "rpgquest:mira_first_map");
        ghost.put("dialogueNodes", 2);
        ghost.put("dialogueChoices", 3);
        ghost.put("sources", List.of("DIALOGUE"));
        ghost.put("state", "UNDEFINED_REFERENCE");
        return NpcDirectory.from(Map.of("npcs",
                List.of(npc("mira_cartographer", "rpgquest:mira_first_map", 9), ghost)));
    }

    // ---- Le cas Mira ---------------------------------------------------------------------------

    /**
     * Le défaut exact du ticket : sans décision, l'atelier produisait
     * {@code rpgquest:mira_cartographer}, un second dialogue que rien ne reliait au PNJ. Désormais
     * la génération <strong>s'arrête</strong> et pose la question.
     */
    @Test
    void anNpcWithADifferentlyNamedDialogueMustDecideFirst() {
        DialogueTargetDecision.Outcome out =
                DialogueTargetDecision.resolve(miraDirectory(), "mira_cartographer", null);

        assertTrue(out.needsDecision(), "la question doit être posée avant tout appel");
        assertEquals("", out.dialogueKey(), "aucune cible n'est choisie à la place de l'utilisateur");
        assertEquals("mira_first_map", out.existingKey());
        assertEquals("mira_cartographer", out.npc().id());
        assertEquals(Integer.valueOf(9), out.npc().citizensNumericId());
        assertFalse(out.related().isEmpty(), "l'entrée fantôme apparentée doit être montrée");
    }

    @Test
    void choosingToEditTheExistingDialogueTargetsIt() {
        DialogueTargetDecision.Outcome out = DialogueTargetDecision.resolve(miraDirectory(),
                "mira_cartographer", "EDIT_EXISTING");

        assertFalse(out.needsDecision());
        assertEquals("mira_first_map", out.dialogueKey(),
                "le dialogue visé est celui réellement lié, pas l'id du PNJ");
        assertTrue(out.hasNote());
        assertTrue(out.note().contains("aucun second dialogue"), out.note());
    }

    /** L'autre intention légitime — mais elle doit annoncer ce qu'elle laisse derrière elle. */
    @Test
    void choosingANewDialogueWarnsThatTheLinkMustStillBeChanged() {
        DialogueTargetDecision.Outcome out = DialogueTargetDecision.resolve(miraDirectory(),
                "mira_cartographer", "NEW_REPLACING_LINK");

        assertFalse(out.needsDecision());
        assertEquals("mira_cartographer", out.dialogueKey());
        assertTrue(out.note().startsWith("ATTENTION"), out.note());
        assertTrue(out.note().contains("repointée") || out.note().contains("repoint"), out.note());
        assertTrue(out.note().contains("mira_first_map"),
                "l'ancien dialogue doit être nommé : il n'est ni supprimé ni délié");
    }

    // ---- Les cas sans ambiguïté ----------------------------------------------------------------

    /** Aucun dialogue : la convention du moteur s'applique, et c'est dit. */
    @Test
    void anNpcWithoutADialogueGetsOneNamedAfterIt() {
        NpcDirectory dir = NpcDirectory.from(Map.of("npcs", List.of(npc("jo", null, 3))));

        DialogueTargetDecision.Outcome out = DialogueTargetDecision.resolve(dir, "jo", null);

        assertFalse(out.needsDecision());
        assertEquals("jo", out.dialogueKey());
        assertEquals("", out.existingKey());
        assertTrue(out.note().contains("aucun dialogue"), out.note());
    }

    /**
     * Le dialogue porte déjà le nom du PNJ : il n'y a qu'une lecture possible, remplacer celui-là.
     * On ne pose pas une question dont la réponse est unique.
     */
    @Test
    void anNpcWhoseDialogueAlreadyBearsItsNameNeedsNoQuestion() {
        NpcDirectory dir = NpcDirectory.from(Map.of("npcs",
                List.of(npc("guide", "rpgquest:guide", 1))));

        DialogueTargetDecision.Outcome out = DialogueTargetDecision.resolve(dir, "guide", null);

        assertFalse(out.needsDecision());
        assertEquals("guide", out.dialogueKey());
        assertTrue(out.note().contains("remplacera"), out.note());
        assertTrue(out.note().contains("confirmation"), out.note());
    }

    @Test
    void anEmptyNpcLeavesEverythingToTheModel() {
        DialogueTargetDecision.Outcome out =
                DialogueTargetDecision.resolve(miraDirectory(), "", null);

        assertFalse(out.needsDecision());
        assertEquals("", out.dialogueKey());
        assertFalse(out.hasNote());
    }

    /** Les deux écritures du PNJ désignent le même PNJ. */
    @Test
    void theNpcCanBeWrittenWithOrWithoutTheNamespace() {
        for (String raw : new String[] {"mira_cartographer", "rpgquest:mira_cartographer",
                "  MIRA_CARTOGRAPHER "}) {
            assertTrue(DialogueTargetDecision.resolve(miraDirectory(), raw, null).needsDecision(),
                    raw);
        }
    }

    // ---- Données manquantes --------------------------------------------------------------------

    /**
     * Sans relevé, on ne peut ni vérifier ni bloquer indéfiniment : on applique la convention
     * <strong>et on dit qu'on n'a pas pu vérifier</strong>. Taire le doute serait le pire choix.
     */
    @Test
    void withoutASurveyTheConventionAppliesButTheDoubtIsSaid() {
        DialogueTargetDecision.Outcome out = DialogueTargetDecision.resolve(
                NpcDirectory.unavailable(), "mira_cartographer", null);

        assertFalse(out.needsDecision());
        assertEquals("mira_cartographer", out.dialogueKey());
        assertTrue(out.note().contains("Aucun relevé"), out.note());
        assertTrue(out.note().contains("deuxième dialogue"), out.note());
    }

    @Test
    void anUnknownNpcIsFlaggedAsUnreachable() {
        DialogueTargetDecision.Outcome out =
                DialogueTargetDecision.resolve(miraDirectory(), "inconnu", null);

        assertFalse(out.needsDecision());
        assertEquals("inconnu", out.dialogueKey());
        assertTrue(out.note().contains("ne figure pas"), out.note());
        assertTrue(out.note().contains("personne ne pourra lui parler"), out.note());
    }

    @Test
    void anUnknownChoiceValueIsTreatedAsNoChoice() {
        assertTrue(DialogueTargetDecision.resolve(miraDirectory(), "mira_cartographer", "n_importe")
                .needsDecision());
        assertTrue(DialogueTargetDecision.Choice.of("edit_existing").isPresent(),
                "la casse ne doit pas faire perdre une décision prise");
    }
}
