package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Gabarit commun des fiches (issue #780, voir {@code docs/control-panel/DOCS_GABARIT.md}) : chaque
 * fiche livrée doit être rendable par {@link Markdown}, suivre le gabarit, n'avoir que des liens
 * internes valides, et garder toutes les ancres existantes (liens d'erreur, favoris).
 */
class DocTemplateTest {

    private final DocLibrary lib = DocLibrary.load();

    @Test
    void everySheetHasAStructureTheEngineRenders() {
        List<String> problems = new ArrayList<>();
        for (DocPage p : lib.all()) {
            for (String problem : Markdown.lint(p.markdown())) {
                problems.add(p.sourcePath() + " " + problem);
            }
        }
        assertEquals(List.of(), problems, "fiches non rendables : corrigez le Markdown");
    }

    @Test
    void everySheetFollowsTheTemplate() {
        for (DocPage p : lib.all()) {
            assertFalse("Divers".equals(p.category()), p.slug() + " : front matter `category` obligatoire");
            assertFalse(p.tags().isEmpty(), p.slug() + " : front matter `tags` obligatoire (recherche)");
            assertFalse(p.related().isEmpty(), p.slug() + " : front matter `related` obligatoire (navigation)");
            for (String r : p.related()) {
                assertTrue(lib.bySlug(r).isPresent(), p.slug() + " : fiche liée inconnue « " + r + " »");
                assertFalse(r.equals(p.slug()), p.slug() + " : une fiche ne se lie pas à elle-même");
            }
            String html = Markdown.render(p.markdown());
            assertTrue(html.startsWith("<p>"), p.slug() + " : un paragraphe d'introduction avant le premier titre");
            assertFalse(html.contains("<h1"), p.slug() + " : un seul titre principal (celui du front matter)");
        }
    }

    @Test
    void everyInternalLinkPointsToAnExistingSheetAndSection() {
        List<String> broken = new ArrayList<>();
        for (DocPage p : lib.all()) {
            for (String href : Markdown.links(p.markdown())) {
                String problem = lib.brokenLinkReason(p, href);
                if (problem != null) {
                    broken.add(p.slug() + " → " + href + " : " + problem);
                }
            }
        }
        assertEquals(List.of(), broken);
    }

    @Test
    void everyAnchorThatExistedBeforeTheRedesignStillExists() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String line : resource("/docs-guard/anchors-780.txt").split("\n")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String slug = s.substring(0, s.indexOf('#'));
            String anchor = s.substring(s.indexOf('#') + 1);
            Set<String> ids = lib.bySlug(slug).map(DocTemplateTest::ids).orElse(Set.of());
            if (!ids.contains(anchor)) {
                missing.add(s);
            }
        }
        assertEquals(List.of(), missing, "ancres disparues : gardez le titre (ou déplacez-le sans le renommer)");
    }

    static Set<String> ids(DocPage page) {
        return Markdown.headings(page.markdown()).stream().map(Markdown.Heading::id).collect(Collectors.toSet());
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = DocTemplateTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
