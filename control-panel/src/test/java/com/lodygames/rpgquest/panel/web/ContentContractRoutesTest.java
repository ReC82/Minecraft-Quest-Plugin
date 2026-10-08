package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.content.ContentPackSchema;
import com.lodygames.rpgquest.panel.content.Descriptors;
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

/**
 * Issue #110 — les trois téléchargements du contrat de contenu.
 *
 * <p>Ce qui compte ici n'est pas le contenu des documents (couvert par
 * {@code ContentPackContractTest}) mais le fait qu'ils soient réellement <strong>servis</strong> :
 * authentifiés, en pièce jointe, et <strong>sans dépendre d'un agent</strong> — le contrat décrit le
 * format, pas l'état du serveur Minecraft, donc il doit rester téléchargeable agent hors ligne. La
 * configuration de test pointe volontairement vers un agent injoignable.</p>
 */
class ContentContractRoutesTest {

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

    @Test
    void theThreeContractDocumentsRequireASession() throws Exception {
        start();

        for (String path : new String[] {"/content/schema.json", "/content/template", "/content/contract.md"}) {
            HttpResponse<String> res = get(path);
            assertEquals(303, res.statusCode(), path + " doit exiger une session");
            assertEquals("/login", res.headers().firstValue("Location").orElse(""), path);
        }
    }

    @Test
    void theSchemaIsServedAsAnAttachmentAndCarriesTheRealObjectiveTypes() throws Exception {
        start();
        login();

        HttpResponse<String> res = get("/content/schema.json");

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Disposition").orElse("").contains("attachment"),
                "le schéma doit se télécharger, pas s'afficher");
        assertTrue(res.headers().firstValue("Content-Disposition").orElse("")
                .contains("lodyquests-content-pack-v" + ContentPackSchema.SCHEMA_VERSION), "nom de fichier versionné");
        for (Descriptors.Descriptor d : Descriptors.OBJECTIVES) {
            assertTrue(res.body().contains("\"" + d.kind() + "\""), "type absent du schéma servi : " + d.kind());
        }
    }

    @Test
    void aTemplateIsServedPerFamilyAndTheFileNameSaysWhichOne() throws Exception {
        start();
        login();

        for (String family : ContentPackSchema.FAMILIES) {
            HttpResponse<String> res = get("/content/template?family=" + family);

            assertEquals(200, res.statusCode(), family);
            assertTrue(res.headers().firstValue("Content-Disposition").orElse("")
                    .contains("lodyquests-template-" + family + ".yml"), "nom de fichier pour " + family);
            assertTrue(res.body().contains("  " + family + ":"), "section « " + family + " » absente");
        }
    }

    /** Une famille inconnue n'est pas une erreur : elle sert le gabarit complet, et le nom le dit. */
    @Test
    void anUnknownFamilyServesTheFullTemplateUnderAnHonestFileName() throws Exception {
        start();
        login();

        HttpResponse<String> res = get("/content/template?family=recettes");

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Disposition").orElse("")
                .contains("lodyquests-template-content.yml"),
                "le nom du fichier doit dire ce qui a réellement été servi");
        assertTrue(res.body().contains("  quests:") && res.body().contains("  npcs:"));
    }

    @Test
    void theWrittenContractEmbedsBothExamples() throws Exception {
        start();
        login();

        HttpResponse<String> res = get("/content/contract.md");

        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Disposition").orElse("")
                .contains("lodyquests-content-contract.md"));
        assertTrue(res.body().contains("## Exemple minimal"));
        assertTrue(res.body().contains("tc110_descente"), "l'exemple complet doit être inclus");
    }

    /** La page d'export annonce les trois documents : sans ça, personne ne les trouve. */
    @Test
    void theExportPageLinksTheThreeDocuments() throws Exception {
        start();
        login();

        String page = get("/content/export").body();

        assertTrue(page.contains("/content/schema.json"), "lien du schéma absent");
        assertTrue(page.contains("/content/contract.md"), "lien du contrat rédigé absent");
        assertTrue(page.contains("/content/template"), "lien du gabarit absent");
        assertTrue(page.contains("Contrat de contenu"), "titre de section absent");
    }

    // ---- Harnais -------------------------------------------------------------------------------

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

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("aucun jeton CSRF dans la page");
        }
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
