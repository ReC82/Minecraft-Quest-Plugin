package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #788 : « État d'un joueur » sur /quests et /stories — un pseudo partiel propose les joueurs
 * connus de l'annuaire {@code player.catalog} (#96), connectés ou hors ligne, et chaque choix
 * sélectionne par UUID. Lecture seule : aucune action n'est envoyée au serveur par la recherche.
 */
class QuestPlayerSearchTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String UUID_LODY = "b56885c8-1111-4111-8111-111111111111";
    private static final String UUID_RON = "44444444-4444-4444-8444-444444444444";

    private static final String CATALOG = "{\"players\":["
            + "{\"uuid\":\"" + UUID_LODY + "\",\"name\":\"LoDyMcFly\",\"online\":true,\"lastSeen\":1600000000000},"
            + "{\"uuid\":\"" + UUID_RON + "\",\"name\":\"Rondoudou9000\",\"online\":false,\"lastSeen\":1599000000000}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void aPartialNameProposesTheOfflinePlayerAndTheChoiceSelectsByUuid() throws Exception {
        start();
        seedCatalog();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=Ron").body();

        assertTrue(page.contains("class=\"player-suggest\""), "suggestions affichées");
        assertTrue(page.contains(">Rondoudou9000</a>"), "le joueur hors ligne est proposé");
        assertTrue(page.contains(">Hors ligne</span>"), "état de connexion lisible");
        assertTrue(page.contains("player=" + UUID_RON + "#etat-joueur"), "le choix sélectionne par UUID");
        assertFalse(page.contains(">LoDyMcFly</a>"), "un joueur sans rapport n'est pas proposé");
        assertFalse(page.contains("Rafraîchir l'état de Ron"), "aucune lecture pour une saisie ambiguë");
        assertFalse(page.contains("Actions admin sur une autre quête"), "aucune action tant que rien n'est choisi");

        String chosen = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=" + UUID_RON).body();
        assertTrue(chosen.contains("Joueur sélectionné : <strong>Rondoudou9000</strong>"));
        assertTrue(chosen.contains("Rafraîchir l&#39;état de Rondoudou9000")
                || chosen.contains("Rafraîchir l'état de Rondoudou9000"), "libellé par pseudo, pas par UUID");
        assertTrue(chosen.contains("name=\"player\" value=\"" + UUID_RON + "\""), "l'agent reçoit l'UUID");
        assertTrue(chosen.contains("Actions admin sur une autre quête"));
    }

    @Test
    void theFieldOffersKnownPlayersNativelyAndAFullNameStillWorks() throws Exception {
        start();
        seedCatalog();
        String empty = get("/quests?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(empty.contains("list=\"dl-known-players\""));
        assertTrue(empty.contains("<option value=\"Rondoudou9000\" label=\"Hors ligne\">"));
        assertTrue(empty.contains("<option value=\"LoDyMcFly\" label=\"En ligne\">"));

        String full = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=rondoudou9000").body();
        assertFalse(full.contains("class=\"player-suggest\""), "un pseudo complet ne redemande pas de choix");
        assertTrue(full.contains("Joueur sélectionné : <strong>Rondoudou9000</strong>"));
    }

    @Test
    void noMatchSaysSoButKeepsTheTypedNameUsable() throws Exception {
        start();
        seedCatalog();
        String page = get("/quests?agent=" + TestConfig.AGENT_ID + "&player=Zorglub").body();
        assertTrue(page.contains("Aucun joueur connu ne correspond à « Zorglub »"));
        assertTrue(page.contains("name=\"player\" value=\"Zorglub\""), "le serveur peut quand même être interrogé");
    }

    @Test
    void storiesShareTheSameSearch() throws Exception {
        start();
        seedCatalog();
        String page = get("/stories?agent=" + TestConfig.AGENT_ID + "&player=doudou").body();
        assertTrue(page.contains(">Rondoudou9000</a>"));
        assertTrue(page.contains("/stories?agent="), "le choix reste sur /stories");
        assertFalse(page.contains("Actions admin sur une story"));
    }

    // ---- infra ----------------------------------------------------------------------------

    private void seedCatalog() {
        String id = store.createAction(TestConfig.AGENT_ID, "player.catalog", Map.of(), "owner");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + CATALOG + "}", Instant.now());
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                store = new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("jeton _csrf absent");
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
