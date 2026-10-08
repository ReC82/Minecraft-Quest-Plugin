package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.ai.AiSettingsStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
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
 * Issue #146 — les deux pages de l'atelier IA, sur un vrai serveur HTTP.
 *
 * <p><strong>Aucun appel réseau sortant.</strong> Aucun test ne configure de clé valide : ce qui est
 * vérifié ici, c'est le comportement du panel — permissions, CSRF, non-divulgation de la clé, et le
 * fait que l'atelier n'offre aucun chemin d'écriture. Le pipeline de génération lui-même est couvert
 * par {@code AiQuestStudioTest} avec un fournisseur bouchon.</p>
 */
class AiPagesTest {

    private static final String KEY = "sk-ant-SECRET-NE-DOIT-JAMAIS-SORTIR-42";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private Path contentRoot;
    private String dbPath;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- Accès ---------------------------------------------------------------------------------

    @Test
    void bothPagesRequireASession() throws Exception {
        start();

        for (String path : new String[] {"/ai/studio", "/ai/providers"}) {
            HttpResponse<String> res = get(path);
            assertEquals(303, res.statusCode(), path);
            assertEquals("/login", res.headers().firstValue("Location").orElse(""), path);
        }
    }

    @Test
    void theStudioIsASeparatePageWithItsOwnNavEntry() throws Exception {
        start();
        login();

        String page = get("/ai/studio").body();

        assertTrue(page.contains("Créer avec une IA"));
        assertTrue(page.contains("href=\"/ai/studio\""), "entrée de menu présente");
        assertTrue(page.contains("href=\"/ai/providers\""), "et celle de la configuration");
    }

    /** Sans fournisseur configuré, la page le dit et renvoie là où il faut, sans formulaire. */
    @Test
    void withoutAnyUsableProviderTheStudioSaysSoAndPointsAtTheConfiguration() throws Exception {
        start();
        login();

        String page = get("/ai/studio").body();

        assertTrue(page.contains("Aucun fournisseur"), page.substring(0, Math.min(600, page.length())));
        assertTrue(page.contains("/ai/providers"));
        assertFalse(page.contains("Demander une proposition"), "pas de formulaire sans fournisseur");
    }

    @Test
    void theFormAppearsOnceAProviderIsUsable() throws Exception {
        start();
        configureProvider();
        login();

        String page = get("/ai/studio").body();

        assertTrue(page.contains("Demander une proposition"));
        assertTrue(page.contains("name=\"intent\""), "le champ d'intention");
        assertTrue(page.contains("Anthropic"), "le fournisseur utilisable est proposé");
    }

    // ---- Non-divulgation de la clé -------------------------------------------------------------

    /**
     * Le point le plus important du lot : une clé enregistrée ne doit <strong>jamais</strong>
     * réapparaître dans une réponse HTTP. Ce test lit la page entière et cherche la clé.
     */
    @Test
    void aStoredKeyNeverAppearsInAnyResponse() throws Exception {
        start();
        configureProvider();
        login();

        for (String path : new String[] {"/ai/providers", "/ai/studio"}) {
            String page = get(path).body();
            assertFalse(page.contains(KEY), "la clé ne doit pas figurer dans " + path);
            assertFalse(page.contains("SECRET"), "ni aucun de ses fragments dans " + path);
        }
    }

    @Test
    void theProvidersPageShowsOnlyALengthAndAFingerprint() throws Exception {
        start();
        configureProvider();
        login();

        String page = get("/ai/providers").body();

        assertTrue(page.contains("clé enregistrée"), "l'état est annoncé");
        assertTrue(page.contains("empreinte"), "une empreinte permet de reconnaître la clé");
        assertTrue(page.contains(String.valueOf(KEY.length())), "la longueur est affichée");
        assertTrue(page.contains("value=\"\""), "le champ de saisie reste vide");
    }

    @Test
    void theKeyFieldIsAPasswordFieldThatBrowsersWillNotAutofill() throws Exception {
        start();
        login();

        String page = get("/ai/providers").body();

        assertTrue(page.contains("name=\"apiKey\""));
        assertTrue(page.contains("type=\"password\""), "jamais en clair à l'écran");
        assertTrue(page.contains("autocomplete=\"new-password\""),
                "le gestionnaire de mots de passe ne doit ni remplir ni enregistrer une clé d'API");
    }

    // ---- Permissions ---------------------------------------------------------------------------

    /**
     * Configurer les clés et utiliser l'atelier sont deux permissions distinctes : un éditeur de
     * contenu peut générer, mais n'a aucune raison de toucher à une clé d'API tierce.
     */
    @Test
    void usingTheStudioAndConfiguringKeysAreTwoDistinctPermissions() {
        var editor = com.lodygames.rpgquest.panel.authz.Role.CONTENT_EDITOR;
        var admin = com.lodygames.rpgquest.panel.authz.Role.ADMIN;
        var tester = com.lodygames.rpgquest.panel.authz.Role.TESTER;
        var readOnly = com.lodygames.rpgquest.panel.authz.Role.READ_ONLY;

        assertTrue(editor.permissions().contains(com.lodygames.rpgquest.panel.authz.Permission.AI_USE));
        assertFalse(editor.permissions().contains(
                com.lodygames.rpgquest.panel.authz.Permission.AI_CONFIGURE),
                "un éditeur de contenu ne configure pas de clé d'API");
        assertTrue(admin.permissions().contains(com.lodygames.rpgquest.panel.authz.Permission.AI_CONFIGURE));
        assertFalse(tester.permissions().contains(com.lodygames.rpgquest.panel.authz.Permission.AI_USE),
                "un testeur ne dépense pas de jetons");
        assertFalse(readOnly.permissions().contains(com.lodygames.rpgquest.panel.authz.Permission.AI_USE));
    }

    // ---- CSRF et validation d'entrée -----------------------------------------------------------

    @Test
    void aPostWithoutAValidCsrfTokenIsRefusedOnBothPages() throws Exception {
        start();
        login();

        assertEquals(403, post("/ai/studio", "intent=test&_csrf=faux").statusCode());
        assertEquals(403, post("/ai/providers", "provider=anthropic&_csrf=faux").statusCode());
    }

    @Test
    void generatingWithoutAnIntentIsRefusedWithoutCallingAnything() throws Exception {
        start();
        configureProvider();
        login();

        String page = post("/ai/studio",
                "intent=&provider=anthropic&_action=generate&_csrf=" + csrf(get("/ai/studio").body())).body();

        // Les libellés passent par Http.esc, qui encode l'apostrophe en « &#39; » : on assert donc
        // sur un fragment sans apostrophe, sinon l'échec porterait sur l'échappement.
        assertTrue(page.contains("Décrivez"), page.substring(0, Math.min(700, page.length())));
        assertFalse(page.contains("2. Proposition"), "aucune proposition sans demande");
    }

    @Test
    void anUnknownProviderOnTheConfigurationPageIsRefused() throws Exception {
        start();
        login();

        String page = post("/ai/providers",
                "provider=inexistant&_action=save&_csrf=" + csrf(get("/ai/providers").body())).body();

        assertTrue(page.contains("Fournisseur inconnu"), page.substring(0, Math.min(600, page.length())));
    }

    // ---- Configuration : enregistrement, conservation, effacement -------------------------------

    @Test
    void savingThenReadingBackKeepsTheKeyServerSideOnly() throws Exception {
        start();
        login();
        String token = csrf(get("/ai/providers").body());

        post("/ai/providers", "provider=anthropic&apiKey=" + enc(KEY)
                + "&model=claude-x&enabled=on&_action=save&_csrf=" + token);

        // La clé est bien là, côté serveur.
        assertEquals(KEY, new AiSettingsStore(dbPath).get("anthropic").apiKey());
        // Et nulle part dans la page.
        assertFalse(get("/ai/providers").body().contains(KEY));
    }

    /** Un champ clé vide conserve la clé : sinon changer de modèle l'effacerait. */
    @Test
    void resavingWithAnEmptyKeyFieldKeepsTheStoredKey() throws Exception {
        start();
        configureProvider();
        login();
        String token = csrf(get("/ai/providers").body());

        post("/ai/providers", "provider=anthropic&apiKey=&model=autre-modele&enabled=on"
                + "&_action=save&_csrf=" + token);

        AiSettingsStore store = new AiSettingsStore(dbPath);
        assertEquals(KEY, store.get("anthropic").apiKey(), "la clé a survécu");
        assertEquals("autre-modele", store.get("anthropic").model());
    }

    @Test
    void clearingTheKeyRemovesItAndDisablesTheProvider() throws Exception {
        start();
        configureProvider();
        login();

        String page = post("/ai/providers", "provider=anthropic&_action=clear&_csrf="
                + csrf(get("/ai/providers").body())).body();

        assertTrue(page.contains("Clé effacée"), page.substring(0, Math.min(600, page.length())));
        AiSettingsStore store = new AiSettingsStore(dbPath);
        assertFalse(store.get("anthropic").hasKey());
        assertFalse(store.get("anthropic").enabled());
    }

    /** Une URL de base en clair est refusée : une clé d'API ne circule jamais sans TLS. */
    @Test
    void aPlainHttpBaseUrlIsRefusedWhenTestingTheConnection() throws Exception {
        start();
        login();
        String token = csrf(get("/ai/providers").body());

        String page = post("/ai/providers", "provider=anthropic&apiKey=" + enc(KEY)
                + "&baseUrl=" + enc("http://exemple.invalid") + "&enabled=on&_action=test&_csrf="
                + token).body();

        assertTrue(page.contains("HTTPS"), page.substring(0, Math.min(900, page.length())));
        assertFalse(page.contains(KEY));
    }

    // ---- L'atelier n'écrit rien ----------------------------------------------------------------

    /**
     * L'atelier ne possède aucun chemin d'écriture : son bouton d'enregistrement poste vers la page
     * d'import, qui exige sa propre confirmation. C'est la dernière exigence du ticket, obtenue par
     * construction plutôt que par vigilance.
     */
    @Test
    void theStudioOffersNoDirectSaveAndSaysSo() throws Exception {
        start();
        configureProvider();
        login();

        String page = get("/ai/studio").body();

        assertTrue(page.contains("Cette page n"), "elle annonce qu'elle n'écrit rien");
        assertTrue(page.contains("page d") && page.contains("import"),
                "et renvoie vers l'import pour l'enregistrement");
        assertFalse(page.contains("action=\"/quests/save\""), "aucun raccourci vers l'écriture");
        assertFalse(page.contains("action=\"/ai/save\""));
    }

    @Test
    void afterAFailedCallNothingIsWrittenOnDisk() throws Exception {
        start();
        configureProvider();
        login();

        // La clé est bidon et l'hôte par défaut inatteignable depuis le test : l'appel échoue.
        post("/ai/studio", "intent=" + enc("Une quête de test.")
                + "&provider=anthropic&_action=generate&_csrf=" + csrf(get("/ai/studio").body()));

        try (var files = Files.list(contentRoot.resolve("quests"))) {
            assertEquals(0, files.count(), "aucun fichier ne doit apparaître");
        }
    }

    // ---- Harnais -------------------------------------------------------------------------------

    private void start() throws Exception {
        contentRoot = tmp.resolve("content");
        for (String kind : com.lodygames.rpgquest.panel.content.ContentWorkspace.KINDS) {
            Files.createDirectories(contentRoot.resolve(kind));
        }
        dbPath = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withContentDir(dbPath, "http://127.0.0.1:1/admin/v1",
                contentRoot.toString()),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(dbPath));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    /** Pose une clé directement dans le stockage, sans passer par l'IHM. */
    private void configureProvider() {
        new AiSettingsStore(dbPath).save(
                new com.lodygames.rpgquest.panel.ai.AiProviderSettings("anthropic", true, KEY,
                        "", "", 4000, 5), "test");
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
