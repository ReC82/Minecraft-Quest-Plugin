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

/** Rendu et écritures de la page {@code /npcs} V2 : définition logique vs binding Citizens. */
class NpcsCatalogTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    // Payload npc.list V2 : guard (défini + lié -> LINKED) + woodcutter_bob (défini, pas de binding -> NOT_LINKED).
    private static final String NPC_DETAILS = "{"
            + "\"citizensAvailable\":true,\"total\":2,\"withDefinition\":2,\"withoutDefinition\":0,"
            + "\"bound\":1,\"withWarnings\":1,"
            + "\"definedIds\":[\"guard\",\"woodcutter_bob\"],\"canonicalIds\":[\"guard\",\"woodcutter_bob\"],"
            + "\"npcs\":["
            + "{\"id\":\"woodcutter_bob\",\"displayName\":\"Bûcheron Bob\",\"logicalDefinitionPresent\":true,"
            + "\"citizensBindingPresent\":false,\"citizensNumericId\":null,\"bindingCount\":0,\"enabled\":true,"
            + "\"description\":null,\"role\":\"quest_giver\",\"definedDialogueId\":null,\"hasDialogue\":false,\"dialogueId\":null,"
            + "\"dialogueNodes\":0,\"dialogueChoices\":0,\"dialogueStartsQuests\":[],\"questsGiven\":[\"rpgquest:woodcutters_request\"],"
            + "\"questsReferenced\":[\"rpgquest:woodcutters_request\"],\"sources\":[\"DEFINITION\",\"QUEST_GIVER\",\"QUEST_TALK\"],"
            + "\"state\":\"NOT_LINKED\",\"warnings\":[{\"code\":\"NOT_LINKED\",\"severity\":\"info\","
            + "\"message\":\"Définition prête — aucun PNJ Citizens tagué « woodcutter_bob » (à créer / lier en jeu).\"}]},"
            + "{\"id\":\"guard\",\"displayName\":\"<yellow>Garde</yellow>\",\"logicalDefinitionPresent\":true,"
            + "\"citizensBindingPresent\":true,\"citizensNumericId\":6,\"bindingCount\":1,\"enabled\":true,"
            + "\"description\":\"Garde du village\",\"role\":\"quest_giver\",\"definedDialogueId\":\"rpgquest:guard\","
            + "\"hasDialogue\":true,\"dialogueId\":\"rpgquest:guard\",\"dialogueNodes\":6,\"dialogueChoices\":9,"
            + "\"dialogueStartsQuests\":[\"rpgquest:first_steps\"],\"questsGiven\":[\"rpgquest:crystal_hunt\"],"
            + "\"questsReferenced\":[\"rpgquest:crystal_hunt\"],\"sources\":[\"DEFINITION\",\"BINDING\",\"DIALOGUE\"],"
            + "\"state\":\"LINKED\",\"warnings\":[]}"
            + "]}";

    // Payload npc.citizens.list : #6 Garde (déjà lié à guard) + #14 Bûcheron Bob (libre).
    private static final String CITIZENS_DETAILS = "{"
            + "\"citizensAvailable\":true,\"total\":2,\"available\":1,\"linked\":1,"
            + "\"citizens\":["
            + "{\"numericId\":6,\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Garde\","
            + "\"linkedNpcId\":\"guard\",\"availableForBinding\":false,\"spawned\":true},"
            + "{\"numericId\":14,\"uuid\":\"22222222-2222-2222-2222-222222222222\",\"name\":\"Bûcheron Bob\","
            + "\"linkedNpcId\":null,\"availableForBinding\":true,\"spawned\":true}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void listIsAccordionOfSyntheticRowsWithStructuredDetail() throws Exception {
        start();
        runListWithSuccess(NPC_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        // Liste = accordion Bootstrap (un PNJ ouvert à la fois)
        assertTrue(page.contains("class=\"accordion npc-accordion\" id=\"npc-accordion\""), "accordion");
        assertTrue(page.contains("accordion-item npc-item"), "item d'accordion par PNJ");
        assertTrue(page.contains("data-bs-toggle=\"collapse\"") && page.contains("data-bs-parent=\"#npc-accordion\""),
                "collapse Bootstrap, un seul ouvert");

        // En-tête = synthèse : nom (MiniMessage rendu, pas de balise brute) + id + badges
        assertTrue(page.contains("class=\"npc-name\"") && page.contains("<span style=\"color:"), "nom rendu");
        int guardNameAt = page.lastIndexOf("class=\"npc-name\"");
        assertFalse(page.substring(guardNameAt, guardNameAt + 120).contains("&lt;yellow&gt;"), "en-tête sans balise brute");
        assertTrue(page.contains("<code class=\"tid npc-id\">guard</code>"), "id logique en en-tête");
        assertTrue(page.contains("text-bg-secondary\">Citizens #6</span>"), "badge Citizens #6 (guard)");
        assertTrue(page.contains("text-bg-warning\">à lier</span>"), "badge « à lier » (woodcutter_bob)");

        // Sections du détail
        for (String s : new String[] {">Identité<", ">Citizens<", ">Contenu<", ">Diagnostics<"}) {
            assertTrue(page.contains(s), "section " + s);
        }
        // Labels humains : rôle affiché « Donneur de quête », valeur technique en secondaire
        assertTrue(page.contains("Donneur de quête"), "libellé humain du rôle");
        assertTrue(page.contains("<code class=\"tid\">quest_giver</code>"), "valeur technique du rôle conservée");
        // id copiable dans la section Identité
        assertTrue(page.contains("data-copy=\"guard\"") && page.contains("data-copy=\"woodcutter_bob\""));
        assertTrue(page.indexOf("data-copy=\"woodcutter_bob\"") < page.indexOf("data-copy=\"guard\""),
                "PNJ avec avertissement listé avant le PNJ sain");

        // Diagnostic dédié pour woodcutter_bob (info) : composant DiagnosticHelp humanisé
        assertTrue(page.contains("alert alert-info pa-diag pa-diag-info"), "alerte Bootstrap différenciée (INFO)");
        assertTrue(page.contains("<span>PNJ pas encore présent en jeu</span>"), "titre humain, pas le message brut du moteur");
        assertTrue(page.contains("<strong>Conséquence :</strong>") && page.contains("<strong>À faire :</strong>"),
                "conséquence + action affichées");
        assertTrue(page.contains("href=\"/docs/pnj-depannage#pnj-pas-encore-present-en-jeu\""),
                "lien vers l'ancre précise de la doc");
        assertTrue(page.contains("Comment corriger ?</a>"), "bouton d'aide contextuelle");
        assertTrue(page.contains("Code technique : <code class=\"tid\">NOT_LINKED</code>"), "code technique en secondaire");
        // terminologie interdite absente du message principal (« tagué », « binding »…)
        int diagAt = page.indexOf("pa-diag pa-diag-info");
        String diagBlock = page.substring(diagAt, page.indexOf("</div>", page.indexOf("pa-diag-code", diagAt)));
        for (String banned : new String[] {"tagué", "binding", "giver", "namespaced"}) {
            assertFalse(diagBlock.contains(banned), "terme technique interdit dans l'UX : " + banned);
        }
        assertTrue(page.contains("npc-diag-ok"), "« Aucune anomalie » pour le PNJ sain (guard)");

        // Actions = boutons qui déplient un formulaire (masqué par défaut)
        assertTrue(page.contains("class=\"npc-actions"), "zone d'actions");
        assertTrue(page.contains(">Modifier</button>"), "bouton Modifier (définition présente)");
        assertTrue(page.contains("class=\"collapse npc-form-collapse\""), "le formulaire est dans un collapse");
        assertTrue(page.contains("name=\"type\" value=\"npc.definition.update\""), "formulaire d'édition");
        assertFalse(page.contains("-f-create"), "aucun formulaire de création par fiche : les deux PNJ sont définis");

        // registre canonique conservé (repli)
        assertTrue(page.contains("Registre canonique"));
    }

    @Test
    void compactToolbarAndInputGroupSearch() throws Exception {
        start();
        runListWithSuccess(NPC_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        // toolbar compacte : boutons btn-sm, pas de grande carte
        assertTrue(page.contains("class=\"npc-catbar\""));
        assertTrue(page.contains("btn btn-sm btn-outline-primary") && page.contains(">Catalogue RPGQuest</button>"));
        assertTrue(page.contains("btn btn-sm btn-outline-secondary") && page.contains(">Citizens</button>"));
        assertTrue(page.contains("name=\"type\" value=\"npc.list\"") && page.contains("name=\"type\" value=\"npc.citizens.list\""));
        // « Nouvelle définition PNJ » = bouton compact qui déplie un collapse
        assertTrue(page.contains("data-bs-target=\"#npc-new-def\"") && page.contains(">Nouvelle définition PNJ</button>"));
        assertTrue(page.contains("<div class=\"collapse\" id=\"npc-new-def\">"), "form de création masqué par défaut");

        // recherche : input-group Bootstrap, l'icône a sa propre zone
        assertTrue(page.contains("<div class=\"input-group npc-search\"><span class=\"input-group-text\">"), "input-group");
        assertTrue(page.contains("<input type=\"search\" class=\"form-control\" data-filter-input=\"npcs\""), "champ Bootstrap");

        // filtres conservés
        for (String f : new String[] {">Tous<", ">Liés<", ">Non liés<", ">Warnings<", ">Erreurs<"}) {
            assertTrue(page.contains(f), "filtre " + f);
        }
        assertTrue(page.contains("data-filter-chip=\"linked\"") && page.contains("data-filter-chip=\"err\""));
    }

    @Test
    void citizensLinkFormShownOnlyWhenDefinedAndNotLinked_withFreeCitizensOnly() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_DETAILS);
        runListWithSuccess("npc.citizens.list", CITIZENS_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains(">Lier un PNJ Citizens</button>"), "bouton d'action de liaison sur le PNJ NOT_LINKED");
        assertTrue(page.contains("name=\"type\" value=\"npc.citizens.link\""));
        // le PNJ Citizens libre est sélectionnable, l'occupé est désactivé
        assertTrue(page.contains("<option value=\"14\">#14 — Bûcheron Bob</option>"), "Citizens libre proposé");
        assertTrue(page.contains("<option value=\"6\" disabled>#6 — Garde  ·  déjà lié à guard</option>"),
                "Citizens occupé non sélectionnable");
        // résumé Citizens
        assertTrue(page.contains("1 libre(s)") && page.contains("1 lié(s)"));
        // le PNJ déjà lié (guard) ne propose pas la liaison
        int guardItem = page.lastIndexOf("<code class=\"tid npc-id\">guard</code>");
        assertFalse(page.substring(guardItem).contains("npc.citizens.link"),
                "pas de liaison proposée sur un PNJ déjà LINKED");
    }

    @Test
    void citizensCreateFormShownOnlyOnDefinedNotLinkedCard_withHeartbeatWorlds() throws Exception {
        start();
        sendHeartbeat("{\"hub\":{\"name\":\"world_hub\",\"loaded\":true},"
                + "\"claims\":{\"name\":\"claims\",\"loaded\":true},"
                + "\"wild\":{\"name\":\"wild\",\"loaded\":false}}");
        runListWithSuccess("npc.list", NPC_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Créer le PNJ Citizens"), "form de spawn sur le PNJ NOT_LINKED défini");
        assertTrue(page.contains("name=\"type\" value=\"npc.citizens.create\""));
        // preview
        assertTrue(page.contains("Aucun PNJ Citizens n'est actuellement lié à « woodcutter_bob »"));
        // mondes chargés proposés en liste (wild non chargé -> absent)
        assertTrue(page.contains("<option value=\"world_hub\">world_hub</option>"));
        assertTrue(page.contains("<option value=\"claims\">claims</option>"));
        assertFalse(page.contains("<option value=\"wild\">"), "monde non chargé exclu de la liste");
        // coordonnées à saisir, jamais devinées
        assertTrue(page.contains("name=\"x\"") && page.contains("name=\"y\"") && page.contains("name=\"z\""));
        assertTrue(page.contains("name=\"yaw\"") && page.contains("name=\"pitch\""));
        // le PNJ déjà lié (guard) n'a pas le formulaire de spawn
        int guardCard = page.lastIndexOf("data-copy=\"guard\"");
        assertFalse(page.substring(guardCard).contains("Créer le PNJ Citizens"),
                "pas de spawn proposé sur un PNJ déjà LINKED");
    }

    @Test
    void citizensCreateActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        // sans confirm -> refusé
        HttpResponse<String> noConfirm = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&world=world_hub&x=125.5&y=64&z=-82.5");
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="), "confirm obligatoire");

        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&world=world_hub&x=125.5&y=64&z=-82.5&yaw=90&pitch=0&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);

        // coordonnée non finie -> refusé
        HttpResponse<String> bad = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&world=world_hub&x=NaN&y=64&z=2&confirm=true");
        assertTrue(bad.headers().firstValue("Location").orElse("").contains("err="));

        // Y hors bornes -> refusé
        HttpResponse<String> outOfBounds = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&world=world_hub&x=1&y=99999&z=2&confirm=true");
        assertTrue(outOfBounds.headers().firstValue("Location").orElse("").contains("err="));
    }

    @Test
    void citizensLinkActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.link&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&citizens_id=14&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);

        HttpResponse<String> bad = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.link&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&citizens_id=abc&confirm=true");
        assertTrue(bad.headers().firstValue("Location").orElse("").contains("err="));
    }

    @Test
    void emptyStateStillOffersDefinitionCreation() throws Exception {
        start();
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("<h1>PNJ</h1>"));
        assertTrue(page.contains("Aucun catalogue chargé"));
        assertTrue(page.contains("name=\"type\" value=\"npc.list\""));
        assertTrue(page.contains("name=\"type\" value=\"npc.definition.create\""));
    }

    @Test
    void createDefinitionActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        // sans confirm -> refusé
        HttpResponse<String> noConfirm = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&display_name=" + enc("Bûcheron Bob"));
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="), "confirm obligatoire");

        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&display_name=" + enc("Bûcheron Bob")
                + "&dialogue_id=rpgquest:woodcutter_bob&enabled=true&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(ok.headers().firstValue("Location").orElse("").startsWith("/npcs?agent="));
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1, "une action npc.definition.create en attente");

        // id invalide -> refusé
        HttpResponse<String> bad = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=" + enc("Bad Id") + "&display_name=X&confirm=true");
        assertTrue(bad.headers().firstValue("Location").orElse("").contains("err="));
    }

    @Test
    void questGiverSetActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=quest.giver.set&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&quest_id=rpgquest:woodcutters_request&npc_id=woodcutter_bob&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);
    }

    // ---- #89 : bug de contexte de formulaire (capture) --------------------------------

    // Deux PNJ SANS définition : "guide" et "woodcutter_bob". La fiche de chacun doit porter
    // un formulaire de création contextualisé à SON id, jamais à celui d'un autre.
    private static final String NPC_TWO_UNDEFINED = "{"
            + "\"citizensAvailable\":true,\"total\":2,\"withDefinition\":0,\"withoutDefinition\":2,"
            + "\"bound\":0,\"withWarnings\":2,\"definedIds\":[],\"canonicalIds\":[\"guide\",\"woodcutter_bob\"],"
            + "\"npcs\":["
            + "{\"id\":\"guide\",\"displayName\":null,\"logicalDefinitionPresent\":false,"
            + "\"citizensBindingPresent\":false,\"citizensNumericId\":null,\"enabled\":false,"
            + "\"role\":null,\"definedDialogueId\":null,\"dialogueId\":null,\"dialogueNodes\":0,\"dialogueChoices\":0,"
            + "\"dialogueStartsQuests\":[],\"questsGiven\":[],\"questsReferenced\":[],"
            + "\"state\":\"UNDEFINED_REFERENCE\",\"warnings\":[{\"code\":\"BINDING_NO_DEFINITION\",\"severity\":\"warning\","
            + "\"message\":\"Le PNJ Citizens est tagué « guide » mais aucune définition logique n'existe.\"}]},"
            + "{\"id\":\"woodcutter_bob\",\"displayName\":\"Bûcheron Bob\",\"logicalDefinitionPresent\":false,"
            + "\"citizensBindingPresent\":false,\"citizensNumericId\":null,\"enabled\":false,"
            + "\"role\":null,\"definedDialogueId\":null,\"dialogueId\":null,\"dialogueNodes\":0,\"dialogueChoices\":0,"
            + "\"dialogueStartsQuests\":[],\"questsGiven\":[],\"questsReferenced\":[],"
            + "\"state\":\"UNDEFINED_REFERENCE\",\"warnings\":[{\"code\":\"BINDING_NO_DEFINITION\",\"severity\":\"warning\","
            + "\"message\":\"tag sans définition\"}]}"
            + "]}";

    @Test
    void perNpcCreateFormIsContextualisedAndCannotCarryAnotherNpcId() throws Exception {
        start();
        runListWithSuccess(NPC_TWO_UNDEFINED);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        // La fiche « guide » : formulaire de création lié à guide (id caché + npc_ctx caché),
        // AUCUN champ éditable d'id, et aucune valeur héritée d'un autre PNJ.
        int guideForm = page.indexOf("id=\"npc-0-guide-f-create\"");
        int bobForm = page.indexOf("id=\"npc-1-woodcutter_bob-f-create\"");
        assertTrue(guideForm > 0 && bobForm > 0 && guideForm < bobForm, "un formulaire de création par fiche");
        // borne au </form> du formulaire de « guide » (le contenu propre du collapse)
        String guideBlock = page.substring(guideForm, page.indexOf("</form>", guideForm) + 7);
        assertTrue(guideBlock.contains("<input type=\"hidden\" name=\"npc_id\" value=\"guide\">"), "npc_id caché = guide");
        assertTrue(guideBlock.contains("<input type=\"hidden\" name=\"npc_ctx\" value=\"guide\">"), "npc_ctx caché = guide");
        assertFalse(guideBlock.contains("name=\"npc_id\" value=\"woodcutter_bob\""), "aucun id d'un autre PNJ");
        assertFalse(guideBlock.contains("Bûcheron Bob"), "aucun nom d'un autre PNJ pré-rempli");
        assertFalse(guideBlock.contains("value=\"rpgquest:woodcutter_bob\""), "aucun dialogue d'un autre PNJ");
        // l'id n'est plus un input texte éditable dans la fiche
        assertFalse(guideBlock.contains("name=\"npc_id\"") && guideBlock.contains("type=\"text\" name=\"npc_id\""),
                "id non éditable dans une fiche");
        assertTrue(page.contains("autocomplete=\"off\""), "formulaire sans autocomplétion navigateur");
    }

    @Test
    void serverRejectsMutationWhenNpcContextMismatchesId() throws Exception {
        start();
        runListWithSuccess(NPC_TWO_UNDEFINED);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        // État de formulaire périmé : ouvert dans la fiche « guide » (npc_ctx=guide) mais
        // npc_id devenu « woodcutter_bob » -> le serveur refuse.
        HttpResponse<String> stale = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_ctx=guide&npc_id=woodcutter_bob&display_name=" + enc("Bûcheron Bob")
                + "&confirm=true");
        assertEquals(303, stale.statusCode());
        assertTrue(stale.headers().firstValue("Location").orElse("").contains("err="), "contexte incohérent -> err");
        assertTrue(stale.headers().firstValue("Location").orElse("").toLowerCase().contains("contexte")
                || stale.headers().firstValue("Location").orElse("").toLowerCase().contains("coh"), "message de contexte");
        assertEquals(0, pendingFor(TestConfig.AGENT_ID), "aucune action créée");

        // Contexte cohérent -> accepté.
        HttpResponse<String> okCtx = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_ctx=guide&npc_id=guide&display_name=" + enc("Guide") + "&confirm=true");
        assertEquals(303, okCtx.statusCode());
        assertFalse(okCtx.headers().firstValue("Location").orElse("").contains("err="));
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);
    }

    // ---- helpers ----------------------------------------------------------------------

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private int pendingFor(String agent) throws Exception {
        Map<String, Object> body = Json.parseObject(get("/agents/actions.json?agent=" + agent).body());
        return ((Number) body.get("pending")).intValue();
    }

    private void sendHeartbeat(String worldsJson) throws Exception {
        String hb = "{\"protocol\":\"agent/v1\",\"agent_id\":\"" + TestConfig.AGENT_ID + "\",\"environment\":\"dev\","
                + "\"plugin\":{\"name\":\"RPGQuest\",\"version\":\"7.7.7\"},"
                + "\"server\":{\"state\":\"ONLINE\",\"players_online\":0,\"max_players\":30,\"uptime_seconds\":10},"
                + "\"worlds\":" + worldsJson + "}";
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri("/agent/v1/heartbeat"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(hb)).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("heartbeat rejeté : " + res.statusCode());
        }
    }

    private void runListWithSuccess(String details) throws Exception {
        runListWithSuccess("npc.list", details);
    }

    private void runListWithSuccess(String type, String details) throws Exception {
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID + "&return=/npcs");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        m.find();
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"2\","
                + "\"message\":\"2 PNJ\",\"details\":" + details + "}";
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
