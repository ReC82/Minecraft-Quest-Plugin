package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.ai.AiProviderSettings;
import com.lodygames.rpgquest.panel.ai.AiSettingsStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore;
import com.lodygames.rpgquest.panel.json.Json;
import com.lodygames.rpgquest.panel.support.TestConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issues #265 / #267 — l'Atelier de création sur un vrai serveur HTTP, avec un fournisseur d'IA
 * bouchonné qui <strong>compte</strong> ses appels.
 *
 * <p>Reprend toutes les garanties de #256 (aucun appel payant sur une navigation, jeton à usage unique,
 * versions, reprise après redémarrage, récupération sans IA) dans le parcours sans emplacement, et
 * ajoute ce que #265 exige : parcours guidé sans jargon, import non destructif, anciennes adresses.</p>
 */
class WorkshopFlowTest {

    private static final String SITES = "{\"total\":1,\"worlds\":[\"world_hub\"],\"sites\":[{"
            + "\"id\":\"buildsite_0009\",\"name\":\"Bibliothèque du Hub\",\"description\":\"\","
            + "\"aiBrief\":\"Bibliothèque médiévale fantastique. Bâtiment sur deux étages, pierre "
            + "claire et bois sombre, façade à l'ouest. Grande salle de lecture, escalier "
            + "intérieur.\",\"world\":\"world_hub\",\"x\":784,\"y\":67,\"z\":-693,"
            + "\"facing\":\"WEST\",\"status\":\"EMPTY\",\"createdBy\":\"Lody\","
            + "\"createdAt\":\"2026-10-11T18:12:06Z\",\"worldLoaded\":true}]}";

    private static final String BRIEF = "Une bibliothèque médiévale sur deux étages, en pierre claire "
            + "et bois sombre, avec un escalier intérieur et des fenêtres.";

    private static final String PLAN = "id: bibliotheque_v2\nname: \"Bibliothèque\"\n"
            + "kind: habitable\nfront: WEST\nsize:\n  x: 19\n  y: 14\n  z: 19\nsteps:\n"
            + "  - op: box\n    from: [0, 0, 0]\n    to: [18, 0, 18]\n    block: minecraft:stone_bricks";

    private static final String AUDIT_OK = "{\"written\":false,\"sizeX\":19,\"sizeY\":14,"
            + "\"sizeZ\":19,\"warnings\":[],\"metrics\":{\"reachableFloors\":1,\"lightSources\":80,"
            + "\"solidCells\":2107,\"glazedCells\":22,\"floors\":1,\"glazedFacades\":3},"
            + "\"kind\":\"bâtiment habitable\",\"code\":\"OK\",\"blocks\":2107,"
            + "\"buildingId\":\"bibliotheque_v2\",\"codes\":[],\"exists\":false,"
            + "\"message\":\"Plan conforme.\"}";

    private static final String AUDIT_REFUSED = "{\"code\":\"NO_ENTRANCE\",\"codes\":[\"NO_ENTRANCE\"],"
            + "\"message\":\"Aucune entrée sur la façade avant.\",\"buildingId\":\"bibliotheque_v2\","
            + "\"blocks\":1500,\"warnings\":[],\"metrics\":{\"floors\":1,\"reachableFloors\":1}}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private HttpServer ai;
    private final AtomicInteger aiCalls = new AtomicInteger();
    private String dbPath;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (ai != null) {
            ai.stop(0);
        }
    }

    // ---- Créer sans emplacement ------------------------------------------------------------------

    @Test
    void aProjectIsCreatedWithoutAnySiteAndWithoutCallingTheAi() throws Exception {
        start();
        String index = get("/buildings/workshop").body();
        assertTrue(index.contains("Atelier de création"), excerpt(index));
        assertTrue(index.contains("Aucun emplacement n&#39;est nécessaire") || index.contains("Aucun emplacement n'est nécessaire"));

        String project = createProject("Bibliothèque", BRIEF);

        String page = get(project).body();
        assertTrue(page.contains("aria-current=\"step\""), "les étapes sont affichées");
        assertTrue(page.contains("Créer un plan avec l&#39;IA") || page.contains("Créer un plan avec l'IA"));
        assertEquals(0, aiCalls.get(), "créer un projet est gratuit");
        assertTrue(get("/buildings/workshop").body().contains("Prêt à demander un plan"));
    }

    @Test
    void aProjectWithoutDescriptionAsksForOneAndNeverCallsTheAi() throws Exception {
        start();
        String project = createProject("Tour", "");
        String page = get(project).body();
        assertTrue(page.contains("Décrivez d&#39;abord le bâtiment") || page.contains("Décrivez d'abord le bâtiment"));

        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=propose&id=" + id(project)
                + "&provider=openai&request_token=t1");
        assertEquals(0, aiCalls.get());
    }

    // ---- Aucun appel payant sur une navigation ------------------------------------------------

    @Test
    void aPaidPlanBecomesAVersionAndNoNavigationCallsTheAiAgain() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String withDraft = propose(project);
        assertEquals(1, aiCalls.get());

        String page = get(withDraft).body();
        assertTrue(page.contains("Vérifier le bâtiment"), "l'action suivante, en clair");
        get(withDraft);
        get("/buildings/workshop");
        get(project);
        assertEquals(1, aiCalls.get(), "reprendre, recharger, revenir : jamais facturé");
    }

    @Test
    void resubmittingTheSamePaidFormNeverCallsTwice() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String page = get(project).body();
        String form = "_csrf=" + csrf(page) + "&_action=propose&id=" + id(project) + "&provider=openai"
                + "&request_token=" + token(page);

        String first = location(post("/buildings/workshop/project", form));
        String second = location(post("/buildings/workshop/project", form));

        assertEquals(1, aiCalls.get());
        assertEquals(draftId(first), draftId(second));
        assertTrue(decoded(second).contains("Aucun nouvel appel"));
    }

    // ---- Vérifier, en français ---------------------------------------------------------------

    @Test
    void aRefusedPlanIsExplainedInPlainFrenchWithoutCodesInTheMainCard() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        HttpResponse<String> res = gesture(get(withDraft).body(), "audit");
        assertTrue(location(res).contains("draft="), "on revient au projet, pas à l'historique");
        assertTrue(mainCard(get(withDraft).body()).contains("Vérification en cours"));

        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(withDraft).body();
        String main = mainCard(page);
        assertTrue(main.contains("pas de porte utilisable"), main);
        assertTrue(main.contains("Améliorer avec l&#39;IA") || main.contains("Améliorer avec l'IA"));
        for (String jargon : new String[] {"NO_ENTRANCE", "PLAN_INVALID", "YAML", "audit", "brief"}) {
            assertFalse(main.contains(jargon), "jargon dans le parcours principal : " + jargon + " — "
                    + main.substring(Math.max(0, main.indexOf(jargon) - 200), Math.min(main.length(), main.indexOf(jargon) + 100)));
        }
        assertTrue(page.contains("NO_ENTRANCE"), "le code brut reste dans les détails techniques");
        assertEquals(1, aiCalls.get());
    }

    @Test
    void aVerifiedPlanOffersToSaveInTheLibraryAndSaysWhenItDiffersFromTheDescription() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        gesture(get(withDraft).body(), "audit");
        answer(latest("building.plan.audit"), AUDIT_OK);

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Enregistrer dans la bibliothèque"), main);
        assertTrue(main.contains("ne correspond pas tout à fait à votre description"),
                "deux étages demandés, un seul mesuré");
        assertTrue(main.contains("demande 2 niveaux"), main);
    }

    /** La grille d'un aperçu : deux niveaux, un mur en L, l'entrée à l'ouest. */
    private static final String PREVIEW_WITH_GRID = "{\"code\":\"OK\",\"front\":\"WEST\",\"plans\":[],"
            + "\"elevations\":[],\"voxels\":{\"palette\":[{\"symbol\":\"A\",\"block\":\"stone_bricks\","
            + "\"role\":\"#\",\"shape\":\"full\"},{\"symbol\":\"B\",\"block\":\"glass_pane\",\"role\":\"o\","
            + "\"shape\":\"full\"}],\"layers\":[[\"AA\",\"AB\"],[\"A.\",\"A.\"]],\"omittedReason\":\"\"}}";

    /**
     * Issue #273 : vérifier, c'est aussi voir. La vérification demande l'aperçu (gratuit) sur la même
     * version, et la page dessine la grille que le serveur a renvoyée — sans aucun appel à l'IA.
     */
    @Test
    void aVerificationAlsoDrawsTheBuildingFromTheServerGrid() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        gesture(get(withDraft).body(), "audit");
        assertEquals(1, store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals("building.plan.preview")).count(), "l'aperçu part avec la vérification");
        assertTrue(get(withDraft).body().contains("Aperçu en cours de calcul"));

        answer(latest("building.plan.audit"), AUDIT_OK);
        answer(latest("building.plan.preview"), PREVIEW_WITH_GRID);
        String page = get(withDraft).body();
        assertTrue(page.contains("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"iso-view\""), "le bâtiment est dessiné");
        assertTrue(page.contains("Vu depuis le sud-ouest"), "par défaut, la vue montre l'entrée");
        assertTrue(page.contains("L&#39;entrée (côté ouest) est la face à gauche")
                || page.contains("L'entrée (côté ouest) est la face à gauche"), "et le dit");
        assertTrue(page.contains("fill-opacity"), "la vitre est translucide");
        assertTrue(page.contains("stone bricks"), "la légende nomme les matériaux");
        assertFalse(mainCard(page).contains("<svg xmlns"), "l'aperçu est à part, l'action principale reste seule");

        String turned = get(withDraft + "&view=2&cut=0").body();
        assertTrue(turned.contains("Vu depuis le nord-ouest"), "on tourne autour du bâtiment");
        assertTrue(turned.contains("tout ce qui dépasse le niveau 0 est retiré"), "on voit l'intérieur");
        assertTrue(get(withDraft + "&view=99&cut=-7").body().contains("Vu depuis le sud-ouest"),
                "une valeur hors bornes revient à la vue par défaut");
        assertEquals(1, aiCalls.get(), "tourner et couper n'appellent jamais l'IA");
    }

    @Test
    void anOlderPluginWithoutTheGridSaysWhatIsMissing() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        gesture(get(withDraft).body(), "audit");
        answer(latest("building.plan.preview"), "{\"code\":\"OK\",\"plans\":[],\"elevations\":[]}");
        String page = get(withDraft).body();
        assertTrue(page.contains("version plus récente du plugin"), "le manque est dit, pas maquillé");
        assertFalse(page.contains("class=\"iso-view\""));
    }

    @Test
    void aTakenLibraryNameBlocksSavingAndSaysWhatToDo() throws Exception {
        start();
        String library = "{\"buildings\":[{\"id\":\"bibliotheque_v2\",\"name\":\"V1\",\"sizeX\":19,"
                + "\"sizeY\":14,\"sizeZ\":19,\"front\":\"WEST\",\"schematic\":\"b.schem\","
                + "\"schematicPresent\":true,\"version\":1}],\"total\":1}";
        String lid = store.createAction(TestConfig.AGENT_ID, "building.definition.list", Map.of(), "owner");
        answer(store.action(lid).orElseThrow(), library.replace("{\"buildings\"", "{\"code\":\"OK\",\"buildings\""));
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        gesture(get(withDraft).body(), "audit");
        answer(latest("building.plan.audit"), AUDIT_OK);

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("porte déjà le nom"), main);
        assertFalse(main.contains("value=\"generate\""), "aucun bouton qui remplacerait l'autre bâtiment");
    }

    @Test
    void savingInTheLibraryNeverReplacesAnything() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        gesture(get(withDraft).body(), "audit");
        answer(latest("building.plan.audit"), AUDIT_OK);

        gesture(get(withDraft).body(), "generate");

        AgentActionRow queued = latest("building.generate");
        assertEquals(PLAN, queued.params().get("plan"));
        assertFalse(queued.params().containsKey("replace"), "jamais de remplacement depuis le parcours");
        answer(queued, "{\"code\":\"OK\",\"buildingId\":\"bibliotheque_v2\"}");
        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Enregistré dans la bibliothèque"), main);
        assertTrue(main.contains("Choisir un emplacement"));
    }

    /** Le nom technique du PROJET s'impose au plan de l'IA — sans quoi on viserait la V1. */
    @Test
    void theProjectNameReplacesTheModelsIdentifierAsANewVersion() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String auditId = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", PLAN.replace("id: bibliotheque_v2", "id: bibliotheque_du_hub")), "owner");
        answer(store.action(auditId).orElseThrow(), AUDIT_OK);
        String page = get(project).body();
        String recovered = location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=recover&id=" + id(project) + "&action_id=" + auditId));

        gesture(get(recovered).body(), "generate");

        AgentActionRow queued = latest("building.generate");
        assertTrue(queued.params().get("plan").startsWith("id: bibliotheque_v2\n"), queued.params().get("plan"));
        BuildingPlanDraft d = drafts().find(draftId(recovered)).orElseThrow();
        assertEquals(2, d.version(), "l'alignement est une nouvelle version, tracée");
        assertEquals(2, drafts().revisions(d.id()).size());
    }

    // ---- Versions, redémarrage --------------------------------------------------------------

    @Test
    void aStaleManualEditIsRefusedAndTheTypedTextIsKept() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        String tabA = get(withDraft).body();
        String tabB = get(withDraft).body();
        editPlan(tabA, "save", PLAN + "\n# onglet A");

        HttpResponse<String> late = editPlan(tabB, "audit", PLAN + "\n# onglet B");

        assertEquals(200, late.statusCode());
        assertTrue(late.body().contains("Vos modifications n&#39;ont pas été enregistrées")
                || late.body().contains("Vos modifications n'ont pas été enregistrées"));
        assertTrue(late.body().contains("# onglet B"));
        assertTrue(drafts().find(draftId(withDraft)).orElseThrow().plan().endsWith("# onglet A"));
    }

    @Test
    void aProjectAndItsVerificationSurviveAPanelRestart() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        gesture(get(withDraft).body(), "audit");
        answer(latest("building.plan.audit"), AUDIT_OK);

        restart();

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Enregistrer dans la bibliothèque"), main);
        assertEquals(1, aiCalls.get());
    }

    @Test
    void anImprovementIsANewVersionAndLeavesTheCurrentOneUntouched() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        gesture(get(withDraft).body(), "audit");
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(withDraft).body();
        String form = formAround(page, "value=\"correct\"");
        assertTrue(form.contains("Aucune entrée sur la façade avant."), "motif prérempli");

        String body = "_csrf=" + csrf(page) + "&_action=correct&id=" + id(withDraft) + "&draft="
                + draftId(withDraft) + "&version=1&provider=openai&request_token=" + token(form)
                + "&reason=" + enc("Ajouter une porte.");
        String next = location(post("/buildings/workshop/project", body));
        post("/buildings/workshop/project", body);

        assertEquals(2, aiCalls.get());
        assertNotEquals(draftId(withDraft), draftId(next));
        assertEquals(BuildingPlanDraft.Origin.AI_CORRECTION, drafts().find(draftId(next)).orElseThrow().origin());
        assertEquals(1, drafts().find(draftId(withDraft)).orElseThrow().version());
    }

    // ---- Reprendre l'existant -------------------------------------------------------------------

    /** Le cas réel : la description de buildsite_0009 et ses deux brouillons de la Bibliothèque. */
    @Test
    void aSiteBriefAndItsDraftsAreImportedWithoutTouchingTheSite() throws Exception {
        start();
        BuildingPlanDraftStore legacy = drafts();
        String d1 = legacy.create(new BuildingPlanDraftStore.NewDraft(TestConfig.AGENT_ID, "buildsite_0009",
                PLAN, BuildingPlanDraft.Origin.RECOVERED, "", "7a1ab38d", "", "", "", "", "", "", 19, 14, "owner")).id();
        String d2 = legacy.create(new BuildingPlanDraftStore.NewDraft(TestConfig.AGENT_ID, "buildsite_0009",
                PLAN, BuildingPlanDraft.Origin.AI_PROPOSAL, "", "", "openai", "OpenAI", "gpt-4o", "", "", "", 19, 14,
                "owner")).id();
        long actionsBefore = store.recentActions(TestConfig.AGENT_ID, 100).size();

        String index = get("/buildings/workshop").body();
        assertTrue(index.contains("Reprendre dans l&#39;Atelier") || index.contains("Reprendre dans l'Atelier"));
        assertTrue(index.contains("2 plan(s)"), excerpt(index));

        String imported = location(post("/buildings/workshop/import", "_csrf=" + csrf(index) + "&site=buildsite_0009"));
        assertTrue(imported.startsWith("/buildings/workshop/project?id="), imported);
        var project = new com.lodygames.rpgquest.panel.building.BuildingProjectStore(dbPath).find(id(imported)).orElseThrow();
        assertTrue(project.brief().contains("Bâtiment sur deux étages"), "description copiée telle quelle");
        assertEquals("WEST", project.front());
        assertEquals(project.id(), drafts().find(d1).orElseThrow().projectId());
        assertEquals(project.id(), drafts().find(d2).orElseThrow().projectId());
        assertEquals("buildsite_0009", drafts().find(d1).orElseThrow().siteId(), "l'origine reste notée");
        assertEquals(actionsBefore, store.recentActions(TestConfig.AGENT_ID, 100).size(),
                "aucune action envoyée au serveur : la fiche de l'emplacement n'est pas touchée");
        assertEquals(0, aiCalls.get());

        String again = location(post("/buildings/workshop/import", "_csrf=" + csrf(index) + "&site=buildsite_0009"));
        assertEquals(project.id(), id(again), "importer deux fois ne crée pas deux projets");
        assertFalse(get("/buildings/workshop").body().contains("name=\"site\" value=\"buildsite_0009\""));
    }

    @Test
    void oldGeneratorAddressesLeadToTheWorkshop() throws Exception {
        start();
        String before = location(get("/buildings/plan?site=buildsite_0009"));
        assertTrue(before.startsWith("/buildings/workshop?ok="), before);

        String index = get("/buildings/workshop").body();
        String imported = location(post("/buildings/workshop/import", "_csrf=" + csrf(index) + "&site=buildsite_0009"));
        assertEquals(id(imported), id(location(get("/buildings/plan?site=buildsite_0009"))));

        HttpResponse<String> forged = post("/buildings/plan", "_csrf=" + csrf(index) + "&_action=propose&site="
                + "buildsite_0009&provider=openai&request_token=x");
        assertEquals(303, forged.statusCode());
        assertEquals(0, aiCalls.get(), "un ancien formulaire ne déclenche rien");
    }

    @Test
    void aPlanIsRecoveredFromAnActionIntoAProjectWithoutCallingTheAi() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String auditId = store.createAction(TestConfig.AGENT_ID, "building.plan.audit", Map.of("plan", PLAN), "owner");
        answer(store.action(auditId).orElseThrow(), AUDIT_OK);

        String actionPage = get("/actions/" + auditId).body();
        assertTrue(actionPage.contains("Reprendre ce plan"), excerpt(actionPage));
        String recovered = location(post("/buildings/workshop/project", "_csrf=" + csrf(actionPage)
                + "&_action=recover&id=" + id(project) + "&action_id=" + auditId));

        String main = mainCard(get(recovered).body());
        assertTrue(main.contains("Enregistrer dans la bibliothèque"), "le verdict d'origine est repris : " + main);
        assertEquals(0, aiCalls.get());
        assertTrue(get("/actions/" + auditId).body().contains("Retour à ce brouillon"));
    }

    @Test
    void theDiagnosticIsCopyableAndCarriesNoSecret() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        String page = get(withDraft).body();
        String diag = field(page, "data-copy=\"([^\"]*)\"");
        assertTrue(diag.contains("Diagnostic Atelier PlugAdmin"), diag);
        assertTrue(diag.contains("bibliotheque_v2"), "le plan est inclus");
        assertFalse(diag.contains("sk-test"), "jamais la clé d'API");
    }

    // ---- Harnais -------------------------------------------------------------------------------

    private void start() throws Exception {
        ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.createContext("/v1/chat/completions", exchange -> {
            aiCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            message.put("content", "```yaml\n" + PLAN + "\n```");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("model", "gpt-4o");
            response.put("choices", List.of(Map.of("message", message)));
            response.put("usage", Map.of("prompt_tokens", 1200, "completion_tokens", 900));
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ai.start();
        dbPath = tmp.resolve("cp.db").toString();
        new AiSettingsStore(dbPath).save(new AiProviderSettings("openai", true, "sk-test",
                "http://127.0.0.1:" + ai.getAddress().getPort(), "", 4000, 5), "test");
        boot();
        seedSites();
    }

    private void boot() throws Exception {
        store = new AgentStore(dbPath);
        app = new PanelApp(TestConfig.withAgent(dbPath, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + enc(TestConfig.OWNER_PASSWORD) + "&_csrf=" + token);
    }

    /** Arrêt complet du panel puis nouvelle instance sur la même base : sessions perdues, données non. */
    private void restart() throws Exception {
        app.stop();
        app = null;
        boot();
    }

    private void seedSites() throws Exception {
        String id = store.createAction(TestConfig.AGENT_ID, "building.site.list", Map.of(), "owner");
        answer(store.action(id).orElseThrow(), SITES);
    }

    private void answer(AgentActionRow action, String details) {
        String status = details.contains("\"code\":\"OK\"") || details.contains("\"sites\"")
                ? "SUCCESS" : "FAILED";
        String message = details.contains("NO_ENTRANCE") ? "Aucune entrée sur la façade avant."
                : "ok";
        store.recordResult(action.id(), TestConfig.AGENT_ID, AgentActionStatus.valueOf(status),
                "", message, "{\"action_id\":\"" + action.id() + "\",\"status\":\"" + status
                        + "\",\"message\":\"" + message + "\",\"details\":" + details + "}",
                Instant.now());
    }

    private String createProject(String name, String brief) throws Exception {
        String page = get("/buildings/workshop/new").body();
        HttpResponse<String> res = post("/buildings/workshop/new", "_csrf=" + csrf(page) + "&name=" + enc(name)
                + "&brief=" + enc(brief) + "&front=WEST&max_width=19&max_height=14&building_id=bibliotheque_v2");
        assertEquals(303, res.statusCode(), res.body());
        return location(res);
    }

    private String propose(String project) throws Exception {
        String page = get(project).body();
        HttpResponse<String> res = post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=propose&id=" + id(project) + "&provider=openai&request_token=" + token(page));
        assertEquals(303, res.statusCode());
        return location(res);
    }

    /** Soumet un geste gratuit tel que la page le rend (formulaire de l'action principale). */
    private HttpResponse<String> gesture(String page, String action) throws Exception {
        String form = formAround(page, "name=\"_action\" value=\"" + action + "\"");
        return post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=" + action
                + "&id=" + field(form, "name=\"id\" value=\"([^\"]+)\"")
                + "&draft=" + field(form, "name=\"draft\" value=\"([^\"]+)\"")
                + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&plan=" + enc(unescape(field(form, "name=\"plan\" value=\"([^\"]*)\""))));
    }

    /** Soumet l'éditeur du plan brut (détails techniques) avec un texte donné. */
    private HttpResponse<String> editPlan(String page, String action, String plan) throws Exception {
        String form = formAround(page, "id=\"ws-plan\"");
        return post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=" + action
                + "&id=" + field(form, "name=\"id\" value=\"([^\"]+)\"")
                + "&draft=" + field(form, "name=\"draft\" value=\"([^\"]+)\"")
                + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&plan=" + enc(plan));
    }

    /** La carte de l'action principale : ce qu'un utilisateur lit en premier. */
    private static String mainCard(String page) {
        int start = page.indexOf("ws-main");
        int end = page.indexOf("Le bâtiment souhaité", start);
        int preview = page.indexOf("ws-preview", start);
        if (preview > start && preview < end) {
            end = preview;
        }
        assertTrue(start > 0 && end > start, "carte principale introuvable");
        return page.substring(start, end);
    }

    private static String id(String location) {
        return field(location, "[?&]id=([^&#]+)");
    }

    private BuildingPlanDraftStore drafts() {
        return new BuildingPlanDraftStore(dbPath);
    }

    private AgentActionRow latest(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private static String draftId(String location) {
        return field(location, "[?&]draft=([^&#]+)");
    }

    private static String location(HttpResponse<String> res) {
        return res.headers().firstValue("Location").orElse("");
    }

    private static String decoded(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String token(String html) {
        return field(html, "name=\"request_token\" value=\"([^\"]+)\"");
    }

    private static String field(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("introuvable : " + regex + " dans " + excerpt(text));
        }
        return m.group(1);
    }

    private static String formAround(String page, String needle) {
        int at = page.indexOf(needle);
        assertTrue(at > 0, "marqueur introuvable : " + needle + " — " + excerpt(page));
        int open = page.lastIndexOf("<form", at);
        int close = page.indexOf("</form>", at);
        return page.substring(open, close);
    }

    private static String unescape(String raw) {
        return raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
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
        return send(HttpRequest.newBuilder(uri(p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String p) {
        int fragment = p.indexOf('#');
        return URI.create("http://127.0.0.1:" + port + (fragment >= 0 ? p.substring(0, fragment) : p));
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
