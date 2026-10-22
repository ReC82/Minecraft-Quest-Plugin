package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.docs.DocLibrary;
import com.lodygames.rpgquest.panel.docs.Markdown;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Issue #729 : les liens d'aide des dialogues (messages d'erreur de l'éditeur, renvois entre le
 * guide et la fiche de dépannage) mènent à une section qui existe. Un titre renommé sans mettre
 * à jour le lien donnerait une page ouverte en haut, sans l'explication attendue.
 */
class DialogueHelpLinksTest {

    private static final List<String> SHEETS = List.of("dialogues", "dialogues-depannage");
    private static final Pattern LINK = Pattern.compile("\\]\\((?:/docs/)?(dialogues(?:-depannage)?)?#([a-z0-9-]+)\\)");

    @Test
    void editorHelpLinksPointToExistingSections() throws Exception {
        assertResolves(DialoguePages.END_HELP_HREF);
        assertResolves("/docs/dialogues-depannage#" + Markdown.slug(DialoguePages.CONFLICT_HELP_TITLE));
        assertResolves(DialoguePages.GUIDE_HREF);
    }

    @Test
    void everyAnchorLinkBetweenDialogueSheetsResolves() throws Exception {
        List<String> checked = new ArrayList<>();
        for (String sheet : SHEETS) {
            Matcher m = LINK.matcher(markdown(sheet));
            while (m.find()) {
                String target = m.group(1) == null ? sheet : m.group(1);
                assertResolves("/docs/" + target + "#" + m.group(2));
                checked.add(target + "#" + m.group(2));
            }
        }
        assertTrue(checked.size() >= 5, "les renvois sont bien trouvés : " + checked);
    }

    @Test
    void theNormalPathOfTheGuideNeedsNoTechnicalWords() throws Exception {
        String guide = markdown("dialogues");
        String normal = guide.substring(0, guide.indexOf("## L'aperçu"));
        for (String jargon : List.of("nœud", "graphe", "YAML", "choices", "/dialogues/diagnostic")) {
            assertFalse(normal.contains(jargon), "parcours normal sans jargon : " + jargon);
        }
    }

    private static void assertResolves(String href) throws Exception {
        String path = href.substring("/docs/".length());
        int hash = path.indexOf('#');
        String slug = hash < 0 ? path : path.substring(0, hash);
        String html = Markdown.render(markdown(slug));
        if (hash >= 0) {
            String anchor = path.substring(hash + 1);
            assertTrue(html.contains("id=\"" + anchor + "\""), href + " : section introuvable");
        }
    }

    private static String markdown(String slug) throws Exception {
        return DocLibrary.load().bySlug(slug).orElseThrow(() -> new AssertionError("fiche absente : " + slug))
                .markdown();
    }
}
