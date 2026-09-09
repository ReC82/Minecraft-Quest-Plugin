package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.docs.DocLibrary;
import com.lodygames.rpgquest.panel.docs.DocPage;
import com.lodygames.rpgquest.panel.docs.Markdown;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Registre d'aide aux diagnostics (#89 / #49) : chaque code du moteur doit produire un message
 * <strong>humain</strong> (problème → conséquence → action), un <strong>lien vers une ancre
 * précise</strong> de la documentation, et garder le code technique en secondaire. La
 * terminologie interdite (§18) ne doit jamais apparaître dans le message principal.
 */
class DiagnosticHelpTest {

    /** Termes techniques bannis du message principal (le code technique lui-même est toléré). */
    private static final String[] BANNED = {"tagué", "binding", "giver", "orphan", "namespaced",
            "quest giver", "raw ", "target", "kind"};

    @Test
    void everyEntryPointsToAPreciseDocAnchorDerivedFromItsTitle() {
        for (DiagnosticHelp.Entry e : DiagnosticHelp.all().values()) {
            assertTrue(e.docHref().startsWith("/docs/" + e.docSlug() + "#"),
                    e.code() + " : lien /docs/<fiche>#<ancre>");
            assertTrue(e.docHref().endsWith("#" + Markdown.slug(e.title())),
                    e.code() + " : ancre = slug(titre)");
            assertTrue(e.anchor().matches("[a-z0-9-]+"), e.code() + " : ancre sûre (" + e.anchor() + ")");
        }
    }

    @Test
    void everyDocSheetExistsIsWhitelistedAndCarriesTheMatchingHeading() throws Exception {
        DocLibrary lib = DocLibrary.load();
        String index;
        try (InputStream in = getClass().getResourceAsStream("/docs/_index.txt")) {
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> slugsSeen = new HashSet<>();
        for (DiagnosticHelp.Entry e : DiagnosticHelp.all().values()) {
            if (slugsSeen.add(e.docSlug())) {
                assertTrue(index.contains(e.docSlug() + ".md"),
                        "fiche " + e.docSlug() + " listée dans _index.txt");
                assertTrue(lib.bySlug(e.docSlug()).isPresent(), "fiche " + e.docSlug() + " chargée");
            }
            DocPage page = lib.bySlug(e.docSlug()).orElseThrow();
            String html = Markdown.render(page.markdown());
            assertTrue(html.contains("id=\"" + e.anchor() + "\""),
                    e.code() + " : la fiche " + e.docSlug() + " a bien une section « " + e.title()
                            + " » (ancre " + e.anchor() + ")");
        }
    }

    @Test
    void knownCodeRendersHumanHelpWithTechnicalCodeSecondary() {
        String html = DiagnosticHelp.render("BINDING_NO_DEFINITION", "error",
                "PNJ Citizens tagué « guide » sans définition logique RPGQuest.", "Guide", "", "");

        // 1. problème en français clair (titre humain, pas le message brut du moteur)
        assertTrue(html.contains("<span>Fiche RPGQuest manquante</span>"), html);
        // 2. conséquence + 3. action recommandée
        assertTrue(html.contains("<strong>Conséquence :</strong>"), html);
        assertTrue(html.contains("<strong>À faire :</strong>"), html);
        // 4. lien vers une ancre précise de la doc + bouton « Comment corriger ? »
        assertTrue(html.contains("href=\"/docs/pnj-depannage#fiche-rpgquest-manquante\""), html);
        assertTrue(html.contains("Comment corriger ?</a>"), html);
        // 5. id technique en secondaire
        assertTrue(html.contains("Code technique : <code class=\"tid\">BINDING_NO_DEFINITION</code>"), html);
        // composant Bootstrap coloré selon la sévérité
        assertTrue(html.contains("alert alert-danger pa-diag pa-diag-error"), html);

        // terminologie interdite : absente de tout SAUF la ligne « code technique »
        String mainText = html.substring(0, html.indexOf("pa-diag-code"));
        for (String banned : BANNED) {
            assertFalse(mainText.toLowerCase(java.util.Locale.ROOT).contains(banned),
                    "terme interdit dans l'UX : " + banned + "\n" + mainText);
        }
    }

    @Test
    void dialogueNoNpcIsInfoLevelAndHumanised() {
        String html = DiagnosticHelp.render("DIALOGUE_NO_NPC", "info",
                "dialogue rpgquest:orphan sans PNJ", "Orphan", "", "");
        assertTrue(html.contains("alert alert-info pa-diag pa-diag-info"), html);
        assertTrue(html.contains("<span>Dialogue non associé à un PNJ</span>"), html);
        assertTrue(html.contains("href=\"/docs/dialogues-depannage#dialogue-non-associe-a-un-pnj\""), html);
    }

    @Test
    void questAndStoryReferenceDiagnosticsAreCovered() {
        assertTrue(DiagnosticHelp.render("QUEST_PREREQ_UNKNOWN", "warning", "", "La chasse", "rpgquest:first_steps", "")
                .contains("href=\"/docs/quetes-depannage#prerequis-inconnu\""));
        assertTrue(DiagnosticHelp.render("QUEST_GIVER_UNKNOWN", "warning", "", "La chasse", "guard", "")
                .contains("<span>Donneur de quête inconnu</span>"));
        assertTrue(DiagnosticHelp.render("STORY_QUEST_UNKNOWN", "warning", "", "Histoire principale", "rpgquest:x", "")
                .contains("href=\"/docs/stories-depannage#quete-inconnue-dans-la-chaine\""));
    }

    @Test
    void unknownCodeKeepsMoteurMessageInTheSameShellWithGenericDoc() {
        String html = DiagnosticHelp.render("SOMETHING_NEW", "warning", "Message brut du moteur.", "X", "", "");
        assertTrue(html.contains("Message brut du moteur."), html);
        assertTrue(html.contains("href=\"/docs\""), html);
        assertTrue(html.contains("pa-diag pa-diag-warning"), html);
    }

    @Test
    void levelMappingIsLenient() {
        assertTrue(DiagnosticHelp.Level.of("error") == DiagnosticHelp.Level.ERROR);
        assertTrue(DiagnosticHelp.Level.of("WARNING") == DiagnosticHelp.Level.WARNING);
        assertTrue(DiagnosticHelp.Level.of(null) == DiagnosticHelp.Level.INFO);
        assertTrue(DiagnosticHelp.Level.of("nonsense") == DiagnosticHelp.Level.INFO);
    }
}
