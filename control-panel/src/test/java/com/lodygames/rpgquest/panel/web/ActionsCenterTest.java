package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
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
 * #93 — toasts + centre de notifications + page {@code /actions} filtrable. Vérifie la
 * disparition des blocs « Actions récentes » des pages métier, le rendu des toasts, la cloche
 * de la topbar, et la liste/filtre/recherche/pagination de {@code /actions}.
 */
class ActionsCenterTest {

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

    // ---- pages métier : plus de bloc « Actions récentes » --------------------------

    @Test
    void businessPagesNoLongerCarryTheBigActionsTable() throws Exception {
        start();
        loginOwner();
        for (String p : new String[] {"/players", "/npcs", "/quests", "/stories", "/dialogues"}) {
            String html = get(p).body();
            assertEquals(200, get(p).statusCode(), p);
            assertFalse(html.contains("Actions récentes"), p + " : bloc « Actions récentes » retiré");
            assertFalse(html.contains("data-actions-agent"), p + " : conteneur pollable retiré");
            assertFalse(html.contains("poll-status"), p + " : ligne de polling retirée");
        }
    }

    // ---- toasts -------------------------------------------------------------------------

    @Test
    void successActionShowsAutodismissSuccessToast() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        settleAgent(id, "SUCCESS", "12 joueurs");
        String html = get("/players?agent=" + TestConfig.AGENT_ID + "&toast=" + id).body();
        assertTrue(html.contains("class=\"toast pa-toast\""), "toast présent");
        assertTrue(html.contains("data-toast-group=\"success\""));
        assertTrue(html.contains("data-bs-autohide=\"true\""), "succès : auto-dismiss");
        assertTrue(html.contains("bi-check-circle"), "icône Bootstrap Icons succès");
        assertTrue(html.contains("Rafraîchir les joueurs connectés"), "libellé humain");
        assertTrue(html.contains("aria-live=\"polite\"") && html.contains("btn-close"), "accessibilité toast");
    }

    @Test
    void failedActionShowsPersistentDangerToast() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        settleAgent(id, "REJECTED", "refusé");
        String html = get("/players?agent=" + TestConfig.AGENT_ID + "&toast=" + id).body();
        assertTrue(html.contains("data-toast-group=\"failed\""));
        assertTrue(html.contains("data-bs-autohide=\"false\""), "échec : reste visible");
        assertTrue(html.contains("bi-x-circle"));
    }

    @Test
    void pendingActionShowsPendingToast() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        String html = get("/players?agent=" + TestConfig.AGENT_ID + "&toast=" + id).body();
        assertTrue(html.contains("data-toast-group=\"pending\""));
        assertTrue(html.contains("data-toast-action=\"" + id + "\""), "id pour le suivi client");
        assertTrue(html.contains("En attente de confirmation de l'agent"));
        assertTrue(html.contains("bi-clock"));
    }

    @Test
    void invalidActionShowsErrorToastNotABanner() throws Exception {
        start();
        loginOwner();
        String token = csrf(get("/players").body());
        // player.variable.get sans joueur -> validation KO -> redirection &err=
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token
                + "&type=player.variable.get&agent=" + TestConfig.AGENT_ID + "&return=/players&key=X");
        assertEquals(303, res.statusCode());
        String loc = res.headers().firstValue("Location").orElse("");
        assertTrue(loc.contains("&err="));
        String html = get(loc).body();
        assertTrue(html.contains("class=\"toast pa-toast\"") && html.contains("data-toast-group=\"failed\""));
        assertTrue(html.contains("Action refusée"));
    }

    // ---- cloche + centre de notifications --------------------------------------------

    @Test
    void topbarHasBellAndNotificationCenter() throws Exception {
        start();
        loginOwner();
        createAction("&type=npc.list");
        String html = get("/home").body();
        assertTrue(html.contains("id=\"notif-center\""), "centre de notifications dans la topbar");
        assertTrue(html.contains("class=\"iconbtn notif-bell\"") && html.contains("bi-bell"));
        assertTrue(html.contains("data-bs-toggle=\"offcanvas\"") && html.contains("id=\"notif-panel\""), "panneau off-canvas Bootstrap");
        assertTrue(html.contains("data-notif-list"), "liste rafraîchissable");
        assertTrue(html.contains("aria-label=\"Notifications\""), "accessibilité cloche");
        assertTrue(html.contains("notif-item"), "au moins une action listée");
        assertTrue(html.contains("Voir toutes les actions") && html.contains("href=\"/actions\""),
                "bouton « Voir plus » vers /actions");
        assertTrue(html.contains("data-notif-badge"), "badge présent");
    }

    // ---- Issue #293 : le badge suit ce que ce compte a lu --------------------------------

    private int badge() throws Exception {
        Matcher m = Pattern.compile("\"badge\":(\\d+)").matcher(
                get("/agents/actions.json?agent=" + TestConfig.AGENT_ID).body());
        assertTrue(m.find());
        return Integer.parseInt(m.group(1));
    }

    @Test
    void openingAFailedNotificationMarksOnlyThatOneRead() throws Exception {
        start();
        loginOwner();
        String first = createAction("&type=npc.list");
        String second = createAction("&type=quest.list");
        settleAgent(first, "FAILED", "panne 1");
        settleAgent(second, "FAILED", "panne 2");
        assertEquals(2, badge(), "deux échecs non lus");

        get("/actions/" + first);

        assertEquals(1, badge(), "lire une notification ne marque pas l'autre");
    }

    @Test
    void markAllReadClearsTheBadgeAndSurvivesALogin() throws Exception {
        start();
        loginOwner();
        settleAgent(createAction("&type=npc.list"), "FAILED", "panne");
        String token = csrf(get("/home").body());
        assertTrue(get("/home").body().contains("Tout marquer comme lu"));

        HttpResponse<String> res = post("/notifications/read-all", "_csrf=" + token);

        assertEquals(303, res.statusCode());
        assertEquals(0, badge());
        jar.clear();
        loginOwner();
        assertEquals(0, badge(), "conservé après reconnexion");
        String failedAgain = createAction("&type=quest.list");
        settleAgent(failedAgain, "FAILED", "nouvelle panne");
        assertEquals(1, badge(), "une nouvelle notification compte");
    }

    @Test
    void markAllReadRequiresTheCsrfToken() throws Exception {
        start();
        loginOwner();

        assertEquals(403, post("/notifications/read-all", "_csrf=faux").statusCode());
    }

    // ---- page /actions --------------------------------------------------------------

    @Test
    void actionsPageListsFiltersAndSearches() throws Exception {
        start();
        loginOwner();
        String kill = createAction("&type=quest.list");
        settleAgent(kill, "SUCCESS", "10 quêtes");
        String talk = createAction("&type=npc.list");
        settleAgent(talk, "REJECTED", "Citizens absent");
        String player = createAction("&type=player.list"); // reste PENDING

        String base = main(get("/actions").body());
        assertEquals(200, get("/actions").statusCode());
        assertTrue(base.contains("<h1>Historique des actions</h1>"));
        assertTrue(base.contains("Rechercher une action, joueur, PNJ, quête"));
        assertTrue(base.contains("nav nav-pills actions-status"));
        // 3 domaines présents -> 3 puces + « Tous »
        assertTrue(base.contains(">Joueurs<") && base.contains(">PNJ<") && base.contains(">Quêtes<"));
        assertTrue(base.contains("3 actions"));

        // filtre statut (contenu principal uniquement — la cloche de la topbar liste tout)
        assertTrue(main(get("/actions?status=success").body()).contains("Rafraîchir le catalogue de quêtes"));
        assertFalse(main(get("/actions?status=success").body()).contains("Rafraîchir le catalogue des PNJ"));
        assertTrue(main(get("/actions?status=failed").body()).contains("Rafraîchir le catalogue des PNJ"));
        assertTrue(main(get("/actions?status=pending").body()).contains("Rafraîchir les joueurs connectés"));
        assertFalse(main(get("/actions?status=pending").body()).contains("Rafraîchir le catalogue de quêtes"));

        // filtre domaine
        String pnj = main(get("/actions?domain=npc").body());
        assertTrue(pnj.contains("Rafraîchir le catalogue des PNJ"));
        assertFalse(pnj.contains("Rafraîchir le catalogue de quêtes"));

        // recherche + combinaison recherche/filtre
        assertTrue(main(get("/actions?q=qu%C3%AAtes").body()).contains("Rafraîchir le catalogue de quêtes"));
        assertFalse(main(get("/actions?q=Citizens&status=success").body()).contains("Rafraîchir le catalogue des PNJ"),
                "recherche ET filtre combinés");
        assertTrue(main(get("/actions?q=Citizens&status=failed").body()).contains("Rafraîchir le catalogue des PNJ"));

        // table desktop + cartes mobile
        assertTrue(base.contains("table align-middle actions-table actions-history"));
        assertTrue(base.contains("d-md-none actions-cards"));
        assertTrue(base.contains("pill pill--success") || base.contains("pill pill--failed"));
    }

    @Test
    void actionsPagePaginates() throws Exception {
        start();
        loginOwner();
        for (int i = 0; i < ActionsPages.PAGE_SIZE + 3; i++) {
            createAction("&type=player.list");
        }
        String p1 = get("/actions").body();
        assertTrue(p1.contains("Page 1 / 2"));
        assertTrue(p1.contains("href=\"/actions?status=all&domain=all&page=2\""));
        String p2 = get("/actions?page=2").body();
        assertTrue(p2.contains("Page 2 / 2"));
    }

    @Test
    void actionDetailPageShowsFullContext() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        settleAgent(id, "SUCCESS", "7 joueurs");
        String d = get("/actions/" + id).body();
        assertEquals(200, get("/actions/" + id).statusCode());
        assertTrue(d.contains("Rafraîchir les joueurs connectés"));
        assertTrue(d.contains("Type technique") && d.contains("player.list"));
        assertTrue(d.contains("Livraisons") && d.contains("Corps brut du résultat"));
        assertTrue(d.contains("href=\"/actions\""), "retour à la liste");
        assertEquals(404, get("/actions/deadbeef-0000-0000-0000-000000000000").statusCode());
    }

    // ---- Issue #583 : copier le corps brut du résultat ------------------------------------

    @Test
    void rawResultBlockOffersCopyButtonWithExactText() throws Exception {
        start();
        loginOwner();
        for (String status : new String[] {"SUCCESS", "FAILED"}) {
            String id = createAction("&type=player.list");
            settleAgent(id, status, "a <b> & 'c'");
            String d = main(get("/actions/" + id).body());
            assertTrue(d.contains("data-copy-from=\"action-result-raw\""), "bouton de copie (" + status + ")");
            assertTrue(d.contains("Copier le résultat brut"));
            assertTrue(d.contains("type=\"button\" data-copy-from"), "bouton natif : clavier, jamais un submit");
            assertTrue(d.contains("id=\"action-result-raw-status\" role=\"status\" aria-live=\"polite\""));
            String raw = unescape(between(d, "<pre id=\"action-result-raw\" tabindex=\"0\" "
                    + "aria-label=\"Corps brut du résultat\">", "</pre>"));
            assertTrue(raw.startsWith("{") && raw.endsWith("}"), "JSON complet, sans balisage : " + raw);
            assertTrue(raw.contains(id), "action_id présent");
            assertTrue(raw.contains("a <b> & 'c'"), "texte brut, aucune troncature ni balise HTML");
            assertTrue(d.contains("&lt;b&gt; &amp;"), "échappé dans le HTML");
        }
    }

    @Test
    void pendingActionSaysThereIsNoRawResultToCopy() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        String d = main(get("/actions/" + id).body());
        assertTrue(d.contains("Aucun corps brut"));
        assertFalse(d.contains("data-copy-from="), "rien à copier sans résultat");
    }

    private static String between(String s, String start, String end) {
        int i = s.indexOf(start);
        assertTrue(i >= 0, "bloc brut introuvable");
        int j = s.indexOf(end, i + start.length());
        return s.substring(i + start.length(), j);
    }

    private static String unescape(String html) {
        return html.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    // ---- Issue #266 : ouvrir une action depuis sa ligne ---------------------------------

    @Test
    void everyDesktopRowOpensItsDetail() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        settleAgent(id, "SUCCESS", "7 joueurs");

        String list = get("/actions?status=success").body();

        assertTrue(list.contains("<tr class=\"act-row\" data-href=\"/actions/" + id + "?back="),
                "la ligne entière mène à la fiche");
        assertTrue(list.contains("<a class=\"act-label\" href=\"/actions/" + id + "?back="),
                "le libellé est un vrai lien (clavier, lecteur d'écran)");
        assertTrue(list.contains(">Voir le détail</a>"), "et le geste est dit en toutes lettres");
    }

    @Test
    void theDetailLeadsBackToTheFilteredList() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        settleAgent(id, "SUCCESS", "7 joueurs");

        String back = java.net.URLEncoder.encode("/actions?status=success&domain=all&q=joueurs",
                java.nio.charset.StandardCharsets.UTF_8);
        String detail = get("/actions/" + id + "?back=" + back).body();

        assertTrue(detail.contains("href=\"/actions?status=success&amp;domain=all&amp;q=joueurs\""),
                "retour à la liste filtrée, pas à une liste réinitialisée");
        assertTrue(detail.contains("Retour à l&#39;historique") || detail.contains("Retour à l'historique"));
    }

    @Test
    void aForeignBackLinkIsIgnored() throws Exception {
        start();
        loginOwner();
        String id = createAction("&type=player.list");
        for (String evil : new String[] {"https://exemple.invalid/", "//exemple.invalid", "/login",
                "/actions?x=\"><script>"}) {
            String detail = get("/actions/" + id + "?back="
                    + java.net.URLEncoder.encode(evil, java.nio.charset.StandardCharsets.UTF_8)).body();
            assertTrue(detail.contains("href=\"/actions\""), "repli sur l'historique pour : " + evil);
            assertFalse(detail.contains("exemple.invalid"), evil);
        }
    }

    // ---- auth & permissions --------------------------------------------------------

    @Test
    void actionsAndBellRequireDiagnosticsRead() throws Exception {
        start();
        // anonyme
        assertEquals(303, get("/actions").statusCode());
        assertEquals("/login", get("/actions").headers().firstValue("Location").orElse(""));

        PermissionService perms = new PermissionService();
        // La cloche et /actions sont gardés par DIAGNOSTICS_READ (jamais « role == OWNER » en dur).
        assertTrue(perms.canByRoleOnly("OWNER", Permission.DIAGNOSTICS_READ));
        assertFalse(perms.canByRoleOnly("__no_such_role__", Permission.DIAGNOSTICS_READ), "un rôle inconnu ne passe pas");
    }

    // ---- infra --------------------------------------------------------------------------

    /** POST /agents/action (params minimaux), renvoie l'id de l'action créée. */
    private String createAction(String extraForm) throws Exception {
        String token = csrf(get("/agents").body());
        HttpResponse<String> res = post("/agents/action",
                "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&return=/players" + extraForm);
        assertEquals(303, res.statusCode());
        Matcher m = Pattern.compile("[?&]toast=([0-9a-fA-F-]{36})").matcher(res.headers().firstValue("Location").orElse(""));
        assertTrue(m.find(), "toast id dans la redirection : " + res.headers().firstValue("Location").orElse(""));
        return m.group(1);
    }

    /** L'agent relève l'action et renvoie un résultat terminal. */
    private void settleAgent(String actionId, String status, String message) throws Exception {
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        String body = "{\"action_id\":\"" + actionId + "\",\"status\":\"" + status + "\",\"value\":\"\","
                + "\"message\":\"" + message + "\"}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + actionId + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Contenu principal de la page (après la topbar : exclut la cloche/notifications). */
    private static String main(String html) {
        int i = html.indexOf("<main class=\"main\">");
        return i < 0 ? html : html.substring(i);
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
