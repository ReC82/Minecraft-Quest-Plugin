package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
import com.lodygames.rpgquest.panel.audit.AuditEntry;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.dbtarget.DbConnectionTester;
import com.lodygames.rpgquest.panel.support.TestConfig;
import com.lodygames.rpgquest.panel.users.InMemoryUserRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Page {@code /database} (issue #42) : configuration de la base MariaDB cible avec mot de passe
 * chiffré et jamais réaffiché, test de connexion, backend actif réel, bascule absente.
 */
class DatabaseTargetPageTest {

    private static final String PASSWORD = "Pw-NE-DOIT-JAMAIS-SORTIR-42";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private String db;
    private InMemoryAuditLog audit;
    private InMemoryUserRepository users;
    private AgentStore agents;
    private final List<String> testedPasswords = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        audit = new InMemoryAuditLog();
        users = new InMemoryUserRepository();
        db = tmp.resolve("cp.db").toString();
        agents = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:2/admin/v1"),
                audit, new BridgeClient(Duration.ofMillis(200), Duration.ofMillis(300)), agents, users);
        app.useDbConnectionTester((settings, password) -> {
            testedPasswords.add(password);
            return password.equals(PASSWORD)
                    ? DbConnectionTester.Result.success("Connexion réussie (lecture seule) — serveur 10.11.6-MariaDB")
                    : DbConnectionTester.Result.failure("Échec de connexion : Access denied for user "
                            + DbConnectionTester.redact("'" + settings.username() + "'@'" + settings.host()
                            + "' (using password: " + password + ")", settings, password));
        });
        port = app.start();
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private String form(String action, String password) {
        return "&_action=" + action + "&host=db.example.test&port=3306&database=rpgquest&username=rpg_mig"
                + "&sslMode=verify-full&password=" + enc(password);
    }

    @Test
    void ownerSavesTargetAndPasswordIsNeverSentBackNorStoredInClear() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);

        HttpResponse<String> saved = owner.post("/database", "_csrf=" + owner.csrf("/database") + form("save", PASSWORD));

        assertEquals(200, saved.statusCode());
        assertTrue(saved.body().contains("Cible enregistrée."));
        assertTrue(saved.body().contains("mot de passe configuré"));
        assertTrue(saved.body().contains("value=\"db.example.test\""), "paramètres non secrets réaffichés");
        String page = owner.get("/database").body();
        assertFalse(page.contains(PASSWORD), "le mot de passe n'est jamais renvoyé au navigateur");
        assertTrue(page.contains("name=\"password\" value=\"\""));
        assertFalse(new String(Files.readAllBytes(Path.of(db)), StandardCharsets.ISO_8859_1).contains(PASSWORD),
                "jamais en clair dans control-panel.db");
        for (AuditEntry entry : audit.recent(50)) {
            assertFalse((entry.target() + entry.details()).contains(PASSWORD), "jamais dans l'audit");
            assertFalse(entry.target().contains("db.example.test"), "pas d'hôte dans l'audit");
        }
        assertTrue(audit.recent(50).stream().anyMatch(e -> e.action().equals("database.target.save")
                && e.target().contains("passwordReplaced=true")));
    }

    @Test
    void emptyPasswordKeepsTheStoredOne() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.post("/database", "_csrf=" + owner.csrf("/database") + form("save", PASSWORD));

        HttpResponse<String> tested = owner.post("/database", "_csrf=" + owner.csrf("/database") + form("test", ""));

        assertTrue(tested.body().contains("Connexion réussie"), tested.body());
        assertEquals(List.of(PASSWORD), testedPasswords, "le secret enregistré a servi au test");
        assertTrue(tested.body().contains("Connexion testée ≠ serveur migré"));
    }

    @Test
    void failedTestIsRedactedAndAudited() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);

        HttpResponse<String> tested = owner.post("/database", "_csrf=" + owner.csrf("/database")
                + form("test", "mauvais-mot-de-passe"));

        String body = tested.body();
        assertTrue(body.contains("Access denied"), body);
        assertFalse(body.contains("mauvais-mot-de-passe"));
        assertFalse(body.contains("&#39;rpg_mig&#39;@") || body.contains("'rpg_mig'@"), "compte expurgé");
        assertTrue(audit.recent(50).stream().anyMatch(e -> e.action().equals("database.target.test")
                && "FAILED".equals(e.result())));
    }

    @Test
    void clearingRequiresConfirmationAndIsSeparate() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.post("/database", "_csrf=" + owner.csrf("/database") + form("save", PASSWORD));

        HttpResponse<String> unconfirmed = owner.post("/database", "_csrf=" + owner.csrf("/database") + "&_action=clear");
        assertTrue(unconfirmed.body().contains("Effacement non confirmé"));
        assertTrue(unconfirmed.body().contains("mot de passe configuré"));

        HttpResponse<String> cleared = owner.post("/database", "_csrf=" + owner.csrf("/database")
                + "&_action=clear&confirm=on");
        assertTrue(cleared.body().contains("Mot de passe effacé."));
        assertTrue(cleared.body().contains("mot de passe non configuré"));
    }

    @Test
    void invalidHostIsRejectedWithoutBeingEchoed() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);

        HttpResponse<String> res = owner.post("/database", "_csrf=" + owner.csrf("/database")
                + "&_action=save&host=" + enc("evil/x?allowLoadLocalInfile=true") + "&port=3306&database=rpgquest"
                + "&username=rpg&sslMode=disable&password=");

        assertTrue(res.body().contains("Hôte invalide"));
        assertFalse(res.body().contains("allowLoadLocalInfile"));
    }

    @Test
    void badCsrfIsRefused() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);

        assertEquals(403, owner.post("/database", "_csrf=faux" + form("save", PASSWORD)).statusCode());
        assertFalse(owner.get("/database").body().contains("mot de passe configuré"));
    }

    @Test
    void adminWithoutPermissionIsForbidden() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        HttpResponse<String> created = owner.post("/users/create", "_csrf=" + owner.csrf("/users")
                + "&username=adm&password=" + enc("admin-password-1234") + "&role=" + Role.ADMIN.name());
        assertEquals(303, created.statusCode());

        Client admin = login("adm", "admin-password-1234");

        assertEquals(403, admin.get("/database").statusCode());
        assertEquals(403, admin.post("/database", "_csrf=" + admin.csrf("/home") + form("save", PASSWORD)).statusCode());
        assertFalse(admin.get("/home").body().contains("href=\"/database\""), "lien masqué");
    }

    @Test
    void activeBackendComesFromHeartbeatAndSwitchIsDisabled() throws Exception {
        agents.saveHeartbeat(new HeartbeatRecord(TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(),
                "1", "RPGQuest", "0.1.0", "ONLINE", 0, 20, 10, "{}",
                "{\"database\":{\"engine\":\"sqlite\",\"schema_version\":34}}"));
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);

        String page = owner.get("/database").body();

        assertTrue(page.contains("SQLite (data.db)"), page);
        assertTrue(page.contains("schéma attendu V34"));
        Matcher button = Pattern.compile("<button[^>]*disabled[^>]*>[^<]*(<[^>]+>)*[^<]*Basculer").matcher(page);
        assertTrue(button.find(), "bouton de bascule présent mais désactivé");
        assertFalse(page.contains("action=\"/database/switch\""), "aucune route de bascule");
        assertEquals(404, owner.post("/database/switch", "_csrf=" + owner.csrf("/database")).statusCode(),
                "aucune sous-route sous /database");
    }

    @Test
    void backendUnknownWithoutDatabaseFieldInHeartbeat() throws Exception {
        agents.saveHeartbeat(new HeartbeatRecord(TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(),
                "1", "RPGQuest", "0.1.0", "ONLINE", 0, 20, 10, "{}", "{}"));
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);

        assertTrue(owner.get("/database").body().contains("plugin antérieur à #42"));
    }

    // ---- harnais ---------------------------------------------------------------------------

    private Client login(String username, String password) throws Exception {
        Client c = new Client();
        String token = c.csrf("/login");
        HttpResponse<String> res = c.post("/login", "username=" + enc(username) + "&password=" + enc(password)
                + "&_csrf=" + token);
        assertEquals(303, res.statusCode(), "connexion de « " + username + " »");
        return c;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** Un navigateur : client HTTP et cookies propres. */
    private final class Client {
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        private final Map<String, String> jar = new LinkedHashMap<>();

        HttpResponse<String> get(String path) throws Exception {
            return send(HttpRequest.newBuilder(uri(path)).GET());
        }

        HttpResponse<String> post(String path, String form) throws Exception {
            return send(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)));
        }

        String csrf(String path) throws Exception {
            Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(get(path).body());
            assertTrue(m.find(), "jeton _csrf absent de " + path);
            return m.group(1);
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

        private URI uri(String p) {
            return URI.create("http://127.0.0.1:" + port + p);
        }
    }
}
