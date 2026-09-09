package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Régression #103 — 502 Bad Gateway après connexion. Une seule ligne {@code agent_action} avec un
 * {@code created_at} sans décalage horaire ({@code 2026-09-09T12:51:33}, sans « Z ») — écrite hors
 * de PlugAdmin pendant la validation live de #101 — faisait lever {@code DateTimeParseException}
 * dans {@code AgentStore.readAction}. L'exception remontait par {@code NotificationCenter} (cloche
 * de la topbar) jusqu'au rendu de <strong>toute</strong> page authentifiée : HTTP 500 côté panel,
 * 502 côté nginx. {@code /login} (anonyme, sans cloche) restait fonctionnel — d'où l'insuffisance
 * du smoke test anonyme « → 303 ».
 *
 * <p>Le correctif rend {@code AgentStore} tolérant : analyse d'horodatage permissive (formes
 * héritées interprétées en UTC), et frontière de sécurité par ligne dans {@code recentActions}
 * (une ligne illisible est ignorée + WARNING, jamais propagée). Une donnée agent
 * invalide/incomplète ⇒ diagnostic, jamais crash du serveur web.</p>
 */
class PanelHardeningMalformedAgentDataTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private String dbPath;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    /** Le cas exact de #103 : ligne #101 « Stan libre », {@code created_at} sans « Z ». */
    @Test
    void authenticatedPagesStay200WhenAnAgentActionRowHasAMalformedTimestamp() throws Exception {
        start();
        insertRawAction("0cd8dd38-d8f7-4e6a-aba7-2972d189b813", "npc.citizens.list", "EXPIRED",
                "2026-09-09T12:51:33", // <-- pas de « Z » : la cause racine
                "claude-101-validation", null);
        loginOwner();

        for (String path : new String[] {"/home", "/dashboard", "/npcs", "/diagnostics", "/docs", "/actions"}) {
            HttpResponse<String> res = get(path);
            assertEquals(200, res.statusCode(), path + " doit rester 200 malgré la ligne mal formée");
        }
        // La cloche se rend quand même (la ligne illisible est simplement ignorée).
        assertTrue(get("/home").body().contains("id=\"notif-panel\""), "cloche de notifications rendue");
        // Et l'API JSON de polling ne casse pas non plus.
        assertEquals(200, get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).statusCode());
    }

    /** Autres formes dégradées tolérées : espace au lieu de « T », date seule, blanc. */
    @Test
    void variousLegacyTimestampShapesAreToleratedOnAuthenticatedPages() throws Exception {
        start();
        insertRawAction("11111111-1111-1111-1111-111111111111", "npc.list", "SUCCESS",
                "2026-09-09 08:14:51", "owner", "{\"details\":{\"npcs\":[],\"total\":0}}");
        insertRawAction("22222222-2222-2222-2222-222222222222", "player.list", "SUCCESS",
                "2026-09-09", "owner", null);
        insertRawAction("33333333-3333-3333-3333-333333333333", "quest.list", "FAILED",
                "   ", "owner", null); // blanc total
        loginOwner();

        for (String path : new String[] {"/home", "/dashboard", "/npcs", "/diagnostics", "/actions"}) {
            assertEquals(200, get(path).statusCode(), path);
        }
    }

    /**
     * Un {@code npc.citizens.list} SUCCESS au {@code result_json} <em>hérité</em> (Stan sans
     * {@code uuid}/{@code availableForBinding}/{@code spawned}) ne doit pas casser {@code /npcs}
     * ni {@code /diagnostics} — le code #101 tolère les champs absents.
     */
    @Test
    void legacyCitizensPayloadWithoutNewFieldsRendersFine() throws Exception {
        start();
        insertRawAction("44444444-4444-4444-4444-444444444444", "npc.citizens.list", "SUCCESS",
                "2026-09-09T09:00:00Z", "owner",
                "{\"details\":{\"citizens\":[{\"numericId\":7,\"name\":\"Stan\",\"linkedNpcId\":null}],"
                        + "\"citizensAvailable\":true,\"total\":1,\"available\":1,\"linked\":0}}");
        insertRawAction("55555555-5555-5555-5555-555555555555", "npc.list", "SUCCESS",
                "2026-09-09T09:00:05Z", "owner",
                "{\"details\":{\"npcs\":[],\"total\":0,\"withDefinition\":0,\"withoutDefinition\":0,"
                        + "\"bound\":0,\"withWarnings\":0,\"citizensAvailable\":true,\"definedIds\":[],\"canonicalIds\":[]}}");
        loginOwner();

        HttpResponse<String> npcs = get("/npcs?agent=" + TestConfig.AGENT_ID);
        assertEquals(200, npcs.statusCode());
        assertTrue(npcs.body().contains("<code class=\"tid npc-id\">Citizens #7</code>"),
                "Stan reste visible même sans uuid/spawned dans le payload");
        assertEquals(200, get("/diagnostics").statusCode());
    }

    // ---- infra ----------------------------------------------------------------------------

    private void insertRawAction(String id, String type, String status, String createdAt,
                                 String createdBy, String resultJson) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = c.createStatement()) {
            String rj = resultJson == null ? "NULL" : "'" + resultJson.replace("'", "''") + "'";
            String rs = "SUCCESS".equals(status) ? "'SUCCESS'" : "NULL";
            st.executeUpdate("INSERT INTO agent_action "
                    + "(id, agent_id, type, params_json, status, created_at, created_by, deliver_count, "
                    + " delivered_at, completed_at, result_status, result_value, result_message, result_json) VALUES ("
                    + "'" + id + "','" + TestConfig.AGENT_ID + "','" + type + "','{}','" + status + "','"
                    + createdAt + "','" + createdBy + "',1,NULL,NULL," + rs + ",NULL,NULL," + rj + ")");
        }
    }

    private void start() throws Exception {
        dbPath = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(dbPath, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(dbPath));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private void loginOwner() throws Exception {
        String token = csrf(get("/login").body());
        HttpResponse<String> res = post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
        assertEquals(303, res.statusCode(), "connexion owner");
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
