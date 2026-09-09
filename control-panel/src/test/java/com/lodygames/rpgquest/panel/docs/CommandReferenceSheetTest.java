package com.lodygames.rpgquest.panel.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Fiche de référence des commandes (issue #49) : une commande = une section (nom humain,
 * description, syntaxe, exemple copiable), <strong>jamais</strong> un gros bloc listant plusieurs
 * commandes différentes derrière un seul bouton « Copier ».
 */
class CommandReferenceSheetTest {

    private static final DocLibrary LIB = DocLibrary.load();
    private final DocPage sheet = LIB.bySlug("rpgquest-commandes").orElseThrow();

    /** Commandes « majeures » que la fiche doit documenter individuellement avec un exemple. */
    private static final List<String> MAJOR = List.of(
            "/rpgadmin npc tag", "/rpgadmin npc untag", "/rpgadmin npc info",
            "/rpgadmin quest start", "/rpgadmin quest complete", "/rpgadmin quest reset",
            "/rpgadmin story info", "/rpgadmin story start", "/rpgadmin story advance",
            "/rpgadmin story complete", "/rpgadmin story reset", "/rpgadmin story resetwithquests",
            "/rpgadmin player resetnew", "/rpgadmin spawn set", "/rpgadmin world create",
            "/rpgadmin worldportal create", "/rpgquest version", "/rpgquest profile",
            "/rpgquest reload", "/dialogue open", "/customitem give");

    // ---- helpers -------------------------------------------------------------------------

    /** Corps de chaque bloc ``` de la fiche (sans les lignes de clôture). */
    private static List<String> fencedBlocks(String md) {
        List<String> blocks = new ArrayList<>();
        String[] lines = md.split("\n", -1);
        StringBuilder cur = null;
        for (String line : lines) {
            if (line.strip().startsWith("```")) {
                if (cur == null) {
                    cur = new StringBuilder();
                } else {
                    blocks.add(cur.toString());
                    cur = null;
                }
                continue;
            }
            if (cur != null) {
                cur.append(line).append('\n');
            }
        }
        return blocks;
    }

    // ---- tests --------------------------------------------------------------------------

    @Test
    void everyCodeBlockHoldsExactlyOneExecutableCommand() {
        List<String> blocks = fencedBlocks(sheet.markdown());
        assertTrue(blocks.size() >= 30, "la fiche a bien un bloc par commande (" + blocks.size() + ")");
        for (String block : blocks) {
            List<String> nonBlank = block.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
            assertTrue(nonBlank.size() == 1,
                    "un bloc de code = une seule ligne exécutable, jamais une liste :\n" + block);
            assertTrue(nonBlank.get(0).startsWith("/"),
                    "la ligne du bloc est une commande : " + nonBlank.get(0));
        }
    }

    @Test
    void renderedCopyButtonsNeverCarryAMultiCommandPayload() {
        String html = Markdown.render(sheet.markdown());
        Matcher m = Pattern.compile("data-copy=\"([^\"]*)\"").matcher(html);
        int seen = 0;
        while (m.find()) {
            seen++;
            String payload = m.group(1);
            assertFalse(payload.contains("&#10;") || payload.contains("\n"),
                    "un bouton Copier ne copie jamais plusieurs lignes : « " + payload + " »");
            long slashes = payload.chars().filter(c -> c == '/').count();
            // une commande peut contenir un « / » d'argument, mais pas deux préfixes de commande
            long cmdPrefixes = Pattern.compile("(^|\\s)/(rpgadmin|rpgquest|quest|customitem|dialogue)\\b")
                    .matcher(payload).results().count();
            assertTrue(cmdPrefixes <= 1,
                    "un bouton Copier = une commande (" + cmdPrefixes + " préfixes) : « " + payload + " »");
        }
        assertTrue(seen >= 30, "chaque bloc d'exemple a son propre bouton Copier (" + seen + ")");
    }

    @Test
    void everyMajorCommandHasItsOwnSectionWithDescriptionSyntaxAndExample() {
        String md = sheet.markdown();
        for (String cmd : MAJOR) {
            // une section dédiée (### …) qui nomme la commande
            assertTrue(Pattern.compile("(?m)^### .*`" + Pattern.quote(cmd) + "`").matcher(md).find(),
                    "section ### dédiée pour " + cmd);
            // au moins un bloc de code contenant la commande complète (syntaxe ou exemple)
            boolean inABlock = fencedBlocks(md).stream().anyMatch(b -> b.contains(cmd));
            assertTrue(inABlock, "un bloc de code contient " + cmd);
        }
        // « Syntaxe » / « Exemple » / « Résultat attendu » sont la structure standard
        assertTrue(md.contains("**Syntaxe**"));
        assertTrue(md.contains("**Exemple") && md.contains("**Résultat attendu**"));
        assertTrue(md.contains("**Paramètres**"));
        // resetnew : preview + confirm + conséquence expliquée
        assertTrue(md.contains("preview") && md.contains("confirm")
                && md.contains("depuis la console") && md.contains("dry-run"),
                "resetnew documente preview / confirm / console / conséquence");
    }

    @Test
    void theOldMonolithicReferenceBlockIsGone() {
        String md = sheet.markdown();
        // l'ancien gros bloc listait plusieurs familles avec des « | » comme séparateurs d'alternative
        assertFalse(md.contains("/rpgadmin npc tag [id] | untag | info"), "ancien bloc fourre-tout retiré");
        assertFalse(md.contains("start|advance|complete|reset|resetwithquests"), "ancienne liste condensée retirée");
        for (String block : fencedBlocks(md)) {
            assertFalse(block.contains(" | "), "aucun bloc de code n'énumère des alternatives avec « | »");
        }
    }

    @Test
    void searchStillFindsTheCommands() {
        DocSearchIndex index = DocSearchIndex.build(LIB);
        for (String q : new String[] {"npc tag", "npc untag", "quest complete", "story reset",
                "player resetnew", "world create", "worldportal create", "dialogue open"}) {
            List<DocSearchIndex.Hit> hits = index.search(q);
            assertFalse(hits.isEmpty(), "« " + q + " » ne renvoie rien");
            assertTrue(hits.stream().anyMatch(h -> h.page().slug().equals("rpgquest-commandes")),
                    "« " + q + " » trouve la fiche de référence des commandes");
        }
        // les recherches pointées de #49 ne changent pas de fiche de tête
        assertTrue(index.search("tag npc").get(0).page().slug().equals("pnj-citizens"));
        assertTrue(index.search("quest complete").get(0).page().slug().equals("quetes"));
        assertTrue(index.search("reset joueur").get(0).page().slug().equals("joueurs-reset"));
    }

    @Test
    void renderingIsSafeAndPlaceholdersAreEscaped() {
        String html = Markdown.render(sheet.markdown());
        assertFalse(html.contains("<script"), "aucune balise script");
        // les <joueur> / <quête> des syntaxes sont neutralisés en entités, jamais des balises réelles
        assertTrue(html.contains("&lt;joueur&gt;"), "placeholders échappés");
        assertFalse(html.contains("<joueur>") || html.contains("<quête>"), "aucun placeholder en balise brute");
        // le titre de fiche n'est pas rendu deux fois (le H1 d'ouverture est retiré côté renderer)
        assertFalse(html.contains("<h1"), "pas de H1 dans le corps rendu (le header de page porte le titre)");
        assertTrue(html.contains("<h2 id=") && html.contains("<h3 id="), "les sections ## / ### sont rendues");
    }
}
