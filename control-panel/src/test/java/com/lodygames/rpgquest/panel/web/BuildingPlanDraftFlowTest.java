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
 * Issue #256 — le parcours complet de génération sur un vrai serveur HTTP, avec un fournisseur
 * d'IA bouchonné qui <strong>compte</strong> ses appels.
 *
 * <p>Le compteur est l'assertion centrale : chaque test vérifie qu'aucun geste de navigation —
 * reprendre, auditer, prévisualiser, recharger, revenir, redémarrer le panel, récupérer — ne coûte
 * un appel.</p>
 */
class BuildingPlanDraftFlowTest {

    private static final String SITE = "buildsite_0009";

    /** Le relevé réel de l'emplacement, brief compris (« sur deux étages »). */
    private static final String SITES = "{\"total\":1,\"worlds\":[\"world_hub\"],\"sites\":[{"
            + "\"id\":\"buildsite_0009\",\"name\":\"Bibliothèque du Hub\",\"description\":\"\","
            + "\"aiBrief\":\"Bibliothèque médiévale fantastique. Bâtiment sur deux étages, pierre "
            + "claire et bois sombre, façade à l'ouest. Grande salle de lecture, escalier "
            + "intérieur.\",\"world\":\"world_hub\",\"x\":784,\"y\":67,\"z\":-693,"
            + "\"facing\":\"WEST\",\"status\":\"EMPTY\",\"createdBy\":\"Lody\","
            + "\"createdAt\":\"2026-10-11T18:12:06Z\",\"worldLoaded\":true}]}";

    private static final String PLAN = "id: bibliotheque_du_hub\nname: \"Bibliothèque du Hub\"\n"
            + "kind: habitable\nfront: WEST\nsize:\n  x: 19\n  y: 14\n  z: 19\nsteps:\n"
            + "  - op: box\n    from: [0, 0, 0]\n    to: [18, 0, 18]\n    block: minecraft:stone_bricks";

    /** Les détails d'audit tels que le plugin les a rendus pour l'action 7a1ab38d. */
    private static final String AUDIT_OK = "{\"written\":false,\"sizeX\":19,\"sizeY\":14,"
            + "\"sizeZ\":19,\"warnings\":[],\"metrics\":{\"reachableFloors\":1,\"lightSources\":80,"
            + "\"envelopeMaterials\":5,\"solidCells\":2107,\"glazedCells\":22,"
            + "\"topMaterial\":\"stone_bricks\",\"floors\":1,\"glazedFacades\":3,"
            + "\"topMaterialShare\":0.69,\"furnishingCells\":272,\"openEnvelopeCellsHigh\":0},"
            + "\"kind\":\"bâtiment habitable\",\"code\":\"OK\",\"blocks\":2107,"
            + "\"buildingId\":\"bibliotheque_du_hub\",\"codes\":[],\"exists\":true,"
            + "\"message\":\"Plan conforme au contrat bâtiment habitable : 2107 blocs.\"}";

    private static final String AUDIT_REFUSED = "{\"code\":\"NO_ENTRANCE\",\"codes\":[\"NO_ENTRANCE\"],"
            + "\"message\":\"Aucune entrée sur la façade avant.\",\"buildingId\":\"bibliotheque_du_hub\","
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

    // ---- Reprise et absence d'appel ------------------------------------------------------------

    @Test
    void aPaidPlanBecomesADraftAndNoNavigationCallsTheAiAgain() throws Exception {
        start();
        String draftUrl = propose();
        assertEquals(1, aiCalls.get());

        String page = get(draftUrl).body();
        assertTrue(page.contains("Plan en cours — version 1"), excerpt(page));
        assertTrue(page.contains("bibliotheque_du_hub"), "le plan est rendu");
        assertTrue(page.contains("Proposé par l&#39;IA"), "son origine est dite");
        assertTrue(page.contains("value=\"audit\"") && page.contains("value=\"preview\"")
                && page.contains("value=\"generate\"") && page.contains("value=\"correct\""),
                "les gestes Auditer, Prévisualiser, Générer et Corriger sont proposés");

        // Recharger, revenir à l'emplacement, rouvrir : aucun appel.
        get(draftUrl);
        String sitePage = get("/buildings/plan?site=" + SITE).body();
        assertTrue(sitePage.contains("Reprendre ce plan"), "le plan est repris d'un clic");
        assertTrue(sitePage.contains("draft=" + draftId(draftUrl)), "par l'adresse du brouillon");
        assertEquals(1, aiCalls.get(), "aucune navigation ne rappelle le modèle");
    }

    /** Double clic, « renvoyer le formulaire » : le même jeton ne paie qu'une fois. */
    @Test
    void resubmittingTheSamePaidFormNeverCallsTwice() throws Exception {
        start();
        String token = token(get("/buildings/plan?site=" + SITE).body());
        HttpResponse<String> first = postPropose(token);
        HttpResponse<String> second = postPropose(token);

        assertEquals(1, aiCalls.get(), "un seul appel pour deux soumissions");
        assertEquals(303, second.statusCode());
        assertEquals(draftId(location(first)), draftId(location(second)),
                "la seconde soumission mène au plan déjà obtenu");
        assertTrue(decoded(location(second)).contains("Aucun nouvel appel"));
        assertEquals(1, drafts().forSite(TestConfig.AGENT_ID, SITE).size(), "un seul brouillon");
    }

    @Test
    void aPaidFormWithoutTokenIsRefusedWithoutCalling() throws Exception {
        start();
        String csrf = csrf(get("/buildings/plan?site=" + SITE).body());
        post("/buildings/plan", "_csrf=" + csrf + "&_action=propose&site=" + SITE
                + "&provider=openai&max_width=19&max_height=14");
        assertEquals(0, aiCalls.get());
    }

    // ---- Audit dans le parcours ----------------------------------------------------------------

    /** Le défaut signalé : auditer quittait le parcours. Le retour se fait au brouillon. */
    @Test
    void auditingReturnsToTheDraftAndShowsItsOwnVerdict() throws Exception {
        start();
        String draftUrl = propose();
        String page = get(draftUrl).body();

        HttpResponse<String> res = gesture(page, "audit", null);

        assertEquals(303, res.statusCode());
        assertTrue(location(res).startsWith("/buildings/plan?site=" + SITE + "&draft="),
                "retour au brouillon, pas à /agents ni à /actions : " + location(res));
        AgentActionRow queued = latest("building.plan.audit");
        assertEquals(PLAN, queued.params().get("plan"), "c'est le plan du brouillon qui part");

        assertTrue(get(draftUrl).body().contains("est en attente de la réponse du serveur"), "audit en cours, dit");

        answer(queued, AUDIT_OK);
        String after = get(draftUrl).body();
        assertTrue(after.contains("Plan conforme"), excerpt(after));
        assertTrue(after.contains(queued.id()), "le verdict nomme son action");
        assertEquals(1, aiCalls.get());
    }

    /** Le second défaut signalé : un seul niveau mesuré pour un brief qui en exige deux. */
    @Test
    void aConformingPlanMissingABriefLevelIsFlagged() throws Exception {
        start();
        String draftUrl = propose();
        gesture(get(draftUrl).body(), "audit", null);
        answer(latest("building.plan.audit"), AUDIT_OK);

        String page = get(draftUrl).body();
        assertTrue(page.contains("mais en écart avec le brief"), excerpt(page));
        assertTrue(page.contains("le brief demande 2 niveaux"), "l'écart est chiffré");
        assertFalse(page.contains("alert-success"), "pas de carte verte pour un écart au brief");
        // Le motif de correction prérempli porte l'écart : c'est lui que le modèle doit corriger.
        String correction = formAround(page, "value=\"correct\"");
        assertTrue(correction.contains("le brief demande 2 niveaux"), correction);
        // L'identifiant existe déjà (V1) : l'écran dit comment ne pas l'écraser.
        assertTrue(page.contains("existe déjà dans la"), "conflit d'identifiant signalé");
        assertTrue(page.contains("bibliotheque_du_hub_v2"), "avec l'identifiant à proposer");
    }

    @Test
    void anotherPlanAuditedMeanwhileDoesNotReplaceThisDraftsVerdict() throws Exception {
        start();
        String draftUrl = propose();
        gesture(get(draftUrl).body(), "audit", null);
        answer(latest("building.plan.audit"), AUDIT_OK);

        // Un autre audit, d'un autre plan, arrive après : avant #256, c'est lui qui s'affichait.
        String other = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", "id: autre\n"), "owner");
        answer(store.action(other).orElseThrow(), AUDIT_REFUSED);

        String page = get(draftUrl).body();
        assertTrue(page.contains("Plan conforme"), "le verdict de CE brouillon reste affiché");
        assertFalse(page.contains("NO_ENTRANCE"), "celui de l'autre plan n'apparaît pas");
    }

    @Test
    void previewingReturnsToTheDraftAndShowsTheSlices() throws Exception {
        start();
        String draftUrl = propose();
        HttpResponse<String> res = gesture(get(draftUrl).body(), "preview", null);
        assertTrue(location(res).contains("&draft="), location(res));

        answer(latest("building.plan.preview"), "{\"code\":\"OK\",\"sizeX\":3,\"sizeY\":2,"
                + "\"sizeZ\":3,\"blocks\":9,\"front\":\"WEST\",\"plans\":[{\"id\":\"y0\","
                + "\"label\":\"Niveau y = 0\",\"axes\":\"x →\",\"rows\":[\"###\",\"#.#\",\"###\"]}],"
                + "\"elevations\":[]}");

        String page = get(draftUrl).body();
        assertTrue(page.contains("Aperçu 2D"), excerpt(page));
        assertTrue(page.contains("Niveau y = 0"), "les coupes du brouillon sont rendues");
        assertEquals(1, aiCalls.get());
    }

    // ---- Versions et concurrence ---------------------------------------------------------------

    @Test
    void editingCreatesANewVersionAndTheOldVerdictIsMarkedStale() throws Exception {
        start();
        String draftUrl = propose();
        gesture(get(draftUrl).body(), "audit", null);
        answer(latest("building.plan.audit"), AUDIT_OK);

        gesture(get(draftUrl).body(), "save", PLAN.replace("bibliotheque_du_hub", "bibliotheque_v2"));

        String page = get(draftUrl).body();
        assertTrue(page.contains("Plan en cours — version 2"), excerpt(page));
        assertTrue(page.contains("porte sur la <strong>version 1</strong>"),
                "l'ancien verdict ne passe pas pour celui du plan modifié");
        assertTrue(page.contains("Versions précédentes (1)"), "la version 1 reste consultable");
    }

    /** Deux onglets : le texte soumis sur une version périmée n'écrase rien et n'est pas perdu. */
    @Test
    void aStaleEditIsRefusedAndTheTypedTextIsKept() throws Exception {
        start();
        String draftUrl = propose();
        String tabA = get(draftUrl).body();
        String tabB = get(draftUrl).body();

        gesture(tabA, "save", PLAN + "\n# onglet A");
        HttpResponse<String> late = gesture(tabB, "audit", PLAN + "\n# onglet B");

        assertEquals(200, late.statusCode(), "pas de redirection : le texte serait perdu");
        assertTrue(late.body().contains("Vos modifications n'ont pas été enregistrées"));
        assertTrue(late.body().contains("# onglet B"), "le texte refusé est rendu");
        BuildingPlanDraft now = drafts().find(draftId(draftUrl)).orElseThrow();
        assertEquals(2, now.version());
        assertTrue(now.plan().endsWith("# onglet A"), "la version la plus récente reste en place");
        assertFalse(store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .anyMatch(a -> a.type().equals("building.plan.audit")),
                "et rien n'a été envoyé au serveur");
    }

    @Test
    void anEditedPlanIsSavedBeforeBeingAudited() throws Exception {
        start();
        String draftUrl = propose();
        String edited = PLAN + "\n  - op: door\n    facade: FRONT\n    offset: 9";

        gesture(get(draftUrl).body(), "audit", edited);

        assertEquals(edited, latest("building.plan.audit").params().get("plan"),
                "on audite ce qu'on a sous les yeux");
        assertEquals(2, drafts().find(draftId(draftUrl)).orElseThrow().version(),
                "et ce qu'on a audité est conservé");
    }

    // ---- Redémarrage -----------------------------------------------------------------------------

    @Test
    void aDraftAndItsVerdictSurviveAPanelRestart() throws Exception {
        start();
        String draftUrl = propose();
        gesture(get(draftUrl).body(), "audit", null);
        answer(latest("building.plan.audit"), AUDIT_OK);

        restart();

        String page = get(draftUrl).body();
        assertTrue(page.contains("Plan en cours — version 1"), excerpt(page));
        assertTrue(page.contains("bibliotheque_du_hub"), "le plan payé est toujours là");
        assertTrue(page.contains("Plan conforme"), "son verdict aussi");
        assertEquals(1, aiCalls.get(), "sans rien redemander");
    }

    // ---- Récupération depuis une action existante ----------------------------------------------

    /** Le cas réel : l'action d'audit 7a1ab38d porte le plan payé, sans brouillon. */
    @Test
    void aPlanIsRecoveredFromAnExistingAuditWithoutCallingTheAi() throws Exception {
        start();
        String auditId = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", PLAN), "owner");
        answer(store.action(auditId).orElseThrow(), AUDIT_OK);

        // La fiche d'action propose désormais le retour au parcours.
        String actionPage = get("/actions/" + auditId).body();
        assertTrue(actionPage.contains("Reprendre ce plan"), excerpt(actionPage));
        assertTrue(actionPage.contains("n'appelle pas l'IA"));

        String csrf = csrf(actionPage);
        HttpResponse<String> res = post("/buildings/plan", "_csrf=" + csrf + "&_action=recover&site="
                + SITE + "&action_id=" + auditId);
        String draftUrl = location(res);
        assertTrue(draftUrl.contains("&draft="), draftUrl);

        String page = get(draftUrl).body();
        assertTrue(page.contains("Récupéré depuis une action"), excerpt(page));
        assertTrue(page.contains("Plan conforme"), "le verdict de l'audit d'origine est repris");
        assertTrue(page.contains("le brief demande 2 niveaux"), "avec l'écart au brief");
        assertEquals(0, aiCalls.get(), "aucun appel à l'IA");

        // Idempotent, et la fiche d'action mène désormais au brouillon.
        assertEquals(draftId(draftUrl), draftId(location(post("/buildings/plan", "_csrf=" + csrf
                + "&_action=recover&site=" + SITE + "&action_id=" + auditId))));
        String backLink = get("/actions/" + auditId).body();
        assertTrue(backLink.contains("Retour à ce brouillon"), excerpt(backLink));
        assertTrue(backLink.contains("draft=" + draftId(draftUrl)));
    }

    @Test
    void recoverableActionsAreListedOnTheSitePage() throws Exception {
        start();
        String auditId = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", PLAN), "owner");
        answer(store.action(auditId).orElseThrow(), AUDIT_OK);

        String page = get("/buildings/plan?site=" + SITE).body();
        assertTrue(page.contains("name=\"action_id\" value=\"" + auditId + "\""), excerpt(page));

        post("/buildings/plan", "_csrf=" + csrf(page) + "&_action=recover&site=" + SITE
                + "&action_id=" + auditId);
        assertFalse(get("/buildings/plan?site=" + SITE).body()
                        .contains("name=\"action_id\" value=\"" + auditId + "\""),
                "une fois repris, il n'est plus proposé deux fois");
    }

    @Test
    void anActionWithoutPlanCannotBeRecovered() throws Exception {
        start();
        String csrf = csrf(get("/buildings/plan?site=" + SITE).body());
        HttpResponse<String> res = post("/buildings/plan", "_csrf=" + csrf
                + "&_action=recover&site=" + SITE + "&action_id=inexistante");
        assertTrue(decoded(location(res)).contains("rien n'a été récupéré"), location(res));
        assertTrue(drafts().forSite(TestConfig.AGENT_ID, SITE).isEmpty());
    }

    // ---- Génération et correction ----------------------------------------------------------------

    @Test
    void generatingSendsTheDraftPlanAndNeverReplacesByDefault() throws Exception {
        start();
        String draftUrl = propose();

        HttpResponse<String> res = gesture(get(draftUrl).body(), "generate", null);

        assertTrue(location(res).contains("&draft="), location(res));
        AgentActionRow queued = latest("building.generate");
        assertEquals(PLAN, queued.params().get("plan"));
        assertFalse(queued.params().containsKey("replace"), "le remplacement n'est jamais implicite");
        assertTrue(get(draftUrl).body().contains("La génération est en attente de la réponse du serveur"),
                "la génération en cours est montrée dans le parcours");
    }

    @Test
    void aCorrectionIsANewDraftAndLeavesTheCorrectedOneUntouched() throws Exception {
        start();
        String draftUrl = propose();
        gesture(get(draftUrl).body(), "audit", null);
        answer(latest("building.plan.audit"), AUDIT_REFUSED);

        String page = get(draftUrl).body();
        String form = formAround(page, "value=\"correct\"");
        assertTrue(form.contains("Aucune entrée sur la façade avant."), "motif prérempli");
        String body = "_csrf=" + csrf(page) + "&_action=correct&site=" + SITE + "&draft="
                + draftId(draftUrl) + "&version=1&provider=openai&max_width=19&max_height=14"
                + "&request_token=" + token(form) + "&reason=" + enc("Aucune entrée.");
        HttpResponse<String> res = post("/buildings/plan", body);
        post("/buildings/plan", body); // double clic

        assertEquals(2, aiCalls.get(), "une proposition + une correction, pas une de plus");
        String corrected = draftId(location(res));
        assertNotEquals(draftId(draftUrl), corrected);
        BuildingPlanDraft fresh = drafts().find(corrected).orElseThrow();
        assertEquals(draftId(draftUrl), fresh.parentId());
        assertEquals(BuildingPlanDraft.Origin.AI_CORRECTION, fresh.origin());
        assertEquals(1, drafts().find(draftId(draftUrl)).orElseThrow().version(),
                "le brouillon corrigé n'est pas touché");
    }

    @Test
    void archivingRemovesTheDraftFromTheListWithoutCalling() throws Exception {
        start();
        String draftUrl = propose();
        String page = get(draftUrl).body();
        post("/buildings/plan", "_csrf=" + csrf(page) + "&_action=archive&site=" + SITE
                + "&draft=" + draftId(draftUrl));

        assertFalse(get("/buildings/plan?site=" + SITE).body().contains("Reprendre ce plan</a>"));
        assertTrue(drafts().find(draftId(draftUrl)).orElseThrow().archived(), "archivé, pas effacé");
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

    /** Demande un plan depuis la page fraîche, et renvoie l'adresse du brouillon obtenu. */
    private String propose() throws Exception {
        String token = token(get("/buildings/plan?site=" + SITE).body());
        HttpResponse<String> res = postPropose(token);
        assertEquals(303, res.statusCode(), res.body());
        return location(res);
    }

    private HttpResponse<String> postPropose(String token) throws Exception {
        String csrf = csrf(get("/buildings/plan?site=" + SITE).body());
        return post("/buildings/plan", "_csrf=" + csrf + "&_action=propose&site=" + SITE
                + "&provider=openai&max_width=19&max_height=14&request_token=" + token);
    }

    /** Soumet le formulaire du plan avec un geste, et le texte donné (ou celui de la page). */
    private HttpResponse<String> gesture(String page, String action, String plan) throws Exception {
        String form = formAround(page, "value=\"" + action + "\"");
        String text = plan != null ? plan : unescape(field(form, "<textarea id=\"bp-plan\"[^>]*>(.*?)</textarea>"));
        return post("/buildings/plan", "_csrf=" + csrf(page) + "&_action=" + action
                + "&site=" + SITE + "&draft=" + field(form, "name=\"draft\" value=\"([^\"]+)\"")
                + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&plan=" + enc(text));
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
