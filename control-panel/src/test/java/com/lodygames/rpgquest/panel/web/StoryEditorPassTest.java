package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.RefData;
import com.lodygames.rpgquest.panel.content.StoryYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Passe UX ciblée de l'éditeur guidé de <em>stories</em> (#46, §2 à §12) — rendu direct de
 * {@link ContentEditorPages}, sans la pile HTTP : sélection recherchable des quêtes (titre humain
 * + id), ajout / suppression / réordonnancement sur un brouillon incomplet sans validation
 * bloquante, ancres de scroll, validation métier finale (id / nom / chaîne vide / référence
 * inconnue / doublon), aperçu YAML + diff, conflit de hash, round-trip.
 */
class StoryEditorPassTest {

    @TempDir
    Path tmp;

    private ContentEditorPages pages;
    private Path storiesDir;

    /** Deux quêtes connues avec leur titre humain, comme le fournirait un relevé {@code quest.list}. */
    private static final RefData REF = new RefData(
            List.of("first_steps", "crystal_hunt"), List.of(), List.of(),
            true, false, false, Map.of(),
            Map.of("first_steps", "Premiers pas", "crystal_hunt", "Chasse aux cristaux"));

    @BeforeEach
    void setUp() throws Exception {
        storiesDir = tmp.resolve("stories");
        Files.createDirectories(storiesDir);
        pages = new ContentEditorPages(new ContentWorkspace(tmp));
    }

    // ---- §2 : rendu du formulaire guidé --------------------------------------------------

    @Test
    void newStoryFormRendersGuidedSections() {
        String body = html(pages.storyPage(REF, null, false));
        assertTrue(body.contains("Créer une story"));
        assertTrue(body.contains("Général") && body.contains("Chaîne de quêtes")
                && body.contains("Validation &amp; aperçu"));
        assertTrue(body.contains("class=\"editor\" novalidate"), "form novalidate");
        assertTrue(body.contains("<datalist id=\"dl-quest\">"), "datalist des quêtes présente");
        assertTrue(body.contains("value=\"add_q\""), "bouton ajouter une quête");
        assertTrue(body.contains("value=\"validate\"") && body.contains("value=\"refresh\""),
                "boutons Vérifier + Actualiser (parité avec l'éditeur de quêtes)");
    }

    @Test
    void editFormRendersFromSourceFile() throws Exception {
        Files.writeString(storiesDir.resolve("saga.yml"),
                "id: saga\nname: \"La Saga\"\nquests:\n  - rpgquest:first_steps\n  - rpgquest:crystal_hunt\n");
        String body = html(pages.storyPage(REF, "saga", false));
        assertTrue(body.contains("Modifier une story"));
        assertTrue(body.contains("value=\"first_steps\"") && body.contains("value=\"crystal_hunt\""));
        assertTrue(body.contains("name=\"expectedSha\" value=\"") && !attr(body, "expectedSha").isEmpty(),
                "hash de version chargé pour la détection de conflit");
    }

    // ---- §3 / §7 : sélection recherchable titre humain + id ----------------------------

    @Test
    void questDatalistCarriesHumanTitlesNextToTechnicalIds() {
        String dl = ContentEditorPages.sharedDatalists(REF);
        assertTrue(dl.contains("<datalist id=\"dl-quest\">"));
        assertTrue(dl.contains("<option value=\"first_steps\" label=\"Premiers pas\">"),
                "titre humain sur la quête « first_steps »");
        assertTrue(dl.contains("<option value=\"crystal_hunt\" label=\"Chasse aux cristaux\">"));
    }

    @Test
    void questDatalistFallsBackToIdWhenNoTitleKnown() {
        RefData bare = new RefData(List.of("mystery_quest"), List.of(), List.of(), true, false, false);
        String dl = ContentEditorPages.sharedDatalists(bare);
        assertTrue(dl.contains("<option value=\"mystery_quest\">"), "id seul, jamais de label vide");
        assertFalse(dl.contains("label=\"mystery_quest\""));
    }

    @Test
    void questLabelResolvesTitleFromPlainOrNamespacedId() {
        assertEquals("Premiers pas", REF.questLabel("first_steps"));
        assertEquals("Premiers pas", REF.questLabel("rpgquest:first_steps"));
        assertEquals("does_not_exist", REF.questLabel("does_not_exist"));
    }

    // ---- §5 : ordre clair — titre humain au-dessus de l'id dans chaque ligne -----------

    @Test
    void chainRowShowsHumanTitleAboveTechnicalId() {
        String body = html(pages.storyPost(REF, form("q.0", "first_steps", "_action", "refresh")));
        assertTrue(body.contains("class=\"row-title\">Premiers pas<"), "titre humain affiché dans la ligne");
        assertTrue(Pattern.compile("name=\"q\\.0\"[^>]*value=\"first_steps\"").matcher(body).find(),
                "l'id technique reste le champ éditable");
        assertTrue(body.contains("class=\"row-n\">1.<"), "rang de la quête dans la chaîne");
    }

    @Test
    void chainRowFlagsAnUnknownQuestWhenCatalogIsLoaded() {
        String body = html(pages.storyPost(REF, form("q.0", "ghost_quest", "_action", "refresh")));
        assertTrue(body.contains("<span class=\"badge text-bg-warning\">quête inconnue</span>"),
                "référence inconnue signalée dès la construction de la ligne (catalogue chargé)");
        assertTrue(body.contains("value=\"ghost_quest\""), "l'id saisi est conservé, jamais effacé");
    }

    // ---- §4 / §8 : brouillon incomplet — ajout / suppression / reorder sans blocage -----

    @Test
    void addQuestOnIncompleteDraftNeverTriggersBusinessValidation() {
        String body = html(pages.storyPost(REF,
                form("id", "", "name", "", "q.0", "first_steps", "_action", "add_q")));
        assertTrue(body.contains("name=\"q.1\""), "une seconde ligne a été ajoutée");
        assertFalse(body.contains("class=\"diag-list\""), "aucune validation métier sur un ajout");
        assertFalse(body.contains("obligatoire"), "aucun message d'obligation sur un ajout");
    }

    @Test
    void removeQuestOnIncompleteDraftAlwaysWorks() {
        String body = html(pages.storyPost(REF, form(
                "id", "", "name", "", "q.0", "first_steps", "q.1", "crystal_hunt", "_action", "del_q:0")));
        assertFalse(body.contains("name=\"q.1\""), "la chaîne ne contient plus qu'une ligne");
        assertTrue(Pattern.compile("name=\"q\\.0\"[^>]*value=\"crystal_hunt\"").matcher(body).find(),
                "la ligne restante est bien l'ancienne seconde quête");
        assertFalse(body.contains("class=\"diag-list\""), "aucune validation métier sur une suppression");
    }

    @Test
    void reorderMovesRowsWithoutValidation() {
        String body = html(pages.storyPost(REF, form(
                "id", "", "name", "", "q.0", "first_steps", "q.1", "crystal_hunt", "_action", "mv_q:0:down")));
        int crystal = body.indexOf("value=\"crystal_hunt\"");
        int first = body.indexOf("value=\"first_steps\"");
        assertTrue(crystal > 0 && first > 0 && crystal < first, "l'ordre des lignes a été inversé");
        assertFalse(body.contains("class=\"diag-list\""));
    }

    @Test
    void everyStructuralButtonCarriesANonEmptyScrollAnchor() {
        String body = html(pages.storyPost(REF, form(
                "q.0", "first_steps", "q.1", "crystal_hunt", "_action", "refresh")));
        Matcher m = Pattern.compile(
                "<button[^>]*name=\"_action\" value=\"(add_q|del_q:0|mv_q:0:down|mv_q:1:up)\"[^>]*>").matcher(body);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertTrue(m.group().contains("formaction=\"/stories/save#") && !m.group().contains("save#\""),
                    "action structurelle sans ancre : " + m.group());
        }
        assertTrue(seen >= 3, "plusieurs boutons structurels attendus (" + seen + ")");
    }

    @Test
    void storyEditorFormEmitsNoBlockingRequiredAttribute() {
        String body = html(pages.storyPage(REF, null, false));
        assertFalse(Pattern.compile("<(input|select|textarea)\\b[^>]*\\brequired\\b").matcher(body).find(),
                "aucun contrôle ne porte l'attribut HTML required");
        assertTrue(body.contains("<span aria-hidden=\"true\">*</span>"), "le marqueur visuel « * » reste");
    }

    // ---- §9 : validation métier finale (Vérifier / Enregistrer uniquement) --------------

    @Test
    void verifyReportsMissingIdAndNameAndEmptyChain() {
        String body = html(pages.storyPost(REF, form("id", "", "name", "", "_action", "validate")));
        assertTrue(body.contains("class=\"diag-list\""), "la validation s'exécute sur « Vérifier »");
        assertTrue(body.contains("identifiant de la story est obligatoire"));
        assertTrue(body.contains("Le nom affiché est obligatoire."));
        assertTrue(body.contains("au moins une quête"));
    }

    @Test
    void verifyFlagsUnknownReferenceAndDuplicate() {
        String body = html(pages.storyPost(REF, form(
                "id", "saga", "name", "La Saga",
                "q.0", "first_steps", "q.1", "first_steps", "q.2", "ghost_quest",
                "_action", "validate")));
        assertTrue(body.contains("plusieurs fois"), "doublon signalé (autorisé par le moteur → avertissement)");
        assertTrue(body.contains("Quête inconnue") || body.contains("inconnue"),
                "référence inexistante signalée à la vérification");
    }

    // ---- §10 : aperçu YAML + diff, identiques au fichier réellement écrit ---------------

    @Test
    void previewYamlMatchesWhatGetsSavedAndRoundTrips() {
        Map<String, String> valid = form("id", "saga", "name", "La Saga",
                "q.0", "first_steps", "q.1", "crystal_hunt", "_action", "validate");
        String preview = html(pages.storyPost(REF, valid));
        assertTrue(preview.contains("Fichier généré"));
        assertTrue(preview.contains("id: saga"));
        assertTrue(preview.contains("- rpgquest:first_steps"));

        Map<String, String> save = form("id", "saga", "name", "La Saga",
                "q.0", "first_steps", "q.1", "crystal_hunt", "_action", "save");
        ContentEditorPages.Result r = pages.storyPost(REF, save);
        assertTrue(r instanceof ContentEditorPages.Result.Redirect, "enregistrement d'une story valide");
        assertEquals("/stories/edit/saga?saved=1",
                ((ContentEditorPages.Result.Redirect) r).location());

        String onDisk = readString(storiesDir.resolve("saga.yml"));
        assertTrue(onDisk.contains("id: saga") && onDisk.contains("- rpgquest:first_steps")
                && onDisk.indexOf("first_steps") < onDisk.indexOf("crystal_hunt"), "ordre conservé sur disque");
        StoryYaml.ReadResult back = StoryYaml.read(onDisk);
        assertEquals("saga", back.draft().id);
        assertEquals("La Saga", back.draft().name);
        assertEquals(List.of("first_steps", "crystal_hunt"), back.draft().questIds);
        assertTrue(StoryYaml.roundTripProblems(onDisk).isEmpty(), "round-trip stable");
    }

    @Test
    void previewShowsADiffAgainstTheExistingSource() {
        pages.storyPost(REF, form("id", "saga", "name", "La Saga",
                "q.0", "first_steps", "_action", "save"));
        String editBody = html(pages.storyPage(REF, "saga", false));
        String sha = attr(editBody, "expectedSha");

        String body = html(pages.storyPost(REF, form(
                "slug", "saga", "expectedSha", sha, "id", "saga", "name", "La Saga",
                "q.0", "first_steps", "q.1", "crystal_hunt", "_action", "validate")));
        assertTrue(body.contains("Différences avec la version actuelle"), "diff affiché avant sauvegarde");
        assertTrue(body.contains("di-add"), "la quête ajoutée apparaît en ligne « + »");
    }

    // ---- §10 : conflit de hash (modification externe) ----------------------------------

    @Test
    void staleHashIsDetectedAsConflict() {
        pages.storyPost(REF, form("id", "saga", "name", "La Saga",
                "q.0", "first_steps", "_action", "save"));
        Path file = storiesDir.resolve("saga.yml");
        String editBody = html(pages.storyPage(REF, "saga", false));
        String sha = attr(editBody, "expectedSha");

        writeString(file, readString(file) + "\n# modification externe\n");

        String body = html(pages.storyPost(REF, form(
                "slug", "saga", "expectedSha", sha, "id", "saga", "name", "La Saga",
                "q.0", "first_steps", "q.1", "crystal_hunt", "_action", "save")));
        assertTrue(body.toLowerCase().contains("conflit"), "conflit de version signalé");
        assertTrue(readString(file).contains("modification externe"), "fichier source non écrasé");
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String html(ContentEditorPages.Result r) {
        return ((ContentEditorPages.Result.Html) r).body();
    }

    private static Map<String, String> form(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String attr(String html, String name) {
        Matcher m = Pattern.compile("name=\"" + Pattern.quote(name) + "\" value=\"([^\"]*)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("champ « " + name + " » absent");
        }
        return m.group(1);
    }

    private static String readString(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeString(Path p, String s) {
        try {
            Files.writeString(p, s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
