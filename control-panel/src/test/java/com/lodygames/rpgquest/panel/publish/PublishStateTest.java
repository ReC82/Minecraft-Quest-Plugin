package com.lodygames.rpgquest.panel.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #47 — le vocabulaire d'état, partagé par toutes les pages.
 *
 * <p>Ces tests existent pour une raison précise : avant #47, {@code SYNCED} voulait seulement dire
 * « présent des deux côtés ». Une quête modifiée dans la source mais jamais republiée s'affichait
 * donc « Synchronisé », ce qui était faux et invisible. La règle est maintenant une fonction pure,
 * et chaque combinaison est vérifiée ici — y compris celles qui n'arrivent pas souvent.</p>
 */
class PublishStateTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    // ---- Le défaut historique que #47 corrige ---------------------------------------------------

    /**
     * Identiques ET confirmé par le moteur : c'est le seul cas qui mérite « Synchronisé ».
     */
    @Test
    void identicalAndLoadedIsTheOnlySynchronizedCase() {
        assertEquals(PublishState.SYNCED,
                PublishState.of(true, true, SHA_A, SHA_A, true, SHA_A));
    }

    /**
     * Identiques mais le moteur ne la voit pas : <strong>pas</strong> « Synchronisé ».
     *
     * <p>C'est le cas d'un fichier déposé sans rechargement. L'ancien modèle l'appelait
     * « Synchronisé » parce que les deux côtés existaient.</p>
     */
    @Test
    void identicalButNotLoadedIsNotSynchronized() {
        PublishState state = PublishState.of(true, true, SHA_A, SHA_A, false, SHA_A);

        assertEquals(PublishState.NOT_LOADED, state);
        assertFalse(state == PublishState.SYNCED);
        assertTrue(state.needsAttention());
        assertTrue(state.publishable(), "republier est la bonne réponse");
    }

    /**
     * Présents des deux côtés mais différents : « Différent », et non « Synchronisé ».
     *
     * <p>Le cas le plus courant après une édition — et celui que l'ancien modèle masquait.</p>
     */
    @Test
    void presentOnBothSidesButDifferentIsNotSynchronized() {
        PublishState state = PublishState.of(true, true, SHA_A, SHA_B, true, SHA_B);

        assertEquals(PublishState.DIFFERENT, state);
        assertTrue(state.publishable());
    }

    // ---- Les cas simples ------------------------------------------------------------------------

    @Test
    void sourceWithoutDevIsSourceOnly() {
        PublishState state = PublishState.of(true, true, SHA_A, "", false, "");

        assertEquals(PublishState.SOURCE_ONLY, state);
        assertTrue(state.publishable(), "c'est le cas de référence de #47 : il faut pouvoir publier");
        assertEquals("Publier sur DEV", state.action());
    }

    @Test
    void devWithoutSourceIsRuntimeOnly() {
        PublishState state = PublishState.of(true, true, "", SHA_A, true, "");

        assertEquals(PublishState.RUNTIME_ONLY, state);
        assertFalse(state.publishable(), "il n'y a rien à envoyer");
        assertEquals(null, state.action());
    }

    @Test
    void neitherSideIsUnknownRatherThanSynced() {
        assertEquals(PublishState.UNKNOWN, PublishState.of(true, true, "", "", false, ""));
    }

    // ---- Conflit : la distinction qui protège le travail d'autrui -------------------------------

    /**
     * DEV ne correspond ni à la source, ni à ce que NOUS y avions publié : quelqu'un d'autre l'a
     * modifié.
     *
     * <p>C'est la différence entre « je n'ai pas publié » et « quelqu'un a touché au fichier », et
     * elle décide si l'on propose de publier ou de regarder d'abord.</p>
     */
    @Test
    void aDevFileChangedBySomeoneElseIsAConflictAndNotJustDifferent() {
        // On avait publié SHA_A ; DEV porte maintenant SHA_B, que nous n'avons pas écrit.
        PublishState state = PublishState.of(true, true, "c".repeat(64), SHA_B, true, SHA_A);

        assertEquals(PublishState.CONFLICT, state);
        assertTrue(state.needsAttention());
        assertEquals("Voir les différences", state.action(),
                "on regarde avant de décider, on ne propose pas d'écraser");
    }

    /** Si DEV correspond à ce que nous avions publié, l'écart vient de nous : simple « différent ». */
    @Test
    void aDevFileStillMatchingOurLastPublishIsOnlyDifferent() {
        assertEquals(PublishState.DIFFERENT,
                PublishState.of(true, true, SHA_B, SHA_A, true, SHA_A));
    }

    /** Sans publication antérieure connue, on ne peut pas accuser : c'est « différent ». */
    @Test
    void withoutAKnownPreviousPublishADifferenceIsNotAccusedOfBeingAConflict() {
        assertEquals(PublishState.DIFFERENT,
                PublishState.of(true, true, SHA_A, SHA_B, true, ""));
        assertEquals(PublishState.DIFFERENT,
                PublishState.of(true, true, SHA_A, SHA_B, true, null));
    }

    // ---- Ne rien affirmer sans relevé -----------------------------------------------------------

    /**
     * Sans relevé du serveur, l'état est <strong>inconnu</strong>.
     *
     * <p>Et pas « Source uniquement », qui serait une affirmation sur un serveur qu'on n'a pas
     * interrogé.</p>
     */
    @Test
    void withoutADevReadingNothingIsAsserted() {
        assertEquals(PublishState.UNKNOWN,
                PublishState.of(false, true, SHA_A, "", false, ""));
        assertEquals(PublishState.UNKNOWN,
                PublishState.of(false, true, SHA_A, SHA_A, true, SHA_A));
    }

    /** Sans espace de travail source non plus : on ne qualifie pas tout de « hors source ». */
    @Test
    void withoutASourceWorkspaceNothingIsAsserted() {
        assertEquals(PublishState.UNKNOWN,
                PublishState.of(true, false, "", SHA_A, true, ""));
    }

    // ---- Le contrat de l'énumération ------------------------------------------------------------

    /** Chaque état porte un code, un libellé et une explication. Aucun n'est muet. */
    @Test
    void everyStateCarriesACodeALabelAndAnExplanation() {
        for (PublishState state : PublishState.values()) {
            assertEquals(state.name(), state.code(), "le code doit être stable");
            assertNotNull(state.label());
            assertFalse(state.label().isBlank(), state.name());
            assertNotNull(state.explanation());
            assertFalse(state.explanation().isBlank(), state.name());
            assertTrue(List.of("ok", "warn", "err", "info").contains(state.tone()), state.name());
        }
    }

    /** Tout état actionnable propose une action ; tout état sans action n'en propose pas. */
    @Test
    void everyActionableStateProposesSomethingToDo() {
        for (PublishState state : PublishState.values()) {
            if (state.publishable() || state.needsAttention() || state == PublishState.UNKNOWN) {
                assertNotNull(state.action(), state.name() + " doit proposer une action");
                assertFalse(state.action().isBlank(), state.name());
            }
        }
        assertEquals(null, PublishState.SYNCED.action(), "rien à faire quand tout va bien");
        assertEquals(null, PublishState.RUNTIME_ONLY.action(), "rien à envoyer");
    }

    /** Les libellés sont uniques : deux états ne doivent pas se lire pareil à l'écran. */
    @Test
    void noTwoStatesShareTheSameLabel() {
        List<String> labels = java.util.Arrays.stream(PublishState.values())
                .map(PublishState::label).toList();

        assertEquals(labels.size(), new java.util.HashSet<>(labels).size(), labels.toString());
    }

    // ---- L'index DEV ----------------------------------------------------------------------------

    @Test
    void anUnavailableIndexMakesEverythingUnknown() {
        DevContentIndex index = DevContentIndex.unavailable();

        assertFalse(index.available());
        assertEquals("", index.devSha("quests", "x"));
        assertFalse(index.present("quests", "x"));
        assertFalse(index.runtimeLoaded("quests", "rpgquest:x"));
        assertEquals(PublishState.UNKNOWN,
                index.stateOf("quests", "x", "rpgquest:x", SHA_A, true, ""));
    }

    @Test
    void theIndexReadsFilesAndRuntimeIdsSeparately() {
        DevContentIndex index = DevContentIndex.from(Map.of(
                "files", List.of(
                        Map.of("kind", "quests", "slug", "tc265", "sha256", SHA_A, "bytes", 617),
                        Map.of("kind", "dialogues", "slug", "guard", "sha256", SHA_B, "bytes", 900)),
                "runtimeIds", Map.of("quests", List.of("rpgquest:tc265"),
                        "dialogues", List.of()),
                "runtimeHash", "abc123"));

        assertTrue(index.available());
        assertEquals(SHA_A, index.devSha("quests", "tc265"));
        assertTrue(index.present("quests", "tc265"));
        assertTrue(index.runtimeLoaded("quests", "rpgquest:tc265"));
        assertTrue(index.runtimeLoaded("quests", "tc265"), "tolérant au préfixe");
        // Le dialogue a son FICHIER mais n'est pas chargé : les deux informations sont distinctes.
        assertTrue(index.present("dialogues", "guard"));
        assertFalse(index.runtimeLoaded("dialogues", "guard"));
        assertEquals(PublishState.NOT_LOADED,
                index.stateOf("dialogues", "guard", "rpgquest:guard", SHA_B, true, SHA_B));
        assertEquals("abc123", index.runtimeHash());
    }

    @Test
    void theIndexIsCaseInsensitiveOnKindsAndSlugs() {
        DevContentIndex index = DevContentIndex.from(Map.of(
                "files", List.of(Map.of("kind", "QUESTS", "slug", "TC265", "sha256", SHA_A)),
                "runtimeIds", Map.of("QUESTS", List.of("RPGQuest:TC265"))));

        assertEquals(SHA_A, index.devSha("quests", "tc265"));
        assertTrue(index.runtimeLoaded("quests", "rpgquest:tc265"));
    }

    @Test
    void theIndexSurvivesAMalformedReading() {
        assertFalse(DevContentIndex.from(null).available());
        // Un relevé présent mais vide est « disponible » et simplement vide : ce n'est pas la même
        // chose qu'un relevé absent, et la distinction change l'état affiché.
        DevContentIndex empty = DevContentIndex.from(Map.of());
        assertTrue(empty.available());
        assertEquals(PublishState.SOURCE_ONLY,
                empty.stateOf("quests", "x", "rpgquest:x", SHA_A, true, ""));
    }

    @Test
    void theIndexListsWhatDevCarriesPerFamily() {
        DevContentIndex index = DevContentIndex.from(Map.of(
                "files", List.of(
                        Map.of("kind", "quests", "slug", "zeta", "sha256", SHA_A),
                        Map.of("kind", "quests", "slug", "alpha", "sha256", SHA_B),
                        Map.of("kind", "stories", "slug", "s1", "sha256", SHA_A)),
                "runtimeIds", Map.of()));

        assertEquals(List.of("alpha", "zeta"), index.devSlugs("quests"), "triés");
        assertEquals(List.of("s1"), index.devSlugs("stories"));
        assertEquals(List.of(), index.devSlugs("dialogues"));
    }
}
