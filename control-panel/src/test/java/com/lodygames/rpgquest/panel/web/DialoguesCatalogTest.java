package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.json.Json;
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

/** Rendu de la page {@code /dialogues} V1 : graphe, actions typées, warnings, squelette de création. */
class DialoguesCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String DIALOGUE_DETAILS = "{"
            + "\"total\":1,\"withWarnings\":1,\"nodeTotal\":3,"
            + "\"loadIssues\":[{\"file\":\"broken.yml\",\"message\":\"« nodes » est obligatoire.\"}],"
            + "\"declaredButMissing\":[{\"npcId\":\"ghost\",\"dialogueId\":\"rpgquest:ghost\"}],"
            + "\"dialogues\":[{"
            + "\"id\":\"rpgquest:guard\",\"key\":\"guard\",\"startNodeId\":\"greeting\","
            + "\"linkedNpcIds\":[\"guard\"],\"nodeCount\":3,\"choiceCount\":4,"
            + "\"referencedQuestIds\":[\"rpgquest:first_steps\"],\"startsQuestIds\":[\"rpgquest:first_steps\"],"
            + "\"warnings\":[{\"code\":\"NODE_UNREACHABLE\",\"severity\":\"info\",\"message\":\"le nœud « orphan » est inaccessible.\"}],"
            + "\"nodes\":["
            + "{\"id\":\"greeting\",\"speaker\":\"Garde\",\"text\":\"<white>Bonjour.</white>\",\"start\":true,\"reachable\":true,"
            + "\"choices\":[{\"text\":\"J'accepte\",\"nextNodeId\":\"accepted\","
            + "\"actions\":[{\"kind\":\"START_QUEST\",\"target\":\"rpgquest:first_steps\",\"value\":\"\",\"raw\":\"START_QUEST rpgquest:first_steps\"}],"
            + "\"conditions\":[{\"kind\":\"QUEST_STATE\",\"target\":\"rpgquest:first_steps\",\"value\":\"NOT_STARTED\",\"raw\":\"r\",\"negated\":false}]},"
            + "{\"text\":\"Non merci\",\"nextNodeId\":\"\",\"actions\":[],\"conditions\":[]}]},"
            + "{\"id\":\"accepted\",\"speaker\":\"Garde\",\"text\":\"Bien.\",\"start\":false,\"reachable\":true,"
            + "\"choices\":[{\"text\":\"OK\",\"nextNodeId\":\"\",\"actions\":[{\"kind\":\"CLOSE\",\"target\":\"\",\"value\":\"\",\"raw\":\"CLOSE\"}],\"conditions\":[]}]},"
            + "{\"id\":\"orphan\",\"speaker\":\"Garde\",\"text\":\"<gold>caché</gold>\",\"start\":false,\"reachable\":false,"
            + "\"choices\":[{\"text\":\"OK\",\"nextNodeId\":\"\",\"actions\":[],\"conditions\":[]}]}"
            + "]}]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void emptyStateOffersRefreshAndSkeletonCreation() throws Exception {
        start();
        String page = get("/dialogues?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<h1>Dialogues</h1>"));
        assertTrue(page.contains("Aucun catalogue chargé"));
        assertTrue(page.contains("name=\"type\" value=\"dialogue.list\""));
        assertTrue(page.contains("name=\"type\" value=\"dialogue.definition.create\""));
        // nav : entrée Dialogues active
        assertTrue(page.contains("href=\"/dialogues\""));
    }

    @Test
    void catalogRendersGraphTypedActionsAndWarnings() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String page = get("/dialogues?agent=" + TestConfig.AGENT_ID).body();

        // liste = accordion Bootstrap (synthèse repliée, détail au clic)
        assertTrue(page.contains("class=\"accordion npc-accordion\" id=\"dialogues-accordion\""), "accordion de dialogues");
        assertTrue(page.contains("data-bs-parent=\"#dialogues-accordion\""), "un seul détail ouvert à la fois");
        assertTrue(page.contains("data-copy=\"rpgquest:guard\""), "id copiable");
        assertTrue(page.contains("Nœud de départ"));
        // le graphe n'est PAS ouvert par défaut : <details> sans attribut open
        assertTrue(page.contains("<details class=\"dlg-graph-wrap\">"), "graphe replié par défaut");
        assertFalse(page.contains("<details open class=\"dlg-graph-wrap\""), "graphe jamais ouvert d'office");
        // MiniMessage rendu dans le texte affiché du nœud (le champ d'édition, lui, porte la source brute).
        assertTrue(page.contains("<span style=\"color:"), "MiniMessage interprété");
        assertFalse(page.contains("dlg-text\">&lt;white&gt;Bonjour."), "texte affiché du nœud jamais en balises brutes");
        // graphe : nœud de départ identifiable + nœud inaccessible marqué
        assertTrue(page.contains("dlg-node start"));
        assertTrue(page.contains("dlg-node") && page.contains("unreachable"));
        assertTrue(page.contains("inaccessible"));
        // action typée START_QUEST rendue en libellé lisible (titre humain si quest.list chargé, sinon prettify)
        assertTrue(page.contains("démarre") || page.contains("START_QUEST"));
        assertTrue(page.contains("→ accepted"), "transition next affichée");
        // diagnostics humanisés (DiagnosticHelp) : titre clair + code technique en secondaire + lien doc
        assertTrue(page.contains("Code technique : <code class=\"tid\">NODE_UNREACHABLE</code>"), "code discret");
        assertTrue(page.contains("<span>Nœud jamais atteint</span>"), "message humain, pas le brut du moteur");
        // fichier rejeté + définition PNJ pointant vers un dialogue absent : composant diagnostic, pas une bannière brute
        assertTrue(page.contains("broken.yml"));
        assertTrue(page.contains("<span>Fichier de dialogue rejeté</span>"), "load issue humanisé");
        assertTrue(page.contains("href=\"/docs/dialogues-depannage#fichier-de-dialogue-rejete\""), "ancre doc précise");
        assertTrue(page.contains("rpgquest:ghost"));
        assertTrue(page.contains("<span>Dialogue déclaré mais absent</span>"), "déclaré-manquant humanisé");
        // PNJ utilisant le dialogue
        assertTrue(page.contains(">PNJ<"), "section PNJ du détail");
        assertTrue(page.contains("Utilisé par"), "PNJ consommateurs listés");
        assertTrue(page.contains("data-copy=\"guard\""));
        assertFalse(page.contains("class=\"banner err\""), "plus de bannière rouge brute pour les anomalies");
    }

    @Test
    void createSkeletonActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());

        // Création de contenu réversible (#118) : aucune case à cocher cachée — acceptée sans « confirm ».
        HttpResponse<String> noConfirm = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=woodcutter_bob&speaker=" + enc("Bûcheron Bob") + "&text=" + enc("Bonjour"));
        assertEquals(303, noConfirm.statusCode());
        assertFalse(noConfirm.headers().firstValue("Location").orElse("").contains("err="), "aucune confirmation requise");
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);

        // La palette de couleurs (#118) : un text_color connu enrobe le texte simple en MiniMessage.
        HttpResponse<String> colored = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=iron_specialist_intro&speaker=Robert"
                + "&text_color=yellow&text=" + enc("Bonjour voyageur."));
        assertEquals(303, colored.statusCode());
        assertFalse(colored.headers().firstValue("Location").orElse("").contains("err="));

        HttpResponse<String> bad = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=" + enc("Bad Key") + "&speaker=X&text=Y");
        assertTrue(bad.headers().firstValue("Location").orElse("").contains("err="));

        HttpResponse<String> badColor = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/dialogues&key=other_intro&speaker=X&text_color=chartreuse&text=Y");
        assertTrue(badColor.headers().firstValue("Location").orElse("").contains("err="), "couleur hors palette refusée");
    }

    @Test
    void catalogExposesGuidedEditFormsAndTargetSelect() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String page = get("/dialogues?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("name=\"type\" value=\"dialogue.node.update\""), "édition de nœud");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.choice.add\""), "ajout de choix");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.node.create\""), "ajout de nœud");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.choice.update\""), "édition de choix simple");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.choice.delete\""), "suppression de choix simple");
        // Cible d'un choix : select des nœuds existants du dialogue, pas un champ libre.
        assertTrue(page.contains("<option value=\"accepted\""));
        // Le choix « J'accepte » porte une action START_QUEST + une condition -> non simple : pas de form d'édition,
        // mais la note « édition avancée » à la place.
        assertTrue(page.contains("édition prévue dans une phase"), "note choix avancé");
        // Bandeau explicatif du format canonique.
        assertTrue(page.contains("canonique</strong>"), "bandeau format canonique");
    }

    @Test
    void nodeUpdateActionIsValidatedAndQueued() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());

        // Édition réversible : plus de confirmation cachée (#111 / #118).
        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.node.update&agent=" + TestConfig.AGENT_ID + "&return=/dialogues"
                + "&dialogue_id=guard&node_id=greeting&speaker=Capitaine&text=" + enc("<y>Salut</y>"));
        assertEquals(303, ok.statusCode());
        assertFalse(ok.headers().firstValue("Location").orElse("").contains("err="));
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);

        HttpResponse<String> badNode = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.node.update&agent=" + TestConfig.AGENT_ID + "&return=/dialogues"
                + "&dialogue_id=guard&node_id=" + enc("Bad Node") + "&speaker=X&text=Y&confirm=true");
        assertTrue(badNode.headers().firstValue("Location").orElse("").contains("err="));
    }

    @Test
    void choiceAddActionRejectsAmbiguousTarget() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> both = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.choice.add&agent=" + TestConfig.AGENT_ID + "&return=/dialogues"
                + "&dialogue_id=guard&node_id=accepted&choice_text=Retour&next_node_id=greeting&close=true&confirm=true");
        assertTrue(both.headers().firstValue("Location").orElse("").contains("err="));

        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.choice.add&agent=" + TestConfig.AGENT_ID + "&return=/dialogues"
                + "&dialogue_id=guard&node_id=accepted&choice_text=Retour&next_node_id=greeting&confirm=true");
        assertEquals(303, ok.statusCode());
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private int pendingFor(String agent) throws Exception {
        Map<String, Object> body = Json.parseObject(get("/agents/actions.json?agent=" + agent).body());
        return ((Number) body.get("pending")).intValue();
    }

    private void runListWithSuccess(String details) throws Exception {
        String token = csrf(get("/dialogues?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=dialogue.list&agent=" + TestConfig.AGENT_ID + "&return=/dialogues");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        m.find();
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"1\","
                + "\"message\":\"1 dialogue\",\"details\":" + details + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        m.find();
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
