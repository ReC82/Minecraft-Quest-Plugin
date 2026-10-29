package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.nio.file.Files;
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

/**
 * Page {@code /orch} — suivi de la file lqorch (issue #907), sur un instantané <strong>fictif</strong>
 * écrit dans un dossier temporaire : jamais l'instantané réel du superviseur.
 */
class OrchPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private Path snapshot;
    private int writes;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        start();
        HttpResponse<String> response = get("/orch");
        assertEquals(303, response.statusCode());
        assertEquals("/login", response.headers().firstValue("Location").orElse(""));
    }

    @Test
    void onlyTheOwnerRoleReadsTheQueueByDefault() {
        for (Role role : Role.values()) {
            assertEquals(role == Role.OWNER, role.permissions().contains(Permission.ORCH_READ), role.name());
        }
    }

    @Test
    void groupsTicketsByStateWithReasonsLinksAndNoAction() throws Exception {
        start();
        write(fixture(Instant.now(), true, "RUNNING"));
        login();

        String page = get("/orch").body();

        assertTrue(page.contains("File de développement"));
        for (String section : new String[] {"En cours", "Prêts", "En attente du propriétaire", "Bloqués",
                "Terminés récemment", "File automatique (agent:auto)", "Dernières sessions"}) {
            assertTrue(page.contains(section), section);
        }
        assertTrue(page.contains("href=\"https://github.com/acme/demo/issues/901\""));
        assertTrue(page.contains("href=\"https://github.com/acme/demo/pull/950\""), "numéro de PR lié");
        assertTrue(page.contains("recette TC-1 en attente"), "motif d'attente visible");
        assertTrue(page.contains("dépendances ouvertes : #321"), "motif d'exclusion visible");
        assertTrue(page.contains("Admissibles, dans l'ordre de prise"));
        assertTrue(page.contains("pas une panne"), "« aucun admissible » n'est pas une panne");
        assertTrue(page.contains("2026-10-28_1200_fixture-905.md"), "rapport de session cité");
        assertTrue(page.contains(">P1<"), "rang P0–P3 connu par l'examen automatique");
        assertFalse(page.contains("<b>gras</b>"));
        assertTrue(page.contains("&lt;b&gt;gras&lt;/b&gt;"), "titre échappé");
        assertFalse(page.contains("/home/"), "aucun chemin local");
        assertFalse(Pattern.compile("method=\"post\"[^>]*action=\"/orch").matcher(page).find(),
                "aucun formulaire d'écriture : seul le filtre GET vise /orch");
        assertTrue(page.contains("<form method=\"get\" action=\"/orch\""));
    }

    @Test
    void doneTicketsAreLimitedToTheMostRecentOnes() throws Exception {
        start();
        write(fixture(Instant.now(), true, "RUNNING"));
        login();

        String page = get("/orch?statut=termines").body();

        assertTrue(page.contains("#1020<"), "le plus récent est affiché");
        assertFalse(page.contains("#1001<"), "le plus ancien est replié");
        assertTrue(page.contains("plus ancien(s) non affiché(s)"));
        assertFalse(page.contains("En attente du propriétaire <span"), "filtre d'état respecté");
    }

    @Test
    void filtersByStatePriorityAndText() throws Exception {
        start();
        write(fixture(Instant.now(), true, "RUNNING"));
        login();

        String waiting = get("/orch?statut=attente").body();
        assertTrue(waiting.contains("#903<"));
        assertFalse(waiting.contains("#902<"));

        String p1 = get("/orch?prio=P1").body();
        assertTrue(p1.contains("#902<"));
        assertFalse(p1.contains("#903<"));

        String text = get("/orch?q=" + URLEncoder.encode("#905", StandardCharsets.UTF_8)).body();
        assertTrue(text.contains("#905<"));
        assertFalse(text.contains("#903<"));
        assertTrue(get("/orch?q=introuvable").body().contains("Aucun ticket ne correspond"));
    }

    @Test
    void missingOrInvalidSnapshotIsShownExplicitly() throws Exception {
        start();
        login();

        String missing = get("/orch").body();
        assertTrue(missing.contains("État du superviseur indisponible"));
        assertTrue(missing.contains("n'a encore publié aucun instantané"));

        Files.writeString(snapshot, "{pas du json");
        String invalid = get("/orch").body();
        assertTrue(invalid.contains("invalide"));
        assertFalse(invalid.contains("Prêts <span"));
    }

    @Test
    void staleOrStoppedSupervisorIsNeverShownAsHealthy() throws Exception {
        start();
        write(fixture(Instant.now().minus(Duration.ofMinutes(20)), true, "RUNNING"));
        login();
        assertTrue(get("/orch").body().contains("ne publie plus depuis"));

        write(fixture(Instant.now(), false, "STOP"));
        String stopped = get("/orch").body();
        assertTrue(stopped.contains("Superviseur arrêté"));
        assertTrue(stopped.contains("lqorch stop"));

        write(fixture(Instant.now(), true, "PAUSED"));
        assertTrue(get("/orch").body().contains("Superviseur en pause"));
    }

    // ---- Instantané fictif --------------------------------------------------------------------

    private static String fixture(Instant generated, boolean alive, String mode) {
        StringBuilder done = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            done.append(",").append(ticket(1000 + i, "DONE", "Terminé " + i,
                    Instant.parse("2026-10-01T00:00:00Z").plus(Duration.ofHours(i)).toString(), "", ""));
        }
        return "{\"version\":1,\"generated_at\":\"" + generated.toString().replaceAll("\\.\\d+Z$", "Z") + "\","
                + "\"repo\":\"acme/demo\","
                + "\"supervisor\":{\"mode\":\"" + mode + "\",\"alive\":" + alive + ",\"code\":\"abc\","
                + "\"idle_reason\":\"\",\"next_start\":{\"allowed\":false,\"reason\":\"Écart minimal entre deux sessions.\","
                + "\"until\":\"2026-10-29T19:29:57Z\"},\"sessions_today\":3,\"max_sessions_per_day\":24},"
                + "\"current\":{\"issue\":901,\"title\":\"Vue en cours\",\"started_at\":\"2026-10-29T19:00:00Z\","
                + "\"last_activity\":\"2026-10-29T19:10:00Z\"},"
                + "\"auto\":{\"enabled\":true,\"examined_at\":\"2026-10-29T18:00:00Z\","
                + "\"eligible\":[{\"issue\":902,\"title\":\"Prêt P1\",\"rank\":1,\"rearm\":\"\",\"status\":\"\"}],"
                + "\"excluded\":[{\"issue\":906,\"title\":\"Dépendant\",\"rank\":2,\"reason\":\"dépendances ouvertes : #321\"},"
                + "{\"issue\":903,\"title\":\"Recette\",\"rank\":null,\"reason\":\"WAITING_OWNER : aucune activité GitHub\"}]},"
                + "\"tasks\":[" + ticket(901, "RUNNING", "Vue en cours", "2026-10-29T19:10:00Z", "", "")
                + "," + ticket(902, "READY", "Prêt P1", "2026-10-29T18:00:00Z", "", "")
                + "," + ticket(903, "WAITING_OWNER", "Recette <b>gras</b>", "2026-10-29T17:00:00Z",
                        "recette TC-1 en attente", "")
                + "," + ticket(904, "BLOCKED", "Bloqué", "2026-10-29T16:00:00Z", "certificat expiré", "")
                + "," + ticket(905, "FAILED", "Échoué", "2026-10-29T15:00:00Z", "", "2026-10-28_1200_fixture-905.md")
                + done + "],"
                + "\"sessions\":[{\"id\":\"abcd1234\",\"issue\":901,\"title\":\"Vue en cours\","
                + "\"started_at\":\"2026-10-29T19:00:00Z\",\"ended_at\":\"\",\"outcome\":\"\",\"detail\":\"\"},"
                + "{\"id\":\"ef567890\",\"issue\":905,\"title\":\"Échoué\",\"started_at\":\"2026-10-29T14:00:00Z\","
                + "\"ended_at\":\"2026-10-29T15:00:00Z\",\"outcome\":\"CRASH\",\"detail\":\"sans rapport\"}]}";
    }

    private static String ticket(int issue, String status, String title, String updated, String blockers,
                                 String report) {
        return "{\"issue\":" + issue + ",\"title\":\"" + title + "\",\"status\":\"" + status + "\",\"priority\":"
                + (issue % 100) + ",\"deps\":\"\",\"branch\":\"feature/" + issue + "-x\",\"pr\":\""
                + (issue == 903 ? "#950 (code)" : "") + "\",\"commit_sha\":\"\",\"summary\":\"résumé " + issue
                + "\",\"tests\":\"\",\"blockers\":\"" + blockers + "\",\"next_action\":\"suite " + issue
                + "\",\"note\":\"\",\"attempts\":0,\"last_session\":\"abcd1234\",\"last_activity\":\"" + updated
                + "\",\"not_before\":\"\",\"updated_at\":\"" + updated + "\",\"auto_hold\":false,\"auto_seen_at\":\"\","
                + "\"reports\":[" + (report.isEmpty() ? "" : "\"" + report + "\"") + "]}";
    }

    private void write(String json) throws Exception {
        Files.writeString(snapshot, json);
        // Le cache se fie à la date et à la taille : une date distincte à chaque écriture.
        Files.setLastModifiedTime(snapshot, java.nio.file.attribute.FileTime.from(
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(++writes)));
    }

    // ---- HTTP -------------------------------------------------------------------------------

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        snapshot = tmp.resolve("queue.json");
        app = new PanelApp(TestConfig.withOrchSnapshot(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                snapshot.toString()), new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p)).GET());
    }

    private HttpResponse<String> post(String p, String form) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
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
