package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #226 — la page {@code /npcs/delete} sur un vrai serveur HTTP : permissions, CSRF,
 * confirmation tapée, URL forgée, et ce qui part réellement vers l'agent.
 *
 * <p><strong>Le PNJ supprimé dans ces tests est un PNJ de test</strong>, jamais un PNJ de gameplay.
 * Mira est présente dans le relevé — c'est le cas réel qu'il faut couvrir — mais uniquement comme
 * <em>contexte</em> : aucun test ne demande sa suppression.</p>
 */
class NpcDeletionPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    /**
     * Relevé {@code npc.list} réaliste : le PNJ de test (défini, Citizens #42, dialogue du même
     * nom), Mira la Cartographe (définie, Citizens #9, dialogue nommé autrement — le cas #225), et
     * un PNJ de test référencé par une quête de remise, qui doit bloquer.
     */
    private static final String NPC_DETAILS = "{"
            + "\"citizensAvailable\":true,\"total\":3,\"withDefinition\":3,\"withoutDefinition\":0,"
            + "\"bound\":2,\"withWarnings\":0,"
            + "\"definedIds\":[\"mira_cartographer\",\"pnj_de_test\",\"pnj_livraison\"],"
            + "\"canonicalIds\":[\"mira_cartographer\",\"pnj_de_test\",\"pnj_livraison\"],"
            + "\"npcs\":["
            + "{\"id\":\"pnj_de_test\",\"displayName\":\"PNJ de test\",\"logicalDefinitionPresent\":true,"
            + "\"citizensBindingPresent\":true,\"citizensNumericId\":42,\"bindingCount\":1,\"enabled\":true,"
            + "\"description\":null,\"role\":null,\"definedDialogueId\":\"rpgquest:pnj_de_test\","
            + "\"hasDialogue\":true,\"dialogueId\":\"rpgquest:pnj_de_test\",\"dialogueNodes\":2,"
            + "\"dialogueChoices\":2,\"dialogueStartsQuests\":[],\"questsGiven\":[],"
            + "\"questsReferenced\":[],\"questsDelivering\":[],"
            + "\"sources\":[\"DEFINITION\",\"BINDING\",\"DIALOGUE\"],\"state\":\"LINKED\",\"warnings\":[]},"
            + "{\"id\":\"mira_cartographer\",\"displayName\":\"Mira la Cartographe\","
            + "\"logicalDefinitionPresent\":true,\"citizensBindingPresent\":true,\"citizensNumericId\":9,"
            + "\"bindingCount\":1,\"enabled\":true,\"description\":null,\"role\":null,"
            + "\"definedDialogueId\":\"rpgquest:mira_first_map\",\"hasDialogue\":true,"
            + "\"dialogueId\":\"rpgquest:mira_first_map\",\"dialogueNodes\":2,\"dialogueChoices\":3,"
            + "\"dialogueStartsQuests\":[],\"questsGiven\":[],\"questsReferenced\":[],"
            + "\"questsDelivering\":[],\"sources\":[\"DEFINITION\",\"BINDING\"],\"state\":\"LINKED\","
            + "\"warnings\":[]},"
            + "{\"id\":\"pnj_livraison\",\"displayName\":\"PNJ de test (remise)\","
            + "\"logicalDefinitionPresent\":true,\"citizensBindingPresent\":false,"
            + "\"citizensNumericId\":null,\"bindingCount\":0,\"enabled\":true,\"description\":null,"
            + "\"role\":null,\"definedDialogueId\":null,\"hasDialogue\":false,\"dialogueId\":null,"
            + "\"dialogueNodes\":0,\"dialogueChoices\":0,\"dialogueStartsQuests\":[],"
            + "\"questsGiven\":[],\"questsReferenced\":[],"
            + "\"questsDelivering\":[\"rpgquest:test_remise\"],"
            + "\"sources\":[\"DEFINITION\",\"QUEST_DELIVER\"],\"state\":\"NOT_LINKED\",\"warnings\":[]}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- Aperçu --------------------------------------------------------------------------------

    /** Sans relevé, aucune opération n'est offerte : on n'a pas regardé, on ne conclut pas. */
    @Test
    void withoutASurveyThePageOffersNothing() throws Exception {
        start();

        String page = get("/npcs/delete?npc=pnj_de_test").body();

        assertTrue(page.contains("Aucun relev"), page.substring(0, Math.min(1500, page.length())));
        assertFalse(page.contains("action=\"/npcs/delete\""),
                "aucun formulaire ne doit exister sans relevé");
    }

    @Test
    void thePreviewListsEveryLayerPresentAndAbsent() throws Exception {
        start();
        seedNpcList();

        String page = get("/npcs/delete?npc=pnj_de_test").body();

        // Http.esc n'échappe que & < > " ' : les accents traversent tels quels en UTF-8.
        for (String layer : List.of("Définition logique RPGQuest", "Liaison RPGQuest",
                "PNJ Citizens physique", "Dialogue lié", "Remises")) {
            assertTrue(page.contains(layer), "couche absente de l'aperçu : " + layer);
        }
        assertTrue(page.contains("présent") && page.contains("absent"),
                "les couches présentes ET absentes doivent être montrées");
        assertTrue(page.contains("#42"), "l'identifiant Citizens doit être affiché");
    }

    /** Les quatre opérations du ticket sont proposées, et le dialogue est déclaré intouchable. */
    @Test
    void theFourOperationsAreOfferedAndTheDialogueIsNeverDeleted() throws Exception {
        start();
        seedNpcList();

        String page = get("/npcs/delete?npc=pnj_de_test").body();

        assertTrue(page.contains("value=\"DEFINITION_ONLY\""));
        assertTrue(page.contains("value=\"UNLINK_CITIZENS\""));
        assertTrue(page.contains("value=\"DELETE_CITIZENS\""));
        assertTrue(page.contains("value=\"FULL_CLEANUP\""));
        assertTrue(page.contains("NE supprime PAS le dialogue"), "la garantie doit être écrite");
    }

    /** Une dépendance de remise bloque, et le formulaire correspondant n'existe pas. */
    @Test
    void aBlockingDeliveryReferenceRemovesTheForm() throws Exception {
        start();
        seedNpcList();

        String page = get("/npcs/delete?npc=pnj_livraison").body();

        assertTrue(page.contains("rpgquest:test_remise"), "la quête bloquante doit être nommée");
        assertTrue(page.contains("infinissables"), page.contains("rpgquest:test_remise") + "");
        assertFalse(page.contains("value=\"DEFINITION_ONLY\""),
                "une opération bloquée n'a pas de formulaire : il n'y a rien à cliquer");
        assertFalse(page.contains("value=\"FULL_CLEANUP\""));
    }

    // ---- Confirmation --------------------------------------------------------------------------

    @Test
    void aPostWithoutAValidCsrfTokenIsRefused() throws Exception {
        start();
        seedNpcList();

        assertEquals(403, post("/npcs/delete",
                "npc=pnj_de_test&op=DEFINITION_ONLY&confirm_id=pnj_de_test&_csrf=faux").statusCode());
        assertEquals(0, queuedOfType("npc.definition.delete"));
    }

    @Test
    void aWrongTypedIdChangesNothing() throws Exception {
        start();
        seedNpcList();

        String page = post("/npcs/delete", "npc=pnj_de_test&op=DEFINITION_ONLY"
                + "&confirm_id=pnj_de_tes&_csrf=" + csrfOfDeletePage("pnj_de_test")).body();

        assertTrue(page.contains("Confirmation incorrecte"),
                page.substring(0, Math.min(1500, page.length())));
        assertEquals(0, queuedOfType("npc.definition.delete"));
    }

    /** Toucher au PNJ Citizens exige de retaper SON identifiant, pas seulement celui du PNJ. */
    @Test
    void aCitizensOperationAlsoRequiresTheNumericIdToBeRetyped() throws Exception {
        start();
        seedNpcList();

        String missing = post("/npcs/delete", "npc=pnj_de_test&op=DELETE_CITIZENS"
                + "&confirm_id=pnj_de_test&_csrf=" + csrfOfDeletePage("pnj_de_test")).body();
        assertTrue(missing.contains("Confirmation incorrecte"), "sans l'id Citizens");
        assertEquals(0, queuedOfType("npc.citizens.delete"));

        String wrong = post("/npcs/delete", "npc=pnj_de_test&op=DELETE_CITIZENS"
                + "&confirm_id=pnj_de_test&confirm_citizens=9&_csrf="
                + csrfOfDeletePage("pnj_de_test")).body();
        assertTrue(wrong.contains("Confirmation incorrecte"),
                "un AUTRE identifiant Citizens ne doit jamais passer — c'est le voisin");
        assertEquals(0, queuedOfType("npc.citizens.delete"));
    }

    /**
     * Une URL forgée vers une opération que l'aperçu a bloquée est refusée : la décision se prend
     * sur le plan recalculé, jamais sur ce que le formulaire prétend.
     */
    @Test
    void aForgedRequestForABlockedOperationIsRefused() throws Exception {
        start();
        seedNpcList();

        String page = post("/npcs/delete", "npc=pnj_livraison&op=DEFINITION_ONLY"
                + "&confirm_id=pnj_livraison&_csrf=" + csrfOfDeletePage("pnj_livraison")).body();

        assertTrue(page.contains("pas disponible"), page.substring(0, Math.min(1500, page.length())));
        assertEquals(0, queuedOfType("npc.definition.delete"));
    }

    @Test
    void anUnknownOperationIsRefused() throws Exception {
        start();
        seedNpcList();

        String page = post("/npcs/delete", "npc=pnj_de_test&op=TOUT_CASSER"
                + "&confirm_id=pnj_de_test&_csrf=" + csrfOfDeletePage("pnj_de_test")).body();

        assertTrue(page.contains("Opération inconnue"),
                page.substring(0, Math.min(1200, page.length())));
    }

    // ---- Ce qui part vraiment ------------------------------------------------------------------

    @Test
    void deletingTheDefinitionQueuesOnlyThatAction() throws Exception {
        start();
        seedNpcList();

        String page = post("/npcs/delete", "npc=pnj_de_test&op=DEFINITION_ONLY"
                + "&confirm_id=pnj_de_test&_csrf=" + csrfOfDeletePage("pnj_de_test")).body();

        assertTrue(page.contains("npc.definition.delete"), "l'action demandée est annoncée");
        assertEquals(1, queuedOfType("npc.definition.delete"));
        assertEquals(0, queuedOfType("npc.citizens.delete"), "aucune cascade");
        assertEquals(0, queuedOfType("npc.citizens.unlink"), "aucune cascade");
        // Ce que l'écran croyait vrai voyage avec la demande.
        assertEquals("rpgquest:pnj_de_test",
                queuedAction("npc.definition.delete").params().get("expect_dialogue"));
    }

    @Test
    void unlinkingQueuesOnlyTheUnlink() throws Exception {
        start();
        seedNpcList();

        post("/npcs/delete", "npc=pnj_de_test&op=UNLINK_CITIZENS&confirm_id=pnj_de_test"
                + "&confirm_citizens=42&_csrf=" + csrfOfDeletePage("pnj_de_test"));

        assertEquals(1, queuedOfType("npc.citizens.unlink"));
        assertEquals(0, queuedOfType("npc.definition.delete"), "la définition est conservée");
        assertEquals("42", queuedAction("npc.citizens.unlink").params().get("citizens_id"));
    }

    @Test
    void deletingTheCitizensQueuesOnlyThatAndKeepsTheDefinition() throws Exception {
        start();
        seedNpcList();

        post("/npcs/delete", "npc=pnj_de_test&op=DELETE_CITIZENS&confirm_id=pnj_de_test"
                + "&confirm_citizens=42&_csrf=" + csrfOfDeletePage("pnj_de_test"));

        assertEquals(1, queuedOfType("npc.citizens.delete"));
        assertEquals(0, queuedOfType("npc.definition.delete"));
        assertEquals("42", queuedAction("npc.citizens.delete").params().get("citizens_id"));
    }

    /** Le nettoyage complet enchaîne les deux, et seulement les deux : jamais le dialogue. */
    @Test
    void theFullCleanupQueuesCitizensThenDefinitionAndNothingElse() throws Exception {
        start();
        seedNpcList();

        String page = post("/npcs/delete", "npc=pnj_de_test&op=FULL_CLEANUP"
                + "&confirm_id=pnj_de_test&confirm_citizens=42&_csrf="
                + csrfOfDeletePage("pnj_de_test")).body();

        assertEquals(1, queuedOfType("npc.citizens.delete"));
        assertEquals(1, queuedOfType("npc.definition.delete"));
        assertEquals(0, queuedOfType("npc.citizens.unlink"));
        assertTrue(page.contains("rpgquest:pnj_de_test") && page.contains("pas été touché"),
                "la page de résultat doit dire que le dialogue est intact");
    }

    /**
     * Double clic : la seconde demande repart, et c'est voulu — les actions sont idempotentes côté
     * serveur ({@code ABSENT} / {@code MISMATCH}). Ce qui compte est qu'aucune des deux ne touche
     * une autre couche que celle demandée.
     */
    @Test
    void aDoubleClickQueuesTheSameHarmlessActionTwice() throws Exception {
        start();
        seedNpcList();
        String token = csrfOfDeletePage("pnj_de_test");

        String body = "npc=pnj_de_test&op=DEFINITION_ONLY&confirm_id=pnj_de_test&_csrf=" + token;
        post("/npcs/delete", body);
        post("/npcs/delete", body);

        assertEquals(2, queuedOfType("npc.definition.delete"));
        assertEquals(0, queuedOfType("npc.citizens.delete"));
    }

    // ---- Permissions ---------------------------------------------------------------------------

    /**
     * La permission est <strong>dédiée</strong> : écrire un PNJ ne donne pas le droit de le
     * supprimer, et un rôle de lecture n'atteint même pas la page.
     */
    @Test
    void deletingIsADedicatedPermission() {
        var admin = com.lodygames.rpgquest.panel.authz.Role.ADMIN;
        var editor = com.lodygames.rpgquest.panel.authz.Role.CONTENT_EDITOR;
        var builder = com.lodygames.rpgquest.panel.authz.Role.BUILDER;
        var tester = com.lodygames.rpgquest.panel.authz.Role.TESTER;
        var readOnly = com.lodygames.rpgquest.panel.authz.Role.READ_ONLY;
        var p = com.lodygames.rpgquest.panel.authz.Permission.NPC_DELETE;

        assertTrue(admin.permissions().contains(p));
        assertFalse(editor.permissions().contains(p), "un éditeur de contenu ne supprime pas de PNJ");
        assertFalse(builder.permissions().contains(p));
        assertFalse(tester.permissions().contains(p));
        assertFalse(readOnly.permissions().contains(p));
        assertTrue(com.lodygames.rpgquest.panel.authz.Role.OWNER.permissions().contains(p));
    }

    /** Le lien « Supprimer… » n'apparaît sur la fiche que si le rôle le permet. */
    @Test
    void theDangerZoneAppearsOnTheNpcCardForAnAllowedRole() throws Exception {
        start();
        seedNpcList();

        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("/npcs/delete?npc=pnj_de_test"), "lien vers l'aperçu");
        assertTrue(page.contains("danger-zone"), "la zone de danger est visuellement séparée");
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

    /** Fait aboutir un {@code npc.list} avec le relevé ci-dessus, comme le ferait l'agent. */
    private void seedNpcList() throws Exception {
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=npc.list&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action à livrer : " + poll.body());
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"3\","
                + "\"message\":\"3 PNJ\",\"details\":" + NPC_DETAILS + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long queuedOfType(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).count();
    }

    private AgentActionRow queuedAction(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private String csrfOfDeletePage(String npc) throws Exception {
        return csrf(get("/npcs/delete?npc=" + npc).body());
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
