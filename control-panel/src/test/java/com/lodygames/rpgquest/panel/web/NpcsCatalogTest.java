package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
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

    // #101 — npc.list ne contient que la fiche « woodcutter_bob » (prête, non liée) : aucune ligne
    // pour Stan, qui n'a ni fiche ni liaison.
    private static final String NPC_ONLY_GUARD = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"withDefinition\":1,\"withoutDefinition\":0,"
            + "\"bound\":0,\"withWarnings\":1,\"definedIds\":[\"woodcutter_bob\"],\"canonicalIds\":[\"woodcutter_bob\"],"
            + "\"npcs\":[{\"id\":\"woodcutter_bob\",\"displayName\":\"Bûcheron Bob\",\"logicalDefinitionPresent\":true,"
            + "\"citizensBindingPresent\":false,\"citizensNumericId\":null,\"bindingCount\":0,\"enabled\":true,"
            + "\"description\":null,\"role\":null,\"definedDialogueId\":null,\"hasDialogue\":false,\"dialogueId\":null,"
            + "\"dialogueNodes\":0,\"dialogueChoices\":0,\"dialogueStartsQuests\":[],\"questsGiven\":[],"
            + "\"questsReferenced\":[],\"sources\":[\"DEFINITION\"],\"state\":\"NOT_LINKED\","
            + "\"warnings\":[{\"code\":\"NOT_LINKED\",\"severity\":\"info\",\"message\":\"à lier\"}]}]}";

    // #101 — Stan (#7) présent dans le registre Citizens, aucun linkedNpcId : PNJ Citizens libre.
    private static final String CITIZENS_WITH_STAN = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":1,\"linked\":0,"
            + "\"citizens\":[{\"numericId\":7,\"uuid\":\"5e081a06-6596-47a3-b769-aef5fcd9e676\",\"name\":\"Stan\","
            + "\"linkedNpcId\":null,\"availableForBinding\":true,\"spawned\":false}]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
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
    void createDefinitionActionIsValidatedAndQueued() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        // Édition de contenu réversible (#111) : plus de case à cocher cachée — accepté sans « confirm ».
        HttpResponse<String> noConfirm = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&display_name=" + enc("Bûcheron Bob"));
        assertEquals(303, noConfirm.statusCode());
        assertFalse(noConfirm.headers().firstValue("Location").orElse("").contains("err="),
                "aucune confirmation requise pour une création réversible");

        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=npc.definition.create&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=woodcutter_bob&display_name=" + enc("Bûcheron Bob")
                + "&dialogue_id=rpgquest:woodcutter_bob&enabled=true");
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

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
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

    // ---- Déplacement d'un PNJ existant --------------------------------------------------------

    @Test
    void theMoveFormTransmitsTheDestinationExactly() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_WITH_LOCATION);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.move&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&npc_id=tan&world=world_hub&x=100.5&y=72&z=-50.5&yaw=180&confirm=true");
        assertEquals(303, res.statusCode(), res.body());

        AgentActionRow queued = latestOfType("npc.citizens.move");
        assertEquals("tan", queued.params().get("npc_id"));
        assertEquals("world_hub", queued.params().get("world"));
        assertEquals("100.5", queued.params().get("x"));
        assertEquals("-50.5", queued.params().get("z"));
        assertEquals("180", queued.params().get("yaw"));
    }

    @Test
    void anInvalidDestinationIsRefusedBeforeReachingTheServer() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_WITH_LOCATION);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        // Y aberrant, puis pitch hors bornes : deux refus distincts, aucune action enfilée.
        assertTrue(post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.move&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&npc_id=tan&world=world_hub&x=0&y=99999&z=0")
                .headers().firstValue("Location").orElse("").contains("err="), "Y hors bornes");
        assertTrue(post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.move&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&npc_id=tan&world=world_hub&x=0&y=70&z=0&pitch=200")
                .headers().firstValue("Location").orElse("").contains("err="), "pitch hors bornes");
    }

    @Test
    void movingRequiresTheSpawnPermissionAndRefreshesBothSurveys() throws Exception {
        start();
        AgentActionCatalog.Spec spec = AgentActionCatalog.spec("npc.citizens.move").orElseThrow();

        assertEquals(com.lodygames.rpgquest.panel.authz.Permission.NPC_SPAWN_WRITE, spec.permission(),
                "même droit que faire apparaître un PNJ — aucun droit nouveau");
        assertEquals(1, spec.requiredPermissions().size(), "aucune permission supplémentaire implicite");
        assertTrue(spec.refreshTypes().contains("npc.list"));
        assertTrue(spec.refreshTypes().contains("npc.citizens.list"), "la position est réactualisée");
    }

    /** La dernière action enfilée d'un type donné, pour inspecter ses paramètres réels. */
    private AgentActionRow latestOfType(String type) {
        return store.latestActionOfType(TestConfig.AGENT_ID, type)
                .orElseThrow(() -> new IllegalStateException("aucune action « " + type + " » enfilée"));
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

    // ---- #165 bis : nom en jeu, nom de définition, et couleur du nom ------------------------

    /**
     * Le cas « Tan » : le PNJ a été renommé en jeu, mais la définition RPGQuest garde son ancien
     * nom. Le relevé {@code npc.list} ne connaît que la définition — afficher ce nom-là sous le
     * libellé « Nom en jeu » montrait un nom périmé indéfiniment.
     */
    private static final String NPC_RENAMED_IN_GAME = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"withDefinition\":1,\"withoutDefinition\":0,"
            + "\"bound\":1,\"withWarnings\":0,\"definedIds\":[\"tan\"],\"canonicalIds\":[\"tan\"],"
            + "\"npcs\":[{\"id\":\"tan\",\"displayName\":\"Ancien Nom\",\"logicalDefinitionPresent\":true,"
            + "\"citizensBindingPresent\":true,\"citizensNumericId\":9,\"bindingCount\":1,\"enabled\":true,"
            + "\"description\":null,\"role\":\"villager\",\"definedDialogueId\":\"rpgquest:tan\","
            + "\"hasDialogue\":true,\"dialogueId\":\"rpgquest:tan\",\"dialogueNodes\":3,\"dialogueChoices\":4,"
            + "\"dialogueStartsQuests\":[\"rpgquest:first_steps\"],\"questsGiven\":[\"rpgquest:crystal_hunt\"],"
            + "\"questsReferenced\":[\"rpgquest:crystal_hunt\"],\"sources\":[\"DEFINITION\",\"BINDING\"],"
            + "\"state\":\"LINKED\",\"warnings\":[]}]}";

    /** Citizens connaît le nom réellement appliqué en jeu. */
    private static final String CITIZENS_RENAMED = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":true}]}";

    @Test
    void theListStaysSearchableByTheNewInGameName() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_RENAMED);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        int item = page.indexOf("data-res-id=\"tan\"");
        assertTrue(item >= 0, "la fiche du PNJ est rendue");
        String filterText = page.substring(page.indexOf("data-filter-text=\"", item - 400),
                page.indexOf("data-res-id=\"tan\"", item));
        assertTrue(filterText.contains("Nouveau Nom"), "le nouveau nom est cherchable");
        assertTrue(filterText.contains("Ancien Nom"), "l'ancien nom de définition reste cherchable");
    }

    /** Localisation Citizens réelle, avec l'état de présence et la fraîcheur du relevé. */
    private static final String CITIZENS_WITH_LOCATION = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":true,\"world\":\"world_hub\",\"x\":12.25,\"y\":70.0,\"z\":-33.5,"
            + "\"yaw\":90.0,\"pitch\":0.0,\"liveLocation\":true,\"shouldSpawn\":true,"
            + "\"chunkLoaded\":true}]}";

    /** Même PNJ, non apparu : Citizens n'a qu'une dernière position enregistrée. */
    private static final String CITIZENS_STORED_ONLY = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":false,\"world\":\"world_hub\",\"x\":12.25,\"y\":70.0,\"z\":-33.5,"
            + "\"yaw\":90.0,\"pitch\":0.0,\"liveLocation\":false,\"shouldSpawn\":true,"
            + "\"chunkLoaded\":false}]}";

    @Test
    void renamingNeverTouchesTheLogicalIdNorTheDialogueAndQuestLinks() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_RENAMED);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        // L'action postée ne porte que l'id logique et le nouveau nom : rien d'autre ne peut bouger.
        // Issue #197 : c'est désormais l'action COORDONNÉE qui est proposée — une seule, au lieu
        // d'un renommage limité à la couche Citizens.
        assertTrue(page.contains("name=\"type\" value=\"npc.identity.rename\""), "action de renommage");
        assertTrue(page.contains("name=\"npc_id\" value=\"tan\""), "ciblage par id logique");
        // Et les liens restent affichés sur la fiche.
        assertTrue(page.contains("rpgquest:tan"), "lien dialogue conservé");
        assertTrue(page.contains("href=\"/quests?q=crystal_hunt\""), "lien quête conservé");
    }

    // ---- Création complète d'un PNJ depuis le panel -----------------------------------------

    @Test
    void partialCoordinatesAreRefusedWithoutCreatingAnything() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        // Monde fourni, Y et Z manquants : refus explicite, aucune action enfilée.
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.provision&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&display_name=" + enc("Marchand de Lune") + "&world=world_hub&x=10");
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="),
                "localisation partielle refusée");
        assertEquals(0, pendingFor(TestConfig.AGENT_ID), "aucune action enfilée");
    }

    @Test
    void anInvalidSkinUrlIsRefusedBeforeAnythingIsCreated() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.provision&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&display_name=" + enc("Marchand") + "&skin_source=url"
                + "&skin_url=" + enc("https://example.com/skin.png"));
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="), "URL hors MineSkin refusée");
        assertEquals(0, pendingFor(TestConfig.AGENT_ID));
    }

    @Test
    void aValidCreationWithoutLocationIsQueuedAndAsksForAGuidePlacement() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.provision&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&display_name=" + enc("<gold>Marchand de Lune</gold>") + "&confirm=true");
        assertEquals(303, res.statusCode());
        assertFalse(res.headers().firstValue("Location").orElse("").contains("err="), res.body());

        AgentActionRow queued = latestOfType("npc.citizens.provision");
        assertEquals("<gold>Marchand de Lune</gold>", queued.params().get("display_name"),
                "le nom stylé est transmis tel quel");
        assertFalse(queued.params().containsKey("world"), "aucune position imposée");
        assertFalse(queued.params().containsKey("x"));
    }

    @Test
    void anExplicitLocationIsTransmittedExactlyAsGiven() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.provision&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&display_name=" + enc("Marchand") + "&world=world_hub&x=12.5&y=70&z=-33.5"
                + "&yaw=180&pitch=0&confirm=true");
        assertEquals(303, res.statusCode());

        AgentActionRow queued = latestOfType("npc.citizens.provision");
        assertEquals("world_hub", queued.params().get("world"));
        assertEquals("12.5", queued.params().get("x"));
        assertEquals("-33.5", queued.params().get("z"));
        assertEquals("180", queued.params().get("yaw"));
    }

    @Test
    void anOutOfBoundsHeightIsRefused() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.provision&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&display_name=" + enc("Marchand") + "&world=world_hub&x=0&y=99999&z=0");
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="), "Y hors bornes refusé");
    }

    @Test
    void theCreationActionRefreshesBothSurveysSoTheListShowsTheNewNpc() throws Exception {
        start();
        AgentActionCatalog.Spec spec = AgentActionCatalog.spec("npc.citizens.provision").orElseThrow();

        assertTrue(spec.refreshTypes().contains("npc.list"), "catalogue RPGQuest réactualisé");
        assertTrue(spec.refreshTypes().contains("npc.citizens.list"), "relevé Citizens réactualisé");
    }

    @Test
    void theCreationActionRequiresAllThreePermissionsAndGrantsNoneImplicitly() throws Exception {
        start();
        AgentActionCatalog.Spec spec = AgentActionCatalog.spec("npc.citizens.provision").orElseThrow();

        // Les effets produits sont ceux de trois permissions : les trois sont exigées.
        assertTrue(spec.requiredPermissions().contains(
                com.lodygames.rpgquest.panel.authz.Permission.NPC_SPAWN_WRITE), "apparition");
        assertTrue(spec.requiredPermissions().contains(
                com.lodygames.rpgquest.panel.authz.Permission.NPC_WRITE), "définition");
        assertTrue(spec.requiredPermissions().contains(
                com.lodygames.rpgquest.panel.authz.Permission.NPC_BIND_WRITE), "liaison / skin");
        assertEquals(3, spec.requiredPermissions().size(), "ni plus, ni moins");
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

    private AgentStore store;

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

    // ---- Comportements Citizens : « regarder les joueurs » et promenade (issue #165) -----------

    /** Relevé portant les comportements : regard actif, promenade active et ancrée. */
    private static final String CITIZENS_BEHAVIOUR_ACTIVE = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":true,\"world\":\"world_hub\",\"x\":12.25,\"y\":70.0,\"z\":-33.5,"
            + "\"yaw\":90.0,\"pitch\":0.0,\"liveLocation\":true,\"shouldSpawn\":true,"
            + "\"chunkLoaded\":true,\"lookCloseEnabled\":true,\"lookCloseRange\":8.0,"
            + "\"wanderEnabled\":true,\"wanderProvider\":\"wander\",\"wanderWaypoints\":0,"
            + "\"wanderWorld\":\"world_hub\",\"wanderX\":100.0,\"wanderY\":64.0,\"wanderZ\":-20.0,"
            + "\"wanderXRange\":12,\"wanderYRange\":2}]}";

    /** Même PNJ, mais porteur d'une PATROUILLE linéaire : activer la promenade l'écraserait. */
    private static final String CITIZENS_WITH_PATROL = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":true,\"world\":\"world_hub\",\"x\":12.25,\"y\":70.0,\"z\":-33.5,"
            + "\"yaw\":90.0,\"pitch\":0.0,\"liveLocation\":true,\"shouldSpawn\":true,"
            + "\"chunkLoaded\":true,\"lookCloseEnabled\":false,\"lookCloseRange\":10.0,"
            + "\"wanderEnabled\":false,\"wanderProvider\":\"linear\",\"wanderWaypoints\":7,"
            + "\"wanderWorld\":null,\"wanderX\":null,\"wanderY\":null,\"wanderZ\":null,"
            + "\"wanderXRange\":25,\"wanderYRange\":3}]}";

    @Test
    void theLookCloseActionTransmitsTheStateAndTheRange() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        assertEquals(303, post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.lookclose&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&npc_id=tan&enabled=true&range=16&confirm=true").statusCode());

        AgentActionRow queued = latestOfType("npc.citizens.lookclose");
        assertEquals("tan", queued.params().get("npc_id"));
        assertEquals("true", queued.params().get("enabled"));
        assertEquals("16", queued.params().get("range"));
    }

    @Test
    void anAmbiguousLookCloseStateIsRefusedBeforeReachingTheServer() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        int before = pendingFor(TestConfig.AGENT_ID);

        for (String enabled : new String[] {"", "toggle", "oui"}) {
            post("/agents/action", "_csrf=" + token
                    + "&type=npc.citizens.lookclose&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                    + "&npc_id=tan&enabled=" + enabled + "&confirm=true");
        }
        assertEquals(before, pendingFor(TestConfig.AGENT_ID), "aucune action ne doit être enfilée");
    }

    @Test
    void anOutOfBoundsLookCloseRangeIsRefused() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        int before = pendingFor(TestConfig.AGENT_ID);

        for (String range : new String[] {"0", "-5", "65", "abc"}) {
            post("/agents/action", "_csrf=" + token
                    + "&type=npc.citizens.lookclose&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                    + "&npc_id=tan&enabled=true&range=" + range + "&confirm=true");
        }
        assertEquals(before, pendingFor(TestConfig.AGENT_ID));
    }

    @Test
    void theWanderActionTransmitsStateAnchorAndZone() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        assertEquals(303, post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.wander&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&npc_id=tan&enabled=true&world=world_hub&x=100.5&y=64&z=-20.5"
                + "&x_range=10&y_range=1&confirm=true").statusCode());

        AgentActionRow queued = latestOfType("npc.citizens.wander");
        assertEquals("true", queued.params().get("enabled"));
        assertEquals("world_hub", queued.params().get("world"));
        assertEquals("100.5", queued.params().get("x"));
        assertEquals("10", queued.params().get("x_range"));
        assertEquals("1", queued.params().get("y_range"));
        assertNull(queued.params().get("confirm_replace"), "non coché = non confirmé");
    }

    @Test
    void disablingWanderCarriesNeitherAnchorNorZone() throws Exception {
        // Ne transmettre que l'état évite de laisser croire qu'un réglage a été enregistré au passage.
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        assertEquals(303, post("/agents/action", "_csrf=" + token
                + "&type=npc.citizens.wander&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&npc_id=tan&enabled=false&confirm=true").statusCode());

        AgentActionRow queued = latestOfType("npc.citizens.wander");
        assertEquals("false", queued.params().get("enabled"));
        assertNull(queued.params().get("world"));
        assertNull(queued.params().get("x_range"));
    }

    @Test
    void aPartialWanderAnchorIsRefusedByThePanel() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        int before = pendingFor(TestConfig.AGENT_ID);

        for (String anchor : new String[] {"&world=world_hub", "&world=world_hub&x=10",
            "&x=10&y=64&z=10"}) {
            post("/agents/action", "_csrf=" + token
                    + "&type=npc.citizens.wander&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                    + "&npc_id=tan&enabled=true&x_range=10&y_range=1" + anchor + "&confirm=true");
        }
        assertEquals(before, pendingFor(TestConfig.AGENT_ID), "aucune ancre partielle ne passe");
    }

    @Test
    void anUnboundedWanderZoneIsRefused() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        int before = pendingFor(TestConfig.AGENT_ID);

        for (String zone : new String[] {"&x_range=0&y_range=1", "&x_range=65&y_range=1",
            "&x_range=10&y_range=33", "&x_range=&y_range=1", "&x_range=10&y_range=-1"}) {
            post("/agents/action", "_csrf=" + token
                    + "&type=npc.citizens.wander&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                    + "&npc_id=tan&enabled=true" + zone + "&confirm=true");
        }
        assertEquals(before, pendingFor(TestConfig.AGENT_ID));
    }

    @Test
    void replayingTheSameWanderRequestNeverInvertsTheState() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        for (int i = 0; i < 3; i++) {
            post("/agents/action", "_csrf=" + token
                    + "&type=npc.citizens.wander&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                    + "&npc_id=tan&enabled=false&confirm=true");
            assertEquals("false", latestOfType("npc.citizens.wander").params().get("enabled"),
                    "chaque rejeu enfile le MÊME état, jamais son inverse");
        }
    }

    // ================================================================================
    //  Issue #197 — un seul nom, une apparence décrite, et un refus annoncé avant le clic
    // ================================================================================

    /** Le cas d'Elias : PNJ joueur, apparence bien visible en jeu, source inconnue de RPGQuest. */
    private static final String CITIZENS_PLAYER_NO_SOURCE = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":true,\"world\":\"world_hub\",\"x\":12.25,\"y\":70.0,\"z\":-33.5,"
            + "\"yaw\":90.0,\"pitch\":0.0,\"liveLocation\":true,\"shouldSpawn\":true,"
            + "\"chunkLoaded\":true,\"entityType\":\"PLAYER\",\"skinSourceKind\":null,"
            + "\"skinSourceValue\":null}]}";

    /** Même PNJ, une fois son apparence définie depuis le panel. */
    private static final String CITIZENS_PLAYER_WITH_SOURCE = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":true,\"world\":\"world_hub\",\"x\":12.25,\"y\":70.0,\"z\":-33.5,"
            + "\"yaw\":90.0,\"pitch\":0.0,\"liveLocation\":true,\"shouldSpawn\":true,"
            + "\"chunkLoaded\":true,\"entityType\":\"PLAYER\",\"skinSourceKind\":\"URL\","
            + "\"skinSourceValue\":\"https://minesk.in/abcd1234\"}]}";

    /** PNJ non joueur : la question du skin ne se pose pas. */
    private static final String CITIZENS_VILLAGER = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":0,\"linked\":1,"
            + "\"citizens\":[{\"numericId\":9,\"uuid\":\"33333333-3333-3333-3333-333333333333\","
            + "\"name\":\"Nouveau Nom\",\"linkedNpcId\":\"tan\",\"availableForBinding\":false,"
            + "\"spawned\":true,\"world\":\"world_hub\",\"x\":12.25,\"y\":70.0,\"z\":-33.5,"
            + "\"yaw\":90.0,\"pitch\":0.0,\"liveLocation\":true,\"shouldSpawn\":true,"
            + "\"chunkLoaded\":true,\"entityType\":\"VILLAGER\",\"skinSourceKind\":null,"
            + "\"skinSourceValue\":null}]}";

    /** PNJ Citizens sans aucune fiche RPGQuest : le cas de « Help ». */
    private static final String CITIZENS_ORPHAN_PLAYER = "{"
            + "\"citizensAvailable\":true,\"total\":1,\"available\":1,\"linked\":0,"
            + "\"citizens\":[{\"numericId\":7,\"uuid\":\"5e081a06-6596-47a3-b769-aef5fcd9e676\","
            + "\"name\":\"Help\",\"linkedNpcId\":null,\"availableForBinding\":true,"
            + "\"spawned\":false,\"entityType\":\"PLAYER\",\"skinSourceKind\":null,"
            + "\"skinSourceValue\":null}]}";

    /** Le formulaire HTML qui contient {@code needle}, bornes comprises. */
    private static String formAround(String page, String needle) {
        int at = page.indexOf(needle);
        assertTrue(at > 0, "marqueur introuvable : " + needle);
        int open = page.lastIndexOf("<form", at);
        int close = page.indexOf("</form>", at);
        assertTrue(open >= 0 && close > open, "formulaire mal borné autour de " + needle);
        return page.substring(open, close);
    }

    @Test
    void theCoordinatedRenameIsEnqueuedWithTheLogicalIdAndNothingElse() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_PLAYER_WITH_SOURCE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());

        post("/agents/action", "_csrf=" + token
                + "&type=npc.identity.rename&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                + "&npc_id=tan&npc_ctx=tan&name=" + URLEncoder.encode("Elias", StandardCharsets.UTF_8)
                + "&confirm=true");

        AgentActionRow row = latestOfType("npc.identity.rename");
        assertEquals("tan", row.params().get("npc_id"), "ciblage par identifiant logique");
        assertEquals("Elias", row.params().get("name"));
        assertEquals(2, row.params().size(), "aucun autre paramètre : rien d'autre ne peut bouger");
    }

    @Test
    void anEmptyOrOverlongNameIsRefusedWithoutEnqueuingAnything() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_PLAYER_WITH_SOURCE);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        int before = pendingFor(TestConfig.AGENT_ID);

        for (String name : new String[] {"", "   ", "x".repeat(49)}) {
            post("/agents/action", "_csrf=" + token
                    + "&type=npc.identity.rename&agent=" + TestConfig.AGENT_ID + "&return=/npcs"
                    + "&npc_id=tan&npc_ctx=tan&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&confirm=true");
        }
        assertEquals(before, pendingFor(TestConfig.AGENT_ID), "aucun renommage invalide n'est enfilé");
    }

    // ================================================================================
    //  Issue #325 — parcours simple : une création, une fiche courte, des formulaires sans jargon
    // ================================================================================

    private void startWithContent() throws Exception {
        Path content = tmp.resolve("content");
        java.nio.file.Files.createDirectories(content.resolve("dialogues"));
        java.nio.file.Files.createDirectories(content.resolve("quests"));
        java.nio.file.Files.createDirectories(content.resolve("stories"));
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1", content.toString()),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private java.util.List<AgentActionRow> pendingOfType(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> type.equals(a.type()) && !a.status().terminal()).toList();
    }

    @Test
    void theMainPageHasOneCreateButtonOneRefreshAndNoTechnicalClutter() throws Exception {
        start();
        runListWithSuccess(NPC_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        assertEquals(1, count(page, "Créer un PNJ</button>"), "un seul bouton de création");
        assertFalse(page.contains("Nouvelle définition PNJ"), "plus de second parcours de création");
        assertTrue(page.contains("action=\"/npcs/refresh\"") && page.contains("Actualiser les PNJ</button>"),
                "une seule actualisation commune");
        assertFalse(page.contains(">Catalogue RPGQuest<") || page.contains("Citizens</button>"),
                "plus de deux boutons de rafraîchissement");
        assertFalse(page.contains("Registre canonique"), "le registre est sur la page de diagnostic");
        assertFalse(page.contains("peut exister avant"), "plus de phrase sur la définition logique");
        assertFalse(page.contains("avec définition ·"), "plus de statistiques compactées");
        assertTrue(page.contains("href=\"/npcs/diagnostic\""), "le diagnostic a sa page");
        assertTrue(page.contains("<strong>2</strong> PNJ"), "un total simple");
        for (String jargon : new String[] {"canonique", "runtime", "heartbeat", "PDC", "YAML", "binding"}) {
            assertFalse(page.toLowerCase(java.util.Locale.ROOT).contains(jargon.toLowerCase(java.util.Locale.ROOT)),
                    "pas de jargon dans le parcours normal : " + jargon);
        }
    }

    @Test
    void theCreateFormAsksOnlyNameLocationAndAppearance() throws Exception {
        start();
        runListWithSuccess(NPC_DETAILS);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        String form = formAround(page, "action=\"/npcs/create\"");

        assertTrue(form.contains("name=\"display_name\"") && form.contains("maxlength=\"48\""), "nom, 48 au plus");
        assertTrue(form.contains("data-charcount-for=\"npc-new-name\"") && form.contains("<span class=\"cc-n\">0</span>/48"),
                "compteur 0/48 visible");
        assertTrue(form.contains("name=\"place\"") && form.contains("À côté du Guide"), "emplacement");
        assertTrue(form.contains("value=\"default\" selected>Apparence par défaut"), "apparence par défaut choisie");
        assertTrue(form.contains("href=\"https://mineskin.org/skins\" target=\"_blank\""), "catalogue MineSkin, nouvel onglet");
        assertTrue(form.contains("name=\"request_token\""), "jeton anti-doublon");
        assertFalse(form.contains("npc_id"), "aucun identifiant à saisir");
        assertFalse(form.contains("<b>Exemple</b>") || form.contains("<b>Défaut</b>"), "plus de blocs Exemple / Défaut");
        assertTrue(form.contains("Créer le PNJ</button>"));
    }

    @Test
    void creatingQueuesTheInitialDialogueThenTheNpcAndNeverTwice() throws Exception {
        startWithContent();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        String form = "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&display_name=Lucien"
                + "&place=auto&skin_source=default&request_token=tok1234567890abcd";

        HttpResponse<String> first = post("/npcs/create", form);
        assertEquals(303, first.statusCode());
        assertTrue(first.headers().firstValue("Location").orElse("").contains("focus=lucien"));
        String yaml = java.nio.file.Files.readString(tmp.resolve("content/dialogues/lucien.yml"));
        assertTrue(yaml.contains("Salut !") && yaml.contains("speaker: \"Lucien\""), "dialogue propre à Lucien");
        assertFalse(yaml.contains("START_QUEST"), "aucune quête proposée");

        assertEquals(1, pendingOfType("content.publish").size(), "publication du dialogue");
        AgentActionRow provision = pendingOfType("npc.citizens.provision").get(0);
        assertEquals("lucien", provision.params().get("npc_id"));
        assertEquals("rpgquest:lucien", provision.params().get("dialogue_id"));
        assertNull(provision.params().get("skin_url"), "skin vide = apparence par défaut côté plugin");

        // Double clic / renvoi : même jeton, rien de plus.
        post("/npcs/create", form);
        assertEquals(1, pendingOfType("npc.citizens.provision").size(), "jamais deux créations");
        assertEquals(1, pendingOfType("content.publish").size());
    }

    @Test
    void anOldCreationSuccessDisappearsOnceTheNpcIsDeleted() throws Exception {
        startWithContent();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        post("/npcs/create", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&display_name=Test+Claude"
                + "&place=auto&skin_source=default&request_token=tok1234567890efgh");
        AgentActionRow provision = pendingOfType("npc.citizens.provision").get(0);
        store.recordResult(provision.id(), TestConfig.AGENT_ID,
                com.lodygames.rpgquest.panel.agent.AgentActionStatus.SUCCESS, "", "ok", "{}",
                java.time.Instant.now());
        assertTrue(get("/npcs?agent=" + TestConfig.AGENT_ID).body().contains("a été créé"),
                "avant actualisation, le succès est annoncé");

        store.createAction(TestConfig.AGENT_ID, "npc.citizens.delete",
                Map.of("npc_id", "test_claude", "citizens_id", "9"), TestConfig.OWNER_USERNAME);
        String after = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertFalse(after.contains("a été créé"), "un PNJ supprimé n'est plus annoncé comme créé");
        assertFalse(after.contains("Création de « Test Claude »"), "ni comme en cours");
    }

    @Test
    void aSecondNpcWithTheSameNameGetsTheNextFreeId() throws Exception {
        startWithContent();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        post("/npcs/create", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&display_name=Guard&place=auto&skin_source=default&request_token=tokAAAAAAAAAAAA");
        assertEquals("guard_2", pendingOfType("npc.citizens.provision").get(0).params().get("npc_id"),
                "« guard » est déjà pris");
    }

    @Test
    void aSkinLinkThatIsNotHttpsIsRefusedSimplyAndNothingIsCreated() throws Exception {
        startWithContent();
        runListWithSuccess("npc.list", NPC_DETAILS);
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> r = post("/npcs/create", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&display_name=Lucien&place=auto&skin_source=url&skin_url=minesk.in/abcdefgh&request_token=tokBBBBBBBBBBBB");
        assertTrue(java.net.URLDecoder.decode(r.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8)
                .contains("Le lien doit commencer par https://"));
        assertTrue(pendingOfType("npc.citizens.provision").isEmpty());
        assertFalse(java.nio.file.Files.exists(tmp.resolve("content/dialogues/lucien.yml")), "aucun dialogue écrit");
        // HTTPS ne suffit pas : seul MineSkin est accepté.
        assertTrue(AgentActionCatalog.mineSkinProblem("https://example.com/skin.png").contains("MineSkin"));
        assertNull(AgentActionCatalog.mineSkinProblem("https://minesk.in/f534ead9d5a3413e9bb51bce9b211995"));
    }

    // Issue #13 — encounter.list : un modèle, deux occurrences (une présente, une désactivée), un
    // fichier refusé, un événement.
    private static final String ENCOUNTER_DETAILS = "{\"available\":true,\"enabled\":true,\"worlds\":[\"wild\"],"
            + "\"maxPerWorld\":200,\"templates\":[{\"id\":\"forest_bandit\",\"displayName\":\"Brigand des bois\","
            + "\"npcId\":\"encounter_bandit\",\"role\":\"brigand\",\"family\":\"forêt\","
            + "\"biomes\":[\"minecraft:forest\"],\"worlds\":[],\"weight\":10,\"maxSites\":0,\"enabled\":true,"
            + "\"skinUrl\":null,\"sites\":2}],\"issues\":{\"broken.yml\":\"biome manquant\"},"
            + "\"sites\":[{\"id\":\"enc_wild_forest_3_-2\",\"world\":\"wild\",\"biomeKey\":\"minecraft:forest\","
            + "\"biomeInstance\":\"i\",\"x\":10,\"y\":70,\"z\":-20,\"templateId\":\"forest_bandit\","
            + "\"npcId\":\"encounter_bandit\",\"citizensId\":5,\"citizensUuid\":\"5e081a06-6596-47a3-b769-aef5fcd9e676\","
            + "\"citizensPresent\":true,\"status\":\"ACTIVE\",\"lastError\":\"\",\"createdAt\":\"t\",\"updatedAt\":\"t\","
            + "\"appearance\":\"FAILED : MineSkin ne répond pas\"},"
            + "{\"id\":\"enc_wild_forest_4_-2\",\"world\":\"wild\",\"biomeKey\":\"minecraft:forest\","
            + "\"biomeInstance\":\"j\",\"x\":300,\"y\":66,\"z\":-20,\"templateId\":\"forest_bandit\","
            + "\"npcId\":\"encounter_bandit\",\"citizensId\":-1,\"citizensUuid\":null,\"citizensPresent\":false,"
            + "\"status\":\"DISABLED\",\"lastError\":\"aucun sol sûr\",\"createdAt\":\"t\",\"updatedAt\":\"t\"}],"
            + "\"events\":[{\"at\":\"2026-10-14T01:00:00Z\",\"world\":\"wild\",\"instance\":\"i\","
            + "\"outcome\":\"CREATED\",\"detail\":\"enc_wild_forest_3_-2\"}],\"generatedAtEpochMs\":1}";

    @Test
    void encounterNpcIsNotOfferedForAttachment() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_ONLY_GUARD);
        runListWithSuccess("npc.citizens.list", CITIZENS_WITH_STAN.replace("\"linkedNpcId\":null",
                "\"linkedNpcId\":null,\"encounterSiteId\":\"enc_wild_forest_3_-2\""));
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertFalse(page.contains("Rattacher à RPGQuest</button>"), "un PNJ récurrent n'est pas « à rattacher »");
        assertTrue(page.contains("href=\"/npcs/encounters\""));
        String diag = get("/npcs/diagnostic?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(diag.contains("PNJ récurrent"));
    }

    @Test
    void encountersPageListsTemplatesSitesAndValidatesSiteActions() throws Exception {
        start();
        String empty = get("/npcs/encounters?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(empty.contains("encore été relevé"));
        runListWithSuccess("encounter.list", ENCOUNTER_DETAILS);
        String page = get("/npcs/encounters?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Brigand des bois") && page.contains("forest_bandit"));
        assertTrue(page.contains("broken.yml") && page.contains("biome manquant"), "fichier refusé visible");
        assertTrue(page.contains("enc_wild_forest_3_-2") && page.contains("/tp @s 10 70 -20"));
        assertTrue(page.contains("présent") && page.contains("désactivée") && page.contains("aucun sol sûr"));
        assertTrue(page.contains("CREATED"));
        assertTrue(page.contains("apparence en échec : MineSkin ne répond pas"), "état de l'apparence visible");
        assertTrue(page.contains("value=\"encounter.remove\"") && page.contains("value=\"encounter.retry\""));

        String token = csrf(page);
        String base = "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&return=/npcs/encounters";
        HttpResponse<String> noConfirm = post("/agents/action", base + "&type=encounter.remove&siteId=enc_wild_forest_3_-2");
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="), "confirmation obligatoire");
        HttpResponse<String> badId = post("/agents/action", base + "&type=encounter.remove&siteId=wp_wild_1&confirm=true");
        assertTrue(badId.headers().firstValue("Location").orElse("").contains("err="), "jamais un waypoint");
        HttpResponse<String> ok = post("/agents/action", base + "&type=encounter.disable&siteId=enc_wild_forest_3_-2&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(ok.headers().firstValue("Location").orElse("").startsWith("/npcs/encounters"));
        assertEquals("enc_wild_forest_3_-2", pendingOfType("encounter.disable").get(0).params().get("siteId"));
        assertEquals(303, post("/agents/action", base + "&type=encounter.templates.reload").statusCode());
        assertEquals(1, pendingOfType("encounter.templates.reload").size());
    }

    @Test
    void encounterTemplatesCanBeCreatedEditedToggledAndDeletedFromThePage() throws Exception {
        start();
        runListWithSuccess("encounter.list", ENCOUNTER_DETAILS);
        String page = get("/npcs/encounters?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Nouveau modèle") && page.contains("value=\"encounter.template.create\""));
        assertTrue(page.contains("value=\"encounter.template.update\"") && page.contains("Dupliquer"));
        assertTrue(page.contains("value=\"encounter.template.toggle\"") && page.contains("Désactiver le modèle"));
        assertTrue(page.contains("value=\"encounter.template.delete\"") && page.contains("acknowledge_sites"),
                "le modèle a des occurrences : l'accord explicite est demandé");

        String base = "_csrf=" + csrf(page) + "&agent=" + TestConfig.AGENT_ID + "&return=/npcs/encounters";
        String form = "&id=swamp_alchemist&display_name=Alchimiste&enabled=true&weight=15"
                + "&biomes=" + URLEncoder.encode("Swamp\nminecraft:mangrove_swamp, ", StandardCharsets.UTF_8);
        assertEquals(303, post("/agents/action", base + "&type=encounter.template.create" + form).statusCode());
        Map<String, String> created = pendingOfType("encounter.template.create").get(0).params();
        assertEquals("swamp,minecraft:mangrove_swamp", created.get("biomes"), "listes normalisées en CSV");
        assertEquals("15", created.get("weight"));
        assertEquals("true", created.get("enabled"));

        HttpResponse<String> noBiome = post("/agents/action", base
                + "&type=encounter.template.update&id=forest_bandit&display_name=Brigand");
        assertTrue(noBiome.headers().firstValue("Location").orElse("").contains("err="));
        HttpResponse<String> badSkin = post("/agents/action", base + "&type=encounter.template.update"
                + "&id=forest_bandit&display_name=Brigand&biomes=forest&skin_url=http://evil.example/s");
        assertTrue(badSkin.headers().firstValue("Location").orElse("").contains("err="));

        post("/agents/action", base + "&type=encounter.template.toggle&id=forest_bandit");
        assertEquals("false", pendingOfType("encounter.template.toggle").get(0).params().get("enabled"));

        HttpResponse<String> noConfirm = post("/agents/action", base + "&type=encounter.template.delete&id=forest_bandit");
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="), "suppression confirmée");
        post("/agents/action", base + "&type=encounter.template.delete&id=forest_bandit&confirm=true");
        Map<String, String> deleted = pendingOfType("encounter.template.delete").get(0).params();
        assertEquals("forest_bandit", deleted.get("id"));
        assertFalse(deleted.containsKey("acknowledge_sites"), "jamais d'accord implicite");
    }

    @Test
    void attachingAFreeCitizensNpcCreatesWhatIsMissingWithoutRecreatingIt() throws Exception {
        startWithContent();
        runListWithSuccess("npc.list", NPC_ONLY_GUARD);
        runListWithSuccess("npc.citizens.list", CITIZENS_WITH_STAN);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("à rattacher") && page.contains("Rattacher à RPGQuest</button>"));
        String token = csrf(page);
        assertEquals(303, post("/npcs/attach", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&citizens_id=7").statusCode());

        AgentActionRow def = pendingOfType("npc.definition.create").get(0);
        assertEquals("stan", def.params().get("npc_id"));
        assertEquals("Stan", def.params().get("display_name"));
        AgentActionRow link = pendingOfType("npc.citizens.link").get(0);
        assertEquals("7", link.params().get("citizens_id"));
        assertTrue(pendingOfType("npc.citizens.provision").isEmpty() && pendingOfType("npc.citizens.create").isEmpty(),
                "le personnage n'est jamais recréé");
        // Double clic : pas de second rattachement.
        post("/npcs/attach", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&citizens_id=7");
        assertEquals(1, pendingOfType("npc.citizens.link").size());
    }

    @Test
    void theFicheIsShortAndOffersTheSevenSections() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_PLAYER_WITH_SOURCE);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();

        for (String label : new String[] {"Modifier le nom", "Apparence", "Déplacer", "Promenade",
                "Regarder les joueurs", "Dialogue et quêtes", "Supprimer"}) {
            assertTrue(page.contains(label + "</button>") || page.contains(label + "</a>"), "section " + label);
        }
        assertTrue(page.contains("data-one-form"), "un seul formulaire ouvert à la fois");
        assertTrue(page.contains("Le nom affiché en jeu est différent : « Nouveau Nom »"), "message simple");
        assertFalse(page.contains("divergent"), "plus de « divergent »");
        assertFalse(page.contains("jamais modifié par un renommage") || page.contains("Jamais modifié par un renommage"),
                "plus de paragraphe sur l'identifiant");
        assertTrue(page.contains("Informations avancées") && page.contains("<code class=\"tid\"")
                && page.contains("peut-on-changer-lidentifiant-dun-pnj"), "identifiant replié + FAQ");
        String rename = formAround(page, "value=\"npc.identity.rename\"");
        assertTrue(rename.contains("data-charcount-for=") && rename.contains("/48</span>"), "compteur");
        assertTrue(rename.contains("Enregistrer le nom</button>"));
    }

    @Test
    void theSkinFormShowsOnlyTheActiveSourceField() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_PLAYER_WITH_SOURCE);
        String form = formAround(get("/npcs?agent=" + TestConfig.AGENT_ID).body(), "value=\"npc.citizens.skin\"");
        assertTrue(form.contains("value=\"url\" selected>Lien MineSkin"), "MineSkin par défaut");
        assertTrue(form.contains("data-switch-value=\"player\" hidden"), "le pseudo est masqué tant qu'on ne le choisit pas");
        assertTrue(form.contains("Lien du skin") && form.contains("Catalogue MineSkin"));
        assertTrue(form.contains("Appliquer</button>"));
        assertFalse(form.contains("asynchrone"), "plus d'explication technique");
    }

    @Test
    void anOldPlayerNpcWithoutRecordedSkinIsNoLongerBlockedFromRenaming() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_PLAYER_NO_SOURCE);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        assertFalse(page.contains("Le renommage sera refusé"), "plus de blocage annoncé");
        assertTrue(formAround(page, "value=\"npc.identity.rename\"").contains("apparence par défaut"),
                "une phrase dit ce qui va se passer");
    }

    @Test
    void theMoveFormUsesWordsForTheDirectionAndKeepsTheOrientationByDefault() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_WITH_LOCATION);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        String form = formAround(page, "value=\"npc.citizens.move\"");
        assertTrue(form.contains("Village (world_hub)"), "monde lisible");
        assertTrue(form.contains("value=\"12.3\"") || form.contains("value=\"12.2\""), "position préremplie");
        assertTrue(form.contains("value=\"north\">Le nord") && form.contains("value=\"up\">Vers le haut"));
        assertTrue(form.contains("value=\"\" selected>Sans changement"), "orientation gardée par défaut");
        assertTrue(form.contains("Confirmer le déplacement</label>"), "case de confirmation libellée");
        assertTrue(form.contains("Déplacer</button>"));

        String token = csrf(page);
        post("/agents/action", "_csrf=" + token + "&type=npc.citizens.move&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=tan&world=world_hub&x=10&y=70&z=-30&facing=north&tilt=&confirm=true");
        AgentActionRow move = latestOfType("npc.citizens.move");
        assertEquals("180", move.params().get("yaw"), "le nord = 180°");
        assertNull(move.params().get("pitch"), "l'inclinaison n'est pas touchée");
    }

    @Test
    void activeWanderShowsOneButtonThatStopsAndReturnsHome() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        String form = formAround(page, "Arrêter la promenade et revenir au départ");
        assertTrue(form.contains("name=\"return_home\" value=\"true\"") && form.contains("name=\"enabled\" value=\"false\""));
        assertTrue(page.contains("Le PNJ se promène autour de sa position de départ."));
        assertFalse(page.contains("name=\"x_range\""), "pas de réglages quand elle tourne");

        String token = csrf(page);
        post("/agents/action", "_csrf=" + token + "&type=npc.citizens.wander&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=tan&enabled=false&return_home=true&confirm=true");
        AgentActionRow stop = latestOfType("npc.citizens.wander");
        assertEquals("true", stop.params().get("return_home"));
        assertNull(stop.params().get("x_range"));
    }

    @Test
    void inactiveWanderAsksActivateRadiusHeightThenConfirmWithoutAnchorFields() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_WITH_PATROL);
        String form = formAround(get("/npcs?agent=" + TestConfig.AGENT_ID).body(), "Activer la promenade");
        int activate = form.indexOf("Activer la promenade");
        int radius = form.indexOf("name=\"x_range\"");
        int height = form.indexOf("name=\"y_range\"");
        int confirm = form.indexOf("Confirmer</button>");
        assertTrue(activate < radius && radius < height && height < confirm, "ordre demandé");
        assertTrue(form.contains("Distance maximale autour de sa position de départ."));
        assertTrue(form.contains("Écart de hauteur autorisé autour de sa position de départ."));
        assertFalse(form.contains("name=\"world\"") || form.contains("name=\"x\""), "aucun champ d'ancre");
        assertTrue(form.contains("Remplacer le trajet qu'il suit déjà"), "trajet existant : confirmation simple");
        assertTrue(form.contains("value=\"12\""), "rayon proposé raisonnable, pas 25");
    }

    @Test
    void lookAtPlayersSendsTheTickedStateNeverAToggle() throws Exception {
        start();
        runListWithSuccess("npc.list", NPC_RENAMED_IN_GAME);
        runListWithSuccess("npc.citizens.list", CITIZENS_BEHAVIOUR_ACTIVE);
        String page = get("/npcs?agent=" + TestConfig.AGENT_ID).body();
        String form = formAround(page, "value=\"npc.citizens.lookclose\"");
        assertTrue(form.contains("name=\"state\" value=\"on\" checked"), "la case reflète l'état actuel");
        assertTrue(form.contains("max=\"64\""));
        assertTrue(form.contains("Le PNJ regarde le joueur le plus proche dans cette distance."));
        String token = csrf(page);
        // Case décochée : l'état « désactivé » est envoyé, deux fois de suite le même.
        for (int i = 0; i < 2; i++) {
            post("/agents/action", "_csrf=" + token + "&type=npc.citizens.lookclose&agent=" + TestConfig.AGENT_ID
                    + "&return=/npcs&npc_id=tan&state_form=1&range=12&confirm=true");
            assertEquals("false", latestOfType("npc.citizens.lookclose").params().get("enabled"));
        }
        int before = pendingFor(TestConfig.AGENT_ID);
        post("/agents/action", "_csrf=" + token + "&type=npc.citizens.lookclose&agent=" + TestConfig.AGENT_ID
                + "&return=/npcs&npc_id=tan&state_form=1&state=on&range=65&confirm=true");
        assertEquals(before, pendingFor(TestConfig.AGENT_ID), "portée > 64 refusée");
    }

    @Test
    void theDiagnosticPageHoldsTheRegistryAndTheRawMessages() throws Exception {
        start();
        runListWithSuccess(NPC_DETAILS);
        String page = get("/npcs/diagnostic?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Diagnostic des PNJ") && page.contains("À quoi sert cette page"));
        assertTrue(page.contains("Registre des identifiants") && page.contains("Compteurs"));
        assertTrue(page.contains("Code technique : <code class=\"tid\">NOT_LINKED</code>"), "messages bruts ici");
    }

    @Test
    void refreshQueuesBothSurveysOnce() throws Exception {
        start();
        String token = csrf(get("/npcs?agent=" + TestConfig.AGENT_ID).body());
        post("/npcs/refresh", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID);
        post("/npcs/refresh", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID);
        assertEquals(1, pendingOfType("npc.list").size());
        assertEquals(1, pendingOfType("npc.citizens.list").size());
    }
}
