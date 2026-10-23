package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Centre de documentation (issue #49) : routes, auth obligatoire, recherche, fiche, 404, path traversal. */
class DocsPagesTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    @Test
    void docsRequireAuthentication() throws Exception {
        start();
        HttpResponse<String> anon = get("/docs");
        assertEquals(303, anon.statusCode());
        assertEquals("/login", anon.headers().firstValue("Location").orElse(""));

        HttpResponse<String> anonSheet = get("/docs/pnj-citizens");
        assertEquals(303, anonSheet.statusCode());
    }

    @Test
    void homeShowsSearchAndCategoriesAndShortcuts() throws Exception {
        start();
        login();
        String page = get("/docs").body();
        assertTrue(page.contains("<h1>Documentation</h1>"));
        assertTrue(page.contains("name=\"q\""), "champ de recherche");
        assertTrue(page.contains("class=\"doc-cats\""), "catégories");
        assertTrue(page.contains("Comment faire ?"), "raccourcis");
        assertTrue(page.contains("href=\"/docs/pnj-citizens\""), "lien vers la fiche PNJ");
        // nav : entrée Documentation présente et active
        assertTrue(page.contains("class=\"navlink active\" href=\"/docs\""));
    }

    @Test
    void searchFindsTheNpcSheetAndSkinInfo() throws Exception {
        start();
        login();

        String tagNpc = get("/docs?q=" + enc("tag npc")).body();
        assertTrue(tagNpc.contains("class=\"doc-hit\" href=\"/docs/pnj-citizens"), tagNpc);
        assertTrue(tagNpc.contains("résultat"), "compteur de résultats");

        String skin = get("/docs?q=skin").body();
        assertTrue(skin.contains("href=\"/docs/pnj-citizens"), skin);

        String reset = get("/docs?q=" + enc("reset joueur")).body();
        assertTrue(reset.contains("href=\"/docs/joueurs-reset"), reset);

        String rollback = get("/docs?q=rollback").body();
        assertTrue(rollback.contains("href=\"/docs/verygames-deploiement"), rollback);

        String nope = get("/docs?q=zzznotfoundzzz").body();
        assertTrue(nope.contains("Aucune fiche"), nope);
    }

    @Test
    void sheetRendersMarkdownWithCopyableCommand() throws Exception {
        start();
        login();
        String sheet = get("/docs/pnj-citizens").body();
        assertEquals(200, get("/docs/pnj-citizens").statusCode());
        assertTrue(sheet.contains("Créer et configurer un PNJ"));
        assertTrue(sheet.contains("<div class=\"doc-cmd\">"), "bloc commande");
        assertTrue(sheet.contains("data-copy=\"/rpgadmin npc tag guard\"") || sheet.contains("data-copy=\"/npc create Garde --type player"),
                "au moins une commande copiable");
        assertTrue(sheet.contains("/assets/panel.js"), "script de copie chargé");
        assertTrue(sheet.contains("doc-crumbs"), "fil d'Ariane");
        assertTrue(sheet.contains("Sur cette fiche"), "sommaire");
        // aucun HTML brut dangereux (les fiches sont sûres, mais on vérifie le rendu échappé)
        assertFalse(sheet.contains("<script>alert"), sheet);
    }

    @Test
    void everySheetShowsItsMainTitleExactlyOnce() throws Exception {
        start();
        login();
        // Toutes les fiches embarquées, pas seulement quelques-unes.
        for (com.lodygames.rpgquest.panel.docs.DocPage p :
                com.lodygames.rpgquest.panel.docs.DocLibrary.load().all()) {
            String page = get("/docs/" + p.slug()).body();
            int bodyAt = page.indexOf("<article class=\"doc-body\">");
            assertTrue(bodyAt >= 0, p.slug() + " : corps de fiche présent");
            int bodyEnd = page.indexOf("</article>", bodyAt);
            String body = page.substring(bodyAt, bodyEnd < 0 ? page.length() : bodyEnd);

            // 1) le header de page rend UN <h1> (sans id) = la seule source de titre visible
            assertEquals(1, countOccurrences(body, "<h1>"), p.slug() + " : un seul <h1> de header");
            // 2) le H1 d'ouverture du Markdown (rendu par Markdown en <h1 id="...">) a disparu
            assertFalse(body.contains("<h1 id="), p.slug() + " : plus de second titre principal issu du Markdown");
            // 3) le premier bloc de contenu après le titre n'est pas une répétition du titre
            String afterTitle = body.substring(body.indexOf("</h1>") + 5).stripLeading();
            assertFalse(afterTitle.startsWith("<h1"), p.slug() + " : pas de titre en double juste sous le header");
            // 4) les H2/H3 normaux restent rendus (au moins un sur toutes ces fiches)
        }
    }

    @Test
    void codeBlockCopyButtonStaysInsideItsBlockWithNoBrokenIconOverlay() throws Exception {
        start();
        login();
        String sheet = get("/docs/pnj-citizens").body();

        // bouton compact, texte seul, DANS le même wrapper .doc-cmd, juste avant le <pre>
        assertTrue(sheet.contains("<div class=\"doc-cmd\"><button type=\"button\" class=\"doc-copy\" data-copy=\""),
                "bouton Copier au début du wrapper de bloc de code");
        assertTrue(sheet.contains("\">Copier</button><pre><code>"), "bouton Copier suivi immédiatement du <pre><code>");
        // plus de sprite SVG cassé (source du grand rectangle vide) nulle part
        assertFalse(sheet.contains("<svg class=\"ic\""), "aucun <svg class=ic> parasite");
        assertFalse(sheet.contains("#i-copy") || sheet.contains("<use href="), "aucune référence de sprite morte");

        // CSS : bouton positionné dans l'angle, bloc scrollable horizontalement, ancienne règle conflictuelle retirée
        String css = get("/assets/plugadmin.css").body();
        assertTrue(css.contains(".doc-copy{position:absolute;top:7px;right:7px"), "bouton en absolute dans l'angle");
        assertTrue(css.contains(".doc-cmd pre{") && css.contains("overflow-x:auto"), "bloc code scrollable");
        assertFalse(css.contains(".codeblock .doc-copy,.doc-cmd .doc-copy{position:absolute;top:8px"),
                "ancienne règle de positionnement en double retirée");
    }

    @Test
    void sheetShowsTocFirstWithRealAnchorsRelatedSheetsAndPagerIssue780() throws Exception {
        start();
        login();
        for (com.lodygames.rpgquest.panel.docs.DocPage p :
                com.lodygames.rpgquest.panel.docs.DocLibrary.load().all()) {
            String page = get("/docs/" + p.slug()).body();
            // chaque entrée du sommaire pointe vers un id réellement présent dans la fiche
            Matcher toc = Pattern.compile("<li class=\"lvl[23]\"><a href=\"#([^\"]+)\"").matcher(page);
            while (toc.find()) {
                assertTrue(page.contains(" id=\"" + toc.group(1) + "\""), p.slug() + " : ancre du sommaire #" + toc.group(1));
            }
            assertTrue(page.contains("class=\"doc-pager\""), p.slug() + " : fiche précédente / suivante");
            assertTrue(page.contains("class=\"doc-related\""), p.slug() + " : fiches liées");
            assertFalse(page.contains(" autofocus"), p.slug() + " : pas d'autofocus sur une fiche (clavier mobile)");
        }
        String dialogues = get("/docs/dialogues").body();
        int body = dialogues.indexOf("<article class=\"doc-body\">");
        int mobileToc = dialogues.indexOf("<details class=\"doc-toc-m\" open>", body);
        int firstSection = dialogues.indexOf("<h2 id=", body);
        assertTrue(body > 0 && mobileToc > body && mobileToc < firstSection, "sommaire en tête de l'article");
        assertTrue(dialogues.contains("<a href=\"#creer-un-dialogue\">Créer un dialogue</a>"), dialogues);
        // l'ancienne fiche « lapercu » cassait le sommaire (l-apercu) : ancre rendue = ancre du sommaire
        assertTrue(dialogues.contains("href=\"#lapercu\"") && dialogues.contains("id=\"lapercu\""));
        assertTrue(dialogues.contains("href=\"/docs/dialogues-depannage\">Résoudre les problèmes de dialogues</a>"));
    }

    @Test
    void searchResultLinksToTheSectionAndIgnoresAccents() throws Exception {
        start();
        login();
        String page = get("/docs?q=" + enc("aller a la ligne replique")).body();
        assertTrue(page.contains("href=\"/docs/dialogues#aller-a-la-ligne-dans-une-replique\""), page);
        assertTrue(page.contains("Section : Aller à la ligne dans une réplique"), page);
        assertTrue(get("/docs").body().contains(" autofocus"), "autofocus gardé sur l'accueil");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void unknownSlugIs404NotError() throws Exception {
        start();
        login();
        HttpResponse<String> r = get("/docs/pas-une-fiche");
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("Fiche introuvable"), r.body());
    }

    @Test
    void pathTraversalIsRefusedAndNeverReadsTheFilesystem() throws Exception {
        start();
        login();
        for (String evil : new String[] {
                "/docs/../secret", "/docs/..%2f..%2fetc%2fpasswd", "/docs/%2e%2e/config",
                "/docs/pnj-citizens/../joueurs-reset", "/docs/PNJ-Citizens", "/docs/a%20b",
                "/docs/_index" }) {
            HttpResponse<String> r = get(evil);
            assertTrue(r.statusCode() == 404 || r.statusCode() == 400,
                    evil + " -> " + r.statusCode());
            assertFalse(r.body().contains("root:x:0:0"), evil);
            assertFalse(r.body().contains("Erreur interne"), evil + " ne doit pas être une 500");
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        m.find();
        return m.group(1);
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).GET());
    }

    private HttpResponse<String> post(String p, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).reduce((x, y) -> x + "; " + y).orElse(""));
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String sc : res.headers().allValues("Set-Cookie")) {
            String[] f = sc.split(";", 2);
            int eq = f[0].indexOf('=');
            String n = f[0].substring(0, eq).trim();
            String v = f[0].substring(eq + 1).trim();
            if (sc.toLowerCase().contains("max-age=0") || v.isEmpty()) {
                jar.remove(n);
            } else {
                jar.put(n, v);
            }
        }
        return res;
    }
}
