package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #335 — Paramètres généraux : valeurs relues, enregistrement validé, auteur transmis. */
class SettingsPageTest {

    private static final String VALUES = "{\"values\":{\"currency_name\":\"LodyCoins\",\"beta_warning_enabled\":\"true\","
            + "\"beta_warning_text\":\"Bêta !\"},\"defaults\":{\"currency_name\":\"LodyCoins\"},\"updatedAt\":\"\","
            + "\"updatedBy\":\"\",\"problem\":\"\"}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private Path content;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void beforeAnyReadingNothingIsEditableAndOneButtonReadsTheServer() throws Exception {
        start();
        String page = get("/settings").body();
        assertTrue(page.contains("pas encore été lus"));
        assertTrue(page.contains("action=\"/settings/refresh\""));
        assertFalse(page.contains("data-busy-label=\"Enregistrement…\""), "rien à enregistrer sans valeurs connues");
        post("/settings/refresh", Map.of("_csrf", csrf(page)), null);
        assertEquals(1, actions("settings.get").size());
    }

    @Test
    void theCurrencyTabShowsTheValueReadAndSavingSendsItWithTheAuthor() throws Exception {
        start();
        record("settings.get", VALUES);
        String page = get("/settings").body();
        assertTrue(page.contains("value=\"LodyCoins\""));
        assertTrue(page.contains("Valeurs relues sur le serveur"));
        Map<String, String> form = formFieldsOf(page, "/settings/save");
        form.put("currency_name", "Écus");
        HttpResponse<String> res = post("/settings/save", form, null);
        assertEquals(303, res.statusCode());
        AgentActionRow set = actions("settings.set").get(0);
        assertEquals("Écus", set.params().get("currency_name"));
        assertEquals(TestConfig.OWNER_USERNAME, set.params().get("actor"));
        assertTrue(get("/settings").body().contains("Enregistrement en cours"), "jamais un succès anticipé");
    }

    @Test
    void anInvalidValueIsRefusedBeforeReachingTheServer() throws Exception {
        start();
        record("settings.get", VALUES);
        String page = get("/settings?tab=connexion").body();
        assertTrue(page.contains("Afficher l&#39;avertissement") || page.contains("Afficher l'avertissement"));
        Map<String, String> form = formFieldsOf(page, "/settings/save");
        form.put("beta_warning_text", "<red>bêta</red>");
        HttpResponse<String> res = post("/settings/save", form, null);
        assertTrue(res.headers().firstValue("Location").orElse("").contains("err="));
        assertTrue(actions("settings.set").isEmpty());
    }

    @Test
    void theAdministrationTabOffersNoSwitchForTheBypass() throws Exception {
        start();
        record("settings.get", VALUES);
        String page = get("/settings?tab=administration").body();
        assertTrue(page.contains("Pas encore disponible"));
        assertFalse(formHtmlSafe(page).contains("bypass"), "aucun interrupteur de bypass");
    }

    private static String formHtmlSafe(String page) {
        int i = page.indexOf("action=\"/settings/save\"");
        return i < 0 ? "" : page.substring(i, page.indexOf("</form>", i));
    }

    // ---- Harnais -------------------------------------------------------------------------------

    private void start() throws Exception {
        content = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(content.resolve(kind));
        }
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1", content.toString()),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + TestConfig.OWNER_USERNAME + "&password="
                        + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token)));
    }

    private void record(String type, String details) {
        String id = store.createAction(TestConfig.AGENT_ID, type, Map.of(), "owner");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + details + "}", Instant.now());
    }

    private void survey(String details) {
        record("dialogue.list", details);
    }

    private List<AgentActionRow> actions(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream().filter(a -> a.type().equals(type)).toList();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String formHtml(String page) {
        int start = page.indexOf("action=\"/dialogues/save\"");
        return page.substring(start, page.indexOf("</form>", start));
    }

    private static Map<String, String> formFields(String page) {
        return formFieldsOf(page, "/dialogues/save");
    }

    /** Ce que le navigateur enverrait : champs non désactivés, cases cochées, option choisie. */
    private static Map<String, String> formFieldsOf(String page, String action) {
        int start = page.indexOf("action=\"" + action + "\"");
        String f = page.substring(start, page.indexOf("</form>", start));
        Map<String, String> out = new LinkedHashMap<>();
        Matcher in = Pattern.compile("<input([^>]*)>").matcher(f);
        while (in.find()) {
            String a = in.group(1);
            String name = attr(a, "name");
            String type = attr(a, "type");
            if (name == null || a.contains(" disabled") || "submit".equals(type)) {
                continue;
            }
            if ("checkbox".equals(type) && !a.contains(" checked")) {
                continue;
            }
            out.put(name, unescape(attr(a, "value") == null ? "" : attr(a, "value")));
        }
        Matcher ta = Pattern.compile("(?s)<textarea([^>]*)>(.*?)</textarea>").matcher(f);
        while (ta.find()) {
            String name = attr(ta.group(1), "name");
            if (name != null && !ta.group(1).contains(" disabled")) {
                out.put(name, unescape(ta.group(2)));
            }
        }
        Matcher se = Pattern.compile("(?s)<select([^>]*)>(.*?)</select>").matcher(f);
        while (se.find()) {
            String name = attr(se.group(1), "name");
            if (name == null || se.group(1).contains(" disabled")) {
                continue;
            }
            Matcher opt = Pattern.compile("<option value=\"([^\"]*)\"([^>]*)>").matcher(se.group(2));
            String value = null;
            String first = null;
            while (opt.find()) {
                first = first == null ? opt.group(1) : first;
                if (opt.group(2).contains("selected")) {
                    value = opt.group(1);
                }
            }
            out.put(name, unescape(value != null ? value : first == null ? "" : first));
        }
        return out;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String csrf(String html) {
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("pas de jeton CSRF");
        }
        return m.group(1);
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).GET());
    }

    private HttpResponse<String> post(String p, Map<String, String> form, String op) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            body.append(body.length() == 0 ? "" : "&").append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        if (op != null) {
            body.append("&_op=").append(URLEncoder.encode(op, StandardCharsets.UTF_8));
        }
        return send(HttpRequest.newBuilder(uri(p))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
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
            String[] parts = sc.split(";", 2);
            int eq = parts[0].indexOf('=');
            String n = parts[0].substring(0, eq).trim();
            String v = parts[0].substring(eq + 1).trim();
            if (sc.toLowerCase().contains("max-age=0") || v.isEmpty()) {
                jar.remove(n);
            } else {
                jar.put(n, v);
            }
        }
        return res;
    }
}
