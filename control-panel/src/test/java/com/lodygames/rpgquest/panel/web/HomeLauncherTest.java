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

/**
 * Home = launcher à tuiles (#92, lot Bootstrap) : page d'arrivée après connexion, Dashboard
 * relégué à une tuile / sa page dédiée, sidebar « Accueil » en tête.
 */
class HomeLauncherTest {

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
    void loginRedirectsToHomeNotDashboard() throws Exception {
        start();
        String token = csrf(get("/login").body());
        HttpResponse<String> login = post("/login", "username=" + TestConfig.OWNER_USERNAME
                + "&password=" + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + token);
        assertEquals(303, login.statusCode());
        assertEquals("/home", login.headers().firstValue("Location").orElse(""));
        assertEquals("/home", get("/").headers().firstValue("Location").orElse(""), "racine -> Home");
    }

    @Test
    void anonymousHomeRedirectsToLogin() throws Exception {
        start();
        HttpResponse<String> res = get("/home");
        assertEquals(303, res.statusCode());
        assertEquals("/login", res.headers().firstValue("Location").orElse(""));
    }

    @Test
    void homeShowsTileLauncherWithAllMainSections() throws Exception {
        start();
        loginOwner();
        String home = get("/home").body();
        assertEquals(200, get("/home").statusCode());

        // en-tête launcher (pas un dashboard bis)
        assertTrue(home.contains(">PlugAdmin<"));
        assertTrue(home.contains("Administration de LodyQuests"));
        assertTrue(home.contains("home-tile"), "tuiles présentes");
        assertTrue(home.contains("row-cols-lg-3") || home.contains("row-cols-xl-4"), "grille Bootstrap responsive");

        // tuiles principales + liens
        for (String[] t : new String[][] {
                {"Dashboard", "/dashboard"}, {"Joueurs", "/players"}, {"PNJ", "/npcs"},
                {"Quêtes", "/quests"}, {"Stories", "/stories"}, {"Dialogues", "/dialogues"},
                {"Documentation", "/docs"}, {"Agents", "/agents"}}) {
            assertTrue(home.contains(">" + t[0] + "<"), "tuile " + t[0]);
            assertTrue(home.contains("class=\"home-tile\" href=\"" + t[1] + "\""), "lien tuile " + t[1]);
        }

        // groupes (titres de section)
        assertTrue(home.contains("Gestion du jeu"));
        assertTrue(home.contains("Vue d&#39;ensemble") || home.contains("Vue d'ensemble"));

        // Diagnostics = tuile « à venir » non cliquable
        assertTrue(home.contains(">Diagnostics<"));
        assertTrue(home.contains("home-tile is-disabled"));
        assertTrue(home.contains("À venir"));
        assertFalse(home.contains("class=\"home-tile\" href=\"/diagnostics\""), "Diagnostics pas un lien");

        // recherche globale future : présente mais désactivée
        assertTrue(home.contains("home-search"));
        assertTrue(home.matches("(?s).*<input type=\"search\"[^>]*disabled.*"));
    }

    @Test
    void sidebarHasAccueilBeforeDashboard() throws Exception {
        start();
        loginOwner();
        String home = get("/home").body();
        int accueil = home.indexOf(">Accueil<");
        int dash = home.indexOf(">Dashboard<");
        assertTrue(accueil > 0 && dash > 0, "Accueil et Dashboard dans la nav");
        assertTrue(accueil < dash, "Accueil avant Dashboard dans la sidebar");
        assertTrue(home.contains("class=\"navlink active\" href=\"/home\""), "Accueil actif sur la Home");
    }

    @Test
    void dashboardStillReachableOnItsOwnPage() throws Exception {
        start();
        loginOwner();
        HttpResponse<String> dash = get("/dashboard");
        assertEquals(200, dash.statusCode());
        assertTrue(dash.body().contains("Dashboard"));
        assertTrue(dash.body().contains("<h1>Dashboard</h1>"));
    }

    // ---- infra ----------------------------------------------------------------------

    private void loginOwner() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:2/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
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
