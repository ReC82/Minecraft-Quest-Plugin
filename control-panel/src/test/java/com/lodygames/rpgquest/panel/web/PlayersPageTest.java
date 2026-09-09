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

/** Annuaire des joueurs {@code /players} V2 (#96) : online/offline, recherche, filtres, ban/unban, capacités offline. */
class PlayersPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String UUID_LODY = "b56885c8-1111-4111-8111-111111111111";
    private static final String UUID_STEVE = "22222222-2222-4222-8222-222222222222";
    private static final String UUID_GRIEF = "33333333-3333-4333-8333-333333333333";

    // 1 en ligne (LoDyMcFly), 1 hors ligne (Steve), 1 hors ligne banni (Grief_Kid).
    private static final String CATALOG = "{"
            + "\"total\":3,\"online\":1,\"offline\":2,\"banned\":1,\"truncated\":false,\"players\":["
            + "{\"uuid\":\"" + UUID_LODY + "\",\"name\":\"LoDyMcFly\",\"online\":true,\"hasPlayedBefore\":true,"
            + "\"firstPlayed\":1600000000000,\"lastSeen\":1600000000000,\"banned\":false,"
            + "\"world\":\"world_hub\",\"x\":125,\"y\":64,\"z\":-82},"
            + "{\"uuid\":\"" + UUID_STEVE + "\",\"name\":\"Steve\",\"online\":false,\"hasPlayedBefore\":true,"
            + "\"firstPlayed\":1500000000000,\"lastSeen\":1599000000000,\"banned\":false},"
            + "{\"uuid\":\"" + UUID_GRIEF + "\",\"name\":\"Grief_Kid\",\"online\":false,\"hasPlayedBefore\":true,"
            + "\"firstPlayed\":1400000000000,\"lastSeen\":1598000000000,\"banned\":true,\"banReason\":\"spam répété\"}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void directoryShowsOnlineAndOfflineWithBadgesAndNameFirst() throws Exception {
        start();
        seedCatalog(CATALOG);
        String page = get("/players?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("<h1>Joueurs</h1>"));
        assertTrue(page.contains("class=\"accordion npc-accordion\" id=\"players-accordion\""), "liste = accordion");
        // badges avec texte (pas seulement la couleur)
        assertTrue(page.contains("text-bg-success\">● En ligne</span>"));
        assertTrue(page.contains("text-bg-secondary\">○ Hors ligne</span>"));
        assertTrue(page.contains("text-bg-danger\">Banni</span>"));
        // nom = libellé principal, UUID PAS en en-tête
        assertTrue(page.contains("<span class=\"npc-name\">LoDyMcFly</span>"));
        int lodyHead = page.indexOf("LoDyMcFly");
        assertFalse(page.substring(lodyHead, lodyHead + 200).contains(UUID_LODY), "UUID absent de l'en-tête");
        // synthèse
        assertTrue(page.contains("3 joueur(s) connus · 1 en ligne · 2 hors ligne · 1 banni(s)"));
        // online d'abord (LoDyMcFly avant Steve avant Grief_Kid)
        assertTrue(page.indexOf("LoDyMcFly") < page.indexOf(">Steve<"));
        assertTrue(page.indexOf(">Steve<") < page.indexOf("Grief_Kid"));
        // sections du détail
        for (String s : new String[] {">Identité<", ">Activité<", ">RPGQuest<", ">Droits<", ">Modération<", ">Actions<"}) {
            assertTrue(page.contains(s), "section " + s);
        }
        // UUID copiable dans le détail
        assertTrue(page.contains("data-copy=\"" + UUID_LODY + "\""));
        // aucune saisie de commande brute
        assertFalse(page.contains("name=\"command\""), "aucune console libre sur /players");
    }

    @Test
    void serverSideSearchByNameAndUuid() throws Exception {
        start();
        seedCatalog(CATALOG);
        assertTrue(get("/players?agent=" + TestConfig.AGENT_ID + "&q=steve").body().contains(">Steve<"));
        assertFalse(get("/players?agent=" + TestConfig.AGENT_ID + "&q=steve").body().contains("LoDyMcFly"));
        assertTrue(get("/players?agent=" + TestConfig.AGENT_ID + "&q=" + UUID_GRIEF).body().contains("Grief_Kid"));
        assertTrue(get("/players?agent=" + TestConfig.AGENT_ID + "&q=zzz").body()
                .contains("Aucun joueur ne correspond"));
    }

    @Test
    void filtersAreServerSideLinks() throws Exception {
        start();
        seedCatalog(CATALOG);
        String online = get("/players?agent=" + TestConfig.AGENT_ID + "&filter=online").body();
        assertTrue(online.contains("LoDyMcFly") && !online.contains(">Steve<"));
        String banned = get("/players?agent=" + TestConfig.AGENT_ID + "&filter=banned").body();
        assertTrue(banned.contains("Grief_Kid") && !banned.contains("LoDyMcFly"));
        // la puce active est marquée
        assertTrue(banned.contains("pa-chip on\" href=\"/players?agent=" + TestConfig.AGENT_ID + "&filter=banned"));
    }

    @Test
    void banFormPresentForNonBannedAndValidated() throws Exception {
        start();
        seedCatalog(CATALOG);
        String page = get("/players?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("name=\"type\" value=\"player.ban\""), "form ban sur un joueur non banni");
        assertTrue(page.contains("name=\"reason\"") && page.contains("required"), "raison obligatoire");
        assertTrue(page.contains("value=\"" + UUID_STEVE + "\">"), "cible = UUID, jamais le pseudo seul");
        // Grief_Kid (banni) : pas de ban, mais un unban
        int griefItem = page.lastIndexOf("Grief_Kid");
        assertTrue(page.indexOf("name=\"type\" value=\"player.unban\"") > 0, "form unban présent");

        String token = csrf(page);
        // sans confirm -> refusé
        HttpResponse<String> noConfirm = post("/agents/action", "_csrf=" + token
                + "&type=player.ban&agent=" + TestConfig.AGENT_ID + "&return=/players&player=" + UUID_STEVE
                + "&reason=" + enc("comportement toxique"));
        assertTrue(noConfirm.headers().firstValue("Location").orElse("").contains("err="));
        // sans raison -> refusé
        HttpResponse<String> noReason = post("/agents/action", "_csrf=" + token
                + "&type=player.ban&agent=" + TestConfig.AGENT_ID + "&return=/players&player=" + UUID_STEVE
                + "&confirm=true");
        assertTrue(noReason.headers().firstValue("Location").orElse("").contains("err="));
        // complet -> 303 + toast + action en file
        HttpResponse<String> ok = post("/agents/action", "_csrf=" + token
                + "&type=player.ban&agent=" + TestConfig.AGENT_ID + "&return=/players&player=" + UUID_STEVE
                + "&reason=" + enc("comportement toxique") + "&confirm=true");
        assertEquals(303, ok.statusCode());
        assertTrue(ok.headers().firstValue("Location").orElse("").contains("toast="));
        assertTrue(pendingFor(TestConfig.AGENT_ID) >= 1);
    }

    @Test
    void giveItemIsOnlineOnly() throws Exception {
        start();
        seedCatalog(CATALOG);
        String page = get("/players?agent=" + TestConfig.AGENT_ID).body();
        // LoDyMcFly online -> formulaire give présent (en ligne uniquement)
        assertTrue(page.contains("en ligne uniquement"));
        assertTrue(page.contains("name=\"type\" value=\"player.item.give\""));
        // Steve offline -> explication d'indisponibilité, pas de submit give pour lui
        assertTrue(page.contains("Indisponible : le joueur est hors ligne"));
    }

    @Test
    void focusParamPreOpensThatPlayer() throws Exception {
        start();
        seedCatalog(CATALOG);
        String page = get("/players?agent=" + TestConfig.AGENT_ID + "&player=" + UUID_STEVE).body();
        // l'élément de Steve est déplié (collapse show), les autres non
        int stevePanel = page.indexOf("data-res-id=\"" + UUID_STEVE + "\"");
        String block = page.substring(stevePanel, page.indexOf("</div></div></div>", stevePanel));
        assertTrue(block.contains("accordion-collapse collapse show"));
    }

    @Test
    void paginationBeyond50() throws Exception {
        start();
        StringBuilder rows = new StringBuilder("{\"total\":130,\"online\":0,\"offline\":130,\"banned\":0,\"players\":[");
        for (int i = 0; i < 130; i++) {
            if (i > 0) {
                rows.append(',');
            }
            rows.append("{\"uuid\":\"aaaaaaaa-0000-4000-8000-").append(String.format("%012d", i))
                    .append("\",\"name\":\"P").append(String.format("%03d", i))
                    .append("\",\"online\":false,\"banned\":false,\"lastSeen\":").append(2_000_000_000_000L - i).append("}");
        }
        rows.append("]}");
        seedCatalog(rows.toString());

        String p1 = get("/players?agent=" + TestConfig.AGENT_ID + "&sort=name").body();
        assertTrue(p1.contains("Page 1 / 3"));
        assertTrue(p1.contains(">P000<") && p1.contains(">P049<") && !p1.contains(">P050<"));
        String p2 = get("/players?agent=" + TestConfig.AGENT_ID + "&sort=name&page=2").body();
        assertTrue(p2.contains(">P050<") && p2.contains("Page 2 / 3"));
    }

    @Test
    void emptyStateWhenCatalogNeverLoaded() throws Exception {
        start();
        String page = get("/players?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Annuaire non chargé"));
        assertTrue(page.contains("name=\"type\" value=\"player.catalog\""));
    }

    // ---- infra ----------------------------------------------------------------------------

    private void seedCatalog(String detailsJson) throws Exception {
        String token = csrf(get("/players?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=player.catalog&agent=" + TestConfig.AGENT_ID + "&return=/players");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("action non livrée : " + poll.body());
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"x\","
                + "\"message\":\"ok\",\"details\":" + detailsJson + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private int pendingFor(String agent) throws Exception {
        Map<String, Object> body = com.lodygames.rpgquest.panel.json.Json.parseObject(
                get("/agents/actions.json?agent=" + agent).body());
        return ((Number) body.get("pending")).intValue();
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

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
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
