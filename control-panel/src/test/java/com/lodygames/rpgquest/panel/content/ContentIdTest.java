package com.lodygames.rpgquest.panel.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Issue #223 — l'identifiant saisi à la main : les deux écritures acceptées, le namespace jamais
 * doublé, et un refus <strong>avant</strong> l'appel payant plutôt qu'après.
 */
class ContentIdTest {

    // ---- Quête : forme canonique namespacée ----------------------------------------------------

    @Test
    void aLocalQuestIdGetsTheNamespace() {
        ContentId.Normalized n = ContentId.quest("tc265_ai_securiser_environs");

        assertTrue(n.ok(), n.error());
        assertEquals("rpgquest:tc265_ai_securiser_environs", n.canonical());
        assertEquals("tc265_ai_securiser_environs", n.key());
    }

    /** Le parcours que l'utilisateur avait tenté le premier jour : il doit marcher aussi. */
    @Test
    void anAlreadyNamespacedQuestIdIsAcceptedAsIs() {
        ContentId.Normalized n = ContentId.quest("rpgquest:tc265_ai_securiser_environs");

        assertTrue(n.ok(), n.error());
        assertEquals("rpgquest:tc265_ai_securiser_environs", n.canonical());
    }

    /** Le défaut qu'il ne faut jamais produire, quelle que soit la saisie. */
    @Test
    void theNamespaceIsNeverDoubled() {
        for (String raw : new String[] {"x", "rpgquest:x", "  RPGQUEST:X  "}) {
            assertEquals("rpgquest:x", ContentId.quest(raw).canonical(), raw);
        }
        assertFalse(ContentId.quest("rpgquest:rpgquest:x").ok(),
                "deux namespaces empilés sont une faute de saisie, pas une forme à réparer");
    }

    @Test
    void aForeignNamespaceIsRefusedAndTheMessageSaysWhatToWrite() {
        ContentId.Normalized n = ContentId.quest("testia:securiser_environs");

        assertFalse(n.ok());
        assertTrue(n.error().contains("rpgquest:"), n.error());
        assertTrue(n.error().contains("securiser_environs"),
                "le message doit proposer la clé à écrire : " + n.error());
    }

    @Test
    void forbiddenCharactersAreRefused() {
        for (String raw : new String[] {"Securiser Environs", "mines/oubliees", "mines.yml",
                "_debut", "-debut", "étape_un", "a".repeat(65)}) {
            assertFalse(ContentId.quest(raw).ok(), "devrait être refusé : " + raw);
        }
    }

    @Test
    void acceptedKeysCoverWhatTheImportCanWrite() {
        for (String raw : new String[] {"a", "mines_oubliees", "tc265-ai", "kit_tier2", "9lives",
                "a".repeat(64)}) {
            assertTrue(ContentId.quest(raw).ok(), "devrait être accepté : " + raw);
        }
    }

    /** Vide = l'IA est libre : ce n'est ni une erreur, ni un identifiant. */
    @Test
    void anEmptyIdIsNeitherAnErrorNorAnId() {
        for (String raw : new String[] {null, "", "   "}) {
            ContentId.Normalized n = ContentId.quest(raw);
            assertTrue(n.ok());
            assertTrue(n.empty());
            assertEquals("", n.canonical());
            assertNull(n.error());
        }
    }

    // ---- Dialogue et story : clé nue -----------------------------------------------------------

    /**
     * Une story et un dialogue n'ont <strong>pas</strong> de namespace dans le champ {@code id} d'un
     * pack : leur identifiant est le nom du fichier. Laisser passer « rpgquest:x » y produirait un
     * slug invalide, donc on le retire au lieu de le refuser.
     */
    @Test
    void aDialogueOrStoryIdIsBroughtBackToItsBareKey() {
        assertEquals("mira_first_map", ContentId.dialogue("rpgquest:mira_first_map").canonical());
        assertEquals("mira_first_map", ContentId.dialogue("mira_first_map").canonical());
        assertEquals("premiers_pas", ContentId.story("rpgquest:premiers_pas").canonical());
        assertEquals("premiers_pas", ContentId.story("premiers_pas").canonical());
    }

    @Test
    void aForeignNamespaceIsRefusedForDialoguesToo() {
        assertFalse(ContentId.dialogue("autre:mira").ok());
        assertFalse(ContentId.story("autre:premiers_pas").ok());
    }

    // ---- Cohérence avec l'import ---------------------------------------------------------------

    /**
     * La règle de saisie et celle de l'import sont la <strong>même</strong> expression : une clé
     * acceptée ici ne peut donc pas être refusée plus loin, ce qui était exactement le piège du
     * ticket — un formulaire qui accepte, un validateur qui refuse après l'appel.
     */
    @Test
    void theAcceptedPatternIsTheOneTheWorkspaceWrites() {
        assertTrue("mines_oubliees".matches(ContentId.KEY_PATTERN));
        assertFalse("Mines".matches(ContentId.KEY_PATTERN));
        assertFalse("mines oubliees".matches(ContentId.KEY_PATTERN));
        assertFalse("rpgquest:mines".matches(ContentId.KEY_PATTERN));
    }

    /** {@code plainKey} sert à comparer : deux écritures du même id ne sont pas deux ids. */
    @Test
    void plainKeyMakesTwoWritingsComparable() {
        assertEquals(ContentId.plainKey("rpgquest:mines").key(), ContentId.plainKey("mines").key());
    }
}
