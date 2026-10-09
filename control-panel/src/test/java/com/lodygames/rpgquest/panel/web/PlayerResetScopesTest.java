package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
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
 * Issue #235 — deux resets joueur, deux intentions, et l'écran le dit.
 *
 * <h2>Le défaut que ces tests ferment</h2>
 *
 * <p>Un seul bouton « Reset « nouveau joueur » » rétablissait le droit au kit de départ sans retirer
 * le kit déjà reçu : le joueur en obtenait un second. L'écran ne mentait pas, il ne <em>disait</em>
 * rien du sort de l'inventaire — et c'est exactement ce qui a été constaté en jeu.</p>
 *
 * <p>Les pages sont <strong>réellement rendues</strong> et le catalogue de joueurs est alimenté par
 * le vrai chemin de l'agent : on vérifie ce que l'administrateur lit, pas ce que le code croit
 * produire.</p>
 */
class PlayerResetScopesTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String UUID_LODY = "b56885c8-1111-4111-8111-111111111111";

    private static final String CATALOG = "{"
            + "\"total\":1,\"online\":1,\"offline\":0,\"banned\":0,\"truncated\":false,\"players\":["
            + "{\"uuid\":\"" + UUID_LODY + "\",\"name\":\"LoDyMcFly\",\"online\":true,"
            + "\"hasPlayedBefore\":true,\"firstPlayed\":1600000000000,\"lastSeen\":1600000000000,"
            + "\"banned\":false,\"world\":\"world_hub\",\"x\":125,\"y\":64,\"z\":-82}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- La frontière de permission -------------------------------------------------------------

    /**
     * Vider l'inventaire d'un joueur a sa <strong>propre</strong> permission.
     *
     * <p>Réinitialiser une progression se refait en rejouant ; vider un inventaire ne se défait pas.
     * Un droit commun aurait fait du second un effet de bord du premier.</p>
     */
    @Test
    void wipingAnInventoryHasItsOwnPermission() {
        assertEquals(Permission.ACTION_PLAYER_RESET,
                AgentActionCatalog.spec("player.resetnew.confirm").orElseThrow().permission());
        assertEquals(Permission.ACTION_PLAYER_RESET_FULL,
                AgentActionCatalog.spec("player.resetfull.confirm").orElseThrow().permission());
    }

    @Test
    void ownerAndAdminMayResetButNoOtherRole() {
        assertTrue(Role.OWNER.has(Permission.ACTION_PLAYER_RESET_FULL));
        assertTrue(Role.ADMIN.has(Permission.ACTION_PLAYER_RESET_FULL), "outil de test d'un administrateur");
        for (Role role : new Role[] {Role.TESTER, Role.BUILDER, Role.CONTENT_EDITOR, Role.READ_ONLY}) {
            assertFalse(role.has(Permission.ACTION_PLAYER_RESET_FULL),
                    role + " ne doit pas pouvoir vider l'inventaire d'un joueur");
            assertFalse(role.has(Permission.ACTION_PLAYER_RESET),
                    role + " ne doit pas pouvoir réinitialiser une progression");
        }
    }

    /** Les deux confirmations sont des mutations sensibles, donc à case à cocher explicite. */
    @Test
    void bothConfirmationsAreSensitiveMutationsTargetingAPlayer() {
        for (String type : new String[] {"player.resetnew.confirm", "player.resetfull.confirm"}) {
            var spec = AgentActionCatalog.spec(type).orElseThrow();
            assertTrue(spec.mutation(), type);
            assertTrue(spec.sensitive(), type + " doit exiger une confirmation explicite");
            assertTrue(spec.needsPlayer(), type);
        }
    }

    /** Les deux aperçus restent en LECTURE : c'est l'écran qui permet de décider. */
    @Test
    void bothPreviewsStayReadOnly() {
        for (String type : new String[] {"player.resetnew.preview", "player.resetfull.preview"}) {
            var spec = AgentActionCatalog.spec(type).orElseThrow();
            assertFalse(spec.mutation(), type + " ne doit rien écrire");
            assertEquals(Permission.PLAYERS_READ, spec.permission(), type);
        }
    }

    // ---- Ce que l'écran DIT ---------------------------------------------------------------------

    /** Plus aucun bouton ne s'appelle « Reset » tout court : chaque portée annonce la sienne. */
    @Test
    void theTwoResetsAreNamedByWhatTheyDoToTheInventory() throws Exception {
        start();
        seedCatalog();

        String page = fiche();

        assertTrue(page.contains("Reset progression RPGQuest"), "portée 1 nommée");
        assertTrue(page.contains("Reset nouveau joueur complet"), "portée 2 nommée");
        assertTrue(page.contains("inventaire conservé"), "la portée 1 annonce ce qu'elle conserve");
        assertTrue(page.contains("vide l&#39;inventaire") || page.contains("vide l'inventaire"),
                "la portée 2 annonce ce qu'elle vide");
    }

    /**
     * L'avertissement du double kit, mot pour mot, sur la portée qui le provoque.
     *
     * <p>C'est l'information qui manquait : le droit revient, les objets restent.</p>
     */
    @Test
    void theProgressionResetWarnsAboutTheSecondKit() throws Exception {
        start();
        seedCatalog();

        String page = fiche();

        assertTrue(page.contains("second kit"), "le risque doit être écrit, pas déduit");
        assertTrue(page.contains("vanilla"),
                "la raison doit être donnée : les outils du kit sont indiscernables");
    }

    /** La portée complète énumère TOUT ce qu'elle vide, Ender compris — aucune surprise. */
    @Test
    void theFullResetEnumeratesEverythingItWipes() throws Exception {
        start();
        seedCatalog();

        String page = fiche();

        for (String mentioned : new String[] {"armure", "main secondaire", "curseur", "Ender"}) {
            assertTrue(page.contains(mentioned), "le vidage doit citer : " + mentioned);
        }
    }

    /** Chaque portée a son propre aperçu : un aperçu « conserve » ne peut pas servir de caution. */
    @Test
    void eachScopeHasItsOwnPreviewAndItsOwnConfirmation() throws Exception {
        start();
        seedCatalog();

        String page = fiche();

        for (String type : new String[] {"player.resetnew.preview", "player.resetfull.preview",
                "player.resetnew.confirm", "player.resetfull.confirm"}) {
            assertTrue(page.contains("value=\"" + type + "\""), "formulaire attendu : " + type);
        }
    }

    /** Le palier de kit et le droit au kit sont annoncés comme réinitialisés. */
    @Test
    void thePageAnnouncesTheKitRightAndTheKitTierAsReset() throws Exception {
        start();
        seedCatalog();

        String page = fiche();

        assertTrue(page.contains("droit au kit de départ"),
                "la cause du doublon doit être nommée dans ce qui est réinitialisé");
        assertTrue(page.contains("palier 1"), "le retour au palier 1 doit être annoncé");
    }

    // ---- infra ----------------------------------------------------------------------------------

    /** La fiche dépliée du joueur de test, telle que le navigateur la reçoit. */
    private String fiche() throws Exception {
        return get("/players?agent=" + TestConfig.AGENT_ID + "&player=" + UUID_LODY).body();
    }

    private void seedCatalog() throws Exception {
        String token = csrf(get("/players?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=player.catalog&agent="
                + TestConfig.AGENT_ID + "&return=/players");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("action non livrée : " + poll.body());
        }
        String result = "{\"action_id\":\"" + m.group(1) + "\",\"status\":\"SUCCESS\",\"value\":\"x\","
                + "\"message\":\"ok\",\"details\":" + CATALOG + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + m.group(1) + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(),
                HttpResponse.BodyHandlers.ofString());
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
            if (sc.toLowerCase(java.util.Locale.ROOT).contains("max-age=0") || v.isEmpty()) {
                jar.remove(n);
            } else {
                jar.put(n, v);
            }
        }
        return res;
    }
}
