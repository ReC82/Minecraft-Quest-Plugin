package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #564 (#228-P1) — page « Portails » en lecture seule, d'après le relevé portal.list. */
class PortalsPageTest {

    private static final String WILD_GUARD = "{\"code\":\"WILD_WARNING\",\"title\":\"Avertissement avant d'entrer "
            + "dans le Wild\",\"rules\":[\"Avant chaque départ, une fenêtre Minecraft demande de confirmer.\"],"
            + "\"texts\":[{\"label\":\"Message\",\"value\":\"Le Wild est une zone dangereuse.\"}]}";
    private static final String CLAIMS_GUARD = "{\"code\":\"CLAIMS_ACCESS\",\"title\":\"Accès au monde des "
            + "claims\",\"rules\":[\"Moyen de retour garanti : une Pierre de retour est remise avant le départ.\"],"
            + "\"texts\":[]}";

    static final String RELEVE = "{\"roles\":{\"hub\":\"world_hub\",\"wild\":\"wild\",\"claims\":\"claims\"},"
            + "\"portals\":[{\"id\":\"hub_to_wild\",\"name\":\"Portail vers le Wild\",\"kind\":\"WORLD_PORTAL\","
            + "\"enabled\":true,\"file\":\"hub_to_wild.yml\",\"world\":\"world_hub\",\"worldLoaded\":true,"
            + "\"worldRole\":\"HUB\",\"min\":\"737, 66, -673\",\"max\":\"740, 69, -673\",\"size\":\"4 × 4 × 1\","
            + "\"destinationWorld\":\"wild\",\"destinationLoaded\":true,\"destinationRole\":\"WILD\","
            + "\"destinationId\":\"\",\"destinationPoint\":\"\",\"strategy\":\"RANDOM_SAFE\",\"arrival\":["
            + "\"Point sûr aléatoire entre 500 et 5000 blocs du point d'arrivée du monde.\"],\"trigger\":["
            + "\"Téléportation immédiate en entrant dans la zone.\"],\"guards\":[" + WILD_GUARD + "],"
            + "\"conditions\":[],\"issues\":[]},"
            + "{\"id\":\"hub_to_claims\",\"name\":\"Portail vers les Claims\",\"kind\":\"WORLD_PORTAL\","
            + "\"enabled\":true,\"file\":\"hub_to_claims.yml\",\"world\":\"world_hub\",\"worldLoaded\":true,"
            + "\"worldRole\":\"HUB\",\"min\":\"730, 66, -676\",\"max\":\"732, 69, -676\",\"size\":\"3 × 4 × 1\","
            + "\"destinationWorld\":\"claims\",\"destinationLoaded\":false,\"destinationRole\":\"CLAIMS\","
            + "\"destinationId\":\"\",\"destinationPoint\":\"\",\"strategy\":\"CLAIM_HOME\",\"arrival\":[],"
            + "\"trigger\":[],\"guards\":[" + CLAIMS_GUARD + "],\"conditions\":[],\"issues\":[{\"code\":"
            + "\"PORTAL_DESTINATION_NOT_LOADED\",\"severity\":\"error\",\"message\":\"Le monde d'arrivée « claims » "
            + "n'est pas chargé.\"}]}],"
            + "\"issues\":[{\"code\":\"PORTAL_FILE_SKIPPED\",\"severity\":\"error\",\"message\":\"Fichier "
            + "world-portals/broken.yml ignoré au chargement.\"}]}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void thePageIsInTheWorldNavigationGroupAndInvitesToRefresh() throws Exception {
        start();
        login();
        String page = get("/portals?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/portals\""), "entrée de navigation");
        assertTrue(page.contains(">Monde<"), "groupe de navigation Monde");
        assertTrue(page.contains("Aucun relevé chargé"), "aucun portail inventé");
        assertTrue(page.contains("value=\"portal.list\""), "seul Rafraîchir est proposé");
    }

    @Test
    void portalsShowRealZonesStrategiesGuardsAndProblems() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE);
        String page = get("/portals?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("hub_to_wild") && page.contains("hub_to_claims"));
        assertTrue(page.contains("737, 66, -673") && page.contains("740, 69, -673"), "vraies coordonnées");
        assertTrue(page.contains("point sûr aléatoire") && page.contains("claim du joueur, sinon point sûr"));
        assertTrue(page.contains("Avertissement Wild") && page.contains("Accès Claims + retour"), "gardes visibles");
        assertTrue(page.contains("Avec problème · 1"));
        assertTrue(page.contains("Fichier de portail ignoré"), "fichier ignoré affiché");
        assertTrue(page.contains("href=\"/worlds?agent="), "lien vers Mondes");
    }

    @Test
    void anEmptySearchOrFilterResultHasAnExplicitMessage() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE);
        String page = get("/portals?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("data-filter-empty=\"portals\" data-empty=\"Aucun portail\""),
                "message vide de recherche, caché tant qu'une carte s'affiche");
        assertTrue(page.contains("<p class=\"empty\" role=\"status\" hidden data-filter-empty"));
        assertTrue(page.contains("data-filter-chip=\"disabled\" data-empty=\"Aucun portail désactivé\""));
        assertTrue(page.contains("data-filter-chip=\"issue\" data-empty=\"Aucun portail avec problème\""));
    }

    @Test
    void theFicheShowsGuardTextsAndLinksToTheWorldsPage() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE);
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild&file=hub_to_wild.yml").body();
        assertTrue(wild.contains("1 · Emplacement") && wild.contains("4 × 4 × 1 blocs"));
        assertTrue(wild.contains("world-portals/hub_to_wild.yml"));
        assertTrue(wild.contains("entre 500 et 5000 blocs"));
        assertTrue(wild.contains("« Le Wild est une zone dangereuse. »"), "texte actuel de l'avertissement");
        assertTrue(wild.contains("/worlds?agent=" + TestConfig.AGENT_ID + "&amp;world=wild"), "lien vers le monde");
        String claims = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_claims").body();
        assertTrue(claims.contains("Pierre de retour"));
        assertTrue(claims.contains("Monde de destination non chargé") && claims.contains("/docs/portails#"), claims);
        String missing = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=inconnu").body();
        assertTrue(missing.contains("n'apparaît pas dans le dernier relevé"));
    }

    @Test
    void noMutatingPortalActionIsExposedAnywhereOnThePage() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE);
        for (String path : new String[] {"/portals?agent=" + TestConfig.AGENT_ID,
                "/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild"}) {
            String page = get(path).body();
            Matcher m = Pattern.compile("name=\"type\" value=\"([^\"]+)\"").matcher(page);
            while (m.find()) {
                assertFalse(m.group(1).startsWith("portal.") && !m.group(1).equals("portal.list"),
                        "action mutative exposée : " + m.group(1));
                assertFalse(m.group(1).startsWith("worldportal") || m.group(1).startsWith("player.teleport"),
                        "action mutative exposée : " + m.group(1));
            }
            assertFalse(page.contains("Créer un portail") || page.contains(">Activer<") || page.contains("Téléporter"));
        }
    }

    @Test
    void aMutatingPortalActionIsRefusedByTheCatalog() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=portal.create&agent="
                + TestConfig.AGENT_ID + "&return=/portals&id=lody_test");
        assertFalse(pollActions().contains("portal.create"), "aucune action portal.create mise en file");
        assertTrue(res.statusCode() >= 300, "refusé / redirigé");
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/portals");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action en file : " + poll.body());
        }
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + m.group(1) + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"status\":\"SUCCESS\",\"message\":\"ok\",\"details\":" + details + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60, "{}", "{}"));
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
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
