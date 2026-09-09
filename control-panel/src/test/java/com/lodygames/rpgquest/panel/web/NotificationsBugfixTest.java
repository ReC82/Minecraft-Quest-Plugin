package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Corrections #93 : (1) le centre de notifications passe d'un dropdown trop étroit à un panneau
 * off-canvas Bootstrap large ; (2) le toast s'affiche réellement après une action (cache-busting
 * des assets + repli d'affichage manuel dans {@code panel.js}).
 */
class NotificationsBugfixTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- BUG 1 : panneau off-canvas large --------------------------------------------

    @Test
    void notificationCenterIsAnOffcanvasPanelNotANarrowDropdown() throws Exception {
        start();
        loginOwner();
        String html = get("/home").body();
        // La cloche ouvre un off-canvas (pas un dropdown)
        assertTrue(html.contains("data-bs-toggle=\"offcanvas\"") && html.contains("data-bs-target=\"#notif-panel\""));
        assertFalse(html.contains("class=\"dropdown-menu dropdown-menu-end notif-menu\""), "plus de dropdown étroit");
        // Le panneau off-canvas Bootstrap avec en-tête, corps scrollable et pied
        assertTrue(html.contains("class=\"offcanvas offcanvas-end notif-panel\"") && html.contains("id=\"notif-panel\""));
        assertTrue(html.contains("offcanvas-header") && html.contains("offcanvas-body notif-body-wrap"));
        assertTrue(html.contains("notif-foot-wrap") && html.contains("Voir toutes les actions"));
        assertTrue(html.contains("data-bs-dismiss=\"offcanvas\""), "bouton fermer accessible");
        // Largeur confortable définie en CSS
        String css = get("/assets/plugadmin.css").body();
        assertTrue(css.contains(".notif-panel{--bs-offcanvas-width:420px"), "largeur desktop ~420px");
        assertTrue(css.contains("--bs-offcanvas-width:92vw"), "quasi pleine largeur mobile");
        assertFalse(css.contains(".notif-menu{width:380px"), "ancien style dropdown retiré");
    }

    // ---- BUG 2 : toast réellement affiché -------------------------------------------

    @Test
    void assetUrlsAreCacheBustedSoNewJsIsNotServedStale() throws Exception {
        start();
        // login : feuilles de style versionnées
        assertTrue(get("/login").body().contains("/assets/plugadmin.css?v="));
        // page complète : scripts versionnés, dans le bon ordre, defer
        loginOwner();
        String page = get("/home").body();
        assertTrue(page.matches("(?s).*<script src=\"/assets/bootstrap/bootstrap\\.bundle\\.min\\.js\\?v=[0-9a-f]{8}\" defer></script>.*"));
        assertTrue(page.matches("(?s).*<script src=\"/assets/panel\\.js\\?v=[0-9a-f]{8}\" defer></script>.*"));
        assertTrue(page.indexOf("bootstrap.bundle.min.js") < page.indexOf("panel.js"), "bundle avant panel.js");
        assertTrue(page.contains("/assets/plugadmin.css?v="));
        // le handler d'asset ignore la query -> le fichier reste servi
        HttpResponse<byte[]> js = client.send(
                HttpRequest.newBuilder(uri("/assets/panel.js?v=deadbeef")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("application/javascript"));
    }

    @Test
    void panelJsHasRobustToastShowWithManualFallback() throws Exception {
        start();
        String js = get("/assets/panel.js").body();
        assertTrue(js.contains("function showToast"));
        assertTrue(js.contains("window.bootstrap.Toast.getOrCreateInstance"), "vrai composant Bootstrap Toast");
        assertTrue(js.contains("el.classList.add(\"show\")"), "repli manuel si Bootstrap JS absent");
        assertTrue(js.contains("upgradeToasts"), "promotion des toasts si Bootstrap charge après");
        assertTrue(js.contains("data-toast-ready"), "un toast n'est pas affiché deux fois");
    }

    @Test
    void toastIsRenderedForEveryRefreshActionIncludingDialogues() throws Exception {
        start();
        loginOwner();
        for (String[] tc : new String[][] {
                {"dialogue.list", "/dialogues"},
                {"npc.list", "/npcs"},
                {"player.list", "/players"},
                {"quest.list", "/quests"},
                {"story.list", "/stories"},
                {"item.list", "/quests"}}) {
            String id = createAction(tc[0], tc[1]);
            String html = get(tc[1] + "?agent=" + TestConfig.AGENT_ID + "&toast=" + id).body();
            assertTrue(html.contains("id=\"toast-root\""), tc[1] + " : conteneur de toasts");
            assertTrue(html.contains("class=\"toast pa-toast\""), tc[1] + " : markup toast présent");
            assertTrue(html.contains("data-toast-action=\"" + id + "\""), tc[1] + " : id d'action");
            assertTrue(html.contains("data-toast-group=\"pending\""), tc[1] + " : statut initial");
            assertTrue(html.contains("aria-live=") && html.contains("btn-close"), tc[1] + " : accessibilité");
            assertTrue(html.contains("<script src=\"/assets/bootstrap/bootstrap.bundle.min.js?v="), tc[1] + " : bundle Bootstrap");
        }
    }

    @Test
    void resolvedActionsToastReflectsSuccessOrFailure() throws Exception {
        start();
        loginOwner();
        String okId = createAction("dialogue.list", "/dialogues");
        settle(okId, "SUCCESS", "7 dialogues chargés");
        String ok = get("/dialogues?agent=" + TestConfig.AGENT_ID + "&toast=" + okId).body();
        assertTrue(ok.contains("data-toast-group=\"success\"") && ok.contains("data-bs-autohide=\"true\""));
        assertTrue(ok.contains("bi-check-circle") && ok.contains("7 dialogues chargés"));

        String koId = createAction("npc.list", "/npcs");
        settle(koId, "FAILED", "Citizens indisponible");
        String ko = get("/npcs?agent=" + TestConfig.AGENT_ID + "&toast=" + koId).body();
        assertTrue(ko.contains("data-toast-group=\"failed\"") && ko.contains("data-bs-autohide=\"false\""));
        assertTrue(ko.contains("bi-x-circle"));
    }

    // ---- non-régression /actions + filtres ---------------------------------------------

    @Test
    void actionsPageAndFiltersUnchanged() throws Exception {
        start();
        loginOwner();
        String a = createAction("quest.list", "/quests");
        settle(a, "SUCCESS", "10 quêtes");
        String list = get("/actions").body();
        assertEquals(200, get("/actions").statusCode());
        assertTrue(list.contains("<h1>Historique des actions</h1>"));
        assertTrue(list.contains("nav nav-pills actions-status"));
        assertTrue(list.contains("table table-hover align-middle actions-table"));
        assertTrue(list.contains("d-md-none actions-cards"));
        assertTrue(get("/actions?status=success").body().contains("actions-table"));
        assertTrue(get("/actions?domain=quests").body().contains("actions-table"));
        assertEquals(200, get("/actions?q=qu%C3%AAtes&status=success").statusCode());
    }

    // ---- infra --------------------------------------------------------------------------

    private String createAction(String type, String returnPath) throws Exception {
        String token = csrf(get("/agents").body());
        HttpResponse<String> res = post("/agents/action",
                "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&type=" + type + "&return=" + returnPath);
        assertEquals(303, res.statusCode());
        Matcher m = Pattern.compile("[?&]toast=([0-9a-fA-F-]{36})").matcher(res.headers().firstValue("Location").orElse(""));
        assertTrue(m.find(), "toast id : " + res.headers().firstValue("Location").orElse(""));
        return m.group(1);
    }

    private void settle(String id, String status, String message) throws Exception {
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        String body = "{\"action_id\":\"" + id + "\",\"status\":\"" + status + "\",\"value\":\"\","
                + "\"message\":\"" + message + "\"}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void loginOwner() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                new AgentStore(db));
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("jeton _csrf absent");
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
