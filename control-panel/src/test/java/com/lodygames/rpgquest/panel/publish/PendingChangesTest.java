package com.lodygames.rpgquest.panel.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #47 — la liste « Changements en attente », son filtrage et son tri.
 *
 * <p>Pur, donc réellement exécuté : les combinaisons famille × état × recherche sont nombreuses, et
 * c'est exactement le genre de conditions qu'on casse sans le voir.</p>
 */
class PendingChangesTest {

    private static PendingChanges.Row row(String kind, String slug, PublishState state) {
        return new PendingChanges.Row(kind, slug, "rpgquest:" + slug, slug + " (nom)", state,
                state == PublishState.RUNTIME_ONLY ? "" : "a".repeat(64),
                state == PublishState.SOURCE_ONLY ? "" : "b".repeat(64),
                state == PublishState.SYNCED, "2026-10-09T10:00:00Z");
    }

    // ---- Ce qui entre dans la liste -------------------------------------------------------------

    /** Seul ce qui demande une décision est listé. Le synchronisé n'a rien à y faire. */
    @Test
    void onlyResourcesNeedingADecisionAreListed() {
        PendingChanges pending = PendingChanges.from(List.of(
                row("quests", "sync", PublishState.SYNCED),
                row("quests", "a_publier", PublishState.SOURCE_ONLY),
                row("stories", "modifiee", PublishState.DIFFERENT)));

        assertEquals(2, pending.total());
        assertTrue(pending.rows().stream().noneMatch(r -> r.state() == PublishState.SYNCED));
    }

    @Test
    void anAllSyncedWorkspaceGivesAnEmptyList() {
        PendingChanges pending = PendingChanges.from(List.of(
                row("quests", "a", PublishState.SYNCED),
                row("stories", "b", PublishState.SYNCED)));

        assertTrue(pending.isEmpty());
        assertEquals(0, pending.total());
    }

    @Test
    void anEmptyOrNullInputIsHandled() {
        assertTrue(PendingChanges.from(List.of()).isEmpty());
        assertTrue(PendingChanges.from(null).isEmpty());
    }

    /**
     * Les problèmes d'abord.
     *
     * <p>Un administrateur qui ouvre cette page veut voir ce qui cloche en haut, pas le chercher.
     * Conflit, puis publié-non-chargé, puis le reste.</p>
     */
    @Test
    void problemsAreSortedFirst() {
        PendingChanges pending = PendingChanges.from(List.of(
                row("quests", "z_source", PublishState.SOURCE_ONLY),
                row("quests", "a_diff", PublishState.DIFFERENT),
                row("stories", "b_conflit", PublishState.CONFLICT),
                row("dialogues", "c_non_charge", PublishState.NOT_LOADED)));

        assertEquals(List.of("b_conflit", "c_non_charge", "a_diff", "z_source"),
                pending.rows().stream().map(PendingChanges.Row::slug).toList());
    }

    @Test
    void withinTheSamePriorityFamiliesThenIdentifiersOrderTheList() {
        PendingChanges pending = PendingChanges.from(List.of(
                row("stories", "b", PublishState.DIFFERENT),
                row("quests", "z", PublishState.DIFFERENT),
                row("quests", "a", PublishState.DIFFERENT),
                row("dialogues", "m", PublishState.DIFFERENT)));

        // Ordre des familles : quests, dialogues, stories (celui de l'affichage).
        assertEquals(List.of("a", "z", "m", "b"),
                pending.rows().stream().map(PendingChanges.Row::slug).toList());
    }

    // ---- Sélection pour la publication groupée --------------------------------------------------

    /**
     * Un conflit n'est <strong>jamais</strong> sélectionnable en lot.
     *
     * <p>Il exige d'avoir regardé la différence, ce qui se fait sur la fiche. L'inclure dans une
     * case à cocher reviendrait à offrir l'écrasement aveugle que le ticket interdit.</p>
     */
    @Test
    void aConflictIsNeverSelectableInABatch() {
        PendingChanges.Row conflict = row("quests", "c", PublishState.CONFLICT);

        assertFalse(conflict.selectable());
        assertTrue(conflict.state().needsAttention());
    }

    @Test
    void publishableStatesAreSelectableAndRuntimeOnlyIsNot() {
        assertTrue(row("quests", "a", PublishState.SOURCE_ONLY).selectable());
        assertTrue(row("quests", "b", PublishState.DIFFERENT).selectable());
        assertTrue(row("quests", "c", PublishState.NOT_LOADED).selectable());
        // Rien à envoyer : la ressource n'existe pas dans la source.
        assertFalse(row("quests", "d", PublishState.RUNTIME_ONLY).selectable());
        assertFalse(row("quests", "e", PublishState.UNKNOWN).selectable());
    }

    /** Sans empreinte de source, rien n'est sélectionnable : on ne publierait rien. */
    @Test
    void aRowWithoutASourceHashIsNotSelectable() {
        PendingChanges.Row noSource = new PendingChanges.Row("quests", "x", "rpgquest:x", "X",
                PublishState.DIFFERENT, "", "b".repeat(64), false, "");

        assertFalse(noSource.selectable());
    }

    @Test
    void countsAreReported() {
        PendingChanges pending = PendingChanges.from(List.of(
                row("quests", "a", PublishState.SOURCE_ONLY),
                row("quests", "b", PublishState.DIFFERENT),
                row("stories", "c", PublishState.CONFLICT),
                row("dialogues", "d", PublishState.NOT_LOADED)));

        assertEquals(4, pending.total());
        assertEquals(2, pending.countOf("quests"));
        assertEquals(1, pending.countOf("stories"));
        assertEquals(1, pending.countOf(PublishState.CONFLICT));
        assertEquals(2, pending.attentionCount(), "conflit + publié-non-chargé");
        assertEquals(3, pending.selectableCount(), "tout sauf le conflit");
    }

    // ---- Filtres --------------------------------------------------------------------------------

    private PendingChanges sample() {
        return PendingChanges.from(List.of(
                row("quests", "secur_environs", PublishState.SOURCE_ONLY),
                row("quests", "autre_quete", PublishState.DIFFERENT),
                row("dialogues", "garde", PublishState.CONFLICT),
                row("stories", "memoires", PublishState.DIFFERENT)));
    }

    @Test
    void filteringByFamilyKeepsOnlyThatFamily() {
        assertEquals(2, sample().filter("quests", "", "").total());
        assertEquals(1, sample().filter("dialogues", "", "").total());
        assertEquals(1, sample().filter("stories", "", "").total());
    }

    @Test
    void filteringByStateKeepsOnlyThatState() {
        assertEquals(2, sample().filter("", "different", "").total());
        assertEquals(1, sample().filter("", "conflict", "").total());
        assertEquals(1, sample().filter("", "source_only", "").total());
    }

    @Test
    void familyAndStateFiltersCombine() {
        assertEquals(1, sample().filter("quests", "different", "").total());
        assertEquals(0, sample().filter("quests", "conflict", "").total(),
                "le conflit est un dialogue, pas une quête");
    }

    @Test
    void searchMatchesIdentifierNameAndDeclaredId() {
        assertEquals(1, sample().filter("", "", "environs").total(), "fragment d'identifiant");
        assertEquals(1, sample().filter("", "", "ENVIRONS").total(), "insensible à la casse");
        assertEquals(1, sample().filter("", "", "garde (nom)").total(), "fragment de nom");
        assertEquals(1, sample().filter("", "", "rpgquest:memoires").total(),
                "identifiant déclaré");
        assertEquals(0, sample().filter("", "", "introuvable").total());
    }

    @Test
    void emptyAndAllMeanNoFilter() {
        assertEquals(4, sample().filter("", "", "").total());
        assertEquals(4, sample().filter("all", "all", "").total());
        assertEquals(4, sample().filter("tous", "TOUS", "   ").total());
        assertEquals(4, sample().filter(null, null, null).total());
    }

    @Test
    void anUnknownFilterValueMatchesNothingRatherThanEverything() {
        assertEquals(0, sample().filter("npcs", "", "").total(),
                "une famille non publiable ne doit pas tout afficher");
        assertEquals(0, sample().filter("", "pas_un_etat", "").total());
    }

    @Test
    void filteringPreservesTheProblemsFirstOrder() {
        PendingChanges filtered = sample().filter("", "", "");

        assertEquals(PublishState.CONFLICT, filtered.rows().get(0).state());
    }

    // ---- La clé de formulaire -------------------------------------------------------------------

    /**
     * La clé identifie la ressource de façon unique, et sert de nom de champ.
     *
     * <p>C'est ce qui garantit qu'une ressource ne peut pas être publiée deux fois dans le même
     * lot : deux cases de même nom n'existent pas.</p>
     */
    @Test
    void theKeyIsUniquePerResourceAndUsableAsAFieldName() {
        assertEquals("quests/secur_environs",
                row("quests", "secur_environs", PublishState.DIFFERENT).key());
        assertEquals("dialogues/garde", row("dialogues", "garde", PublishState.DIFFERENT).key());
        // Pas d'espace ni de caractère exotique : utilisable tel quel dans un name="".
        assertTrue(row("quests", "a_b-c", PublishState.DIFFERENT).key().matches("[a-z]+/[a-z0-9_-]+"));
    }

    @Test
    void shortHashesAreReadableAndAbsenceIsShown() {
        PendingChanges.Row r = row("quests", "a", PublishState.SOURCE_ONLY);

        assertEquals(12, r.shortSource().length());
        assertEquals("—", r.shortDev(), "absente de DEV");
    }

    @Test
    void theFamilyLabelIsHumanAndSingular() {
        assertEquals("Quête", row("quests", "a", PublishState.DIFFERENT).kindLabel());
        assertEquals("Story", row("stories", "a", PublishState.DIFFERENT).kindLabel());
        assertEquals("Dialogue", row("dialogues", "a", PublishState.DIFFERENT).kindLabel());
    }

    @Test
    void aMissingNameFallsBackToTheIdentifier() {
        PendingChanges.Row r = new PendingChanges.Row("quests", "sans_nom", "rpgquest:sans_nom",
                "  ", PublishState.DIFFERENT, "a".repeat(64), "b".repeat(64), false, null);

        assertEquals("sans_nom", r.name());
        assertEquals("", r.verifiedAt());
    }
}
