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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #563 (#331-W4) — charger, décharger, importer un dossier et « charger au démarrage » :
 * boutons seulement si le relevé le permet, raisons des refus affichées, confirmation, auteur et
 * jeton imposés, WORLD_WRITE contrôlé par le serveur PlugAdmin. Aucun monde réel.
 */
class WorldStatePageTest {

    /** Relevé world.state.options : terres déchargeable, hub refusé, un dossier importable, un refusé. */
    static final String OPTIONS = "{\"multiverse\":true,\"multiverseReason\":\"\",\"mainWorld\":\"world\","
            + "\"worlds\":[{\"name\":\"terres\",\"loaded\":true,\"players\":0,\"multiverseKnown\":true,"
            + "\"autoLoad\":true,\"essential\":false,\"operations\":{"
            + "\"LOAD\":{\"operation\":\"LOAD\",\"world\":\"terres\",\"ok\":false,\"autoLoad\":true,"
            + "\"environment\":\"\",\"summary\":\"\",\"errors\":[\"« terres » est déjà chargé.\"],\"warnings\":[]},"
            + "\"UNLOAD\":{\"operation\":\"UNLOAD\",\"world\":\"terres\",\"ok\":true,\"autoLoad\":true,"
            + "\"environment\":\"\",\"summary\":\"Enregistrer puis décharger « terres »\",\"errors\":[],"
            + "\"warnings\":[\"Le portail « terres_to_hub » part de ce monde\"]},"
            + "\"AUTOLOAD\":{\"operation\":\"AUTOLOAD\",\"world\":\"terres\",\"ok\":true,\"autoLoad\":false,"
            + "\"environment\":\"\",\"summary\":\"\",\"errors\":[],\"warnings\":[]}}},"
            + "{\"name\":\"world_hub\",\"loaded\":true,\"players\":3,\"multiverseKnown\":true,\"autoLoad\":true,"
            + "\"essential\":true,\"operations\":{"
            + "\"UNLOAD\":{\"operation\":\"UNLOAD\",\"world\":\"world_hub\",\"ok\":false,\"autoLoad\":true,"
            + "\"environment\":\"\",\"summary\":\"\",\"errors\":[\"« world_hub » a le rôle HUB dans LodyQuests (config.yml).\","
            + "\"3 joueur(s) se trouvent dans « world_hub »\"],\"warnings\":[]},"
            + "\"AUTOLOAD\":{\"operation\":\"AUTOLOAD\",\"world\":\"world_hub\",\"ok\":false,\"autoLoad\":false,"
            + "\"environment\":\"\",\"summary\":\"\",\"errors\":[\"« world_hub » a le rôle HUB\"],\"warnings\":[]}}}],"
            + "\"importable\":[{\"name\":\"ancien\",\"dimension\":\"NETHER\",\"plan\":{\"operation\":\"IMPORT\","
            + "\"world\":\"ancien\",\"ok\":true,\"autoLoad\":true,\"environment\":\"NETHER\",\"summary\":\"\","
            + "\"errors\":[],\"warnings\":[]}},{\"name\":\"vide\",\"dimension\":\"\",\"plan\":{\"operation\":\"IMPORT\","
            + "\"world\":\"vide\",\"ok\":false,\"autoLoad\":true,\"environment\":\"\",\"summary\":\"\","
            + "\"errors\":[\"Le dossier « vide » ne permet pas de déterminer sa dimension\"],\"warnings\":[]}}],"
            + "\"history\":[]}";

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
    private static final String LIST = "/worlds?agent=" + TestConfig.AGENT_ID;

    @Test
    void writesNeedWorldWriteAndConfirmation() {
        for (String type : List.of("world.load", "world.unload", "world.import", "world.autoload")) {
            var spec = AgentActionCatalog.spec(type).orElseThrow();
            assertEquals(Permission.WORLD_WRITE, spec.permission(), type);
            assertTrue(spec.mutation() && spec.sensitive(), type);
        }
        assertEquals(Permission.WORLD_READ, AgentActionCatalog.spec("world.state.options").orElseThrow().permission());
        for (Role role : Role.values()) {
            assertEquals(role == Role.OWNER, role.has(Permission.WORLD_WRITE), role.name());
        }
    }

    @Test
    void catalogBoundsMirrorThePlugin() {
        Map<String, String> form = new LinkedHashMap<>(Map.of("world", "terres", "auto_load", "true",
                "token", "0f4c9e8a-2222", "by", "owner"));
        assertFalse(AgentActionCatalog.validate("world.unload", form).valid(), "confirmation exigée");
        form.put("confirm", "true");
        var ok = AgentActionCatalog.validate("world.unload", form);
        assertTrue(ok.valid(), ok.error());
        assertEquals(Map.of("world", "terres", "auto_load", "true", "token", "0f4c9e8a-2222", "by", "owner",
                "confirm", "true"), ok.params());
        assertFalse(AgentActionCatalog.validate("world.import", form).valid(), "dimension exigée à l'import");
        form.put("environment", "NETHER");
        assertTrue(AgentActionCatalog.validate("world.import", form).valid());
        for (String bad : List.of("../world", "a/b", "", "x y")) {
            Map<String, String> f = new LinkedHashMap<>(form);
            f.put("world", bad);
            assertFalse(AgentActionCatalog.validate("world.load", f).valid(), bad);
        }
        Map<String, String> noToken = new LinkedHashMap<>(form);
        noToken.remove("token");
        assertFalse(AgentActionCatalog.validate("world.load", noToken).valid());
        Map<String, String> badAuto = new LinkedHashMap<>(form);
        badAuto.put("auto_load", "oui");
        assertFalse(AgentActionCatalog.validate("world.load", badAuto).valid());
    }

    @Test
    void ficheShowsAllowedOperationsAndRefusalReasons() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.result("world.list", releveWithTerres());
        String page = owner.get(FICHE).body();
        assertTrue(page.contains("Relever le chargement"), "relevé à demander d'abord");
        assertFalse(page.contains("value=\"world.unload\""));
        owner.result("world.state.options", OPTIONS);
        page = owner.get(FICHE).body();
        assertTrue(page.contains("value=\"world.unload\"") && page.contains("Enregistrer puis décharger"), page);
        assertTrue(page.contains("terres_to_hub"), "avertissement affiché");
        assertTrue(page.contains("value=\"world.autoload\""));
        assertTrue(page.contains("name=\"confirm\" value=\"true\" required"), "case de confirmation");
        assertTrue(page.contains("Aucune suppression n'est possible d'ici"));

        String hub = owner.get("/worlds?agent=" + TestConfig.AGENT_ID + "&world=world_hub").body();
        assertTrue(hub.contains("a le rôle HUB") && hub.contains("3 joueur(s)"), "raisons du refus");
        assertFalse(hub.contains("value=\"world.unload\""), "aucun bouton pour un monde essentiel");

        String list = owner.get(LIST).body();
        assertTrue(list.contains("Importer un dossier existant") && list.contains("value=\"world.import\""), list);
        assertTrue(list.contains("<option value=\"NETHER\" selected>"), "dimension lue dans le dossier pré-choisie");
        assertTrue(list.contains("ne permet pas de déterminer sa dimension"), "dossier refusé expliqué");
    }

    @Test
    void unloadIsQueuedWithSessionActorAndFormTokenOnce() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.result("world.list", releveWithTerres());
        owner.result("world.state.options", OPTIONS);
        String form = "_csrf=" + owner.csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID + "&type=world.unload&return=/worlds"
                + "&world=terres&focus=terres&submit_token=0f4c9e8a-3333&token=0f4c9e8a-3333&auto_load=false"
                + "&auto_load=true&confirm=true&by=quelquun";
        HttpResponse<String> res = owner.post("/agents/action", form);
        assertTrue(res.headers().firstValue("Location").orElse("").contains("focus=terres"), "fiche gardée ouverte");
        AgentActionRow row = store.latestActionOfType(TestConfig.AGENT_ID, "world.unload").orElseThrow();
        assertEquals("terres", row.params().get("world"));
        assertEquals(TestConfig.OWNER_USERNAME, row.params().get("by"), "auteur = session, jamais le formulaire");
        assertEquals("true", row.params().get("auto_load"));
        assertEquals("0f4c9e8a-3333", row.params().get("token"));
        owner.post("/agents/action", form.replace("_csrf=", "_csrf=") );
        assertEquals(1, count("world.unload"), "double clic : un seul envoi");

        owner.complete(row.id(), "{\"world\":\"terres\",\"operation\":\"UNLOAD\",\"result\":\"DONE\","
                + "\"message\":\"Monde « terres » enregistré puis déchargé\",\"replayed\":false,\"checks\":["
                + "{\"label\":\"Chargé\",\"expected\":\"false\",\"actual\":\"false\",\"ok\":true}],\"notes\":[],"
                + "\"history\":[]}");
        String page = owner.get(FICHE).body();
        assertTrue(page.contains("Déchargement de <code>terres</code>") && page.contains("Chargé : demandé false, relu false"),
                page);
    }

    @Test
    void worldWriteIsCheckedByTheServerNotOnlyHiddenInThePage() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.post("/users/create", "_csrf=" + owner.csrf("/users") + "&username=adm&password="
                + URLEncoder.encode("admin-password-1234", StandardCharsets.UTF_8) + "&role=" + Role.ADMIN.name());
        owner.result("world.list", releveWithTerres());
        owner.result("world.state.options", OPTIONS);
        Client admin = login("adm", "admin-password-1234");
        String page = admin.get(FICHE).body();
        assertTrue(page.contains("Enregistrer puis décharger"), "lecture autorisée (WORLD_READ)");
        assertFalse(page.contains("value=\"world.unload\""), "pas de formulaire sans WORLD_WRITE");
        assertFalse(admin.get(LIST).body().contains("value=\"world.import\""));
        admin.post("/agents/action", "_csrf=" + admin.csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID
                + "&type=world.unload&return=/worlds&world=terres&token=0f4c9e8a-4444&auto_load=true&confirm=true");
        admin.post("/agents/action", "_csrf=" + admin.csrf(FICHE) + "&agent=" + TestConfig.AGENT_ID
                + "&type=world.import&return=/worlds&world=ancien&environment=NETHER&token=0f4c9e8a-5555"
                + "&auto_load=true&confirm=true");
        assertEquals(0, count("world.unload") + count("world.import"));
    }

    private long count(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 50).stream().filter(a -> type.equals(a.type())).count();
    }

    private static String releveWithTerres() {
        String terres = "{\"name\":\"terres\",\"loaded\":true,\"manager\":\"MULTIVERSE\",\"main\":false,"
                + "\"essential\":false,\"roles\":[],\"multiverseKnown\":true,\"rpgquestManaged\":false,"
                + "\"environment\":\"NORMAL\",\"worldType\":\"NORMAL\",\"seed\":\"1\",\"structures\":true,"
                + "\"players\":0,\"difficulty\":\"EASY\",\"pvp\":true,\"hardcore\":false,\"spawn\":\"0, 64, 0\","
                + "\"generator\":null,\"generatorSettings\":null,\"autoLoad\":true,\"links\":{\"portalsFrom\":[],"
                + "\"portalsTo\":[],\"sites\":[],\"waypoints\":0,\"autoWaypoints\":false,\"mobs\":[],\"npcSpots\":[],"
                + "\"cities\":[]},\"issues\":[]}";
        return WorldsPageTest.RELEVE.replace("\"worlds\":[", "\"worlds\":[" + terres + ",");
    }

    // ---- Outils -------------------------------------------------------------------------------



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
