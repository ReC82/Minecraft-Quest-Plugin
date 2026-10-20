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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #543 — page « Capacités » (catalogue, filtres, fiche) et cartes de capacités du profil, rendues
 * à partir du catalogue publié par le plugin.
 */
class MobAbilitiesPageTest {

    /** Un mob spécial au tirage naturel (Renforts désactivée), un boss sans capacité. */
    private static final String MOB_LIST = "{\"profiles\":["
            + "{\"id\":\"rpgquest:cave_zombie\",\"category\":\"SPECIAL\",\"enabled\":true,\"entityType\":\"ZOMBIE\","
            + "\"displayName\":\"Zombie des cavernes\",\"spawnChance\":0.2,\"alivePopulation\":0,"
            + "\"abilitiesSummary\":[],\"bossMode\":\"QUEST\",\"yaml\":\"id: x\","
            + "\"abilityWarnings\":[\"Capacité « Enragé » (ENRAGED) en double : une seule par profil. Le profil reste chargé.\"],"
            + "\"abilities\":[{\"type\":\"ENRAGED\",\"label\":\"Enragé\",\"enabled\":true,\"values\":{\"health-fraction\":0.3,"
            + "\"speed-multiplier\":1.3,\"damage-multiplier\":1.5,\"enabled\":true,\"chance\":1.0,\"cooldown-seconds\":0}},"
            + "{\"type\":\"SUMMON_ON_DAMAGE\",\"label\":\"Renforts\",\"enabled\":false,\"values\":{\"summon-entity-type\":"
            + "\"ZOMBIE\",\"amount\":2,\"max-alive\":4,\"despawn-with-owner\":false,\"enabled\":false,\"chance\":0.25,"
            + "\"cooldown-seconds\":20}}]},"
            + "{\"id\":\"rpgquest:ogre\",\"category\":\"BOSS\",\"enabled\":true,\"entityType\":\"ZOMBIE\","
            + "\"displayName\":\"Ogre\",\"spawnChance\":0.0,\"alivePopulation\":0,\"abilitiesSummary\":[],"
            + "\"bossMode\":\"QUEST\",\"yaml\":\"id: y\",\"abilities\":[],\"abilityWarnings\":[]}],"
            + "\"spawnSettings\":{\"enabled\":true,\"chance\":1.0,\"maxSimultaneousSpecial\":null},"
            + "\"hasIssues\":false,\"issues\":[],\"abilityCatalog\":" + AbilityFixtures.catalogJson() + "}";

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
    void catalogueListsTheFiveTypesWithUsageAndFiltersAndTheMenuEntrySitsUnderMobs() throws Exception {
        start();
        login();
        runAction("mob.list", MOB_LIST);
        String page = get("/mobs/abilities?agent=" + TestConfig.AGENT_ID).body();

        for (String label : new String[] {"Explosion renforcée", "Explosif au contact", "Division", "Enragé", "Renforts"}) {
            assertTrue(page.contains("<div class=\"cap-name\">" + label + "</div>"), "carte absente : " + label);
        }
        assertTrue(page.contains("5 capacité(s) sur 5"), "compte");
        assertTrue(page.contains("1 — cave_zombie (désactivée)"), "profil qui utilise Renforts, désactivée");
        int mobs = page.indexOf(">Mobs spéciaux &amp; boss</span>");
        int abilities = page.indexOf(">Capacités</span>");
        assertTrue(mobs > 0 && abilities > mobs, "entrée « Capacités » juste sous les mobs (Q10)");
        assertTrue(page.contains("navlink active\" href=\"/mobs/abilities\""), "Capacités active");
        assertFalse(page.contains("navlink active\" href=\"/mobs\""), "une seule entrée active");

        String explosions = get("/mobs/abilities?agent=" + TestConfig.AGENT_ID + "&cat=EXPLOSION").body();
        assertTrue(explosions.contains("2 capacité(s) sur 5"), "filtre catégorie");
        String zombie = get("/mobs/abilities?agent=" + TestConfig.AGENT_ID + "&entity=zombie").body();
        assertTrue(zombie.contains("4 capacité(s) sur 5"), "Explosion renforcée sans effet sur ZOMBIE");
        String planned = get("/mobs/abilities?agent=" + TestConfig.AGENT_ID + "&status=PLANNED").body();
        assertTrue(planned.contains("Aucune capacité ne correspond"), "aucune prévue aujourd'hui");
    }

    @Test
    void detailPageShowsBoundedSettingsPresetsGuaranteesAndUsers() throws Exception {
        start();
        login();
        runAction("mob.list", MOB_LIST);
        String page = get("/mobs/abilities?agent=" + TestConfig.AGENT_ID + "&type=ENRAGED").body();
        assertTrue(page.contains("Seuil de vie"), page);
        assertTrue(page.contains("doit être strictement compris entre 0 et 1"), "bornes");
        assertTrue(page.contains("aucun — obligatoire"), "obligatoire plutôt qu'un défaut inventé");
        assertTrue(page.contains("<span class=\"k\">Brutal</span>"), "préréglages");
        assertTrue(page.contains("Ne pose ni ne casse aucun bloc."), "garantie terrain");
        assertTrue(page.contains("Profils qui l'utilisent · 1"), "profils");
        assertTrue(get("/mobs/abilities?agent=" + TestConfig.AGENT_ID + "&type=NOPE").body()
                .contains("Capacité inconnue"));
    }

    @Test
    void profileFormShowsOnlyConfiguredCardsAndAChecklistWithReasons() throws Exception {
        start();
        login();
        runAction("mob.list", MOB_LIST);
        String page = get("/mobs?agent=" + TestConfig.AGENT_ID).body();
        String form = slice(page, "id=\"mob-edit-0\"", "</form>");

        assertTrue(form.contains("Capacités · 2 configurées"), form);
        assertTrue(form.contains("name=\"ab_form\" value=\"1\""));
        assertTrue(form.contains("name=\"ab_0_type\" value=\"ENRAGED\""));
        assertTrue(form.contains("name=\"ab_1_enabled\" value=\"true\">"), "Renforts désactivée : case décochée");
        assertTrue(form.contains("class=\"ab-card off\""), "carte désactivée grisée, valeurs gardées");
        assertTrue(form.contains("name=\"ab_1_chance\" value=\"0.25\""), "valeurs gardées");
        assertTrue(form.contains("en double : une seule par profil"), "avertissement de chargement visible");
        // Liste à cocher : présentes refusées avec la raison, les autres pré-réglées « Normal ».
        assertTrue(form.contains("id=\"mob-edit-0-new-enraged\" name=\"ab_new_ENRAGED\" value=\"true\" disabled"));
        assertTrue(form.contains("Déjà dans ce profil"));
        assertTrue(form.contains("name=\"ab_new_SPLIT_ON_HIT_max-depth\" value=\"2\""), "préréglage Normal copié");
        assertTrue(form.contains("Sans effet sur un ZOMBIE"), "Explosion renforcée : avertissement de compatibilité");
        assertTrue(form.contains("data-ab-preset="), "préréglages copiables");
        assertFalse(form.contains("name=\"enraged_health_fraction\""), "anciens champs plats retirés");
        String inner = form.substring(form.indexOf("<form"));
        assertEquals(count(inner, "<div"), count(inner, "</div>"), "balisage équilibré");
        // Le boss sans capacité : rien d'affiché sauf l'ajout.
        String boss = slice(page, "id=\"mob-edit-1\"", "</form>");
        assertTrue(boss.contains("Capacités · 0 configurée"), boss);
        assertFalse(boss.contains("data-ab-card"));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String slice(String s, String from, String to) {
        int a = s.indexOf(from);
        if (a < 0) {
            throw new IllegalStateException("fragment introuvable : " + from);
        }
        int b = s.indexOf(to, a);
        return b < 0 ? s.substring(a) : s.substring(a, b);
    }

    private void runAction(String type, String details) throws Exception {
        String token = csrf(get("/mobs?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID + "&return=/mobs");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
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
