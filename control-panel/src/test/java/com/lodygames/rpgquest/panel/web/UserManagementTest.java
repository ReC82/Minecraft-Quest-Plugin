package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.AuditEntry;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import com.lodygames.rpgquest.panel.users.InMemoryUserRepository;
import com.lodygames.rpgquest.panel.users.PanelUser;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bout-en-bout de la gestion des comptes PlugAdmin (issue #50) : contrôle backend 403, CSRF,
 * audit, protection du dernier OWNER, invalidation de session d'un compte désactivé, et navigation
 * filtrée par permission.
 */
class UserManagementTest {

    private static final String TESTER_PW = "tester-pass-1234";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private InMemoryAuditLog audit;
    private InMemoryUserRepository users;

    @BeforeEach
    void start() throws Exception {
        audit = new InMemoryAuditLog();
        users = new InMemoryUserRepository();
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:2/admin/v1"),
                audit, new BridgeClient(Duration.ofMillis(200), Duration.ofMillis(300)),
                new AgentStore(db), users);
        port = app.start();
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- Scénarios --------------------------------------------------------------------

    @Test
    void ownerOpensUsersPageAndSeesBootstrapOwner() throws Exception {
        Session s = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        HttpResponse<String> res = s.get("/users");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("<h1>Utilisateurs</h1>") || res.body().contains(">Utilisateurs<"));
        assertTrue(res.body().contains(TestConfig.OWNER_USERNAME));
        assertTrue(res.body().contains("Propriétaire"), "libellé de rôle affiché");
        assertTrue(res.body().contains("Créer un compte"));
        // rôle courant affiché dans la barre
        assertTrue(res.body().contains("userbox-r"));
    }

    @Test
    void nonOwnerIsForbiddenOnUsersPageAndOnBackendMutations() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String testerId = createUser(owner, "tess", TESTER_PW, Role.TESTER);

        Session tester = login("tess", TESTER_PW);

        assertEquals(403, tester.get("/users").statusCode(), "liste refusée");
        assertEquals(403, tester.get("/users/" + testerId).statusCode(), "détail refusé");

        // Le bouton est masqué côté UI, mais la requête directe doit répondre 403.
        HttpResponse<String> create = tester.post("/users/create",
                "_csrf=" + tester.csrf("/home") + "&username=evil&password=evil-password-1234&role=OWNER");
        assertEquals(403, create.statusCode());
        assertTrue(users.findByUsername("evil").isEmpty(), "aucun compte créé par un rôle non autorisé");

        HttpResponse<String> role = tester.post("/users/" + testerId + "/role",
                "_csrf=" + tester.csrf("/home") + "&role=OWNER");
        assertEquals(403, role.statusCode());
        assertEquals(Role.TESTER, users.findById(testerId).orElseThrow().role(), "auto-élévation impossible");
    }

    @Test
    void ownerCreatesUserAuditedAndPasswordNeverEchoedOrLogged() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        HttpResponse<String> res = owner.post("/users/create",
                "_csrf=" + owner.csrf("/users") + "&username=newbie&password=" + enc("s3cret-passphrase-xyz")
                        + "&role=CONTENT_EDITOR");
        assertEquals(303, res.statusCode());

        PanelUser created = users.findByUsername("newbie").orElseThrow();
        assertEquals(Role.CONTENT_EDITOR, created.role());
        assertTrue(created.active());
        assertFalse(created.passwordHash().contains("s3cret-passphrase-xyz"));

        AuditEntry entry = audit.recent(20).stream()
                .filter(e -> e.action().equals("user.create") && "OK".equals(e.result()))
                .findFirst().orElseThrow();
        assertEquals(TestConfig.OWNER_USERNAME, entry.actor());
        assertTrue(entry.target().contains("newbie"));
        assertTrue(entry.details().contains("CONTENT_EDITOR"));
        for (AuditEntry e : audit.recent(50)) {
            assertFalse(String.valueOf(e.details()).contains("s3cret-passphrase-xyz"), "mot de passe jamais audité");
            assertFalse(String.valueOf(e.target()).contains("s3cret-passphrase-xyz"));
        }
        assertFalse(owner.get("/users").body().contains("s3cret-passphrase-xyz"), "mot de passe jamais réaffiché");
    }

    @Test
    void weakPasswordIsRejectedAndNoUserCreated() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        HttpResponse<String> res = owner.post("/users/create",
                "_csrf=" + owner.csrf("/users") + "&username=shorty&password=abc&role=READ_ONLY");
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="));
        assertTrue(users.findByUsername("shorty").isEmpty());
    }

    @Test
    void roleChangeIsAuditedWithFromAndTo() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String id = createUser(owner, "mover", TESTER_PW, Role.READ_ONLY);

        HttpResponse<String> res = owner.post("/users/" + id + "/role",
                "_csrf=" + owner.csrf("/users/" + id) + "&role=ADMIN");
        assertEquals(303, res.statusCode());
        assertEquals(Role.ADMIN, users.findById(id).orElseThrow().role());

        AuditEntry entry = audit.recent(20).stream()
                .filter(e -> e.action().equals("user.role.change") && "OK".equals(e.result()))
                .findFirst().orElseThrow();
        assertTrue(entry.details().contains("from=READ_ONLY"));
        assertTrue(entry.details().contains("to=ADMIN"));
    }

    @Test
    void lastActiveOwnerIsProtectedFromDemotionAndDeactivation() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String ownerId = users.findByUsername(TestConfig.OWNER_USERNAME).orElseThrow().id();

        HttpResponse<String> demote = owner.post("/users/" + ownerId + "/role",
                "_csrf=" + owner.csrf("/users/" + ownerId) + "&role=TESTER");
        assertEquals(303, demote.statusCode());
        assertTrue(demote.headers().firstValue("Location").orElse("").contains("err="));
        assertEquals(Role.OWNER, users.findById(ownerId).orElseThrow().role());

        HttpResponse<String> disable = owner.post("/users/" + ownerId + "/active",
                "_csrf=" + owner.csrf("/users/" + ownerId) + "&active=false");
        assertEquals(303, disable.statusCode());
        assertTrue(users.findById(ownerId).orElseThrow().active(), "dernier OWNER reste actif");
    }

    @Test
    void withASecondOwnerTheFirstOwnerCanBeDemoted() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String ownerId = users.findByUsername(TestConfig.OWNER_USERNAME).orElseThrow().id();
        createUser(owner, "owner2", "second-owner-pass-1", Role.OWNER);

        HttpResponse<String> demote = owner.post("/users/" + ownerId + "/role",
                "_csrf=" + owner.csrf("/users/" + ownerId) + "&role=ADMIN");
        assertEquals(303, demote.statusCode());
        assertEquals(Role.ADMIN, users.findById(ownerId).orElseThrow().role());
    }

    @Test
    void csrfTokenIsRequiredForEveryUserMutation() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String id = createUser(owner, "victim", TESTER_PW, Role.READ_ONLY);

        assertEquals(403, owner.post("/users/create", "username=x&password=long-enough-1234&role=ADMIN").statusCode());
        assertEquals(403, owner.post("/users/" + id + "/role", "role=ADMIN").statusCode());
        assertEquals(403, owner.post("/users/" + id + "/active", "active=false").statusCode());
        assertEquals(403, owner.post("/users/create",
                "_csrf=not-the-real-token&username=x&password=long-enough-1234&role=ADMIN").statusCode());
    }

    @Test
    void deactivatedUserLosesTheSessionOnNextRequest() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String id = createUser(owner, "gone", TESTER_PW, Role.TESTER);

        Session victim = login("gone", TESTER_PW);
        assertEquals(200, victim.get("/home").statusCode());

        HttpResponse<String> off = owner.post("/users/" + id + "/active",
                "_csrf=" + owner.csrf("/users/" + id) + "&active=false");
        assertEquals(303, off.statusCode());

        HttpResponse<String> afterHome = victim.get("/home");
        assertEquals(303, afterHome.statusCode());
        assertEquals("/login", afterHome.headers().firstValue("Location").orElse(""));
    }

    @Test
    void roleChangeTakesEffectWithoutRelogin() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String id = createUser(owner, "promo", TESTER_PW, Role.READ_ONLY);

        Session user = login("promo", TESTER_PW);
        assertEquals(403, user.get("/quests/new").statusCode(), "READ_ONLY ne peut pas éditer");

        owner.post("/users/" + id + "/role", "_csrf=" + owner.csrf("/users/" + id) + "&role=CONTENT_EDITOR");

        assertEquals(200, user.get("/quests/new").statusCode(), "le nouveau rôle s'applique à la requête suivante");
    }

    @Test
    void navigationAndHomeReflectPermissions() throws Exception {
        Session owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String home = owner.get("/home").body();
        assertTrue(home.contains("href=\"/users\""), "OWNER voit la tuile / le lien Utilisateurs");

        createUser(owner, "peon", TESTER_PW, Role.TESTER);
        Session tester = login("peon", TESTER_PW);
        String testerHome = tester.get("/home").body();
        assertFalse(testerHome.contains("href=\"/users\""), "TESTER ne voit pas Utilisateurs");
        assertFalse(tester.get("/dashboard").body().contains("class=\"navlink\" href=\"/users\""),
                "pas de lien /users dans la sidebar du TESTER");
    }

    // ---- infra -----------------------------------------------------------------------

    private String createUser(Session owner, String username, String password, Role role) throws Exception {
        HttpResponse<String> res = owner.post("/users/create",
                "_csrf=" + owner.csrf("/users") + "&username=" + enc(username)
                        + "&password=" + enc(password) + "&role=" + role.name());
        assertEquals(303, res.statusCode(), "création de « " + username + " »");
        return users.findByUsername(username).orElseThrow().id();
    }

    private Session login(String username, String password) throws Exception {
        Session s = new Session();
        String token = s.csrf("/login");
        HttpResponse<String> res = s.post("/login", "username=" + enc(username)
                + "&password=" + enc(password) + "&_csrf=" + token);
        assertEquals(303, res.statusCode(), "connexion de « " + username + " »");
        assertEquals("/home", res.headers().firstValue("Location").orElse(""));
        return s;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** Un client HTTP avec son propre bocal à cookies (une session navigateur). */
    private final class Session {
        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build();
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
