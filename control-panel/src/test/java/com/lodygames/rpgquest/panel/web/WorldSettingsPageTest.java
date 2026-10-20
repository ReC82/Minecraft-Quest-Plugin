package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.support.TestConfig;
import com.lodygames.rpgquest.panel.users.InMemoryUserRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #561 (#331-W2) — réglages d'un monde dans la fiche : relecture, aperçu sans écriture,
 * application confirmée reprise de l'aperçu enregistré, double envoi impossible, WORLD_WRITE
 * contrôlé par le serveur PlugAdmin.
 */
class WorldSettingsPageTest {

    private static final String READING = "{\"world\":\"terres\",\"loaded\":true,\"snapshot\":{\"multiverse\":false,"
            + "\"roles\":[],\"players\":0,\"minHeight\":-64,\"maxHeight\":320,\"values\":{\"difficulty\":\"EASY\","
            + "\"pvp\":\"true\",\"time\":\"1000\",\"spawn\":\"0,64,0,0\",\"border_size\":\"59999968\"},"
            + "\"writers\":{\"difficulty\":\"BUKKIT\",\"pvp\":\"GAMERULE\",\"time\":\"BUKKIT\",\"spawn\":\"BUKKIT\","
            + "\"border_size\":\"BUKKIT\"},\"unavailable\":{\"gamemode\":\"Réglage propre à Multiverse-Core\"},"
            + "\"rules\":[{\"name\":\"keep_inventory\",\"type\":\"BOOLEAN\",\"value\":\"false\"},"
            + "{\"name\":\"pvp\",\"type\":\"BOOLEAN\",\"value\":\"true\",\"dedicatedTo\":\"pvp\"}],\"notes\":[]},"
            + "\"fingerprint\":\"0123456789abcdef01234567\",\"history\":[{\"at\":\"2026-10-20T09:00:00Z\","
            + "\"actor\":\"owner\",\"result\":\"APPLIED\",\"changes\":[{\"key\":\"time\",\"before\":\"18000\","
            + "\"requested\":\"1000\",\"after\":\"1000\",\"outcome\":\"OK\"}]}]}";

    private static final String PREVIEW = "{\"world\":\"terres\",\"loaded\":true,\"message\":\"1 à appliquer\","
            + "\"fingerprint\":\"0123456789abcdef01234567\",\"applicable\":1,\"refused\":0,\"warnings\":[],"
            + "\"changes\":[{\"key\":\"difficulty\",\"label\":\"Difficulté\",\"current\":\"EASY\",\"proposed\":\"HARD\","
            + "\"writer\":\"BUKKIT\",\"status\":\"APPLY\",\"reason\":\"\",\"effect\":\"Difficulté changée\"},"
            + "{\"key\":\"pvp\",\"label\":\"PvP\",\"current\":\"true\",\"proposed\":\"true\",\"writer\":\"GAMERULE\","
            + "\"status\":\"UNCHANGED\",\"reason\":\"\",\"effect\":\"\"}]}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private AgentStore store;
    private InMemoryUserRepository users;

    @BeforeEach
    void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        users = new InMemoryUserRepository();
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"), new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store, users);
        port = app.start();
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60, "{}", "{}"));
    }

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    private static final String FICHE = "/worlds?agent=" + TestConfig.AGENT_ID + "&world=terres";

    @Test
    void onlyTheOwnerMayWriteByDefault() {
        assertTrue(Role.OWNER.has(Permission.WORLD_WRITE));
        for (Role role : Role.values()) {
            if (role != Role.OWNER) {
                assertFalse(role.has(Permission.WORLD_WRITE), role.name());
            }
        }
        assertEquals(Permission.WORLD_READ, AgentActionCatalog.spec("world.settings.get").orElseThrow().permission());
        assertEquals(Permission.WORLD_WRITE, AgentActionCatalog.spec("world.settings.preview").orElseThrow().permission());
        var apply = AgentActionCatalog.spec("world.settings.apply").orElseThrow();
        assertEquals(Permission.WORLD_WRITE, apply.permission());
        assertTrue(apply.mutation() && apply.sensitive());
    }

    @Test
    void readingIsRequestedForThatWorldThenShownWithRealValuesAndHistory() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.result("world.list", WorldsPageTest.RELEVE);
        String page = owner.get(FICHE).body();
        // « terres » n'est pas dans le relevé world.list : la fiche le dit, sans section Réglages.
        assertTrue(page.contains("n'apparaît pas dans le"), "monde absent du relevé");

        owner.result("world.list", releveWithTerres());
        page = owner.get(FICHE).body();
        assertTrue(page.contains("Lire les réglages réels"), page);
        owner.post("/worlds/settings/read", "_csrf=" + owner.csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID + "&world=terres");
        AgentActionRow get = store.latestActionOfType(TestConfig.AGENT_ID, "world.settings.get").orElseThrow();
        assertEquals("terres", get.params().get("world"));
        owner.post("/worlds/settings/read", "_csrf=" + owner.csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID + "&world=terres");
        assertEquals(1, store.recentActions(TestConfig.AGENT_ID, 20).stream()
                .filter(a -> "world.settings.get".equals(a.type())).count(), "pas de relecture en double");
        owner.complete(get.id(), READING);
        page = owner.get(FICHE).body();
        assertTrue(page.contains("Valeur réelle") && page.contains("Facile"), "valeurs relues");
        assertTrue(page.contains("Réglage propre à Multiverse-Core"), "indisponibilité expliquée");
        assertTrue(page.contains("réglée par « PvP"), "règle dédiée signalée");
        assertTrue(page.contains("— inchangé —"), "aucune valeur pré-choisie");
        assertFalse(page.contains("name=\"set.rule.pvp\""), "un seul chemin d'écriture pour le PvP");
        assertTrue(page.contains("Dernières modifications (1)") && page.contains("18000 (00h00) → 1000 (07h00)"), page);
    }

    @Test
    void previewSendsOnlyChosenValuesAndApplyReusesTheRecordedPreviewOnce() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.result("world.list", releveWithTerres());
        String token = owner.csrf(FICHE);
        owner.post("/worlds/settings/preview", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&world=terres&submit_token=tok-preview-1&set.difficulty=HARD&set.pvp=&set.time=&set.rule.keep_inventory=");
        AgentActionRow preview = store.latestActionOfType(TestConfig.AGENT_ID, "world.settings.preview").orElseThrow();
        assertEquals(Map.of("world", "terres", "set.difficulty", "HARD"), preview.params(),
                "seules les valeurs choisies partent dans l'aperçu");
        owner.post("/worlds/settings/preview", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&world=terres&submit_token=tok-preview-1&set.difficulty=HARD");
        assertEquals(1, count("world.settings.preview"), "double clic sur l'aperçu");
        owner.complete(preview.id(), PREVIEW);

        String page = owner.get(FICHE + "&preview=" + preview.id()).body();
        assertTrue(page.contains("Aperçu — rien n'a été écrit") && page.contains("À appliquer")
                && page.contains("Inchangé"), page);
        assertTrue(page.contains("name=\"confirm\"") && page.contains("Appliquer et relire"));

        String noConfirm = "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&world=terres&preview=" + preview.id()
                + "&submit_token=tok-apply-0";
        owner.post("/worlds/settings/apply", noConfirm);
        assertEquals(0, count("world.settings.apply"), "sans confirmation, rien n'est envoyé");

        owner.post("/worlds/settings/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&world=terres&preview="
                + preview.id() + "&submit_token=tok-apply-1&confirm=true&set.pvp=false&actor=pirate&expected=ffffffffffffffffffffffff");
        AgentActionRow apply = store.latestActionOfType(TestConfig.AGENT_ID, "world.settings.apply").orElseThrow();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("world", "terres");
        expected.put("set.difficulty", "HARD");
        expected.put("expected", "0123456789abcdef01234567");
        expected.put("actor", TestConfig.OWNER_USERNAME);
        expected.put("confirm", "true");
        expected.put("preview_id", preview.id());
        assertEquals(expected, apply.params(), "valeurs, empreinte et auteur repris de l'aperçu, jamais du navigateur");

        owner.post("/worlds/settings/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&world=terres&preview="
                + preview.id() + "&submit_token=tok-apply-2&confirm=true");
        owner.post("/worlds/settings/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&world=terres&preview="
                + preview.id() + "&submit_token=tok-apply-1&confirm=true");
        assertEquals(1, count("world.settings.apply"), "un aperçu ne s'applique qu'une fois");

        owner.complete(apply.id(), "{\"world\":\"terres\",\"result\":\"PARTIAL\",\"message\":\"0 sur 1\",\"applied\":["
                + "{\"key\":\"difficulty\",\"label\":\"Difficulté\",\"before\":\"EASY\",\"requested\":\"HARD\","
                + "\"after\":\"NORMAL\",\"outcome\":\"MISMATCH\",\"detail\":\"relu NORMAL\"}],\"notes\":[]}");
        page = owner.get(FICHE + "&applied=" + apply.id()).body();
        assertTrue(page.contains("Relu après") && page.contains("Écart") && page.contains("Normale"), page);
    }

    @Test
    void worldWriteIsCheckedByTheServerNotOnlyHiddenInThePage() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.post("/users/create", "_csrf=" + owner.csrf("/users") + "&username=adm&password="
                + URLEncoder.encode("admin-password-1234", StandardCharsets.UTF_8) + "&role=" + Role.ADMIN.name());
        owner.result("world.list", releveWithTerres());
        String reading = owner.enqueue("/worlds/settings/read", "world=terres", "world.settings.get");
        owner.complete(reading, READING);

        Client admin = login("adm", "admin-password-1234");
        String page = admin.get(FICHE).body();
        assertTrue(page.contains("Valeur réelle"), "lecture autorisée (WORLD_READ)");
        assertFalse(page.contains("/worlds/settings/preview"), "pas de formulaire sans WORLD_WRITE");
        String token = admin.csrf(FICHE);
        HttpResponse<String> res = admin.post("/worlds/settings/preview", "_csrf=" + token + "&agent="
                + TestConfig.AGENT_ID + "&world=terres&set.difficulty=HARD");
        assertTrue(res.statusCode() == 403 || res.statusCode() >= 300, "refusé : " + res.statusCode());
        admin.post("/worlds/settings/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&world=terres&preview=x&confirm=true");
        assertEquals(0, count("world.settings.preview") + count("world.settings.apply"));

        owner.post("/agents/action", "_csrf=" + owner.csrf(FICHE) + "&type=world.settings.apply&agent="
                + TestConfig.AGENT_ID + "&return=/worlds&world=terres&set.difficulty=HARD&confirm=true"
                + "&expected=0123456789abcdef01234567&actor=owner");
        assertEquals(0, count("world.settings.apply"), "jamais par le formulaire générique");
    }

    @Test
    void displayNameIsEditableWithItsDefaultAndPreviewedLikeAnyOtherSetting() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.result("world.list", releveWithTerres());
        owner.post("/worlds/settings/read", "_csrf=" + owner.csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID + "&world=terres");
        AgentActionRow get = store.latestActionOfType(TestConfig.AGENT_ID, "world.settings.get").orElseThrow();
        owner.complete(get.id(), READING.replace("\"values\":{", "\"values\":{\"alias\":\"-\",")
                .replace("\"writers\":{", "\"writers\":{\"alias\":\"RPGQUEST\","));
        String page = owner.get(FICHE).body();
        assertTrue(page.contains("Nom affiché") && page.contains("aucun — nom par défaut"), "valeur relue");
        assertTrue(page.contains("RPGQuest (world-names.yml)"), "chemin d'écriture annoncé");
        assertTrue(page.contains("name=\"set.alias\""), "champ du nom affiché");
        assertTrue(page.contains("l'identifiant <code>terres</code> ne change pas"), page);
        owner.post("/worlds/settings/preview", "_csrf=" + owner.csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID
                + "&world=terres&submit_token=tok-alias-1&set.alias=" + URLEncoder.encode("Terres sauvages",
                StandardCharsets.UTF_8));
        AgentActionRow preview = store.latestActionOfType(TestConfig.AGENT_ID, "world.settings.preview").orElseThrow();
        assertEquals(Map.of("world", "terres", "set.alias", "Terres sauvages"), preview.params(),
                "même chemin que les autres réglages (aperçu, puis confirmation)");
    }

    @Test
    void catalogBoundsMirrorThePlugin() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("world", "terres");
        assertFalse(AgentActionCatalog.validate("world.settings.preview", form).valid(), "rien de choisi");
        form.put("set.Bad", "1");
        assertFalse(AgentActionCatalog.validate("world.settings.preview", form).valid());
        form.remove("set.Bad");
        form.put("set.rule.keep_inventory", "true");
        form.put("spawn_player", "Lo");
        assertFalse(AgentActionCatalog.validate("world.settings.preview", form).valid(), "pseudo trop court");
        form.put("spawn_player", "LoDyMcFly");
        assertTrue(AgentActionCatalog.validate("world.settings.preview", form).valid());
        form.put("confirm", "true");
        assertFalse(AgentActionCatalog.validate("world.settings.apply", form).valid(), "empreinte et auteur exigés");
        form.put("expected", "0123456789abcdef01234567");
        form.put("actor", "owner");
        var ok = AgentActionCatalog.validate("world.settings.apply", form);
        assertTrue(ok.valid(), ok.error());
        assertFalse(ok.params().containsKey("spawn_player"), "la position est relevée à l'aperçu seulement");
        assertEquals("true", ok.params().get("confirm"));
    }

    // ---- Outils -------------------------------------------------------------------------------

    private static String releveWithTerres() {
        String terres = "{\"name\":\"terres\",\"loaded\":true,\"manager\":\"RPGQUEST\",\"main\":false,\"essential\":false,"
                + "\"roles\":[],\"multiverseKnown\":false,\"rpgquestManaged\":true,\"environment\":\"NORMAL\","
                + "\"worldType\":\"NORMAL\",\"seed\":\"1\",\"structures\":true,\"players\":0,\"difficulty\":\"EASY\","
                + "\"pvp\":true,\"hardcore\":false,\"spawn\":\"0, 64, 0\",\"generator\":null,\"generatorSettings\":null,"
                + "\"autoLoad\":null,\"links\":{\"portalsFrom\":[],\"portalsTo\":[],\"sites\":[],\"waypoints\":0,"
                + "\"autoWaypoints\":false,\"mobs\":[],\"npcSpots\":[],\"cities\":[]},\"issues\":[]}";
        return WorldsPageTest.RELEVE.replace("\"worlds\":[", "\"worlds\":[" + terres + ",");
    }

    private long count(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 50).stream().filter(a -> type.equals(a.type())).count();
    }

    private Client login(String user, String password) throws Exception {
        Client c = new Client();
        String token = c.csrf("/login");
        c.post("/login", "username=" + user + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)
                + "&_csrf=" + token);
        return c;
    }

    private final class Client {
        private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        private final Map<String, String> jar = new LinkedHashMap<>();

        String csrf(String path) throws Exception {
            Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(get(path).body());
            if (!m.find()) {
                throw new IllegalStateException("aucun jeton CSRF sur " + path);
            }
            return m.group(1);
        }

        /** Met une action en file via la route puis renvoie son identifiant. */
        String enqueue(String route, String form, String type) throws Exception {
            post(route, "_csrf=" + csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID + "&" + form);
            return store.latestActionOfType(TestConfig.AGENT_ID, type).orElseThrow().id();
        }

        /** Crée {@code type} par le formulaire générique (lecture) et le termine avec {@code details}. */
        void result(String type, String details) throws Exception {
            post("/agents/action", "_csrf=" + csrf("/worlds?agent=" + TestConfig.AGENT_ID) + "&type=" + type + "&agent="
                    + TestConfig.AGENT_ID + "&return=/worlds");
            complete(store.latestActionOfType(TestConfig.AGENT_ID, type).orElseThrow().id(), details);
        }

        void complete(String id, String details) throws Exception {
            // L'agent récupère la file (livraison) puis rend son résultat.
            http.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                    .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                    .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(), HttpResponse.BodyHandlers.ofString());
            http.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                    .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                    .header("X-Agent-Id", TestConfig.AGENT_ID)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"status\":\"SUCCESS\",\"message\":\"ok\",\"details\":"
                            + details + "}")).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String p) throws Exception {
            return send(HttpRequest.newBuilder(uri(p)).GET());
        }

        HttpResponse<String> post(String p, String form) throws Exception {
            return send(HttpRequest.newBuilder(uri(p)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)));
        }

        private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
            if (!jar.isEmpty()) {
                b.header("Cookie", String.join("; ", jar.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).toList()));
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
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

    private URI uri(String p) {
        return URI.create("http://127.0.0.1:" + port + p);
    }
}
