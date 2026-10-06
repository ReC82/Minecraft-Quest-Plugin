package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
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
 * Rafraîchissement automatique de {@code /ops} (correctif #95).
 *
 * <p>Deux bugs reproduits par l'exploitant avaient la <strong>même</strong> cause : le suivi
 * d'opération rechargeait toute la page dès que l'état était « terminal » — y compris quand il
 * l'était déjà au chargement, donc en boucle. Conséquences : (1) l'annonce en cours de saisie
 * était effacée et les cases décochées, (2) la notification de résultat, re-rendue depuis le
 * drapeau laissé dans l'URL, réapparaissait sans fin.</p>
 *
 * <p>Ces tests verrouillent le contrat sur lequel repose le correctif :</p>
 * <ul>
 *   <li>la page se déclare auto-rafraîchissante, pour que le rechargement mutualisé du panel
 *       l'épargne ;</li>
 *   <li>les blocs rafraîchis (état, carte d'opération) sont isolés et ne contiennent
 *       <strong>aucun</strong> formulaire — un remplacement ne peut donc pas détruire une
 *       saisie ;</li>
 *   <li>le script ne recharge jamais la page depuis le suivi d'opération ;</li>
 *   <li>les drapeaux de résultat portés par l'URL sont à usage unique ;</li>
 *   <li>et surtout : rendre la page plusieurs fois après un envoi ne <strong>réexécute</strong>
 *       pas l'annonce — la répétition observée était visuelle, l'historique le prouve.</li>
 * </ul>
 */
class OpsAutoRefreshTest {

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

    // ---- Contrat HTML : ce qui est rafraîchi ne porte pas de formulaire ---------------------

    @Test
    void thePageDeclaresItselfSelfRefreshingSoTheSharedReloadSkipsIt() throws Exception {
        start();
        login();
        freshHeartbeat(3, 20, 120, "0.1.0-SNAPSHOT");

        String page = get("/ops").body();

        assertTrue(page.contains("data-pa-selfrefresh=\"ops\""),
                "sans ce marqueur, le rechargement mutualisé reconstruirait les formulaires");
        assertTrue(page.contains("data-ops-state"), "le bloc d'état est un conteneur remplaçable");
        assertTrue(page.contains("data-ops-announce"), "le formulaire d'annonce est bien présent");
    }

    @Test
    void theRefreshedStateBlockCarriesNoFormAndNoCsrfToken() throws Exception {
        start();
        login();
        freshHeartbeat(5, 20, 3_600, "0.1.0-SNAPSHOT");

        Map<String, Object> state = Json.parseObject(get("/ops/state.json").body());
        String stateHtml = String.valueOf(state.get("stateHtml"));

        // Le contenu réellement relevé est là…
        assertTrue(stateHtml.contains("ONLINE"), stateHtml);
        assertTrue(stateHtml.contains(">5<"), "joueurs connectés dans le bloc rafraîchi");
        // … et il ne contient AUCUN formulaire : le remplacer ne peut pas effacer une saisie.
        assertFalse(stateHtml.contains("<form"), "le bloc rafraîchi ne doit porter aucun formulaire");
        assertFalse(stateHtml.contains("_csrf"), "aucun jeton CSRF dans un bloc remplacé en boucle");
        assertFalse(stateHtml.contains("<input"), "aucun champ de saisie dans le bloc rafraîchi");
    }

    @Test
    void theStateEndpointHonoursTheAgentOfTheCurrentPage() throws Exception {
        start();
        login();
        freshHeartbeat(9, 20, 60, "0.1.0-SNAPSHOT");

        // Le même agent que la page : l'état rafraîchi décrit bien ce que la page affichait.
        String withAgent = String.valueOf(
                Json.parseObject(get("/ops/state.json?agent=" + TestConfig.AGENT_ID).body()).get("stateHtml"));
        assertTrue(withAgent.contains("Agent " + TestConfig.AGENT_ID), withAgent);

        // Un agent inconnu ne doit pas inventer d'état : aucun relevé, et le dire.
        String unknown = String.valueOf(
                Json.parseObject(get("/ops/state.json?agent=inconnu").body()).get("stateHtml"));
        assertTrue(unknown.contains("aucune donnée : l'agent n'a jamais envoyé de relevé"), unknown);
    }

    @Test
    void anIdleServiceReturnsNoOperationCardSoNothingIsEmptiedInPlace() throws Exception {
        start();
        login();

        Map<String, Object> state = Json.parseObject(get("/ops/state.json").body());

        assertEquals("IDLE", state.get("phase"));
        assertEquals(Boolean.TRUE, state.get("terminal"));
        assertEquals("", String.valueOf(state.get("html")),
                "aucune carte à l'arrêt : le script ne doit pas remplacer un bloc par du vide");
    }

    // ---- Contrat JavaScript : plus aucun rechargement depuis le suivi d'opération ------------

    @Test
    void theOpsPollerNeverReloadsThePage() throws Exception {
        start();
        String js = get("/assets/panel.js").body();

        assertTrue(js.contains("function initOpsState()"), "le suivi d'état existe");
        assertFalse(js.contains("function initOpsRestart()"),
                "l'ancienne fonction qui rechargeait la page a disparu");
        assertTrue(js.contains("run(\"initOpsState\", initOpsState)"), "et il est bien branché");

        String body = functionBody(js, "function initOpsState()");
        assertFalse(body.contains("runReload"), "le suivi d'opération ne doit plus armer de rechargement");
        assertFalse(body.contains("location.reload"), "ni recharger directement");
        assertFalse(body.contains("location.href"), "ni naviguer de force");
        // Ce qu'il fait à la place : remplacer deux conteneurs sans formulaire.
        assertTrue(body.contains("stateHtml"), "il remplace le bloc d'état");
        assertTrue(body.contains("outerHTML"), "et la carte d'opération, en place");
        assertTrue(body.contains("data-ops-terminal"),
                "une opération déjà terminée au chargement ne doit pas relancer un cycle rapide");
    }

    @Test
    void theOnlyPageReloadLeftIsGuardedAgainstSelfRefreshingViews() throws Exception {
        start();
        String js = get("/assets/panel.js").body();

        // Invariant partagé avec CatalogResyncTest : un seul point de rechargement dans tout le fichier.
        assertEquals(1, countOf(js, "window.location.reload()"),
                "un seul mécanisme de rechargement, donc un seul endroit à garder");
        String runReload = functionBody(js, "function runReload()");
        assertTrue(runReload.contains("selfRefreshingView()"),
                "ce point unique doit épargner une vue qui se réconcilie seule");
        assertTrue(js.contains("data-pa-selfrefresh"), "le marqueur est lu côté script");
        // Et la planification elle-même n'est plus armée sur une telle vue : sinon le sondage des
        // notifications s'arrêterait net au premier succès, figeant la cloche.
        assertTrue(js.contains("!selfRefreshingView()"), "aucun plan de rechargement armé sur /ops");
    }

    @Test
    void resultFlagsCarriedByTheUrlAreOneShot() throws Exception {
        start();
        String js = get("/assets/panel.js").body();

        assertTrue(js.contains("function dropOneShotResultFlags()"));
        assertTrue(js.contains("run(\"dropOneShotResultFlags\", dropOneShotResultFlags)"),
                "branché à l'initialisation, après l'affichage des toasts");
        assertTrue(js.contains("ONE_SHOT_FLAGS = [\"ok\", \"err\", \"toast\"]"),
                "les trois drapeaux de résultat sont retirés de l'URL");
        assertTrue(js.contains("history.replaceState"),
                "on remplace l'entrée d'historique, sans recharger ni rejouer la requête");

        // L'ordre compte : le toast doit être rendu AVANT que le drapeau ne soit retiré.
        assertTrue(js.indexOf("run(\"initToasts\"") < js.indexOf("run(\"dropOneShotResultFlags\""),
                "retirer le drapeau avant d'afficher le toast l'escamoterait");
    }

    // ---- Preuve par l'historique : répétition visuelle, et non réexécution -------------------

    @Test
    void renderingThePageAgainAfterAnAnnounceDoesNotReplayTheSubmission() throws Exception {
        start();
        login();
        freshHeartbeat(2, 20, 90, "0.1.0-SNAPSHOT");

        String token = csrf(get("/ops").body());
        HttpResponse<String> sent = post("/agents/action",
                "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&type=server.announce"
                        + "&return=/ops&confirm=true&channel=chat"
                        + "&message=" + URLEncoder.encode("Maintenance dans 5 minutes",
                        StandardCharsets.UTF_8));
        assertEquals(303, sent.statusCode());
        String location = sent.headers().firstValue("Location").orElse("");
        Matcher toast = Pattern.compile("[?&]toast=([0-9a-fA-F-]{36})").matcher(location);
        assertTrue(toast.find(), "la redirection porte le drapeau de résultat : " + location);
        assertEquals(1, announceCount(), "une soumission = une action");

        // Le navigateur re-rendait cette URL en boucle (c'était le bug) : refaisons-le plusieurs
        // fois. Le toast est bien re-rendu à chaque fois — c'est la répétition VISUELLE observée —
        // mais aucune action supplémentaire n'est créée : la soumission n'est jamais rejouée.
        for (int i = 0; i < 4; i++) {
            String page = get(location).body();
            assertTrue(page.contains("data-toast-action=\"" + toast.group(1) + "\""),
                    "le drapeau laissé dans l'URL re-rend le même toast");
        }
        assertEquals(1, announceCount(), "aucune réexécution : l'historique ne compte qu'une annonce");

        // Et l'URL débarrassée du drapeau ne montre plus ce résultat : une nouvelle action aura sa
        // propre redirection, donc sa propre notification.
        String clean = get("/ops?agent=" + TestConfig.AGENT_ID).body();
        assertFalse(clean.contains("data-toast-action=\"" + toast.group(1) + "\""),
                "sans drapeau, plus de notification ressuscitée");
        assertEquals(1, announceCount());
    }

    // ---- Harnais -----------------------------------------------------------------------------

    /** Nombre d'actions {@code server.announce} réellement enfilées, lu depuis l'historique. */
    private int announceCount() throws Exception {
        Map<String, Object> body = Json.parseObject(
                get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).body());
        @SuppressWarnings("unchecked")
        List<Object> actions = (List<Object>) body.get("actions");
        int n = 0;
        for (Object raw : actions) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) raw;
            if ("server.announce".equals(row.get("type"))) {
                n += 1;
            }
        }
        return n;
    }

    /**
     * Corps d'une fonction JavaScript, délimité par l'accolade ouvrante de sa signature et son
     * accolade fermante appariée. Vérifier le fichier entier ne dirait rien : c'est l'absence de
     * rechargement <em>dans cette fonction précise</em> qui constitue le correctif.
     */
    private static String functionBody(String js, String signature) {
        int start = js.indexOf(signature);
        assertTrue(start >= 0, "signature introuvable dans panel.js : " + signature);
        int open = js.indexOf('{', start);
        assertTrue(open >= 0, "corps introuvable pour " + signature);
        int depth = 0;
        for (int i = open; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '{') {
                depth += 1;
            } else if (c == '}') {
                depth -= 1;
                if (depth == 0) {
                    return js.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("accolade fermante introuvable pour " + signature);
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n += 1;
            from = at + needle.length();
        }
    }

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
        store.saveHeartbeat(new HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", version, "ONLINE", online, max, uptime, "{}", "{}"));
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        if (!jar.isEmpty()) {
            String cookie = jar.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "; " + b).orElse("");
            builder.header("Cookie", cookie);
        }
        HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        for (String setCookie : res.headers().allValues("Set-Cookie")) {
            String[] first = setCookie.split(";", 2);
            int eq = first[0].indexOf('=');
            String name = first[0].substring(0, eq).trim();
            String value = first[0].substring(eq + 1).trim();
            if (setCookie.toLowerCase().contains("max-age=0") || value.isEmpty()) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
        return res;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), "jeton _csrf absent");
        return m.group(1);
    }
}
