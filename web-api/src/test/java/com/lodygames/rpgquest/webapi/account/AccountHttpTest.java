package com.lodygames.rpgquest.webapi.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.webapi.HttpServerBootstrap;
import com.lodygames.rpgquest.webapi.SnapshotStore;
import com.lodygames.rpgquest.webapi.WebApiConfig;
import com.lodygames.rpgquest.webapi.privacy.AgePolicy;
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
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #53 : parcours complet par HTTP — inscription, connexion, code, preuve en jeu, dissociation. */
class AccountHttpTest {

    private static final String API_TOKEN = "general-api-token";
    private static final String LINK_TOKEN = "link-token-for-minecraft";
    private static final String ALICE_UUID = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private HttpServerBootstrap bootstrap;
    private AccountStore store;
    private String base;

    @AfterEach
    void tearDown() throws Exception {
        if (bootstrap != null) {
            bootstrap.stop();
        }
        if (store != null) {
            store.close();
        }
    }

    private void start(boolean withAccounts) throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        AgePolicy policy = new AgePolicy(Map.of("BE", 13), AgePolicy.Outcome.PARENTAL_CONSENT_REQUIRED,
                AgePolicy.Outcome.REFUSED);
        AccountSettings settings = new AccountSettings(withAccounts, tempDir.resolve("accounts.db"),
                Duration.ofDays(7), Duration.ofMinutes(10), LINK_TOKEN, true);
        WebApiConfig config = new WebApiConfig(port, tempDir.resolve("snapshot.json"), 120, API_TOKEN, 1000, "Test",
                tempDir.resolve("products.json"), tempDir.resolve("store.db"), null, "https://portail.example",
                "127.0.0.1", false, false, false, policy, settings);
        AccountService service = null;
        if (withAccounts) {
            store = new AccountStore(settings.databaseFile());
            service = new AccountService(store, new PasswordHasher(1_000), policy, settings, java.time.Clock.systemUTC());
        }
        bootstrap = new HttpServerBootstrap(config,
                new SnapshotStore(config.snapshotFile(), 120, Logger.getLogger("account-http-test")), null, service);
        bootstrap.start();
    }

    // --- outils ----------------------------------------------------------------------------

    private HttpResponse<String> get(String path, String cookies) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (cookies != null) {
            builder.header("Cookie", cookies);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String cookies, Map<String, String> form, String origin)
            throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookies != null) {
            builder.header("Cookie", cookies);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> confirm(String token, String json) throws Exception {
        return postJson("/api/link/confirm", token, json);
    }

    private HttpResponse<String> progress(String token, String json) throws Exception {
        return postJson("/api/link/progress", token, json);
    }

    private HttpResponse<String> postJson(String path, String token, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String cookie(HttpResponse<?> response, String name) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            if (header.startsWith(name + "=")) {
                return header;
            }
        }
        return null;
    }

    private static String cookieValue(HttpResponse<?> response, String name) {
        String header = cookie(response, name);
        return header == null ? null : header.substring(name.length() + 1, header.indexOf(';'));
    }

    private static String hidden(String html, String name) {
        Matcher matcher = Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Map<String, String> form(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** Inscrit puis connecte « alice » ; renvoie le cookie de session. */
    private String registerAndLogin() throws Exception {
        HttpResponse<String> page = get("/compte", null);
        String formCookie = cookieValue(page, Cookies.FORM);
        String csrf = hidden(page.body(), "csrf");
        String cookies = Cookies.FORM + "=" + formCookie;

        HttpResponse<String> registered = post("/compte/inscription", cookies, form("csrf", csrf,
                "identifiant", "alice", "motdepasse", "motdepasse-alice", "pays", "BE", "age", "15"), null);
        assertEquals(303, registered.statusCode(), registered.body());

        HttpResponse<String> login = post("/compte/connexion", cookies,
                form("csrf", csrf, "identifiant", "alice", "motdepasse", "motdepasse-alice"), "https://portail.example");
        assertEquals(303, login.statusCode(), login.body());
        return Cookies.SESSION + "=" + cookieValue(login, Cookies.SESSION);
    }

    // --- tests -----------------------------------------------------------------------------

    @Test
    void accountsDisabledShowsAClosedPageAndNoConfirmRoute() throws Exception {
        start(false);

        HttpResponse<String> page = get("/compte", null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("pas encore ouverts"));
        assertFalse(page.body().contains("<form"));
        assertEquals(401, confirm(LINK_TOKEN, "{}").statusCode(), "route absente : fermée par /api/");
    }

    @Test
    void sessionCookieIsHttpOnlySecureLaxAndPagesAreNotCached() throws Exception {
        start(true);
        HttpResponse<String> page = get("/compte", null);
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(""));
        String formCookie = cookie(page, Cookies.FORM);
        assertTrue(formCookie.contains("HttpOnly") && formCookie.contains("SameSite=Strict") && formCookie.contains("Secure"));

        String session = registerAndLogin();

        HttpResponse<String> dashboard = get("/compte", session);
        assertTrue(dashboard.body().contains("Connecté en tant que <strong>alice</strong>"));
        assertTrue(dashboard.body().contains("Aucun compte Minecraft relié"));
    }

    @Test
    void formsWithoutTokenOrFromAnotherSiteAreRefused() throws Exception {
        start(true);
        HttpResponse<String> page = get("/compte", null);
        String cookies = Cookies.FORM + "=" + cookieValue(page, Cookies.FORM);
        String csrf = hidden(page.body(), "csrf");
        Map<String, String> registration = form("identifiant", "eve", "motdepasse", "motdepasse-eve", "pays", "BE",
                "age", "20");

        assertEquals(403, post("/compte/inscription", cookies, registration, null).statusCode(), "sans jeton");
        Map<String, String> withToken = new LinkedHashMap<>(registration);
        withToken.put("csrf", csrf);
        assertEquals(403, post("/compte/inscription", null, withToken, null).statusCode(), "sans cookie");
        assertEquals(403, post("/compte/inscription", cookies, withToken, "https://evil.example").statusCode());
        assertTrue(store.findAccountByLogin("eve").isEmpty());

        String session = registerAndLogin();
        assertEquals(403, post("/compte/lien", session, form("csrf", "faux"), null).statusCode());
        assertEquals(403, post("/compte/deconnexion", session, form(), null).statusCode());
    }

    @Test
    void fullLinkJourneyThroughTheMinecraftProofAndBack() throws Exception {
        start(true);
        String session = registerAndLogin();
        String csrf = hidden(get("/compte", session).body(), "csrf");

        HttpResponse<String> challenge = post("/compte/lien", session, form("csrf", csrf), null);
        assertEquals(200, challenge.statusCode());
        Matcher code = Pattern.compile("/lien ([A-Z0-9]{8})").matcher(challenge.body());
        assertTrue(code.find(), challenge.body());

        String proof = "{\"code\":\"" + code.group(1) + "\",\"uuid\":\"" + ALICE_UUID + "\",\"name\":\"AliceMC\"}";
        assertEquals(401, confirm(null, proof).statusCode());
        assertEquals(401, confirm(API_TOKEN, proof).statusCode(), "le jeton général n'ouvre pas la liaison");
        HttpResponse<String> linked = confirm(LINK_TOKEN, proof);
        assertEquals(200, linked.statusCode());
        assertEquals("{\"result\":\"LINKED\"}", linked.body());
        assertFalse(linked.body().contains("alice"), "aucune donnée du compte web renvoyée au jeu");
        assertTrue(confirm(LINK_TOKEN, proof).body().contains("INVALID_CODE"), "code non rejouable");

        String dashboard = get("/compte", session).body();
        assertTrue(dashboard.contains("Relié à <strong>AliceMC</strong>"));
        assertTrue(dashboard.contains("liaison avec AliceMC"));

        assertEquals(403, post("/compte/delier", session, form("csrf", csrf, "motdepasse", "faux"), null).statusCode());
        HttpResponse<String> unlinked = post("/compte/delier", session,
                form("csrf", csrf, "motdepasse", "motdepasse-alice"), null);
        assertEquals(303, unlinked.statusCode());
        assertTrue(get("/compte?m=delie", session).body().contains("dissociation de AliceMC"));
    }

    private static final String PROBE = "{\"uuid\":\"" + ALICE_UUID + "\"}";

    private static String progressBody(String generatedAt, String boardName) {
        return "{\"uuid\":\"" + ALICE_UUID + "\",\"name\":\"AliceMC\",\"progress\":{\"generatedAt\":\"" + generatedAt
                + "\",\"skills\":[{\"skill\":\"GLOBAL\",\"xp\":1234,\"level\":7},{\"skill\":\"COMBAT\",\"xp\":600,\"level\":4}],"
                + "\"quests\":{\"completed\":3,\"rank\":2,\"players\":9},"
                + "\"boards\":[{\"id\":\"combat_semaine\",\"name\":\"" + boardName + "\",\"category\":\"combat\","
                + "\"metric\":\"RPG_XP_COMBAT\",\"period\":\"weekly\",\"window\":\"2026-W47\",\"status\":\"ACTIVE\","
                + "\"value\":42,\"rank\":1,\"players\":5},"
                + "{\"id\":\"peche_jour\",\"name\":\"Pêcheurs du jour\",\"category\":\"recolte\",\"metric\":\"FISH_CAUGHT\","
                + "\"period\":\"daily\",\"window\":\"2026-11-20\",\"status\":\"ACTIVE\",\"value\":0}]}}";
    }

    /** Relie « alice » à {@link #ALICE_UUID} par le vrai parcours (code puis preuve). */
    private void link(String session) throws Exception {
        String csrf = hidden(get("/compte", session).body(), "csrf");
        Matcher code = Pattern.compile("/lien ([A-Z0-9]{8})").matcher(
                post("/compte/lien", session, form("csrf", csrf), null).body());
        assertTrue(code.find());
        assertEquals("{\"result\":\"LINKED\"}", confirm(LINK_TOKEN,
                "{\"code\":\"" + code.group(1) + "\",\"uuid\":\"" + ALICE_UUID + "\",\"name\":\"AliceMC\"}").body());
    }

    @Test
    void linkedProgressIsStoredOnlyForTheLinkedAccountAndShownOnlyToItsOwner() throws Exception {
        start(true);
        String session = registerAndLogin();

        assertEquals(401, progress(null, PROBE).statusCode());
        assertEquals(401, progress(API_TOKEN, PROBE).statusCode(), "le jeton général n'ouvre pas cette route");
        assertEquals("{\"result\":\"NOT_LINKED\"}", progress(LINK_TOKEN, PROBE).body());
        assertEquals("{\"result\":\"NOT_LINKED\"}",
                progress(LINK_TOKEN, progressBody("2026-11-20T10:00:00Z", "Combat")).body(), "rien n'est gardé");
        assertTrue(get("/compte", session).body().contains("Aucun compte Minecraft relié"));

        link(session);
        assertTrue(get("/compte", session).body().contains("Ta progression arrivera ici"), "aucune valeur inventée");
        assertEquals("{\"result\":\"LINKED\"}", progress(LINK_TOKEN, PROBE).body());
        assertEquals("{\"result\":\"STORED\"}",
                progress(LINK_TOKEN, progressBody("2026-11-20T10:00:00Z", "Combattants <b>semaine</b>")).body());
        assertEquals("{\"result\":\"THROTTLED\"}",
                progress(LINK_TOKEN, progressBody("2026-11-20T10:01:00Z", "Autre")).body(), "envoi trop rapproché");

        String dashboard = get("/compte", session).body();
        assertTrue(dashboard.contains("Ma progression"));
        assertTrue(dashboard.contains("Niveau 7"));
        assertTrue(dashboard.contains("Combattants &lt;b&gt;semaine&lt;/b&gt;"), "texte échappé");
        assertTrue(dashboard.contains("2<sup>e</sup> sur 9 joueurs"));
        assertTrue(dashboard.contains("Pas encore classé"));
        assertFalse(get("/compte", null).body().contains("Ma progression"), "jamais sans session");

        String csrf = hidden(dashboard, "csrf");
        post("/compte/delier", session, form("csrf", csrf, "motdepasse", "motdepasse-alice"), null);
        assertTrue(store.findProgress(store.findAccountByLogin("alice").orElseThrow().id()).isEmpty(),
                "la progression disparaît avec la liaison");
        assertEquals("{\"result\":\"NOT_LINKED\"}", progress(LINK_TOKEN, PROBE).body());
        assertFalse(get("/compte", session).body().contains("Ma progression"));
    }

    @Test
    void progressRejectsAnythingOutsideTheContract() throws Exception {
        start(true);
        link(registerAndLogin());

        assertEquals(400, progress(LINK_TOKEN, "pas du json").statusCode());
        assertEquals(400, progress(LINK_TOKEN, "{\"uuid\":\"x\"}").statusCode());
        String valid = progressBody("2026-11-20T10:00:00Z", "Combat");
        assertEquals(400, progress(LINK_TOKEN, valid.replace("\"xp\":600", "\"xp\":-1")).statusCode(), "négatif");
        assertEquals(400, progress(LINK_TOKEN, valid.replace("\"rank\":1,\"players\":5", "\"rank\":6,\"players\":5"))
                .statusCode(), "rang hors effectif");
        assertEquals(400, progress(LINK_TOKEN, valid.replace("\"status\":\"ACTIVE\"", "\"status\":\"X\"")).statusCode());
        assertEquals(400, progress(LINK_TOKEN, valid.replace("\"metric\":\"RPG_XP_COMBAT\"", "\"metric\":\"<x>\""))
                .statusCode());
        assertEquals(400, progress(LINK_TOKEN, valid.replace("\"name\":\"AliceMC\"", "\"name\":\"<x>\"")).statusCode());
        assertEquals(400, progress(LINK_TOKEN, "{\"uuid\":\"" + ALICE_UUID + "\",\"name\":\"A\",\"progress\":\""
                + "x".repeat(LinkProgressHandler.MAX_BODY_BYTES) + "\"}").statusCode(), "trop long");
        assertTrue(store.findProgress(store.findAccountByLogin("alice").orElseThrow().id()).isEmpty());
    }

    @Test
    void demoAccountPageIsLabelledAndNotIndexed() throws Exception {
        start(false);

        HttpResponse<String> demo = get("/demo/compte", null);
        assertEquals(200, demo.statusCode());
        assertTrue(demo.body().contains("joueurs fictifs"));
        assertTrue(demo.body().contains("noindex"));
        assertTrue(demo.body().contains("Ma progression"));
        assertTrue(demo.body().contains("Demo_Aube"));
        assertFalse(demo.body().contains("<form"), "aucun formulaire de compte dans l'aperçu");
    }

    @Test
    void confirmRejectsMalformedProofs() throws Exception {
        start(true);

        assertEquals(400, confirm(LINK_TOKEN, "pas du json").statusCode());
        assertEquals(400, confirm(LINK_TOKEN, "{\"code\":\"ABCDEFGH\",\"uuid\":\"x\",\"name\":\"A\"}").statusCode());
        assertEquals(400, confirm(LINK_TOKEN,
                "{\"code\":\"ABCDEFGH\",\"uuid\":\"" + ALICE_UUID + "\",\"name\":\"<script>\"}").statusCode());
    }

    @Test
    void logoutInvalidatesTheSessionServerSide() throws Exception {
        start(true);
        String session = registerAndLogin();
        String csrf = hidden(get("/compte", session).body(), "csrf");

        HttpResponse<String> logout = post("/compte/deconnexion", session, form("csrf", csrf), null);
        assertEquals(303, logout.statusCode());
        assertTrue(cookie(logout, Cookies.SESSION).contains("Max-Age=0"));

        assertFalse(get("/compte", session).body().contains("Connecté en tant que"));
    }
}
