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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #178 — page {@code /cities} sur un vrai serveur HTTP : rendu de la règle du carré, limites,
 * chevauchements, ce qui part vers l'agent, validation et confirmation de suppression, et l'éditeur
 * de quête (objectif « Découvrir des villes » avec sélection par nom).
 */
class CitiesPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String CITIES = "{\"total\":2,\"minDistance\":5,\"maxDistance\":500,"
            + "\"defaultDistance\":50,\"pendingMoves\":[{\"player\":\"Lody\",\"cityId\":\"ville_faubourg\","
            + "\"requestedBy\":\"PlugAdmin\",\"expiresAt\":\"2026-10-13T20:00:00Z\"}],\"cities\":["
            + "{\"id\":\"ville_port_lily\",\"name\":\"Port-Lily\",\"world\":\"world_hub\",\"x\":100.5,"
            + "\"y\":70,\"z\":-20.5,\"centerX\":100,\"centerZ\":-21,\"distance\":50,\"active\":true,"
            + "\"createdAt\":\"2026-10-13T18:00:00Z\",\"worldLoaded\":true,\"minX\":50,\"maxX\":150,"
            + "\"minZ\":-71,\"maxZ\":29,\"width\":101,\"discoveries\":3,\"overlaps\":[\"ville_faubourg\"]},"
            + "{\"id\":\"ville_faubourg\",\"name\":\"Faubourg\",\"world\":\"world_hub\",\"x\":160.5,"
            + "\"y\":70,\"z\":-20.5,\"centerX\":160,\"centerZ\":-21,\"distance\":20,\"active\":false,"
            + "\"createdAt\":\"2026-10-13T18:05:00Z\",\"worldLoaded\":true,\"minX\":140,\"maxX\":180,"
            + "\"minZ\":-41,\"maxZ\":-1,\"width\":41,\"discoveries\":0,\"overlaps\":[\"ville_port_lily\"]}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void withoutASurveyThePageAsksToRefreshAndExplainsTheTool() throws Exception {
        start();
        String page = get("/cities?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Aucun relev"));
        assertTrue(page.contains("/rpgadmin ville outil"), "la marche à suivre est là");
        assertTrue(page.contains("href=\"/cities\""), "entrée de navigation « Villes »");
    }

    @Test
    void theListShowsTheSquareRuleDimensionsBoundsStateAndOverlaps() throws Exception {
        start();
        seed("city.list", CITIES);
        String page = get("/cities?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Port-Lily"));
        assertTrue(page.contains("ville_port_lily"));
        assertTrue(page.contains("La ville s&#39;étend de 50 blocs dans chaque direction depuis son centre")
                || page.contains("La ville s'étend de 50 blocs dans chaque direction depuis son centre"));
        assertTrue(page.contains("101 × 101 blocs"), "largeur totale affichée, pas seulement le rayon");
        assertTrue(page.contains("X 50 … 150 · Z -71 … 29"), "limites calculées, coins inclus");
        assertTrue(page.contains("3 joueur(s)"), "découvertes");
        assertTrue(page.contains("inactive"), "état");
        assertTrue(page.contains("chevauchement"));
        assertTrue(page.contains("le centre est le plus proche"), "règle de chevauchement expliquée");
        assertTrue(page.contains("<svg"), "aperçu du périmètre");
        assertTrue(page.contains("Déplacement en attente"), "un déplacement armé est relu");
        assertTrue(page.contains("data-filter-item=\"cities\""), "recherche par nom / monde");
    }

    @Test
    void renameAndResizeAreQueuedWithTheirParameters() throws Exception {
        start();
        seed("city.list", CITIES);
        String token = csrf(get("/cities?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.rename&return=/cities&id=ville_port_lily&name=" + enc("Port Lily  Nouveau")
                + "&confirm=true");
        AgentActionRow rename = queued("city.rename");
        assertEquals("ville_port_lily", rename.params().get("id"));
        assertEquals("Port Lily Nouveau", rename.params().get("name"));

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.resize&return=/cities&id=ville_port_lily&distance=75&confirm=true");
        assertEquals("75", queued("city.resize").params().get("distance"));
    }

    @Test
    void anOutOfRangeDistanceOrBadNameIsRefusedBeforeQueueing() throws Exception {
        start();
        seed("city.list", CITIES);
        String token = csrf(get("/cities?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.resize&return=/cities&id=ville_port_lily&distance=501&confirm=true");
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.resize&return=/cities&id=ville_port_lily&distance=4&confirm=true");
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.rename&return=/cities&id=ville_port_lily&name=X&confirm=true");
        assertEquals(0, count("city.resize"));
        assertEquals(0, count("city.rename"));
    }

    @Test
    void deletionRequiresAnExplicitConfirmationAndExplainsTheConsequences() throws Exception {
        start();
        seed("city.list", CITIES);
        String page = get("/cities?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("découverte(s) sont effacées"));
        assertTrue(page.contains("Désactiver la ville"), "l'alternative non destructive est proposée");

        String token = csrf(page);
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.delete&return=/cities&id=ville_faubourg");
        assertEquals(0, count("city.delete"), "sans case cochée : rien ne part");
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.delete&return=/cities&id=ville_faubourg&confirm=true");
        assertEquals("ville_faubourg", queued("city.delete").params().get("id"));
    }

    @Test
    void moveAndPerimeterTargetAConnectedPlayer() throws Exception {
        start();
        seed("city.list", CITIES);
        String token = csrf(get("/cities?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.move.arm&return=/cities&id=ville_port_lily&player=Lody&confirm=true");
        AgentActionRow arm = queued("city.move.arm");
        assertEquals("Lody", arm.params().get("player"));
        assertEquals("ville_port_lily", arm.params().get("id"));
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.perimeter.show&return=/cities&id=ville_port_lily&player=" + enc("pas un pseudo")
                + "&confirm=true");
        assertEquals(0, count("city.perimeter.show"), "pseudo invalide : refusé");
    }

    /** Issue #322 : Port-Lily a un PNJ central sorti de la ville et une arrivée distincte. */
    private static final String CITIES_WITH_ANCHORS = CITIES
            .replace("\"overlaps\":[\"ville_faubourg\"]}", "\"overlaps\":[\"ville_faubourg\"],"
                    + "\"centralNpcId\":\"guide\",\"npcDisplayName\":\"Guide du Hub\",\"npcStatus\":\"OUTSIDE\","
                    + "\"npcSeverity\":\"WARNING\",\"npcMessage\":\"« guide » a quitté la ville : il est à 61 blocs "
                    + "du centre\",\"npcWorld\":\"world_hub\",\"npcX\":161.2,\"npcY\":70,\"npcZ\":-20.0,"
                    + "\"npcLive\":false,\"npcDistance\":61,\"arrivalWorld\":\"world_hub\",\"arrivalX\":110.5,"
                    + "\"arrivalY\":71,\"arrivalZ\":-10.5,\"arrivalYaw\":90.0,\"arrivalPitch\":0.0,"
                    + "\"arrivalStatus\":\"SET\",\"arrivalSeverity\":\"OK\",\"arrivalMessage\":\"ok\"}");

    @Test
    void theCentralNpcDiagnosticAndTheDistinctArrivalAreShown() throws Exception {
        start();
        seed("city.list", CITIES_WITH_ANCHORS);
        seed("npc.list", "{\"npcs\":[{\"id\":\"guide\",\"displayName\":\"Guide du Hub\","
                + "\"logicalDefinitionPresent\":true},{\"id\":\"julius\",\"displayName\":\"Julius\","
                + "\"logicalDefinitionPresent\":true},{\"id\":\"fantome\",\"logicalDefinitionPresent\":false}]}");
        String page = get("/cities?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("PNJ central et arrivée"));
        assertTrue(page.contains("Guide du Hub (guide)"), "nom affiché + identifiant stable");
        assertTrue(page.contains("a quitté la ville"), "diagnostic du plugin relayé tel quel");
        assertTrue(page.contains("X 161 · Y 70 · Z -20 (dernière position enregistrée)"),
                "position relue, marquée comme non chargée");
        assertTrue(page.contains("X 110 · Y 71 · Z -11 · regard 90°"), "arrivée distincte affichée");
        assertTrue(page.contains("Revenir à l&#39;arrivée au centre") || page.contains("Revenir à l'arrivée au centre"));
        assertTrue(page.contains("<datalist id=\"dl-city-npc\">"), "sélecteur recherchable");
        assertTrue(page.contains("value=\"julius\" label=\"Julius\""));
        assertFalse(page.contains("value=\"fantome\""), "un id cité sans définition n'est pas proposé");
        // La ville sans PNJ ni arrivée garde le comportement de #178.
        assertTrue(page.contains("Centre de la ville (par défaut)"));
    }

    @Test
    void linkingUnlinkingAndArrivalActionsAreQueuedAndValidated() throws Exception {
        start();
        seed("city.list", CITIES);
        String token = csrf(get("/cities?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.npc.link&return=/cities&id=ville_port_lily&npc=" + enc("Guide du Hub")
                + "&confirm=true");
        assertEquals(0, count("city.npc.link"), "un nom affiché n'est pas une identité : refusé");
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.npc.link&return=/cities&id=ville_port_lily&npc=guide&confirm=true");
        assertEquals("guide", queued("city.npc.link").params().get("npc"));

        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.arrival.set&return=/cities&id=ville_port_lily&player=Lody&confirm=true");
        AgentActionRow arrival = queued("city.arrival.set");
        assertEquals("Lody", arrival.params().get("player"));
        assertEquals("ville_port_lily", arrival.params().get("id"));
        post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=city.arrival.clear&return=/cities&id=ville_port_lily&confirm=true");
        assertEquals("ville_port_lily", queued("city.arrival.clear").params().get("id"));
    }

    @Test
    void theQuestEditorOffersCitiesByNameAndTheTwoForms() throws Exception {
        start();
        seed("city.list", CITIES);
        String editor = get("/quests/new").body();
        assertTrue(editor.contains("DISCOVER_CITY"), "objectif au catalogue");
        assertTrue(editor.contains("Découvrir des villes"), "libellé français");
        assertTrue(editor.contains("<datalist id=\"dl-city\">"), "sélection recherchable");
        assertTrue(editor.contains("value=\"ville_port_lily\" label=\"Port-Lily\""), "par nom");
        assertTrue(editor.contains("Nouvelles découvertes seulement"), "règle de comptage expliquée");
    }

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

    private void seed(String type, String details) throws Exception {
        String token = csrf(get("/cities?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent="
                + TestConfig.AGENT_ID + "&return=/cities");
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
                + "\"message\":\"2 emplacement(s)\",\"details\":" + details + "}";
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
