package com.lodygames.rpgquest.panel.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.npc.NpcAppearance.LastAttempt;
import com.lodygames.rpgquest.panel.npc.NpcAppearance.Reason;
import com.lodygames.rpgquest.panel.npc.NpcAppearance.Status;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #197 — l'état d'apparence d'un PNJ, et surtout ce qu'il ne doit jamais affirmer.
 *
 * <p>Ces tests portent sur la distinction qui a coûté une session de diagnostic en jeu : une
 * apparence dont RPGQuest ignore la <em>source</em> n'est pas une apparence <em>absente</em>.</p>
 */
class NpcAppearanceTest {

    // ---- Le cas qui a motivé le ticket : le Guide renommé en Elias --------------------------

    @Test
    @DisplayName("PNJ joueur sans source enregistrée : « à configurer », jamais « sans skin »")
    void aPlayerNpcWithoutRecordedSourceIsToConfigureAndNeverCalledSkinless() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "", "", LastAttempt.NONE);

        assertEquals(Status.TO_CONFIGURE, a.status());
        assertEquals(Reason.SOURCE_UNKNOWN, a.reason());
        // LE point du ticket : ne pas écrire « sans skin » quand seule la source manque.
        String text = (a.label() + " " + a.explanation()).toLowerCase(Locale.ROOT);
        assertFalse(text.contains("sans skin"), "ne doit jamais affirmer l'absence de skin");
        assertFalse(text.contains("aucun skin"), "ne doit jamais affirmer l'absence de skin");
        assertTrue(a.explanation().contains("hors de PlugAdmin"), "doit dire que c'est l'ORIGINE qui manque");
    }

    @Test
    @DisplayName("Issue #325 : ce même état ne bloque plus le renommage, il annonce l'apparence par défaut")
    void thatStateNoLongerBlocksTheRenameButAnnouncesTheDefaultAppearance() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "", "", LastAttempt.NONE);

        assertFalse(a.blocksRename(), "le serveur pose l'apparence par défaut au lieu de refuser");
        assertTrue(a.renameAppliesDefault());
        assertTrue(a.explanation().contains("apparence par défaut"), "dit ce qui va se passer");
        assertEquals("", a.filterCategory(), "ce n'est pas un problème à traiter");
    }

    @Test
    @DisplayName("Après enregistrement d'une source, le renommage n'est plus bloqué")
    void onceASourceIsRecordedTheRenameIsNoLongerBlocked() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "URL", "https://minesk.in/abcd1234",
                LastAttempt.NONE);

        assertEquals(Status.CONFIGURED, a.status());
        assertEquals(Reason.SOURCE_RECORDED, a.reason());
        assertFalse(a.blocksRename());
        assertFalse(a.needsConfiguration());
        assertEquals("", a.filterCategory(), "rien à traiter");
        assertEquals("personnalisée", a.label());
        assertTrue(a.sourceLabel().contains("https://minesk.in/abcd1234"),
                "la source exacte est nommée, pas résumée");
    }

    @Test
    @DisplayName("Une source « pseudo » est décrite comme telle, sans être confondue avec un lien")
    void aPlayerNameSourceIsDescribedAsSuch() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "NAME", "Notch", LastAttempt.NONE);

        assertEquals(Status.CONFIGURED, a.status());
        assertTrue(a.sourceLabel().contains("Notch"));
        assertTrue(a.sourceLabel().contains("compte"), "doit dire que l'apparence vient d'un compte");
    }

    // ---- PNJ non joueur : non applicable, mais pas « indescriptible » -----------------------

    @Test
    @DisplayName("PNJ non joueur : non applicable, et son type EST son apparence")
    void aNonPlayerNpcIsNotApplicableButItsTypeIsItsAppearance() {
        NpcAppearance a = NpcAppearance.of(true, "VILLAGER", "", "", LastAttempt.NONE);

        assertEquals(Status.NOT_APPLICABLE, a.status());
        assertEquals(Reason.NON_PLAYER, a.reason());
        assertFalse(a.needsConfiguration(), "rien à configurer : ce n'est pas un oubli");
        assertFalse(a.blocksRename(), "son renommage est libre — aucun skin n'en dépend");
        assertEquals("villager", a.label(), "le type doit être nommé");
        assertTrue(a.explanation().contains("pas de skin"), "pas de skin à choisir, ce n'est pas un oubli");
    }

    @Test
    @DisplayName("Type d'entité inconnu : on le dit, on ne suppose pas « pas un joueur »")
    void anUnknownEntityTypeIsSaidToBeUnknownRatherThanAssumed() {
        NpcAppearance a = NpcAppearance.of(true, "", "", "", LastAttempt.NONE);

        assertEquals(Status.TO_CONFIGURE, a.status());
        assertEquals(Reason.TYPE_UNKNOWN, a.reason());
        // SkinPreservationPlanner refuse aussi ce cas : l'écran doit annoncer le même refus.
        assertTrue(a.blocksRename());
        assertTrue(a.explanation().contains("Actualisez"), "dit quoi faire");
        // Mais ce n'est PAS une apparence à décider : un relevé antérieur à #197 ne porte aucun
        // type, et inclure ce cas noierait la liste de travail dans des faux positifs.
        assertEquals("", a.filterCategory(),
                "un relevé périmé ne doit pas remplir le filtre « à configurer »");
    }

    @Test
    @DisplayName("La chaîne « null » du relevé vaut « absent », pas un type nommé « null »")
    void theLiteralNullStringFromTheSurveyMeansAbsent() {
        NpcAppearance a = NpcAppearance.of(true, "null", "null", "null", LastAttempt.NONE);

        assertEquals(Reason.TYPE_UNKNOWN, a.reason());
        assertEquals("", a.sourceLabel());
        assertFalse(a.explanation().contains("null"));
    }

    // ---- Pas de PNJ en jeu ------------------------------------------------------------------

    @Test
    @DisplayName("Sans PNJ Citizens lié, il n'y a pas d'apparence à configurer")
    void withoutALinkedCitizensNpcThereIsNoAppearanceYet() {
        NpcAppearance a = NpcAppearance.of(false, "", "", "", LastAttempt.NONE);

        assertEquals(Status.NOT_APPLICABLE, a.status());
        assertEquals(Reason.NO_ENTITY, a.reason());
        assertFalse(a.needsConfiguration(), "ne doit pas polluer le filtre « à configurer »");
        assertFalse(a.blocksRename(), "renommer la seule définition ne touche aucune apparence");
    }

    // ---- États de runtime : priorité et honnêteté -------------------------------------------

    @Test
    @DisplayName("Un échec récent passe devant tout le reste, même avec une source enregistrée")
    void arecentFailureTakesPrecedenceOverARecordedSource() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "URL", "https://minesk.in/abcd1234",
                LastAttempt.FAILED);

        assertEquals(Status.ERROR, a.status());
        assertEquals(Reason.ATTEMPT_FAILED, a.reason());
        assertEquals("text-bg-danger", a.badgeClass());
        assertTrue(a.explanation().contains("garde la précédente"),
                "un échec ne doit pas laisser craindre une apparence détruite");
    }

    @Test
    @DisplayName("Une demande non confirmée par un relevé se dit « en cours », pas « configurée »")
    void anUnconfirmedRequestIsInProgressRatherThanConfigured() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "URL", "https://minesk.in/abcd1234",
                LastAttempt.PENDING);

        assertEquals(Status.IN_PROGRESS, a.status());
        assertEquals(Reason.ATTEMPT_PENDING, a.reason());
        assertFalse(a.needsConfiguration());
    }

    @Test
    @DisplayName("Un échec est signalé même sur un PNJ non joueur : c'est un fait, pas une déduction")
    void afailureIsReportedEvenOnANonPlayerNpc() {
        NpcAppearance a = NpcAppearance.of(true, "VILLAGER", "", "", LastAttempt.FAILED);

        assertEquals(Status.ERROR, a.status(), "le journal dit qu'une tentative a échoué : on le dit");
    }

    // ---- Rendu ------------------------------------------------------------------------------

    @Test
    @DisplayName("Chaque état a une pastille, et seules les vraies pannes sont rouges")
    void everyStatusHasABadgeAndOnlyRealFailuresAreRed() {
        assertEquals("text-bg-success",
                NpcAppearance.of(true, "PLAYER", "NAME", "Notch", LastAttempt.NONE).badgeClass());
        assertEquals("text-bg-secondary",
                NpcAppearance.of(true, "PLAYER", "", "", LastAttempt.NONE).badgeClass());
        assertEquals("text-bg-info",
                NpcAppearance.of(true, "PLAYER", "NAME", "Notch", LastAttempt.PENDING).badgeClass());
        assertEquals("text-bg-danger",
                NpcAppearance.of(true, "PLAYER", "NAME", "Notch", LastAttempt.FAILED).badgeClass());
        assertEquals("text-bg-secondary",
                NpcAppearance.of(true, "VILLAGER", "", "", LastAttempt.NONE).badgeClass());
    }

    @Test
    @DisplayName("Aucun état ne rend un libellé ou une explication vide")
    void noStatusEverRendersAnEmptyLabelOrExplanation() {
        for (LastAttempt attempt : LastAttempt.values()) {
            for (String type : new String[] {"PLAYER", "VILLAGER", ""}) {
                for (String kind : new String[] {"URL", "NAME", ""}) {
                    NpcAppearance a = NpcAppearance.of(true, type, kind,
                            kind.isEmpty() ? "" : "valeur", attempt);
                    assertFalse(a.label().isBlank(), "libellé vide pour " + type + "/" + kind);
                    assertFalse(a.explanation().isBlank(),
                            "explication vide pour " + type + "/" + kind);
                }
            }
        }
    }

    @Test
    @DisplayName("Issue #325 : l'apparence par défaut est reconnue et nommée simplement")
    void theDefaultAppearanceIsRecognised() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "URL", NpcAppearance.DEFAULT_SKIN_URL, LastAttempt.NONE);
        assertEquals("par défaut", a.label());
        assertEquals("apparence par défaut", a.sourceLabel());
    }

    @Test
    @DisplayName("Issue #325 : une source enregistrée que le personnage ne porte pas est une erreur à réessayer")
    void aRecordedSourceThatIsNotWornIsAnErrorToRetry() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "URL", NpcAppearance.DEFAULT_SKIN_URL,
                false, LastAttempt.NONE);
        assertEquals(Status.ERROR, a.status());
        assertEquals(Reason.NOT_WORN, a.reason());
        assertTrue(a.explanation().contains("Réessayer"), a.explanation());
    }

    @Test
    @DisplayName("Issue #325 : une apparence relue sur le personnage est annoncée comme vérifiée")
    void aWornSourceIsAnnouncedAsChecked() {
        NpcAppearance a = NpcAppearance.of(true, "PLAYER", "URL", NpcAppearance.DEFAULT_SKIN_URL,
                true, LastAttempt.NONE);
        assertEquals(Status.CONFIGURED, a.status());
        assertTrue(a.explanation().contains("Vérifié"), a.explanation());
        NpcAppearance unknown = NpcAppearance.of(true, "PLAYER", "URL", "https://minesk.in/abcd1234",
                null, LastAttempt.NONE);
        assertFalse(unknown.explanation().contains("Vérifié"), "invérifiable : rien n'est affirmé");
    }
}
