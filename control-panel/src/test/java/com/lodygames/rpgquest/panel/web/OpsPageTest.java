package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Page {@code /ops} — « Exploitation serveur » (issue #95).
 *
 * <p>Dans cette configuration de test, <strong>aucun accès RCON n'est déclaré</strong> : c'est
 * exactement le cas que le ticket demande de traiter proprement (« Affiche clairement les fonctions
 * indisponibles »). Les tests vérifient donc que le redémarrage est annoncé indisponible
 * <em>avec son motif</em> et qu'aucune commande ne peut être déclenchée, plutôt qu'un bouton
 * décoratif.</p>
 */
class OpsPageTest {

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

    // ---- Accès -----------------------------------------------------------------------------

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        start();

        HttpResponse<String> response = get("/ops");

        assertEquals(303, response.statusCode());
        assertEquals("/login", response.headers().firstValue("Location").orElse(""));
    }

    @Test
    void theJsonEndpointsRefuseAnAnonymousCaller() throws Exception {
        start();

        assertEquals(401, get("/ops/logs.json").statusCode());
        assertEquals(401, get("/ops/state.json").statusCode());
    }

    @Test
    void restartingRequiresAPostAndACsrfToken() throws Exception {
        start();
        login();

        assertEquals(405, get("/ops/restart").statusCode());
        assertEquals(403, post("/ops/restart", "delay_seconds=0&confirm_word=REDEMARRER").statusCode(),
                "sans jeton CSRF, la requête doit être refusée");
    }

    // ---- État réel et fraîcheur ------------------------------------------------------------

    @Test
    void thePageShowsTheRealStateAndSaysHowFreshItIs() throws Exception {
        start();
        login();
        freshHeartbeat(7, 20, 3_600, "0.1.0-SNAPSHOT");

        String page = get("/ops").body();

        assertTrue(page.contains("<h1>Exploitation serveur</h1>"), page.substring(0, Math.min(600, page.length())));
        assertTrue(page.contains(">ONLINE<"), "vivacité de l'agent");
        assertTrue(page.contains(">7<"), "joueurs connectés réellement relevés");
        assertTrue(page.contains("capacité 20"));
        assertTrue(page.contains("0.1.0-SNAPSHOT"), "version du plugin");
        assertTrue(page.contains("1h 0min"), "uptime lisible");
        // La fraîcheur est explicite : ces valeurs datent du dernier relevé, ce n'est pas du direct.
        assertTrue(page.contains("ces valeurs datent du dernier heartbeat"), "fraîcheur annoncée");
    }

    @Test
    void withoutAnyHeartbeatThePageSaysThereIsNoDataRatherThanShowingZeros() throws Exception {
        start();
        login();

        String page = get("/ops").body();

        assertTrue(page.contains("aucune donnée : l'agent n'a jamais envoyé de relevé"), "aveu explicite");
        assertTrue(page.contains(">UNKNOWN<"));
    }

    // ---- Fonctions indisponibles ------------------------------------------------------------

    @Test
    void restartIsShownUnavailableWithItsReasonWhenNoRconIsConfigured() throws Exception {
        start();
        login();
        freshHeartbeat(1, 20, 60, "0.1.0-SNAPSHOT");

        String page = get("/ops").body();

        assertTrue(page.contains("Redémarrage indisponible"), "la fonction absente est affichée, pas masquée");
        assertTrue(page.contains("Aucun accès RCON configuré"), "avec le motif exact");
        assertFalse(page.contains("Redémarrer maintenant"), "aucun bouton qui ne mènerait à rien");
    }

    @Test
    void aRestartRequestIsRefusedWithItsReasonWhenRconIsMissing() throws Exception {
        start();
        login();
        String token = csrf(get("/ops").body());

        HttpResponse<String> response = post("/ops/restart",
                "delay_seconds=0&confirm_word=REDEMARRER&_csrf=" + token);

        assertEquals(303, response.statusCode());
        String location = response.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("/ops?err="), location);
        assertTrue(location.contains("RCON"), location);
    }

    @Test
    void theUnavailableFunctionsAreListedWithTheirRealReasons() throws Exception {
        start();
        login();

        String page = get("/ops").body();

        // Chaque limite affichée doit être une limite RÉELLE, mesurée, pas une précaution vague.
        assertTrue(page.contains("Non disponible dans ce lot"));
        assertTrue(page.contains("l'hébergeur n'expose aucune "), "start/stop : motif réel");
        assertTrue(page.contains("racine FTP"), "console complète : motif mesuré");
        assertTrue(page.contains("ce n'est pas un point de restauration"), "save-all ≠ sauvegarde");
        assertTrue(page.contains("#131"), "rechargement de contenu annoncé comme lot suivant");
        assertTrue(page.contains("#210"), "actions joueur / OP annoncées comme lot suivant");
    }

    // ---- Annonce ----------------------------------------------------------------------------

    @Test
    void theAnnounceFormOffersTemplatesChannelsAndAPreview() throws Exception {
        start();
        login();
        freshHeartbeat(2, 20, 60, "0.1.0-SNAPSHOT");

        String page = get("/ops").body();

        assertTrue(page.contains("name=\"type\" value=\"server.announce\""));
        assertTrue(page.contains("data-ops-template="), "modèles rapides");
        assertTrue(page.contains("Redémarrage du serveur dans 5 minutes."), "un modèle concret");
        for (String channel : List.of("chat", "actionbar", "title")) {
            assertTrue(page.contains("value=\"" + channel + "\""), "canal " + channel);
        }
        assertTrue(page.contains("data-ops-preview"), "aperçu");
        assertTrue(page.contains("ni commande, ni mise en forme interprétée"),
                "le contrat du champ est écrit à l'écran");
        assertTrue(page.contains("name=\"return\" value=\"/ops\""), "retour sur la page d'origine");
    }

    @Test
    void anAnnounceIsEnqueuedAsAWhitelistedAgentActionAndComesBackToOps() throws Exception {
        start();
        login();
        freshHeartbeat(2, 20, 60, "0.1.0-SNAPSHOT");
        String token = csrf(get("/ops").body());

        HttpResponse<String> response = post("/agents/action",
                "type=server.announce&agent=" + TestConfig.AGENT_ID
                        + "&message=" + URLEncoder.encode("Maintenance dans 5 minutes.", StandardCharsets.UTF_8)
                        + "&channel=chat&confirm=true&return=/ops&_csrf=" + token);

        assertEquals(303, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElse("").startsWith("/ops?"),
                response.headers().firstValue("Location").orElse(""));
        List<AgentActionRow> actions = store.recentActions(TestConfig.AGENT_ID, 10);
        assertEquals(1, actions.size());
        assertEquals("server.announce", actions.get(0).type());
        assertEquals("Maintenance dans 5 minutes.", actions.get(0).params().get("message"));
        assertEquals("chat", actions.get(0).params().get("channel"));
    }

    @Test
    void anAnnounceWithNobodyOnlineWarnsBeforeSending() throws Exception {
        start();
        login();
        freshHeartbeat(0, 20, 60, "0.1.0-SNAPSHOT");

        String page = get("/ops").body();

        assertTrue(page.contains("Aucun joueur connecté au dernier relevé"),
                "prévenir vaut mieux qu'un « envoyé » qui n'a touché personne");
    }

    // ---- Console ----------------------------------------------------------------------------

    @Test
    void theConsoleIsReadOnlyWithSearchLevelsPauseAndAutoscroll() throws Exception {
        start();
        login();
        freshHeartbeat(1, 20, 60, "0.1.0-SNAPSHOT");

        String page = get("/ops").body();

        assertTrue(page.contains("data-ops-console"), "zone de console");
        assertTrue(page.contains("data-ops-search"), "recherche");
        for (String level : List.of("ERROR", "WARN", "INFO")) {
            assertTrue(page.contains("data-ops-level=\"" + level + "\""), "filtre " + level);
        }
        assertTrue(page.contains("data-ops-pause"), "pause / reprise");
        assertTrue(page.contains("data-ops-autoscroll"), "suivi automatique");
        assertTrue(page.contains("data-ops-bottom"), "retour en bas");
        assertTrue(page.contains("Lecture seule"), "le contrat est écrit");
        assertFalse(page.contains("<textarea"), "aucune saisie : ce n'est pas un terminal");
        // La fraîcheur réelle du flux est annoncée, pas enjolivée en « temps réel ».
        assertTrue(page.contains("~15 s"), "latence réelle de l'agent annoncée");
    }

    @Test
    void theLogEndpointReturnsTheLinesOfTheLatestSurveyAfterTheCursor() throws Exception {
        start();
        login();
        seedLogs(1, 2, 3);

        String body = get("/ops/logs.json?after=1").body();

        assertFalse(body.contains("\"seq\":1"), "la ligne déjà vue n'est pas renvoyée");
        assertTrue(body.contains("\"seq\":2"), body);
        assertTrue(body.contains("\"seq\":3"), body);
        assertTrue(body.contains("\"cursor\":3"), body);
        assertTrue(body.contains("Citizens"), "la source de la ligne est conservée");
    }

    @Test
    void theLogEndpointEnqueuesExactlyOneSurveyAtATime() throws Exception {
        start();
        login();
        seedLogs(1);

        get("/ops/logs.json?after=0");
        get("/ops/logs.json?after=0");
        get("/ops/logs.json?after=0");

        long open = store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .filter(a -> "server.logs.tail".equals(a.type()))
                .filter(a -> !a.status().terminal())
                .count();
        assertEquals(1, open, "le navigateur peut interroger souvent sans remplir la file de l'agent");
    }

    @Test
    void watchingTheConsoleDoesNotMakeTheActionHistoryGrowForever() throws Exception {
        start();
        login();

        // Dix cycles de consultation : chacun enfile un relevé puis le résout, comme l'agent.
        for (int i = 1; i <= 10; i++) {
            get("/ops/logs.json?after=0");
            for (AgentActionRow row : store.recentActions(TestConfig.AGENT_ID, 50)) {
                if ("server.logs.tail".equals(row.type()) && !row.status().terminal()) {
                    store.recordResult(row.id(), TestConfig.AGENT_ID, AgentActionStatus.SUCCESS,
                            "0", "relevé", "{\"details\":{\"lines\":[]}}", Instant.now());
                }
            }
        }

        long surveys = store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> "server.logs.tail".equals(a.type()))
                .count();
        // Sans purge, regarder des logs pendant une heure laisserait des centaines de lignes
        // derrière lui et noierait l'historique réel des actions d'administration.
        assertTrue(surveys <= 6, "relevés conservés : " + surveys);
    }

    @Test
    void thePruningNeverRemovesASurveyStillInFlight() throws Exception {
        start();
        login();

        get("/ops/logs.json?after=0");   // enfile un relevé, laissé EN COURS
        get("/ops/logs.json?after=0");   // un second appel ne doit ni enfiler ni purger celui-ci

        long open = store.recentActions(TestConfig.AGENT_ID, 50).stream()
                .filter(a -> "server.logs.tail".equals(a.type()))
                .filter(a -> !a.status().terminal())
                .count();
        assertEquals(1, open, "un travail en cours ne disparaît jamais d'une purge");
    }

    @Test
    void aCaptureLimitationIsPassedThroughToTheBrowser() throws Exception {
        start();
        login();
        store.recordResult(store.createAction(TestConfig.AGENT_ID, "server.logs.tail",
                        Map.of("after", "0"), "tester"),
                TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "0", "limité",
                "{\"details\":{\"lines\":[],\"limitation\":\"Capture indisponible : motif exact.\"}}",
                Instant.now());

        String body = get("/ops/logs.json?after=0").body();

        assertTrue(body.contains("Capture indisponible : motif exact."), body);
    }

    @Test
    void aMissingSurveyIsAnEmptyConsoleNotAnError() throws Exception {
        start();
        login();

        HttpResponse<String> response = get("/ops/logs.json?after=0");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"lines\":[]"), response.body());
    }

    @Test
    void anInvalidCursorIsTreatedAsTheStartRatherThanRejected() throws Exception {
        start();
        login();
        seedLogs(1, 2);

        // Un curseur illisible ne doit pas casser la console : on repart du début du tampon.
        String body = get("/ops/logs.json?after=pas-un-nombre").body();

        assertTrue(body.contains("\"seq\":1"), body);
    }

    // ---- Suivi d'opération ------------------------------------------------------------------

    @Test
    void theStateEndpointReportsAnIdleServiceAsTerminal() throws Exception {
        start();
        login();

        String body = get("/ops/state.json").body();

        assertTrue(body.contains("\"phase\":\"IDLE\""), body);
        assertTrue(body.contains("\"terminal\":true"), body);
    }

    // ---- Harnais ----------------------------------------------------------------------------

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private void freshHeartbeat(long online, long max, long uptime, String version) {
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", version, "ONLINE", online, max, uptime, "{}", "{}"));
    }

    /** Dépose un relevé {@code server.logs.tail} réussi contenant les séquences demandées. */
    private void seedLogs(long... sequences) {
        StringBuilder lines = new StringBuilder();
        for (long seq : sequences) {
            if (lines.length() > 0) {
                lines.append(',');
            }
            lines.append("{\"seq\":").append(seq).append(",\"at\":").append(Instant.now().toEpochMilli())
                    .append(",\"level\":\"WARN\",\"source\":\"Citizens\",\"message\":\"ligne ")
                    .append(seq).append("\"}");
        }
        String id = store.createAction(TestConfig.AGENT_ID, "server.logs.tail", Map.of("after", "0"), "tester");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS,
                String.valueOf(sequences.length == 0 ? 0 : sequences[sequences.length - 1]),
                "relevé", "{\"details\":{\"lines\":[" + lines + "],\"firstSequence\":1,\"lastSequence\":"
                        + (sequences.length == 0 ? 0 : sequences[sequences.length - 1])
                        + ",\"dropped\":0,\"capacity\":500,\"gap\":false}}",
                Instant.now());
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
