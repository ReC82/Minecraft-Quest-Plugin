package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
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
 * Issue #213 — la page {@code /buildings/sites} sur un vrai serveur HTTP : rendu, filtre,
 * permissions, CSRF, et ce qui part réellement vers l'agent.
 */
class BuildingSitesPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    /** Relevé réaliste : deux emplacements, deux mondes, dont un non chargé. */
    private static final String SITES = "{"
            + "\"total\":2,\"worlds\":[\"world_hub\",\"monde_archive\"],"
            + "\"sites\":["
            + "{\"id\":\"buildsite_0001\",\"name\":\"Taverne du village\","
            + "\"description\":\"Deux étages.\",\"world\":\"world_hub\","
            + "\"x\":712,\"y\":67,\"z\":-702,\"facing\":\"WEST\",\"status\":\"EMPTY\","
            + "\"createdBy\":\"Lody\",\"createdAt\":\"2026-10-08T18:00:00Z\",\"worldLoaded\":true},"
            + "{\"id\":\"buildsite_0002\",\"name\":\"Nouvel emplacement\",\"description\":\"\","
            + "\"world\":\"monde_archive\",\"x\":10,\"y\":64,\"z\":10,\"facing\":\"NORTH\","
            + "\"status\":\"EMPTY\",\"createdBy\":\"\",\"createdAt\":\"2026-10-08T18:05:00Z\","
            + "\"worldLoaded\":false}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- Rendu ---------------------------------------------------------------------------------

    /** Sans relevé, on ne prétend pas « aucun emplacement » : on dit de rafraîchir. */
    @Test
    void withoutASurveyThePageAsksToRefreshRatherThanClaimingItIsEmpty() throws Exception {
        start();

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Aucun relev"), page.substring(0, Math.min(2000, page.length())));
        assertFalse(page.contains("Aucun emplacement de construction pour le moment"));
        // Et la marche à suivre est là même quand la liste est vide.
        assertTrue(page.contains("/rpgadmin buildsite tool"));
    }

    @Test
    void theListShowsIdNameWorldPositionFacingStateAndDate() throws Exception {
        start();
        seed();

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("buildsite_0001"));
        assertTrue(page.contains("Taverne du village"));
        assertTrue(page.contains("world_hub"));
        assertTrue(page.contains("712 / 67 / -702"), "la position telle qu'on la lit en jeu");
        assertTrue(page.contains("ouest"), "orientation en français");
        assertTrue(page.contains("vide"), "état");
        assertTrue(page.contains("08/10/2026 18:00 UTC"), "date de création");
        assertTrue(page.contains("Lody"), "auteur");
        assertTrue(page.contains("Deux étages."), "description");
    }

    /** Un monde déchargé est signalé, pas masqué — et l'emplacement reste compté. */
    @Test
    void anUnloadedWorldIsFlaggedAndTheSiteStillListed() throws Exception {
        start();
        seed();

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("buildsite_0002"));
        assertTrue(page.contains("monde non chargé") || page.contains("non chargé"));
        assertTrue(page.contains("2 emplacement(s)"));
    }

    @Test
    void anUnknownAuthorIsShownAsUnknownNotInvented() throws Exception {
        start();
        seed();

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("inconnu"), "l'auteur absent du second emplacement");
    }

    @Test
    void theWorldFilterNarrowsTheListAndIsShareable() throws Exception {
        start();
        seed();

        String all = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(all.contains("buildsite_0001") && all.contains("buildsite_0002"));

        String filtered = get("/buildings/sites?agent=" + TestConfig.AGENT_ID
                + "&world=world_hub").body();

        assertTrue(filtered.contains("buildsite_0001"));
        assertFalse(filtered.contains("buildsite_0002"),
                "l'emplacement de l'autre monde doit disparaître");
        assertTrue(filtered.contains("Tous les mondes"), "et on peut revenir");
    }

    @Test
    void theSearchToolbarIsPresentForTheList() throws Exception {
        start();
        seed();

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("data-filter-input=\"buildsites\""));
        assertTrue(page.contains("data-filter-item=\"buildsites\""));
    }

    /**
     * L'absence de bouton « Créer » est un choix : la page doit l'expliquer, sinon elle se lit comme
     * un oubli.
     */
    @Test
    void thePageHasNoCreateFormAndSaysWhereToCreate() throws Exception {
        start();
        seed();

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("building.site.create"));
        assertTrue(page.contains("/rpgadmin buildsite tool"));
        assertTrue(page.contains("clic droit") || page.contains("Clic droit"));
    }

    // ---- Mutations -----------------------------------------------------------------------------

    @Test
    void renamingQueuesTheActionWithTheExactId() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.rename&return=/buildings/sites"
                + "&id=buildsite_0001&name=" + enc("Test hutte") + "&confirm=true");

        AgentActionRow queued = queued("building.site.rename");
        assertEquals("buildsite_0001", queued.params().get("id"));
        assertEquals("Test hutte", queued.params().get("name"));
    }

    @Test
    void anEmptyNameIsRefusedBeforeQueueing() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.rename&return=/buildings/sites"
                + "&id=buildsite_0001&name=&confirm=true");

        assertEquals(0, count("building.site.rename"));
    }

    /** Une description vide est une valeur valide : c'est le geste « effacer la note ». */
    @Test
    void anEmptyDescriptionIsAcceptedBecauseItClearsTheNote() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.describe&return=/buildings/sites"
                + "&id=buildsite_0001&description=&confirm=true");

        assertEquals("", queued("building.site.describe").params().get("description"));
    }

    @Test
    void anUnknownFacingIsRefusedBeforeQueueing() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.facing&return=/buildings/sites"
                + "&id=buildsite_0001&facing=UPSIDE_DOWN&confirm=true");
        assertEquals(0, count("building.site.facing"));

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.facing&return=/buildings/sites"
                + "&id=buildsite_0001&facing=east&confirm=true");
        assertEquals("EAST", queued("building.site.facing").params().get("facing"),
                "la casse est normalisée");
    }

    /** Un identifiant forgé n'atteint jamais le serveur. */
    @Test
    void aForgedSiteIdIsRefusedBeforeQueueing() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());

        for (String id : new String[] {"../etc/passwd", "village_tavern_01", "", "buildsite_"}) {
            post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                    + "&type=building.site.delete&return=/buildings/sites"
                    + "&id=" + enc(id) + "&confirm=true");
        }

        assertEquals(0, count("building.site.delete"));
    }

    @Test
    void deletingQueuesTheActionAndNothingElse() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.delete&return=/buildings/sites"
                + "&id=buildsite_0001&confirm=true");

        assertEquals(1, count("building.site.delete"));
        assertEquals("buildsite_0001", queued("building.site.delete").params().get("id"));
        assertEquals(0, count("building.site.rename"), "aucune cascade");
    }

    /** Double clic : deux demandes partent, et c'est sans danger — le serveur est idempotent. */
    @Test
    void aDoubleClickOnDeleteIsHarmless() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());
        String body = "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.delete&return=/buildings/sites"
                + "&id=buildsite_0001&confirm=true";

        post("/agents/action", body);
        post("/agents/action", body);

        assertEquals(2, count("building.site.delete"));
    }

    @Test
    void aPostWithoutAValidCsrfTokenQueuesNothing() throws Exception {
        start();
        seed();

        assertEquals(403, post("/agents/action", "_csrf=faux&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.delete&return=/buildings/sites"
                + "&id=buildsite_0001&confirm=true").statusCode());
        assertEquals(0, count("building.site.delete"));
    }

    /** La suppression est déclarée sensible : la case à cocher est obligatoire. */
    @Test
    void deletingWithoutTheExplicitConfirmationQueuesNothing() throws Exception {
        start();
        seed();
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.delete&return=/buildings/sites&id=buildsite_0001");

        assertEquals(0, count("building.site.delete"));
    }

    // ---- Permissions ---------------------------------------------------------------------------

    /**
     * Trois permissions distinctes, et c'est voulu : consulter un repère de construction, corriger
     * sa fiche et le supprimer ne sont pas le même geste.
     */
    @Test
    void thePermissionsAreSplitAndGrantedToTheRightRoles() {
        var read = Permission.BUILDING_READ;
        var write = Permission.BUILDING_WRITE;
        var delete = Permission.BUILDING_DELETE;

        assertTrue(Role.OWNER.permissions().containsAll(java.util.Set.of(read, write, delete)));
        assertTrue(Role.ADMIN.permissions().containsAll(java.util.Set.of(read, write, delete)));

        // Un builder consulte les repères — c'est son métier — mais n'écrit rien.
        assertTrue(Role.BUILDER.permissions().contains(read));
        assertFalse(Role.BUILDER.permissions().contains(write));
        assertFalse(Role.BUILDER.permissions().contains(delete));

        // Un testeur vérifie qu'un emplacement marqué en jeu est bien arrivé.
        assertTrue(Role.TESTER.permissions().contains(read));
        assertFalse(Role.TESTER.permissions().contains(write));

        assertFalse(Role.READ_ONLY.permissions().contains(write));
        assertFalse(Role.READ_ONLY.permissions().contains(delete));
        assertFalse(Role.CONTENT_EDITOR.permissions().contains(delete),
                "éditer du contenu ne donne pas le droit de supprimer un repère");
    }

    @Test
    void theNavEntryIsGuardedByTheReadPermission() throws Exception {
        start();
        seed();

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Bâtiments"), "le groupe de navigation");
        assertTrue(page.contains("href=\"/buildings/sites\""));
    }

    @Test
    void thePageRequiresASession() throws Exception {
        start();
        jar.clear();

        HttpResponse<String> res = get("/buildings/sites");

        assertEquals(303, res.statusCode());
        assertEquals("/login", res.headers().firstValue("Location").orElse(""));
    }

    // ---- Harnais -------------------------------------------------------------------------------

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + token);
    }

    /** Fait aboutir un {@code building.site.list} avec le relevé ci-dessus, comme le ferait l'agent. */
    private void seed() throws Exception {
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=building.site.list&agent="
                + TestConfig.AGENT_ID + "&return=/buildings/sites");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action à livrer : " + poll.body());
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"2\","
                + "\"message\":\"2 emplacement(s)\",\"details\":" + SITES + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long count(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).count();
    }

    private AgentActionRow queued(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).findFirst().orElseThrow();
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
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((x, y) -> x + "; " + y).orElse(""));
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
