package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.support.TestConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #248 — la suppression d'un dialogue sur un vrai serveur HTTP : ce qui part vers l'agent,
 * une seule fois, et seulement après un aperçu fondé sur un relevé récent.
 */
class DialogueDeletionFlowTest {

    /** Le relevé réel : help hors source (démarre help_recolte), jo lié au PNJ jo. */
    private static final String SURVEY = "{\"dialogues\":["
            + "{\"id\":\"rpgquest:help\",\"key\":\"help\",\"linkedNpcIds\":[],"
            + "\"startsQuestIds\":[\"rpgquest:help_recolte\"],\"referencedQuestIds\":[\"rpgquest:help_recolte\"],"
            + "\"nodeCount\":1,\"choiceCount\":2,\"warnings\":[],\"nodes\":[{\"id\":\"start\",\"choices\":["
            + "{\"text\":\"Commencer\",\"actions\":[{\"kind\":\"START_QUEST\",\"target\":\"rpgquest:help_recolte\"}]}]}]},"
            + "{\"id\":\"rpgquest:jo\",\"key\":\"jo\",\"linkedNpcIds\":[\"jo\"],\"startsQuestIds\":[],"
            + "\"referencedQuestIds\":[],\"nodeCount\":1,\"choiceCount\":1,\"warnings\":[],\"nodes\":[]}"
            + "],\"loadIssues\":[],\"declaredButMissing\":[],\"total\":2}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private Path content;
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
    void theDialoguePageOffersDeletionEvenForAServerOnlyDialogue() throws Exception {
        start();
        survey(Instant.now());

        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/dialogues/delete?slug=help\""), "hors source compris");
        assertTrue(page.contains("Supprimer le dialogue…"), page.substring(0, Math.min(500, page.length())));
    }

    @Test
    void confirmingQueuesOneServerDeletionEvenOnDoubleClick() throws Exception {
        start();
        survey(Instant.now());
        String preview = get("/dialogues/delete?slug=help").body();
        assertTrue(preview.contains("Hors source"), preview);

        String form = "_csrf=" + csrf(preview) + "&slug=help&confirm_id=help&expected_sha=&confirm_ack=1";
        String result = post("/dialogues/delete", form).body();
        post("/dialogues/delete", form); // double clic

        List<AgentActionRow> deletions = actions("content.definition.delete");
        assertEquals(1, deletions.size(), "jamais deux suppressions serveur en file");
        assertEquals("dialogues", deletions.get(0).params().get("kind"));
        assertEquals("help", deletions.get(0).params().get("id"));
        assertTrue(result.contains("/actions/" + deletions.get(0).id()), "le suivi de l'action est donné");
    }

    @Test
    void aStaleSurveyRefusesAndQueuesNothing() throws Exception {
        start();
        survey(Instant.now().minus(Duration.ofHours(2)));

        String preview = get("/dialogues/delete?slug=help").body();
        assertTrue(preview.contains("Vérification incomplète"), preview);
        assertTrue(preview.contains("value=\"dialogue.list\""), "le bouton de rafraîchissement");
        assertFalse(preview.contains("name=\"confirm_id\""));

        post("/dialogues/delete", "_csrf=" + csrf(preview) + "&slug=help&confirm_id=help");
        assertTrue(actions("content.definition.delete").isEmpty(), "un POST forgé ne passe pas non plus");
    }

    @Test
    void aDialogueLinkedToAnNpcIsRefused() throws Exception {
        start();
        survey(Instant.now());
        String preview = get("/dialogues/delete?slug=jo").body();
        assertTrue(preview.contains("Lié au(x) PNJ"), preview);
        post("/dialogues/delete", "_csrf=" + csrf(preview) + "&slug=jo&confirm_id=jo");
        assertTrue(actions("content.definition.delete").isEmpty());
    }

    @Test
    void aSourceChangedSincePreviewIsNotDeleted() throws Exception {
        start();
        survey(Instant.now());
        Path file = content.resolve("dialogues").resolve("help.yml");
        Files.writeString(file, "id: rpgquest:help\nstart: a\n");
        String preview = get("/dialogues/delete?slug=help").body();
        String sha = field(preview, "name=\"expected_sha\" value=\"([0-9a-f]+)\"");

        Files.writeString(file, "id: rpgquest:help\nstart: b\n"); // autre onglet
        String refused = post("/dialogues/delete", "_csrf=" + csrf(preview)
                + "&slug=help&confirm_id=help&confirm_ack=1&expected_sha=" + sha).body();

        assertTrue(refused.contains("a changé depuis l"), refused);
        assertTrue(Files.exists(file), "le fichier modifié n'est pas supprimé");
        assertTrue(actions("content.definition.delete").isEmpty());
    }

    @Test
    void aWrongConfirmationChangesNothing() throws Exception {
        start();
        survey(Instant.now());
        String preview = get("/dialogues/delete?slug=help").body();
        post("/dialogues/delete", "_csrf=" + csrf(preview) + "&slug=help&confirm_id=oui");
        assertTrue(actions("content.definition.delete").isEmpty());
    }

    // ---- Harnais -------------------------------------------------------------------------------

    private void start() throws Exception {
        content = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(content.resolve(kind));
        }
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1", content.toString()),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private void survey(Instant at) {
        String id = store.createAction(TestConfig.AGENT_ID, "dialogue.list", Map.of(), "owner");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "2", "2 dialogue(s)",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + SURVEY + "}", at);
    }

    private List<AgentActionRow> actions(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).toList();
    }

    private static String field(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("introuvable : " + regex);
        }
        return m.group(1);
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
