package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.content.ContentPackSchema;
import com.lodygames.rpgquest.panel.support.TestConfig;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Issue #109 — la page d'import, de bout en bout sur un vrai serveur HTTP.
 *
 * <p>Ce que ces tests vérifient, c'est le <strong>comportement observable</strong> : qu'un POST
 * d'analyse n'écrit rien sur le disque, qu'une collision affiche deux boutons de décision au lieu
 * d'écraser, et que la confirmation n'écrit qu'après arbitrage. Le moteur lui-même est couvert par
 * {@code ContentPackImportTest}.</p>
 */
class ContentImportPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private Path contentRoot;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private static final String PACK = "format: " + ContentPackSchema.FORMAT + "\n"
            + "schemaVersion: " + ContentPackSchema.SCHEMA_VERSION + "\n"
            + "content:\n"
            + "  quests:\n"
            + "    - id: rpgquest:mines\n"
            + "      title: \"Les mines\"\n"
            + "      description: \"Descendre.\"\n"
            + "      category: test\n"
            + "      icon: LANTERN\n"
            + "      steps:\n"
            + "        - id: creuser\n"
            + "          objectives:\n"
            + "            - type: BREAK_BLOCK\n"
            + "              material: STONE\n"
            + "              amount: 10\n";

    @Test
    void thePageRequiresASession() throws Exception {
        start();

        HttpResponse<String> res = get("/content/import");

        assertEquals(303, res.statusCode());
        assertEquals("/login", res.headers().firstValue("Location").orElse(""));
    }

    @Test
    void theEmptyPageExplainsThePipelineAndWhatIsNotDone() throws Exception {
        start();
        login();

        String page = get("/content/import").body();

        assertTrue(page.contains("1. Le pack à importer"), "étape 1 annoncée");
        // Les libellés rendus passent par Http.esc : l'apostrophe y devient « &#39; ». On vérifie
        // donc des fragments sans apostrophe, sinon le test échouerait sur l'échappement et non sur
        // le contenu.
        assertTrue(page.contains("avant la confirmation"), "la page doit dire que rien n'est écrit avant");
        assertTrue(page.contains("Aucun écrasement silencieux"));
        assertTrue(page.contains("import n") && page.contains("active rien"),
                "la distinction source/serveur doit être dite");
        assertFalse(page.contains("2. Analyse"), "pas d'analyse tant qu'aucun pack n'est soumis");
    }

    /** Le critère central du ticket, vérifié sur le disque réel : analyser n'écrit rien. */
    @Test
    void analysingDoesNotWriteAnythingOnDisk() throws Exception {
        start();
        login();

        String page = post("/content/import", "pack=" + enc(PACK) + "&_csrf=" + csrf(get("/content/import").body())).body();

        assertTrue(page.contains("2. Analyse du pack"));
        assertTrue(page.contains("nouveau"), "l'élément doit être annoncé nouveau");
        assertTrue(page.contains("Enregistrer dans la source"), "la confirmation est proposée");
        try (var files = Files.list(contentRoot.resolve("quests"))) {
            assertEquals(0, files.count(), "aucun fichier ne doit avoir été créé par l'analyse");
        }
    }

    @Test
    void confirmingWritesTheElementAndSaysWhereItWent() throws Exception {
        start();
        login();
        String token = csrf(get("/content/import").body());

        String page = post("/content/import",
                "pack=" + enc(PACK) + "&confirm=true&_csrf=" + token).body();

        assertTrue(page.contains("Import enregistré"), page.substring(0, Math.min(400, page.length())));
        assertTrue(page.contains("quests/mines"));
        assertTrue(Files.isRegularFile(contentRoot.resolve("quests/mines.yml")));
        String saved = Files.readString(contentRoot.resolve("quests/mines.yml"));
        assertTrue(saved.contains("Les mines"), saved);
    }

    /** Une collision affiche les deux décisions et retire le bouton d'enregistrement. */
    @Test
    void aCollisionOffersBothDecisionsAndBlocksTheSaveButton() throws Exception {
        start();
        login();
        Files.writeString(contentRoot.resolve("quests/mines.yml"),
                "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");

        String page = post("/content/import",
                "pack=" + enc(PACK) + "&_csrf=" + csrf(get("/content/import").body())).body();

        assertTrue(page.contains("conflit"), "l'élément doit être marqué en conflit");
        assertTrue(page.contains("value=\"REPLACE\"") && page.contains("value=\"SKIP\""),
                "les deux décisions doivent être proposées comme boutons");
        assertTrue(page.contains("Remplacer l") && page.contains("Garder l"),
                "avec leurs libellés (apostrophe échappée par Http.esc)");
        assertFalse(page.contains("Enregistrer dans la source"),
                "aucun bouton d'enregistrement tant que la collision n'est pas tranchée");
        assertTrue(page.contains("attendent une décision explicite"), page);
        assertTrue(Files.readString(contentRoot.resolve("quests/mines.yml")).contains("Ancien titre"),
                "le contenu existant est intact");
    }

    /** Et une confirmation forcée sur une collision non tranchée est refusée, pas appliquée. */
    @Test
    void forcingAConfirmationOnAPendingCollisionIsRefused() throws Exception {
        start();
        login();
        Files.writeString(contentRoot.resolve("quests/mines.yml"),
                "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");

        String page = post("/content/import",
                "pack=" + enc(PACK) + "&confirm=true&_csrf=" + csrf(get("/content/import").body())).body();

        assertTrue(page.contains("Import refusé"), page.substring(0, Math.min(600, page.length())));
        assertTrue(Files.readString(contentRoot.resolve("quests/mines.yml")).contains("Ancien titre"),
                "rien n'a été écrasé");
    }

    @Test
    void decidingToReplaceThenConfirmingWritesTheNewContent() throws Exception {
        start();
        login();
        Files.writeString(contentRoot.resolve("quests/mines.yml"),
                "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");
        String token = csrf(get("/content/import").body());

        String page = post("/content/import", "pack=" + enc(PACK)
                + "&decision." + enc("quests/mines") + "=REPLACE&confirm=true&_csrf=" + token).body();

        assertTrue(page.contains("Import enregistré"), page.substring(0, Math.min(400, page.length())));
        String saved = Files.readString(contentRoot.resolve("quests/mines.yml"));
        assertTrue(saved.contains("Les mines"), saved);
        assertFalse(saved.contains("Ancien titre"));
    }

    @Test
    void decidingToSkipKeepsTheExistingContent() throws Exception {
        start();
        login();
        Files.writeString(contentRoot.resolve("quests/mines.yml"),
                "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");
        String token = csrf(get("/content/import").body());

        String page = post("/content/import", "pack=" + enc(PACK)
                + "&decision." + enc("quests/mines") + "=SKIP&_csrf=" + token).body();

        assertTrue(page.contains("ignoré"));
        assertFalse(page.contains("Enregistrer dans la source"), "plus rien à écrire");
        assertTrue(Files.readString(contentRoot.resolve("quests/mines.yml")).contains("Ancien titre"));
    }

    /** Une valeur d'arbitrage inconnue n'est jamais interprétée comme « remplacer ». */
    @Test
    void anUnknownDecisionValueLeavesTheConflictPending() throws Exception {
        start();
        login();
        Files.writeString(contentRoot.resolve("quests/mines.yml"),
                "id: rpgquest:mines\ntitle: \"Ancien titre\"\n");

        String page = post("/content/import", "pack=" + enc(PACK)
                + "&decision." + enc("quests/mines") + "=ECRASE_TOUT&confirm=true&_csrf="
                + csrf(get("/content/import").body())).body();

        assertTrue(page.contains("Import refusé") || page.contains("conflit"), page);
        assertTrue(Files.readString(contentRoot.resolve("quests/mines.yml")).contains("Ancien titre"));
    }

    @Test
    void aPostWithoutAValidCsrfTokenIsRefused() throws Exception {
        start();
        login();

        HttpResponse<String> res = post("/content/import", "pack=" + enc(PACK) + "&_csrf=faux");

        assertEquals(403, res.statusCode());
    }

    @Test
    void anUnsupportedSchemaVersionIsRefusedOnThePage() throws Exception {
        start();
        login();

        String page = post("/content/import", "pack="
                + enc("format: " + ContentPackSchema.FORMAT + "\nschemaVersion: 99\ncontent: {}\n")
                + "&_csrf=" + csrf(get("/content/import").body())).body();

        assertTrue(page.contains("plus récente"), page.substring(0, Math.min(600, page.length())));
        assertFalse(page.contains("Enregistrer dans la source"));
    }

    // ---- Harnais -------------------------------------------------------------------------------

    private void start() throws Exception {
        contentRoot = tmp.resolve("content");
        for (String kind : com.lodygames.rpgquest.panel.content.ContentWorkspace.KINDS) {
            Files.createDirectories(contentRoot.resolve(kind));
        }
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1", contentRoot.toString()),
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

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
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
