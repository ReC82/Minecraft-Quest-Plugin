package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.building.BuildingDesignDocStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore;
import com.lodygames.rpgquest.panel.building.BuildingProjectStore;
import com.lodygames.rpgquest.panel.building.ReferenceImageStore;
import com.lodygames.rpgquest.panel.support.TestConfig;
import java.net.URI;
import java.net.URLDecoder;
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
 * Issue #881 — la liste de l'Atelier se cherche, se filtre et se trie ; un projet NON utilisé se supprime,
 * un projet posé, publié ou à l'usage incertain est refusé côté serveur, même par une requête forgée.
 * Aucun appel IA : les versions de plan sont créées directement dans la base du panel.
 */
class WorkshopProjectCleanup881Test {

    private static final String PLAN = "id: %s\nname: \"Essai\"\nkind: habitable\nfront: WEST\nsize:\n"
            + "  x: 5\n  y: 4\n  z: 5\nsteps:\n  - op: box\n    from: [0, 0, 0]\n    to: [4, 0, 4]\n"
            + "    block: minecraft:stone_bricks";

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private String dbPath;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- Recherche, filtres, tri ----------------------------------------------------------------

    @Test
    void theListIsSearchedIgnoringCaseAndAccentsFilteredAndSorted() throws Exception {
        start();
        sites("");
        createProject("Église du Hub", "Une église en pierre.", "eglise_hub");
        createProject("Tour de garde", "Une tour près des remparts.", "tour_garde");
        createProject("Moulin", "Moulin à vent, ailes en toile.", "moulin_vent");
        sites(placement("eglise_hub"));

        String all = get("/buildings/workshop").body();
        assertTrue(all.contains("3 projet(s)"), excerpt(all));
        assertTrue(all.contains("Utilisés en jeu (1)") && all.contains("Non utilisés (2)"), excerpt(all));

        String eglise = get("/buildings/workshop?q=EGLISE").body();
        assertTrue(eglise.contains("Église du Hub") && !eglise.contains("Tour de garde"), "accents et casse ignorés");
        assertTrue(eglise.contains("1 projet(s) sur 3"));
        assertTrue(get("/buildings/workshop?q=remparts").body().contains("Tour de garde"), "la description compte");
        assertTrue(get("/buildings/workshop?q=moulin_vent").body().contains("Moulin"), "l'identifiant aussi");

        String used = get("/buildings/workshop?filtre=utilises").body();
        assertTrue(used.contains("Église du Hub") && !used.contains("Tour de garde"));
        String unusedTour = get("/buildings/workshop?filtre=non_utilises&q=tour").body();
        assertTrue(unusedTour.contains("Tour de garde") && !unusedTour.contains("Moulin")
                && !unusedTour.contains("Église du Hub"), "filtre et recherche se combinent");

        String none = get("/buildings/workshop?q=chateau&filtre=utilises").body();
        assertTrue(none.contains("Aucun projet ne correspond à « chateau » dans « Utilisés en jeu »"), excerpt(none));
        assertTrue(none.contains("Effacer la recherche et les filtres"));

        String byName = get("/buildings/workshop?tri=nom").body();
        assertTrue(byName.indexOf("Église du Hub") < byName.indexOf("Moulin")
                && byName.indexOf("Moulin") < byName.indexOf("Tour de garde"), "É trié comme E");
        String oldest = get("/buildings/workshop?tri=anciens").body();
        assertTrue(oldest.indexOf("Église du Hub") < oldest.indexOf("Moulin"), "le plus ancien d'abord");
    }

    @Test
    void eachTileAlwaysShowsItsUsageAndWhereItIsPlaced() throws Exception {
        start();
        sites("");
        createProject("Église du Hub", "Une église.", "eglise_hub");
        createProject("Moulin", "Un moulin.", "moulin_vent");
        sites(placement("eglise_hub"));
        String page = get("/buildings/workshop").body();
        String eglise = tile(page, "Église du Hub");
        assertTrue(eglise.contains("Utilisé en jeu") && eglise.contains("1 emplacement(s)")
                && eglise.contains("Voir où utilisé"), eglise);
        String moulin = tile(page, "Moulin");
        assertTrue(moulin.contains("Non utilisé") && !moulin.contains("Voir où utilisé"), moulin);
        assertTrue(moulin.contains("Jamais enregistré"), "statut Bibliothèque visible : " + moulin);
    }

    // ---- Corbeille, restauration, suppression définitive -------------------------------------------

    @Test
    void anUnusedProjectGoesToTheTrashIntactIsRestoredThenDeletedOnlyWithAStrongConfirmation() throws Exception {
        start();
        sites("");
        String project = createProject("Essai jetable", "Un essai.", "essai_jetable");
        String other = createProject("Autre projet", "À garder.", "autre_projet");
        String pid = id(project);
        addDraft(pid, "essai_jetable");
        addDraft(id(other), "autre_projet");
        new ReferenceImageStore(dbPath).add(pid, "ref.png", png(), "owner");
        new BuildingDesignDocStore(dbPath).save(pid, "Conception.", BuildingDesignDocStore.Origin.MANUAL, "", "",
                "", "owner", 0);
        int actionsBefore = store.recentActions(TestConfig.AGENT_ID, 500).size();

        // 1. Fiche : « Mettre à la corbeille » — rien n'est effacé.
        String page = get(project).body();
        String zone = section(page, "id=\"supprimer\"");
        assertTrue(zone.contains("Mettre à la corbeille") && zone.contains("Non utilisé")
                && zone.contains("est effacé</strong>"), zone);
        assertFalse(zone.contains("value=\"project_delete\""), "pas de suppression définitive depuis la fiche");
        String trashed = decoded(location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=project_trash&id=" + pid + "&project_version=" + version(zone))));
        assertTrue(trashed.contains("mis à la corbeille") && trashed.contains("restauré"), trashed);
        assertTrue(projects().find(pid).orElseThrow().archived());
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, pid).size(), "versions conservées");
        assertEquals(1, new ReferenceImageStore(dbPath).list(pid).size(), "images conservées");
        String list = get("/buildings/workshop").body();
        assertFalse(list.contains("Essai jetable"), "hors de la liste principale");
        assertTrue(list.contains("Corbeille (1)") && list.contains("1 projet(s)"), excerpt(list));
        assertTrue(location(get(project)).startsWith("/buildings/workshop/trash"), "la fiche renvoie à la corbeille");
        String again = decoded(location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=project_trash&id=" + pid + "&project_version=" + version(zone))));
        assertTrue(again.contains("rien de plus n'a été fait"), "double clic sans erreur : " + again);

        // 2. Corbeille : restaurer, tel quel.
        String bin = get("/buildings/workshop/trash").body();
        String item = trashItem(bin, pid);
        assertTrue(item.contains("Essai jetable") && item.contains("1 version(s) de plan")
                && item.contains("1 conception(s) détaillée(s)") && item.contains("1 image(s)"), item);
        String restored = decoded(location(post("/buildings/workshop/trash", "_csrf=" + csrf(bin)
                + "&_action=project_restore&id=" + pid + "&project_version=" + version(item))));
        assertTrue(restored.contains("restauré dans l'Atelier"), restored);
        assertFalse(projects().find(pid).orElseThrow().archived());
        assertTrue(get("/buildings/workshop").body().contains("Essai jetable"));
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, pid).size());

        // 3. De nouveau à la corbeille, puis suppression définitive : identifiant recopié exigé.
        page = get(project).body();
        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=project_trash&id=" + pid
                + "&project_version=" + version(section(page, "id=\"supprimer\"")));
        bin = get("/buildings/workshop/trash").body();
        item = trashItem(bin, pid);
        String form = "_csrf=" + csrf(bin) + "&_action=project_delete&id=" + pid + "&project_version=" + version(item)
                + "&confirm=yes";
        String refused = decoded(location(post("/buildings/workshop/trash", form + "&confirm_id=autre_chose")));
        assertTrue(refused.contains("Recopiez l'identifiant") && refused.contains("rien n'a été supprimé"), refused);
        assertTrue(projects().find(pid).isPresent(), "rien sans l'identifiant");

        String done = decoded(location(post("/buildings/workshop/trash", form + "&confirm_id=essai_jetable")));
        assertTrue(done.contains("supprimé définitivement"), done);
        assertTrue(projects().find(pid).isEmpty());
        assertTrue(drafts().forProject(TestConfig.AGENT_ID, pid).isEmpty());
        assertTrue(new ReferenceImageStore(dbPath).list(pid).isEmpty());
        assertTrue(new BuildingDesignDocStore(dbPath).history(pid).isEmpty());
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, id(other)).size(), "l'autre projet est intact");
        assertEquals(actionsBefore, store.recentActions(TestConfig.AGENT_ID, 500).size(),
                "aucune action envoyée au serveur, historique conservé");
        String twice = decoded(location(post("/buildings/workshop/trash", form + "&confirm_id=essai_jetable")));
        assertTrue(twice.contains("déjà été supprimé"), "double clic sans erreur : " + twice);
        assertTrue(get("/buildings/workshop/trash").body().contains("La corbeille est vide"));

        // 4. Historique visible : chaque geste, refus compris, avec qui et quoi.
        String history = get("/actions?status=panel&domain=all").body();
        assertTrue(history.contains("Opérations du panel (") && history.contains("Mis à la corbeille")
                && history.contains("Restauré depuis la corbeille") && history.contains("Supprimé définitivement")
                && history.contains("Essai jetable") && history.contains("existe plus)"), excerpt(history));
        assertTrue(history.contains("Recopiez l") && history.contains(TestConfig.OWNER_USERNAME), "le refus et l'acteur");
        assertTrue(history.contains("Autre projet") && history.contains("Ouvrir"), "lien vers un projet existant");
        assertTrue(get("/actions?status=panel&domain=all&q=corbeille").body().contains("Mis à la corbeille"));
    }

    @Test
    void aProjectEditedMeanwhileIsNotTrashed() throws Exception {
        start();
        sites("");
        String project = createProject("Essai", "Un essai.", "essai_concurrent");
        String page = get(project).body();
        String version = version(section(page, "id=\"supprimer\""));
        var p = projects().find(id(project)).orElseThrow();
        projects().update(p.id(), p.version(), new BuildingProjectStore.Fields("Essai renommé", p.brief(),
                p.buildingId(), p.front(), p.maxWidth(), p.maxDepth(), p.maxHeight()), "autre");
        String res = decoded(location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=project_trash&id=" + id(project) + "&project_version=" + version)));
        assertTrue(res.contains("modifié entre-temps"), res);
        assertFalse(projects().find(id(project)).orElseThrow().archived());
    }

    @Test
    void aProjectOutsideTheTrashIsNeverDeletedDefinitively() throws Exception {
        start();
        sites("");
        String project = createProject("Essai", "Un essai.", "essai_actif");
        var p = projects().find(id(project)).orElseThrow();
        String page = get(project).body();
        String fromFiche = decoded(location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=project_delete&id=" + p.id() + "&project_version=" + p.version() + "&confirm=yes")));
        assertTrue(fromFiche.contains("Mettez d'abord le projet à la corbeille"), fromFiche);
        String fromTrash = decoded(location(post("/buildings/workshop/trash", "_csrf=" + csrf(page)
                + "&_action=project_delete&id=" + p.id() + "&project_version=" + p.version()
                + "&confirm=yes&confirm_id=essai_actif")));
        assertTrue(fromTrash.contains("déjà dans la liste"), fromTrash);
        assertTrue(projects().find(p.id()).isPresent());
    }

    @Test
    void aTrashedProjectThatBecameUsedCannotBeDeletedDefinitively() throws Exception {
        start();
        sites("");
        String project = createProject("Église du Hub", "Une église.", "eglise_hub");
        var p = projects().find(id(project)).orElseThrow();
        assertEquals(BuildingProjectStore.TrashOutcome.DONE, projects().trash(p.id(), p.version(), "owner"));
        sites(placement("eglise_hub"));
        String bin = get("/buildings/workshop/trash").body();
        String item = trashItem(bin, p.id());
        assertTrue(item.contains("Suppression définitive impossible") && !item.contains("value=\"project_delete\""),
                item);
        var trashed = projects().find(p.id()).orElseThrow();
        String res = decoded(location(post("/buildings/workshop/trash", "_csrf=" + csrf(bin)
                + "&_action=project_delete&id=" + p.id() + "&project_version=" + trashed.version()
                + "&confirm=yes&confirm_id=eglise_hub")));
        assertTrue(res.contains("rien n'a été supprimé") && res.contains("posé sur 1 emplacement"), res);
        assertTrue(projects().find(p.id()).isPresent());
        assertTrue(get("/actions?status=panel&domain=all").body().contains("refusé"));
    }

    // ---- Suppression refusée côté serveur --------------------------------------------------------

    @Test
    void aPlacedProjectHasNoDeleteFormAndAForgedRequestIsRefused() throws Exception {
        start();
        sites("");
        String project = createProject("Église du Hub", "Une église.", "eglise_hub");
        addDraft(id(project), "eglise_hub");
        sites(placement("eglise_hub"));
        String page = get(project).body();
        String zone = section(page, "id=\"supprimer\"");
        assertTrue(zone.contains("Utilisé en jeu") && zone.contains("buildsite_0009")
                && zone.contains("Mise à la corbeille impossible"), zone);
        assertFalse(zone.contains("value=\"project_trash\""), "pas de bouton, pas d'option forcer");
        assertForgedDeleteRefused(page, project, "posé sur 1 emplacement");
    }

    @Test
    void aPublishedButUnplacedProjectPointsToTheLibraryInstead() throws Exception {
        start();
        sites("");
        String project = createProject("Moulin", "Un moulin.", "moulin_vent");
        String gen = store.createAction(TestConfig.AGENT_ID, "building.generate",
                Map.of("plan", PLAN.formatted("moulin_vent")), "owner");
        answer(gen, "{\"code\":\"OK\",\"buildingId\":\"moulin_vent\",\"version\":1}");
        String list = get("/buildings/workshop?filtre=bibliotheque").body();
        assertTrue(list.contains("Moulin"), excerpt(list));
        String page = get(project).body();
        String zone = section(page, "id=\"supprimer\"");
        assertTrue(zone.contains("publié dans la bibliothèque") && zone.contains("/buildings/library"), zone);
        assertForgedDeleteRefused(page, project, "publié dans la bibliothèque");
    }

    @Test
    void withoutASiteSurveyTheUsageIsUncertainAndDeletionBlocked() throws Exception {
        start();
        String project = createProject("Essai", "Un essai.", "essai_incertain");
        assertTrue(tile(get("/buildings/workshop").body(), "Essai").contains("Utilisation à vérifier"));
        String page = get(project).body();
        assertTrue(section(page, "id=\"supprimer\"").contains("Aucun relevé des emplacements"));
        assertForgedDeleteRefused(page, project, "Aucun relevé des emplacements");
    }

    @Test
    void aBuildingCitedByAPortalCannotBeDeleted() throws Exception {
        start();
        sites("");
        String project = createProject("Arche", "Une arche.", "arche_hub");
        String portals = store.createAction(TestConfig.AGENT_ID, "portal.list", Map.of(), "owner");
        answer(portals, "{\"portals\":[{\"id\":\"hub_to_wild\",\"site\":\"\",\"building\":\"arche_hub\"}]}");
        String page = get(project).body();
        assertTrue(tile(get("/buildings/workshop").body(), "Arche").contains("Utilisé en jeu"));
        assertForgedDeleteRefused(page, project, "hub_to_wild");
    }

    @Test
    void aPendingVerificationBlocksDeletion() throws Exception {
        start();
        sites("");
        String project = createProject("Essai", "Un essai.", "essai_attente");
        BuildingPlanDraft d = addDraft(id(project), "essai_attente");
        String audit = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", d.plan()), "owner");
        drafts().link(d.id(), audit, BuildingPlanDraftStore.Check.AUDIT, d.version());
        assertForgedDeleteRefused(get(project).body(), project, "attend la réponse du serveur");
    }

    @Test
    void aRoleWithoutBuildingWriteSeesNoFormAndIsRefused() throws Exception {
        start();
        sites("");
        String project = createProject("Essai", "Un essai.", "essai_droits");
        post("/users/create", "_csrf=" + csrf(get("/users").body()) + "&username=eddy&password="
                + enc("Eddy-Password-2026!") + "&role=CONTENT_EDITOR");
        jar.clear();
        post("/login", "username=eddy&password=" + enc("Eddy-Password-2026!") + "&_csrf=" + csrf(get("/login").body()));
        String page = get(project).body();
        String zone = section(page, "id=\"supprimer\"");
        assertTrue(zone.contains("Votre rôle ne permet pas"), zone);
        assertForgedDeleteRefused(page, project, "Votre rôle ne permet pas");
    }

    private void assertForgedDeleteRefused(String page, String project, String reason) throws Exception {
        var p = projects().find(id(project)).orElseThrow();
        String res = decoded(location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=project_trash&id=" + p.id() + "&project_version=" + p.version())));
        assertTrue(res.contains("rien n'a changé"), res);
        assertTrue(res.contains(reason), res);
        assertFalse(projects().find(p.id()).orElseThrow().archived(), "le projet est toujours dans la liste");
    }

    // ---- Outils ---------------------------------------------------------------------------------

    private void start() throws Exception {
        dbPath = tmp.resolve("cp.db").toString();
        store = new AgentStore(dbPath);
        app = new PanelApp(TestConfig.withAgent(dbPath, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password=" + enc(TestConfig.OWNER_PASSWORD)
                + "&_csrf=" + csrf(get("/login").body()));
    }

    /** Un relevé des emplacements, avec les bâtiments posés donnés (JSON, vide = aucun). */
    private void sites(String placements) throws Exception {
        Thread.sleep(5);
        String id = store.createAction(TestConfig.AGENT_ID, "building.site.list", Map.of(), "owner");
        answer(id, "{\"total\":1,\"worlds\":[\"world_hub\"],\"sites\":[{\"id\":\"buildsite_0009\",\"name\":\"Place\","
                + "\"world\":\"world_hub\",\"x\":1,\"y\":64,\"z\":1,\"facing\":\"WEST\",\"status\":\""
                + (placements.isEmpty() ? "EMPTY" : "OCCUPIED") + "\",\"worldLoaded\":true}],\"placements\":["
                + placements + "]}");
    }

    private static String placement(String buildingId) {
        return "{\"siteId\":\"buildsite_0009\",\"buildingId\":\"" + buildingId + "\",\"buildingName\":\"x\","
                + "\"world\":\"world_hub\",\"buildingVersion\":1,\"restorable\":true}";
    }

    private void answer(String actionId, String details) {
        store.recordResult(actionId, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "", "ok",
                "{\"action_id\":\"" + actionId + "\",\"status\":\"SUCCESS\",\"message\":\"ok\",\"details\":"
                        + details + "}", Instant.now());
    }

    private String createProject(String name, String brief, String buildingId) throws Exception {
        Thread.sleep(5);
        String page = get("/buildings/workshop/new").body();
        HttpResponse<String> res = post("/buildings/workshop/new", "_csrf=" + csrf(page) + "&name=" + enc(name)
                + "&brief=" + enc(brief) + "&front=WEST&max_width=19&max_height=14&building_id=" + buildingId);
        assertEquals(303, res.statusCode(), res.body());
        return location(res);
    }

    private BuildingPlanDraft addDraft(String projectId, String buildingId) {
        return drafts().create(new BuildingPlanDraftStore.NewDraft(TestConfig.AGENT_ID, "", PLAN.formatted(buildingId),
                BuildingPlanDraft.Origin.RECOVERED, "", "", "", "", "", "", "", "", 19, 19, 14, "owner", projectId));
    }

    private static byte[] png() {
        byte[] b = new byte[64];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }

    private BuildingPlanDraftStore drafts() {
        return new BuildingPlanDraftStore(dbPath);
    }

    private BuildingProjectStore projects() {
        return new BuildingProjectStore(dbPath);
    }

    private static String tile(String page, String name) {
        int at = page.indexOf(">" + name + "</a>");
        assertTrue(at > 0, "tuile introuvable : " + name);
        int open = page.lastIndexOf("ws-card", at);
        int close = page.indexOf("</div></div>", at);
        return page.substring(open, close);
    }

    private static String section(String page, String marker) {
        int at = page.indexOf(marker);
        assertTrue(at > 0, "section introuvable : " + marker + " — " + excerpt(page));
        return page.substring(at, page.indexOf("</details>", at));
    }

    private static String version(String html) {
        return field(html, "name=\"project_version\" value=\"([^\"]+)\"");
    }

    private static String trashItem(String page, String projectId) {
        int at = page.indexOf("id=\"projet-" + projectId + "\"");
        assertTrue(at > 0, "absent de la corbeille : " + projectId + " — " + excerpt(page));
        int next = page.indexOf("class=\"card ws-trash\"", at + 1);
        return page.substring(at, next < 0 ? page.length() : next);
    }

    private static String id(String location) {
        return field(location, "[?&]id=([^&#]+)");
    }

    private static String location(HttpResponse<String> res) {
        return res.headers().firstValue("Location").orElse("");
    }

    private static String decoded(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String field(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("introuvable : " + regex + " dans " + excerpt(text));
        }
        return m.group(1);
    }

    private static String excerpt(String page) {
        int at = page.indexOf("<main");
        String from = at >= 0 ? page.substring(at) : page;
        return from.substring(0, Math.min(3000, from.length()));
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        return field(html, "name=\"_csrf\" value=\"([^\"]+)\"");
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).GET());
    }

    private HttpResponse<String> post(String p, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((x, y) -> x + "; " + y).orElse(""));
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String sc : res.headers().allValues("Set-Cookie")) {
            String[] f = sc.split(";", 2);
            int eq = f[0].indexOf('=');
            jar.put(f[0].substring(0, eq).trim(), f[0].substring(eq + 1).trim());
        }
        return res;
    }
}
