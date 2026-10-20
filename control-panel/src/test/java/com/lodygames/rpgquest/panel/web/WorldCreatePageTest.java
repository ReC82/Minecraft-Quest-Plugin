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
 * Issue #562 (#331-W3) — page « Créer un monde » : options lues, aperçu limité aux champs qui
 * s'appliquent, création confirmée reprise de l'aperçu enregistré (jeton = aperçu), double envoi
 * impossible, WORLD_CREATE contrôlé par le serveur PlugAdmin. Aucun monde n'est créé : l'agent est
 * simulé par des résultats écrits à la main.
 */
class WorldCreatePageTest {

    private static final String OPTIONS = "{\"multiverse\":true,\"multiverseReason\":\"\",\"mainWorld\":\"world\","
            + "\"loaded\":[\"world\",\"world_hub\",\"wild\",\"claims\"],\"multiverseKnown\":[\"world_hub\",\"claims\"],"
            + "\"folders\":[\"world\",\"ancien_dossier\"],\"essential\":[\"world_hub\",\"wild\",\"claims\",\"world\"],"
            + "\"presets\":[{\"id\":\"CLASSIQUE\",\"label\":\"Classique\",\"biome\":\"minecraft:plains\",\"layers\":["
            + "{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2}]},"
            + "{\"id\":\"NEIGE\",\"label\":\"Neige\",\"biome\":\"minecraft:snowy_plains\",\"layers\":[]}],"
            + "\"biomes\":[\"minecraft:plains\",\"minecraft:snowy_plains\"],\"blocks\":[\"minecraft:stone\",\"minecraft:dirt\"],"
            + "\"history\":[{\"at\":\"2026-10-20T09:00:00Z\",\"actor\":\"owner\",\"world\":\"vieux\",\"result\":\"REFUSED\","
            + "\"message\":\"nom pris\"}]}";

    private static final String PLAN_OK = "{\"world\":\"terres_gelees\",\"ok\":true,\"dimension\":\"NORMAL\","
            + "\"terrain\":\"FLAT\",\"seed\":\"\",\"seedLabel\":\"au hasard\",\"structures\":false,\"preset\":\"NEIGE\","
            + "\"layers\":[],\"biome\":\"minecraft:snowy_plains\",\"generatorSettings\":\"{}\",\"summary\":["
            + "\"Identifiant du dossier : terres_gelees\",\"Monde normal, terrain plat\"],\"warnings\":[\"fige\"],"
            + "\"errors\":[],\"fingerprint\":\"0123456789abcdef01234567\"}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private AgentStore store;

    @BeforeEach
    void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"), new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store, new InMemoryUserRepository());
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

    private static final String PAGE = "/worlds/create?agent=" + TestConfig.AGENT_ID;

    @Test
    void onlyTheOwnerMayCreateByDefault() {
        assertTrue(Role.OWNER.has(Permission.WORLD_CREATE));
        for (Role role : Role.values()) {
            if (role != Role.OWNER) {
                assertFalse(role.has(Permission.WORLD_CREATE), role.name());
            }
        }
        for (String type : new String[]{"world.create.options", "world.create.preview", "world.create.apply"}) {
            assertEquals(Permission.WORLD_CREATE, AgentActionCatalog.spec(type).orElseThrow().permission(), type);
        }
        var apply = AgentActionCatalog.spec("world.create.apply").orElseThrow();
        assertTrue(apply.mutation() && apply.sensitive());
    }

    @Test
    void optionsThenPreviewThenCreationReusingTheRecordedPreviewOnce() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String page = owner.get(PAGE).body();
        assertTrue(page.contains("Lire les options du serveur"), page);
        String token = owner.csrf(PAGE);
        owner.post("/worlds/create/options", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID);
        owner.post("/worlds/create/options", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID);
        assertEquals(1, count("world.create.options"), "pas de lecture en double");
        owner.complete(store.latestActionOfType(TestConfig.AGENT_ID, "world.create.options").orElseThrow().id(), OPTIONS);
        page = owner.get(PAGE).body();
        assertTrue(page.contains("name=\"environment\" value=\"NETHER\"") && page.contains("value=\"LARGE_BIOMES\""));
        assertTrue(page.contains("<datalist id=\"wc-blocks\">") && page.contains("snowy_plains"));
        assertTrue(page.contains("Noms déjà pris sur le serveur (5)") && page.contains("ancien_dossier"), page);
        assertTrue(page.contains("Dernières demandes de création (1)"));

        // Nether : le terrain et le monde plat (masqués) ne partent pas.
        owner.post("/worlds/create/preview", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&submit_token=t-n"
                + "&name=Enfer&environment=NETHER&terrain=FLAT&flat_preset=NEIGE&layer.1.block=stone&layer.1.height=2");
        AgentActionRow nether = store.latestActionOfType(TestConfig.AGENT_ID, "world.create.preview").orElseThrow();
        assertEquals(Map.of("name", "Enfer", "environment", "NETHER", "terrain", "NORMAL", "structures", "false"),
                nether.params());

        owner.post("/worlds/create/preview", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&submit_token=t-f"
                + "&name=" + URLEncoder.encode("Terres gelées", StandardCharsets.UTF_8) + "&environment=NORMAL"
                + "&terrain=FLAT&flat_preset=NEIGE&biome=&layer.1.block=minecraft%3Abedrock&layer.1.height=1"
                + "&layer.2.block=&layer.2.height=");
        owner.post("/worlds/create/preview", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&submit_token=t-f"
                + "&name=autre");
        assertEquals(2, count("world.create.preview"), "double clic sur l'aperçu");
        AgentActionRow preview = store.latestActionOfType(TestConfig.AGENT_ID, "world.create.preview").orElseThrow();
        assertEquals("minecraft:bedrock", preview.params().get("layer.1.block"));
        assertFalse(preview.params().containsKey("layer.2.block"), "ligne vide ignorée");
        owner.complete(preview.id(), PLAN_OK);

        page = owner.get(PAGE + "&preview=" + preview.id()).body();
        assertTrue(page.contains("Résumé avant création") && page.contains("Identifiant du dossier : terres_gelees"));
        assertTrue(page.contains("Je confirme la création du monde <code>terres_gelees</code>") && page.contains("Créer le monde"));
        assertTrue(page.contains("value=\"Terres gelées\""), "formulaire prérempli depuis l'aperçu");

        owner.post("/worlds/create/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&preview=" + preview.id()
                + "&submit_token=c-0");
        assertEquals(0, count("world.create.apply"), "sans confirmation, rien n'est envoyé");

        owner.post("/worlds/create/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&preview=" + preview.id()
                + "&submit_token=c-1&confirm=true&name=pirate&actor=pirate&expected=ffffffffffffffffffffffff&token=xxxxxxxx");
        AgentActionRow apply = store.latestActionOfType(TestConfig.AGENT_ID, "world.create.apply").orElseThrow();
        assertEquals("Terres gelées", apply.params().get("name"), "paramètres repris de l'aperçu, jamais du navigateur");
        assertEquals("0123456789abcdef01234567", apply.params().get("expected"));
        assertEquals(preview.id(), apply.params().get("token"));
        assertEquals(TestConfig.OWNER_USERNAME, apply.params().get("actor"));
        assertEquals("FLAT", apply.params().get("terrain"));

        owner.post("/worlds/create/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&preview=" + preview.id()
                + "&submit_token=c-2&confirm=true");
        owner.post("/worlds/create/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&preview=" + preview.id()
                + "&submit_token=c-1&confirm=true");
        assertEquals(1, count("world.create.apply"), "un aperçu ne sert qu'une fois");

        page = owner.get(PAGE + "&preview=" + preview.id() + "&applied=" + apply.id()).body();
        assertTrue(page.contains("En cours : création du monde"), page);
        owner.complete(apply.id(), "{\"world\":\"terres_gelees\",\"result\":\"CREATED\",\"message\":\"créé\","
                + "\"replayed\":false,\"checks\":[{\"label\":\"Dimension\",\"expected\":\"NORMAL\",\"actual\":\"NORMAL\","
                + "\"ok\":true}],\"notes\":[],\"history\":[]}");
        page = owner.get(PAGE + "&applied=" + apply.id()).body();
        assertTrue(page.contains("Prêt : monde créé et relu conforme.") && page.contains("Confirmé"), page);
        assertTrue(page.contains("Ouvrir la fiche du monde"));
    }

    @Test
    void refusedPreviewOffersNoCreation() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        String token = owner.csrf(PAGE);
        owner.post("/worlds/create/preview", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&name=wild");
        AgentActionRow preview = store.latestActionOfType(TestConfig.AGENT_ID, "world.create.preview").orElseThrow();
        owner.complete(preview.id(), PLAN_OK.replace("\"ok\":true", "\"ok\":false").replace("\"errors\":[]",
                "\"errors\":[\"« wild » est un monde essentiel\"]"));
        String page = owner.get(PAGE + "&preview=" + preview.id()).body();
        assertTrue(page.contains("Création impossible en l'état") && page.contains("monde essentiel"), page);
        assertFalse(page.contains("Créer le monde</button>"));
        owner.post("/worlds/create/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&preview=" + preview.id()
                + "&confirm=true");
        assertEquals(0, count("world.create.apply"), "un aperçu refusé ne se crée jamais");
    }

    @Test
    void worldCreateIsCheckedByTheServerNotOnlyHiddenInThePage() throws Exception {
        Client owner = login(TestConfig.OWNER_USERNAME, TestConfig.OWNER_PASSWORD);
        owner.post("/users/create", "_csrf=" + owner.csrf("/users") + "&username=adm&password="
                + URLEncoder.encode("admin-password-1234", StandardCharsets.UTF_8) + "&role=" + Role.ADMIN.name());
        owner.result("world.list", WorldsPageTest.RELEVE);
        assertTrue(owner.get("/worlds?agent=" + TestConfig.AGENT_ID).body().contains("Créer un monde</a>"));

        Client admin = login("adm", "admin-password-1234");
        assertFalse(admin.get("/worlds?agent=" + TestConfig.AGENT_ID).body().contains("Créer un monde</a>"));
        HttpResponse<String> res = admin.get(PAGE);
        assertFalse(res.body().contains("/worlds/create/preview"), "pas de formulaire sans WORLD_CREATE");
        String token = admin.csrf("/worlds?agent=" + TestConfig.AGENT_ID);
        admin.post("/worlds/create/options", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID);
        admin.post("/worlds/create/preview", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&name=terres");
        admin.post("/worlds/create/apply", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID + "&preview=x&confirm=true");
        assertEquals(0, count("world.create.options") + count("world.create.preview") + count("world.create.apply"));

        String ownerToken = owner.csrf("/worlds?agent=" + TestConfig.AGENT_ID);
        owner.post("/agents/action", "_csrf=" + ownerToken + "&type=world.create.apply&agent=" + TestConfig.AGENT_ID
                + "&return=/worlds&name=terres&confirm=true&expected=0123456789abcdef01234567&token=abcdefgh&actor=owner");
        owner.post("/agents/action", "_csrf=" + ownerToken + "&type=world.create.preview&agent=" + TestConfig.AGENT_ID
                + "&return=/worlds&name=terres");
        assertEquals(0, count("world.create.apply") + count("world.create.preview"), "jamais par le formulaire générique");
    }

    @Test
    void catalogBoundsMirrorThePlugin() {
        Map<String, String> form = new LinkedHashMap<>();
        assertFalse(AgentActionCatalog.validate("world.create.preview", form).valid(), "nom manquant");
        form.put("name", "terres");
        form.put("environment", "OVERWORLD");
        assertFalse(AgentActionCatalog.validate("world.create.preview", form).valid());
        form.put("environment", "NORMAL");
        form.put("terrain", "FLAT");
        form.put("layer.1.block", "minecraft:stone");
        form.put("layer.1.height", "0");
        assertFalse(AgentActionCatalog.validate("world.create.preview", form).valid(), "épaisseur 0");
        form.put("layer.1.height", "385");
        assertFalse(AgentActionCatalog.validate("world.create.preview", form).valid(), "épaisseur 385");
        form.put("layer.1.height", "3");
        form.put("seed", "x".repeat(33));
        assertFalse(AgentActionCatalog.validate("world.create.preview", form).valid(), "graine trop longue");
        form.put("seed", "glace");
        assertTrue(AgentActionCatalog.validate("world.create.preview", form).valid());
        form.put("confirm", "true");
        assertFalse(AgentActionCatalog.validate("world.create.apply", form).valid(), "empreinte, jeton et auteur exigés");
        form.put("expected", "0123456789abcdef01234567");
        form.put("token", "abcdefgh");
        form.put("actor", "owner");
        var ok = AgentActionCatalog.validate("world.create.apply", form);
        assertTrue(ok.valid(), ok.error());
        assertEquals("3", ok.params().get("layer.1.height"));
    }

    // ---- Outils -------------------------------------------------------------------------------

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
            post(route, "_csrf=" + csrf(PAGE) + "&agent=" + TestConfig.AGENT_ID + "&" + form);
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
