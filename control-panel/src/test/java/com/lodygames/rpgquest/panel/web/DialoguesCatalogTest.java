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
    void emptyStateOffersRefreshAndSourceCreation() throws Exception {
        start();
        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Dialogues — détails techniques"));
        assertTrue(page.contains("Aucun catalogue chargé"));
        assertTrue(page.contains("name=\"type\" value=\"dialogue.list\""));
        // nav : entrée Dialogues active
        assertTrue(page.contains("href=\"/dialogues\""));
    }

    @Test
    void catalogRendersGraphTypedActionsAndWarnings() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();

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
        String token = csrf(get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body());

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
        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("name=\"type\" value=\"dialogue.node.update\""), "édition de nœud");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.choice.add\""), "ajout de choix");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.node.create\""), "ajout de nœud");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.choice.update\""), "édition de choix");
        assertTrue(page.contains("name=\"type\" value=\"dialogue.choice.delete\""), "suppression de choix simple");
        // Cible d'un choix : select des nœuds existants du dialogue, pas un champ libre.
        assertTrue(page.contains("<option value=\"accepted\""));
        // Le choix « J'accepte » porte une action START_QUEST + une condition QUEST_STATE : il est
        // désormais éditable, et ces deux propriétés sont offertes en sélecteurs pré-remplis (#82).
        assertFalse(page.contains("édition prévue dans une phase"), "plus de cul-de-sac sur un choix riche");
        assertTrue(page.contains("name=\"quest_action\""), "sélecteur d'action de quête");
        assertTrue(page.contains("name=\"quest_condition\""), "sélecteur de condition d'état");
        assertTrue(page.contains("<option value=\"start_quest\" selected>"), "action de quête pré-remplie");
        assertTrue(page.contains("<option value=\"not_started\" selected>"), "état de quête pré-rempli");
        assertTrue(page.contains("name=\"condition_negate\""), "négation de la condition");
        // Champ MiniMessage : source brute éditable + palette + aperçu rendu.
        // Le texte du choix passe par le composant partagé StyleField (#195) — pas une palette
        // parallèle : couleur au clic, styles, aperçu, et texte multi-styles jamais aplati.
        assertTrue(page.contains("name=\"choice_text\""), "champ du texte de choix");
        assertTrue(page.contains("data-sf-store"), "champ stylé partagé (StyleField)");
        assertTrue(page.contains("data-sf-palette"), "palette de couleurs");
        assertTrue(page.contains("data-sf-preview"), "aperçu du texte");
        // Bandeau explicatif du format canonique.
        assertTrue(page.contains("canonique</strong>"), "bandeau format canonique");
    }

    /**
     * Issue #238 — l'éditeur annonce la largeur réelle du libellé, et avertit s'il sera tronqué.
     *
     * <p>Le besoin vient du terrain : des boutons étaient coupés en jeu sans que rien, dans
     * l'éditeur, ne puisse le laisser prévoir. L'avertissement est <strong>non bloquant</strong> —
     * un libellé long reste parfois le bon choix.</p>
     */
    @Test
    void theChoiceEditorMeasuresTheLabelAndWarnsWhenItWillBeTruncated() throws Exception {
        start();
        // « J'accepte » tient largement ; un second choix volontairement trop long doit déclencher
        // l'avertissement, et lui seul.
        String longLabel = "Je veux prouver ma valeur pour obtenir un bout de terrain (palier 1)";
        runListWithSuccess(DIALOGUE_DETAILS.replace(
                "{\"text\":\"Non merci\",\"nextNodeId\":\"\",\"actions\":[],\"conditions\":[]}",
                "{\"text\":\"" + longLabel + "\",\"nextNodeId\":\"\",\"actions\":[],\"conditions\":[]}"));
        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();

        // La mesure est annoncée pour chaque libellé, même quand tout va bien : l'auteur apprend la
        // contrainte dans l'éditeur plutôt qu'en jeu.
        assertTrue(page.contains("Longueur :"), "la longueur du libellé est affichée");
        assertTrue(page.contains("px sur 150 disponibles"),
                "le budget réel du bouton est cité, pas un nombre de caractères arbitraire");
        // « J'accepte » = 49 px ; le libellé trop long = 358 px.
        assertTrue(page.contains("49 px sur 150"), "mesure du libellé court");
        assertTrue(page.contains("358 px sur 150"), "mesure du libellé réellement vu tronqué en jeu");
        assertTrue(page.contains("risque d'être tronqué") || page.contains("risque d&#39;être tronqué"),
                "l'avertissement doit apparaître pour le libellé trop long");
        // Non bloquant : le formulaire d'enregistrement reste proposé.
        assertTrue(page.contains("name=\"type\" value=\"dialogue.choice.update\""),
                "l'avertissement ne retire pas la possibilité d'enregistrer");
    }

    /** Le formulaire d'AJOUT, lui, n'a rien à mesurer : il explique la contrainte en amont. */
    @Test
    void theAddChoiceFormExplainsTheButtonBudgetBeforeAnythingIsTyped() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Le bouton fait 150 px de large en jeu"),
                "la contrainte est annoncée avant la saisie");
        assertTrue(page.contains("environ 25 caractères"), "ordre de grandeur utilisable");
        assertTrue(page.contains("corps</strong> du nœud"),
                "et la règle : le texte long va dans le corps, pas sur le bouton");
    }

    @Test
    void richChoiceKeepsItsUneditablePropertiesAndSaysSo() throws Exception {
        start();
        runListWithSuccess(RICH_CHOICE_DETAILS);
        String page = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();

        // L'action GIVE_ITEM et la condition HAS_PERMISSION ne sont pas éditables ici : elles sont
        // annoncées comme conservées à l'identique, jamais perdues en silence.
        assertTrue(page.contains("Conservé à l'identique par l'enregistrement"), "limites annoncées");
        assertTrue(page.contains("action donne"), "action non éditable listée");
        assertTrue(page.contains("condition permission"), "condition non éditable listée");
        // Le formulaire ne porte aucun champ pour ces propriétés : il ne peut pas les écraser.
        assertFalse(page.contains("name=\"give_item\""));
        // Deux actions de quête sur un même choix : l'éditeur n'y touche pas du tout.
        assertTrue(page.contains("<input type=\"hidden\" name=\"quest_action\" value=\"keep\">"),
                "deux actions de quête : conservées telles quelles");
    }

    /** Un choix riche : 2 actions de quête (non représentables), + GIVE_ITEM et HAS_PERMISSION. */
    private static final String RICH_CHOICE_DETAILS = "{"
            + "\"total\":1,\"withWarnings\":0,\"nodeTotal\":1,\"loadIssues\":[],\"declaredButMissing\":[],"
            + "\"dialogues\":[{"
            + "\"id\":\"rpgquest:jo\",\"key\":\"jo\",\"startNodeId\":\"start\","
            + "\"linkedNpcIds\":[\"jo\"],\"nodeCount\":1,\"choiceCount\":1,"
            + "\"referencedQuestIds\":[],\"startsQuestIds\":[],\"warnings\":[],"
            + "\"nodes\":[{\"id\":\"start\",\"speaker\":\"Jo\",\"text\":\"Salut.\",\"start\":true,\"reachable\":true,"
            + "\"choices\":[{\"text\":\"Merci\",\"nextNodeId\":\"\",\"actions\":["
            + "{\"kind\":\"START_QUEST\",\"target\":\"rpgquest:a\",\"value\":\"\",\"raw\":\"\"},"
            + "{\"kind\":\"ADVANCE_QUEST\",\"target\":\"rpgquest:b\",\"value\":\"\",\"raw\":\"\"},"
            + "{\"kind\":\"GIVE_ITEM\",\"target\":\"BREAD\",\"value\":\"3\",\"raw\":\"\"}],"
            + "\"conditions\":[{\"kind\":\"HAS_PERMISSION\",\"target\":\"rpgquest.vip\",\"value\":\"\",\"raw\":\"\","
            + "\"negated\":false}]}]}]}]}";

    @Test
    void nodeUpdateActionIsValidatedAndQueued() throws Exception {
        start();
        runListWithSuccess(DIALOGUE_DETAILS);
        String token = csrf(get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body());

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
        String token = csrf(get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> both = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.choice.add&agent=" + TestConfig.AGENT_ID + "&return=/dialogues"
                + "&dialogue_id=guard&node_id=accepted&choice_text=Retour&next_node_id=greeting&close=true&confirm=true");
        assertTrue(both.headers().firstValue("Location").orElse("").contains("err="));

        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=dialogue.choice.add&agent=" + TestConfig.AGENT_ID + "&return=/dialogues"
                + "&dialogue_id=guard&node_id=accepted&choice_text=Retour&next_node_id=greeting&confirm=true");
        assertEquals(303, ok.statusCode());
    }

    private static String miraDetails(boolean withInvestigate) {
        String start = "{\"id\":\"start\",\"speaker\":\"Mira\",\"text\":\"Bonjour.\",\"start\":true,"
                + "\"reachable\":true,\"choices\":[{\"text\":\"Au revoir\",\"nextNodeId\":\"\","
                + "\"actions\":[{\"kind\":\"CLOSE\",\"target\":\"\",\"value\":\"\",\"raw\":\"CLOSE\"}],"
                + "\"conditions\":[]}]}";
        String investigate = ",{\"id\":\"investigate\",\"speaker\":\"Mira\",\"text\":\"Une balise.\","
                + "\"start\":false,\"reachable\":false,\"choices\":[{\"text\":\"Fermer\",\"nextNodeId\":\"\","
                + "\"actions\":[{\"kind\":\"CLOSE\",\"target\":\"\",\"value\":\"\",\"raw\":\"CLOSE\"}],"
                + "\"conditions\":[]}]}";
        int nodes = withInvestigate ? 2 : 1;
        String warnings = withInvestigate
                ? "[{\"code\":\"NODE_UNREACHABLE\",\"severity\":\"info\","
                        + "\"message\":\"le nœud « investigate » est inaccessible.\"}]"
                : "[]";
        return "{\"total\":1,\"withWarnings\":" + (withInvestigate ? 1 : 0) + ",\"nodeTotal\":" + nodes + ","
                + "\"loadIssues\":[],\"declaredButMissing\":[],\"dialogues\":[{"
                + "\"id\":\"rpgquest:mira_first_map\",\"key\":\"mira_first_map\",\"startNodeId\":\"start\","
                + "\"linkedNpcIds\":[\"mira\"],\"nodeCount\":" + nodes + ",\"choiceCount\":" + nodes + ","
                + "\"referencedQuestIds\":[],\"startsQuestIds\":[],\"warnings\":" + warnings + ","
                + "\"nodes\":[" + start + (withInvestigate ? investigate : "") + "]}]}";
    }

    /**
     * Issue #130 : après « Ajouter un nœud », l'utilisateur revient sur l'éditeur de graphe (et non sur la
     * liste simple /dialogues), le dialogue rouvert ; la resynchronisation automatique (relevé
     * dialogue.list ré-enfilé) suffit alors à montrer le nouveau nœud, son compteur et son diagnostic.
     */
    @Test
    void addedNodeShowsUpOnTheGraphPageAfterTheAutomaticResync() throws Exception {
        start();
        runListWithSuccess(miraDetails(false));
        String before = get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(before.contains("Afficher le graphe — 1 nœud(s)"));
        assertTrue(before.contains("name=\"return\" value=\"/dialogues/diagnostic\""),
                "les formulaires du graphe reviennent sur l'éditeur de graphe");
        assertTrue(before.contains("name=\"focus\" value=\"mira_first_map\""), "le dialogue édité sera rouvert");
        assertFalse(before.contains("<details open class=\"dlg-graph-wrap\""), "sans focus, rien n'est déplié d'office");

        HttpResponse<String> created = post("/agents/action", "_csrf=" + csrf(before)
                + "&type=dialogue.node.create&agent=" + TestConfig.AGENT_ID + "&return=/dialogues/diagnostic"
                + "&focus=mira_first_map&dialogue_id=" + enc("rpgquest:mira_first_map")
                + "&node_id=investigate&speaker=Mira&text=" + enc("Une balise."));
        assertEquals(303, created.statusCode());
        String location = created.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("/dialogues/diagnostic?agent="), location);
        assertTrue(location.contains("&focus=mira_first_map") && location.contains("&toast="), location);
        String actionId = location.substring(location.indexOf("&toast=") + "&toast=".length());

        // La page rendue suit l'action (toast « en cours ») : panel.js rechargera dès la file au repos.
        String waiting = get(location).body();
        assertTrue(waiting.contains("data-toast-action=\"" + actionId + "\""), "toast suivi par panel.js");
        assertTrue(waiting.contains("aria-expanded=\"true\"") && waiting.contains("accordion-collapse collapse show"),
                "dialogue rouvert");

        agentResult(actionId, "{\"action_id\":\"" + actionId + "\",\"status\":\"SUCCESS\",\"message\":\"nœud ajouté\"}");
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1, "dialogue.list ré-enfilé : la file n'est pas au repos");
        String listId = pollOneActionId();
        agentResult(listId, "{\"action_id\":\"" + listId + "\",\"status\":\"SUCCESS\",\"value\":\"1\","
                + "\"message\":\"1 dialogue\",\"details\":" + miraDetails(true) + "}");
        assertEquals(0, pendingFor(TestConfig.AGENT_ID), "file au repos : panel.js recharge la page");

        // Ce que montre le rechargement automatique (même URL), sans F5 ni « Rafraîchir ».
        String after = get(location).body();
        assertTrue(after.contains("Afficher le graphe — 2 nœud(s)"), "compteur à jour");
        assertTrue(after.contains("<details open class=\"dlg-graph-wrap\">"), "graphe du dialogue édité déplié");
        assertTrue(after.contains("investigate") && after.contains("inaccessible"), "nouveau nœud visible");
        assertTrue(after.contains("NODE_UNREACHABLE"), "diagnostic immédiat du nœud orphelin");
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private int pendingFor(String agent) throws Exception {
        Map<String, Object> body = Json.parseObject(get("/agents/actions.json?agent=" + agent).body());
        return ((Number) body.get("pending")).intValue();
    }

    private String pollOneActionId() throws Exception {
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        assertTrue(m.find(), "une action attendue par l'agent");
        return m.group(1);
    }

    private void agentResult(String id, String body) throws Exception {
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
    }

    private void runListWithSuccess(String details) throws Exception {
        String token = csrf(get("/dialogues/diagnostic?agent=" + TestConfig.AGENT_ID).body());
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
