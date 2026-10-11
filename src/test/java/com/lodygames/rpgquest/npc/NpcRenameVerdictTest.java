package com.lodygames.rpgquest.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.npc.NpcRenameVerdict.Layer;
import com.lodygames.rpgquest.npc.NpcRenameVerdict.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #197 — un renommage coordonné ne doit jamais présenter un demi-succès comme un succès.
 *
 * <p>Ces tests couvrent la panne partielle demandée par le ticket : chaque combinaison de couches,
 * et la certitude que seule celle où <em>les deux</em> sont à jour est annoncée comme réussie.</p>
 */
class NpcRenameVerdictTest {

    @Test
    @DisplayName("Les deux couches à jour : succès complet, et il le dit")
    void bothLayersUpdatedIsTheOnlyFullSuccess() {
        Verdict v = NpcRenameVerdict.of("guide", "Elias", "Guide",
                Layer.UPDATED, Layer.UPDATED, "Elias", "Elias", "");

        assertTrue(v.ok());
        assertEquals("RENAMED", v.code());
        assertTrue(v.message().contains("Elias"));
        assertTrue(v.message().contains("relus identiques"), "le succès repose sur une relecture");
        assertTrue(v.message().contains("guide"), "l'identifiant est rappelé comme inchangé");
    }

    @Test
    @DisplayName("Citizens à jour, définition en retard : ÉCHEC, et les deux noms sont nommés")
    void aStaleDefinitionIsAFailureNotAPartialSuccess() {
        Verdict v = NpcRenameVerdict.of("guide", "Elias", "Guide",
                Layer.STALE, Layer.UPDATED, "Elias", "Guide", "");

        assertFalse(v.ok(), "une seule couche changée n'est PAS un succès");
        assertEquals("DEFINITION_NOT_RENAMED", v.code());
        assertTrue(v.message().contains("« Elias » (à jour)"), "la couche à jour est nommée");
        assertTrue(v.message().contains("« Guide » (PAS à jour)"), "la couche en retard aussi");
        assertTrue(v.message().contains("Relancer l'opération est sans risque"),
                "la reprise est annoncée : l'opération est idempotente");
    }

    @Test
    @DisplayName("Définition à jour, Citizens en retard : ÉCHEC symétrique")
    void aStaleCitizensNameIsAlsoAFailure() {
        Verdict v = NpcRenameVerdict.of("guide", "Elias", "Guide",
                Layer.UPDATED, Layer.STALE, "Guide", "Elias", "");

        assertFalse(v.ok());
        assertEquals("CITIZENS_NOT_RENAMED", v.code());
        assertTrue(v.message().contains("Renommage incomplet"));
    }

    @Test
    @DisplayName("La cause de l'échec de la définition est reprise quand elle est connue")
    void theDefinitionFailureReasonIsQuotedWhenKnown() {
        Verdict v = NpcRenameVerdict.of("guide", "Elias", "Guide",
                Layer.STALE, Layer.UPDATED, "Elias", "Guide", "Fichier en lecture seule.");

        assertTrue(v.message().contains("Fichier en lecture seule."), "la raison réelle est citée");
    }

    @Test
    @DisplayName("Une cause absente ne transforme pas une divergence en succès")
    void anUnknownCauseStillLeavesADivergenceAsAFailure() {
        Verdict v = NpcRenameVerdict.of("guide", "Elias", "Guide",
                Layer.STALE, Layer.UPDATED, "Elias", "Guide", "");

        assertFalse(v.ok(), "on ne tait pas une divergence faute d'explication");
    }

    // ---- Couches légitimement absentes ------------------------------------------------------

    @Test
    @DisplayName("PNJ sans Citizens : succès, et il dit qu'il n'y avait rien à synchroniser")
    void adefinitionOnlyNpcSucceedsWithoutPretendingToHaveTouchedTheGame() {
        Verdict v = NpcRenameVerdict.of("mira_cartographer", "Mira", "Ancienne Mira",
                Layer.UPDATED, Layer.ABSENT, "", "Mira", "");

        assertTrue(v.ok());
        assertEquals("RENAMED_DEFINITION_ONLY", v.code());
        assertTrue(v.message().contains("Aucun PNJ Citizens n'est lié"),
                "le succès est borné à ce qui existe");
    }

    @Test
    @DisplayName("PNJ Citizens sans définition (cas « Help ») : succès, sans fiche créée à son insu")
    void acitizensOnlyNpcSucceedsWithoutCreatingADefinitionBehindTheAdminsBack() {
        Verdict v = NpcRenameVerdict.of("help", "Aide", "Help",
                Layer.ABSENT, Layer.UPDATED, "Aide", "", "");

        assertTrue(v.ok());
        assertEquals("RENAMED_CITIZENS_ONLY", v.code());
        assertTrue(v.message().contains("n'a pas de définition RPGQuest"));
        assertTrue(v.message().contains("Aucune définition n'a été créée"),
                "la non-création est explicite : c'est une garantie, pas un oubli");
    }

    @Test
    @DisplayName("Ni définition ni Citizens : rien à renommer, et ce n'est pas un succès")
    void anNpcWithNeitherLayerIsNotFound() {
        Verdict v = NpcRenameVerdict.of("fantome", "Fantôme", "",
                Layer.ABSENT, Layer.ABSENT, "", "", "");

        assertFalse(v.ok());
        assertEquals("NOT_FOUND", v.code());
    }

    // ---- Robustesse des messages ------------------------------------------------------------

    @Test
    @DisplayName("Sans nom précédent connu, le message reste correct plutôt que bancal")
    void anUnknownPreviousNameDoesNotProduceABrokenSentence() {
        Verdict v = NpcRenameVerdict.of("guide", "Elias", "",
                Layer.UPDATED, Layer.UPDATED, "Elias", "Elias", "");

        assertTrue(v.ok());
        assertFalse(v.message().contains("«  »"), "aucun nom vide entre guillemets");
        assertFalse(v.message().contains("→"), "pas de flèche sans origine");
    }

    @Test
    @DisplayName("Un nom Citizens illisible est dit illisible, jamais inventé")
    void anUnreadableCitizensNameIsSaidToBeUnreadable() {
        Verdict v = NpcRenameVerdict.of("guide", "Elias", "Guide",
                Layer.UPDATED, Layer.STALE, "", "Elias", "");

        assertFalse(v.ok());
        assertTrue(v.message().contains("illisible"));
    }

    @Test
    @DisplayName("Aucune combinaison ne rend un code ou un message vide")
    void noCombinationEverRendersAnEmptyCodeOrMessage() {
        for (Layer definition : Layer.values()) {
            for (Layer citizens : Layer.values()) {
                Verdict v = NpcRenameVerdict.of("guide", "Elias", "Guide",
                        definition, citizens, "Elias", "Elias", "");
                assertFalse(v.code().isBlank(), "code vide pour " + definition + "/" + citizens);
                assertFalse(v.message().isBlank(), "message vide pour " + definition + "/" + citizens);
                // La règle centrale, vérifiée sur TOUTES les combinaisons.
                boolean bothPresentAndUpdated = definition == Layer.UPDATED && citizens == Layer.UPDATED;
                boolean oneAbsentOtherUpdated =
                        (definition == Layer.ABSENT && citizens == Layer.UPDATED)
                                || (definition == Layer.UPDATED && citizens == Layer.ABSENT);
                assertEquals(bothPresentAndUpdated || oneAbsentOtherUpdated, v.ok(),
                        "succès annoncé à tort pour " + definition + "/" + citizens);
            }
        }
    }
}
