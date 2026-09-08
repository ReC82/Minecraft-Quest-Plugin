package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.json.Json;
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
 * Rendu du catalogue de la page {@code /stories} à partir du résultat de l'action {@code story.list}.
 *
 * <p>Régression : une action {@code story.list} plus récente encore en cours (PENDING) ne doit pas
 * faire disparaître le catalogue déjà chargé par une action {@code story.list} réussie
 * ({@code AgentPages.latestDetails} → {@code AgentStore.latestSuccessfulActionOfType}).</p>
 */
class StoriesCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    /** Deux stories, dont un titre avec MiniMessage/crochets, comme le vrai {@code story.list}. */
    private static final String STORY_DETAILS =
            "{\"stories\":["
            + "{\"id\":\"main_story\",\"title\":\"Histoire principale\",\"stepQuestIds\":[\"rpgquest:premiers_pas\",\"rpgquest:first_steps\"]},"
            + "{\"id\":\"story_test\",\"title\":\"<red>[TEST]</red> Histoire de test\",\"stepQuestIds\":[\"rpgquest:test_break_block\"]}"
            + "]}";

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
        loginOk();
    }

    // ---- Tests ---------------------------------------------------------------------------

    @Test
    void catalogRendersTheStoriesReturnedByStoryList() throws Exception {
        start();
        runStoryListWithSuccess(STORY_DETAILS);

        String page = get("/stories?agent=" + TestConfig.AGENT_ID).body();
        String catalogue = section(page);
        assertFalse(catalogue.contains("Aucun catalogue chargé"), "le catalogue ne doit plus être vide");
        assertTrue(catalogue.contains("main_story"), "id technique story #1 conservé");
        assertTrue(catalogue.contains("story_test"), "id technique story #2 conservé");
        assertTrue(catalogue.contains("Histoire principale"));
        assertTrue(catalogue.contains("rpgquest:premiers_pas"), "les quêtes de l'étape sont listées");
        // #74 : aucune balise MiniMessage brute, mais le libellé humain reste lisible.
        assertFalse(catalogue.contains("&lt;red&gt;") || catalogue.contains("<red>"),
                "aucune balise MiniMessage brute dans le catalogue");
        assertTrue(catalogue.contains("[TEST]") && catalogue.contains("Histoire de test"), "libellé humain nettoyé");
    }

    /** Le fragment de page depuis « Catalogue » jusqu'à la section suivante. */
    private static String section(String page) {
        int a = page.indexOf("<h2>Catalogue</h2>");
        int b = page.indexOf("<h2>", a + 4);
        return a < 0 ? page : page.substring(a, b < 0 ? page.length() : b);
    }

    @Test
    void newerPendingStoryListDoesNotBlankAnAlreadyLoadedCatalog() throws Exception {
        start();
        runStoryListWithSuccess(STORY_DETAILS);
        assertTrue(get("/stories?agent=" + TestConfig.AGENT_ID).body().contains("main_story"));

        // L'opérateur reclique « Rafraîchir le catalogue » : une nouvelle action story.list est
        // créée et reste PENDING tant que l'agent n'a pas répondu.
        Thread.sleep(1100); // created_at tronqué à la seconde
        String token = csrf(get("/stories?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action",
                "_csrf=" + token + "&type=story.list&agent=" + TestConfig.AGENT_ID + "&return=/stories");
        assertEquals(303, res.statusCode());
        assertTrue(pendingCount() >= 1, "une action story.list en attente");

        String page = get("/stories?agent=" + TestConfig.AGENT_ID).body();
        assertFalse(page.contains("Aucun catalogue chargé"),
                "une action story.list PENDING plus récente ne doit pas vider le catalogue déjà chargé");
        assertTrue(page.contains("main_story"));
        assertTrue(page.contains("story_test"));
    }

    @Test
    void catalogIsEmptyWhenStoryListNeverSucceeded() throws Exception {
        start();
        // Aucune action story.list encore réussie (juste une PENDING créée).
        String token = csrf(get("/stories?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=story.list&agent=" + TestConfig.AGENT_ID + "&return=/stories");

        String page = get("/stories?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Aucun catalogue chargé"));
        assertFalse(page.contains("main_story"));
    }

    // ---- Helpers ------------------------------------------------------------------------

    /** Crée une action story.list, la fait relever par l'agent, et renvoie un résultat SUCCESS. */
    private void runStoryListWithSuccess(String details) throws Exception {
        String token = csrf(get("/stories?agent=" + TestConfig.AGENT_ID).body());
        assertEquals(303, post("/agents/action",
                "_csrf=" + token + "&type=story.list&agent=" + TestConfig.AGENT_ID + "&return=/stories").statusCode());

        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, poll.statusCode());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        assertTrue(m.find(), "action non relevée par l'agent");
        String actionId = m.group(1);

        String result = "{\"action_id\":\"" + actionId + "\",\"status\":\"SUCCESS\",\"value\":\"2\","
                + "\"message\":\"2 story(s) chargée(s).\",\"details\":" + details + "}";
        HttpResponse<String> rr = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + actionId + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rr.statusCode());
    }

    private int pendingCount() throws Exception {
        Map<String, Object> body = Json.parseObject(get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).body());
        return ((Number) body.get("pending")).intValue();
    }

    private void loginOk() throws Exception {
        String token = csrf(get("/login").body());
        assertEquals(303, post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token).statusCode());
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), "jeton _csrf absent");
        return m.group(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        if (!jar.isEmpty()) {
            builder.header("Cookie", jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + "; " + b).orElse(""));
        }
        HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        for (String setCookie : res.headers().allValues("Set-Cookie")) {
            String[] first = setCookie.split(";", 2);
            int eq = first[0].indexOf('=');
            String name = first[0].substring(0, eq).trim();
            String value = first[0].substring(eq + 1).trim();
            if (setCookie.toLowerCase().contains("max-age=0") || value.isEmpty()) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
        return res;
    }
}
