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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/api/link/confirm"))
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
