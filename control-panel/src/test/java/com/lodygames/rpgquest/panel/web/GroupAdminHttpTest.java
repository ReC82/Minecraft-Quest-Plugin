package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.AuditEntry;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gestion des groupes <strong>de bout en bout en HTTP</strong> (issue #199) : vrai serveur, vraies
 * sessions, vrai stockage SQLite.
 *
 * <p>Deux propriétés ne peuvent être vérifiées qu'ici, et ce sont les plus importantes du ticket :
 * <strong>une révocation prend effet sur une session déjà ouverte</strong>, sans reconnexion ; et
 * <strong>chaque route revérifie les droits côté backend</strong>, y compris atteinte directement
 * sans passer par un lien de l'interface.</p>
 */
class GroupAdminHttpTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private InMemoryAuditLog audit;
    /** Un bocal à cookies par identité : c'est ce qui permet de tenir DEUX sessions à la fois. */
    private final Map<String, String> ownerJar = new LinkedHashMap<>();
    private final Map<String, String> otherJar = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        audit = new InMemoryAuditLog();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:2/admin/v1"),
                audit, new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        loginOwner();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- Migration -----------------------------------------------------------------------------

    @Test
    void aFreshInstallHasNoGroupAndNoRoleIsWidened() throws Exception {
        String body = get("/groups", ownerJar).body();

        assertTrue(body.contains("Aucun groupe"), body);
        // Le propriétaire conserve son accès : c'est l'exigence « ne pas perdre l'accès propriétaire ».
        assertEquals(200, get("/users", ownerJar).statusCode());
    }

    // ---- Cycle de vie d'un groupe --------------------------------------------------------------

    @Test
    void theOwnerCreatesEditsAndDeletesAGroup() throws Exception {
        String id = createGroup("Exploitation", "perm_OPS_LOGS=on");

        String detail = get("/groups/" + id, ownerJar).body();
        assertTrue(detail.contains("Exploitation"), detail);
        assertTrue(detail.contains("OPS_LOGS"), "la permission accordée doit être visible");

        // Renommer
        assertEquals(303, postForm("/groups/rename", ownerJar,
                "group=" + id + "&name=" + enc("Exploitation serveur") + "&description=" + enc("console")).statusCode());
        assertTrue(get("/groups/" + id, ownerJar).body().contains("Exploitation serveur"));

        // Supprimer sans retaper le nom : refusé, et le groupe est toujours là.
        assertEquals(303, postForm("/groups/delete", ownerJar, "group=" + id + "&confirm=mauvais").statusCode());
        assertEquals(200, get("/groups/" + id, ownerJar).statusCode());

        assertEquals(303, postForm("/groups/delete", ownerJar,
                "group=" + id + "&confirm=" + enc("Exploitation serveur")).statusCode());
        assertEquals(404, get("/groups/" + id, ownerJar).statusCode());
    }

    @Test
    void aDuplicateGroupNameIsRefused() throws Exception {
        createGroup("Testeurs", "");

        HttpResponse<String> res = postForm("/groups/create", ownerJar, "name=" + enc("testeurs"));

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="),
                "le refus doit être signalé, pas silencieux");
        assertEquals(1, countGroups(), "aucun doublon créé");
    }

    // ---- Révocation sur une session ACTIVE : le cœur du ticket ---------------------------------

    @Test
    void grantingAndRevokingAGroupTakesEffectOnAnAlreadyOpenSession() throws Exception {
        String userId = createUser("groupietest", "READ_ONLY");
        loginOther("groupietest");

        // READ_ONLY n'a pas USER_MANAGE : la page est refusée.
        assertEquals(403, get("/users", otherJar).statusCode(),
                "avant tout groupe, le rôle seul décide");

        String groupId = createGroup("Gestion comptes", "perm_USER_MANAGE=on");
        assignGroups(userId, groupId);

        // MÊME session, aucune reconnexion : le droit arrive par le groupe.
        assertEquals(200, get("/users", otherJar).statusCode(),
                "un droit accordé par groupe doit agir immédiatement sur la session ouverte");

        // Retrait du groupe : la même session perd le droit, toujours sans reconnexion.
        assignGroups(userId);
        assertEquals(403, get("/users", otherJar).statusCode(),
                "une révocation doit agir immédiatement, sans attendre une reconnexion");
    }

    @Test
    void removingAPermissionFromAGroupRevokesItOnOpenSessions() throws Exception {
        String userId = createUser("groupietest", "READ_ONLY");
        String groupId = createGroup("Gestion comptes", "perm_USER_MANAGE=on");
        assignGroups(userId, groupId);
        loginOther("groupietest");
        assertEquals(200, get("/users", otherJar).statusCode());

        // On vide le groupe (aucune case cochée) : le droit disparaît pour tous ses membres.
        assertEquals(303, postForm("/groups/permissions", ownerJar, "group=" + groupId).statusCode());

        assertEquals(403, get("/users", otherJar).statusCode());
    }

    @Test
    void deletingAGroupRevokesItOnOpenSessions() throws Exception {
        String userId = createUser("groupietest", "READ_ONLY");
        String groupId = createGroup("Gestion comptes", "perm_USER_MANAGE=on");
        assignGroups(userId, groupId);
        loginOther("groupietest");
        assertEquals(200, get("/users", otherJar).statusCode());

        assertEquals(303, postForm("/groups/delete", ownerJar,
                "group=" + groupId + "&confirm=" + enc("Gestion comptes")).statusCode());

        assertEquals(403, get("/users", otherJar).statusCode());
    }

    // ---- Routes atteintes directement ----------------------------------------------------------

    @Test
    void everyGroupRouteIsCheckedOnTheBackendEvenWithoutAnyLink() throws Exception {
        createUser("groupietest", "READ_ONLY");
        loginOther("groupietest");

        // Lecture : refusée.
        assertEquals(403, get("/groups", otherJar).statusCode());
        // Écritures : refusées AVANT toute validation de formulaire, donc aucun effet de bord.
        for (String route : List.of("/groups/create", "/groups/rename", "/groups/permissions",
                "/groups/delete", "/users/groups")) {
            HttpResponse<String> res = postForm(route, otherJar, "name=X&group=Y&user=Z");
            assertEquals(403, res.statusCode(), route + " doit être refusée côté backend");
        }
        assertEquals(0, countGroups(), "aucun groupe créé par une route atteinte directement");
    }

    @Test
    void anUnauthenticatedCallerIsRedirectedToLoginAndChangesNothing() throws Exception {
        Map<String, String> noJar = new LinkedHashMap<>();

        assertEquals(303, get("/groups", noJar).statusCode());
        // Sans session, il n'y a aucune page d'où lire un jeton CSRF : on poste donc directement.
        // La redirection vers la connexion doit arriver AVANT toute considération de jeton.
        assertEquals(303, post("/groups/create", noJar, "name=Pirate&_csrf=peu-importe").statusCode());
        assertEquals(0, countGroups());
    }

    @Test
    void aMissingCsrfTokenIsRefused() throws Exception {
        HttpResponse<String> res = post("/groups/create", ownerJar, "name=" + enc("SansJeton"));

        assertEquals(403, res.statusCode());
        assertEquals(0, countGroups());
    }

    // ---- Droits effectifs et provenance --------------------------------------------------------

    @Test
    void theUserPageShowsEffectiveRightsAndWhereTheyComeFrom() throws Exception {
        String userId = createUser("groupietest", "READ_ONLY");
        String groupId = createGroup("Console", "perm_OPS_LOGS=on");
        assignGroups(userId, groupId);

        String body = get("/users/" + userId, ownerJar).body();

        assertTrue(body.contains("Droits effectifs"), body);
        assertTrue(body.contains("OPS_LOGS"), "le droit ajouté par le groupe doit apparaître");
        assertTrue(body.contains("groupe « Console »"), "la provenance doit être nommée");
        assertTrue(body.contains("rôle READ_ONLY"), "le rôle de base doit apparaître comme provenance");
    }

    @Test
    void aUserCanBelongToSeveralGroupsAtOnce() throws Exception {
        String userId = createUser("groupietest", "READ_ONLY");
        String a = createGroup("Console", "perm_OPS_LOGS=on");
        String b = createGroup("Rédaction", "perm_QUEST_CONTENT_WRITE=on");

        assignGroups(userId, a, b);

        String body = get("/users/" + userId, ownerJar).body();
        assertTrue(body.contains("groupe « Console »"), body);
        assertTrue(body.contains("groupe « Rédaction »"), body);
    }

    // ---- Protection du dernier propriétaire ----------------------------------------------------

    @Test
    void theLastOwnerKeepsItsAccessWhateverHappensToGroups() throws Exception {
        String groupId = createGroup("Minimal", "perm_DASHBOARD_VIEW=on");
        String ownerId = ownerId();
        assignGroups(ownerId, groupId);

        // Un groupe n'enlève rien : le propriétaire garde la gestion des comptes.
        assertEquals(200, get("/users", ownerJar).statusCode());

        assignGroups(ownerId);
        assertEquals(200, get("/users", ownerJar).statusCode());

        postForm("/groups/delete", ownerJar, "group=" + groupId + "&confirm=Minimal");
        assertEquals(200, get("/users", ownerJar).statusCode(),
                "aucun geste sur les groupes ne peut verrouiller le dernier propriétaire");
    }

    @Test
    void theLastOwnerCannotBeDemotedOrDeactivated() throws Exception {
        // Protection héritée de l'issue #50, revérifiée ici parce que #199 ne doit pas l'affaiblir.
        String ownerId = ownerId();
        String detail = get("/users/" + ownerId, ownerJar).body();
        assertTrue(detail.contains("dernier OWNER actif"), detail);

        postForm("/users/" + ownerId + "/role", ownerJar, "role=READ_ONLY");
        assertEquals(200, get("/users", ownerJar).statusCode(), "le propriétaire garde son accès");
    }

    // ---- Audit ---------------------------------------------------------------------------------

    @Test
    void everyGroupMutationIsAuditedIncludingRefusals() throws Exception {
        String groupId = createGroup("Console", "perm_OPS_LOGS=on");
        String userId = createUser("groupietest", "READ_ONLY");
        assignGroups(userId, groupId);
        postForm("/groups/create", ownerJar, "name=x");

        List<AuditEntry> entries = audit.recent(100);
        assertTrue(entries.stream().anyMatch(e -> "group.create".equals(e.action()) && "OK".equals(e.result())));
        assertTrue(entries.stream().anyMatch(e -> "user.groups".equals(e.action()) && "OK".equals(e.result())));
        // Un refus doit être relisible : c'est précisément ce qu'on cherche après coup.
        assertTrue(entries.stream().anyMatch(e -> "group.create".equals(e.action())
                && "DENIED".equals(e.result())), () -> entries.toString());
    }

    // ---- infra ---------------------------------------------------------------------------------

    private void loginOwner() throws Exception {
        String token = csrf(get("/login", ownerJar).body());
        HttpResponse<String> res = post("/login", ownerJar, "username=" + TestConfig.OWNER_USERNAME
                + "&password=" + enc(TestConfig.OWNER_PASSWORD) + "&_csrf=" + token);
        assertEquals(303, res.statusCode(), "login propriétaire");
    }

    private void loginOther(String username) throws Exception {
        String token = csrf(get("/login", otherJar).body());
        HttpResponse<String> res = post("/login", otherJar, "username=" + username
                + "&password=" + enc(OTHER_PASSWORD) + "&_csrf=" + token);
        assertEquals(303, res.statusCode(), "login du compte secondaire");
    }

    private static final String OTHER_PASSWORD = "un mot de passe assez long";

    private String createUser(String username, String role) throws Exception {
        HttpResponse<String> res = postForm("/users/create", ownerJar, "username=" + username
                + "&password=" + enc(OTHER_PASSWORD) + "&role=" + role);
        assertEquals(303, res.statusCode(), "création de compte : " + res.body());
        String list = get("/users", ownerJar).body();
        Matcher m = Pattern.compile("href=\"/users/([0-9a-fA-F-]{36})\"[^>]*>\\s*<span class=\"usr-name\">"
                + Pattern.quote(username)).matcher(list);
        assertTrue(m.find(), "compte « " + username + " » introuvable dans la liste");
        return m.group(1);
    }

    private String ownerId() throws Exception {
        String list = get("/users", ownerJar).body();
        Matcher m = Pattern.compile("href=\"/users/([0-9a-fA-F-]{36})\"[^>]*>\\s*<span class=\"usr-name\">"
                + Pattern.quote(TestConfig.OWNER_USERNAME)).matcher(list);
        assertTrue(m.find(), "compte propriétaire introuvable");
        return m.group(1);
    }

    private String createGroup(String name, String permissionForm) throws Exception {
        String form = "name=" + enc(name) + (permissionForm.isEmpty() ? "" : "&" + permissionForm);
        HttpResponse<String> res = postForm("/groups/create", ownerJar, form);
        assertEquals(303, res.statusCode(), "création de groupe");
        String location = res.headers().firstValue("Location").orElse("");
        assertFalse(location.contains("err="), "création refusée : " + location);
        Matcher m = Pattern.compile("/groups/([0-9a-fA-F-]{36})").matcher(location);
        assertTrue(m.find(), "identifiant de groupe absent de la redirection : " + location);
        return m.group(1);
    }

    private void assignGroups(String userId, String... groupIds) throws Exception {
        StringBuilder form = new StringBuilder("user=").append(userId);
        for (String groupId : groupIds) {
            form.append("&group_").append(groupId).append("=on");
        }
        HttpResponse<String> res = postForm("/users/groups", ownerJar, form.toString());
        assertEquals(303, res.statusCode());
        assertFalse(res.headers().firstValue("Location").orElse("").contains("err="),
                "affectation refusée : " + res.headers().firstValue("Location").orElse(""));
    }

    private int countGroups() throws Exception {
        String body = get("/groups", ownerJar).body();
        Matcher m = Pattern.compile("Groupes <span class=\"muted\">\\((\\d+)\\)").matcher(body);
        assertTrue(m.find(), "compteur de groupes absent");
        return Integer.parseInt(m.group(1));
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), "jeton _csrf absent");
        return m.group(1);
    }

    /** POST avec le jeton CSRF de la session, relu sur une page de la même session. */
    private HttpResponse<String> postForm(String path, Map<String, String> jar, String form) throws Exception {
        String token = csrf(get("/home", jar).body());
        return post(path, jar, form + "&_csrf=" + token);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path, Map<String, String> jar) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET(), jar);
    }

    private HttpResponse<String> post(String path, Map<String, String> jar, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), jar);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, Map<String, String> jar) throws Exception {
        if (!jar.isEmpty()) {
            StringBuilder cookie = new StringBuilder();
            jar.forEach((k, v) -> cookie.append(cookie.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            builder.header("Cookie", cookie.toString());
        }
        HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        for (String header : res.headers().allValues("Set-Cookie")) {
            String pair = header.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0) {
                jar.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return res;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
