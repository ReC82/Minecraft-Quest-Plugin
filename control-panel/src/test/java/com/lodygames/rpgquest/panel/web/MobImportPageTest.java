package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #214 — parcours PlugAdmin « modèle → IA → import → vérification → confirmation » sur le
 * vrai serveur HTTP du panel. Les résultats du plugin sont simulés (relevés et vérifications),
 * comme dans les autres tests de page ; la vérification elle-même est testée côté plugin.
 */
class MobImportPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    private static final String AGENT = TestConfig.AGENT_ID;
    private static final String TEMPLATE = "# modèle boss communautaire\\nid: \\\"\\\"\\ncategory: BOSS\\nboss-mode: COMMUNITY\\n";
    private static final String CURRENT = "id: rpgquest:swamp_king\ncategory: BOSS\nenabled: true\n"
            + "entity-type: ZOMBIE\nname: \"Roi\"\nhealth: 20.0\nabilities:\n  - type: SPLIT_ON_HIT\n    max-depth: 2\n";
    private static final String SHA = "ab".repeat(32);

    @BeforeEach
    void setUp() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                AGENT, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60, "{}", "{}"));
        seed("mob.catalogs", Map.of(), "{\"entityTypes\":[\"ZOMBIE\",\"HUSK\",\"CREEPER\"],\"biomes\":[\"PLAINS\",\"SWAMP\"],"
                + "\"worlds\":[\"hub\",\"wild\"],\"wildWorld\":\"wild\",\"templates\":{\"special\":\"" + TEMPLATE
                + "\",\"boss-quest\":\"" + TEMPLATE + "\",\"boss-community\":\"" + TEMPLATE + "\"},\"templateFormat\":1}");
        seed("mob.list", Map.of(), "{\"profiles\":[{\"id\":\"rpgquest:swamp_king\",\"category\":\"BOSS\",\"enabled\":true,"
                + "\"entityType\":\"ZOMBIE\",\"displayName\":\"Roi\",\"spawnChance\":0.0,\"worlds\":[],\"biomes\":[],"
                + "\"zones\":[],\"bossMode\":\"QUEST\",\"communityWeight\":1,\"participationRewards\":[],"
                + "\"abilitiesSummary\":[],\"yaml\":" + json(CURRENT) + "}],\"spawnSettings\":{}}");
        seed("reward.catalog.status", Map.of(), "{\"rewards\":[{\"key\":\"badge_boss\",\"label\":\"Badge\"}],"
                + "\"tables\":[{\"id\":\"coffre\",\"label\":\"Coffre\"}]}");
        login();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void createProfileAndEveryProfileExposeTheTemplateImportAndAiPaths() throws Exception {
        String page = get("/mobs?agent=" + AGENT).body();
        assertTrue(page.contains("Modèles, IA et import"), "accès depuis la barre du catalogue");
        assertTrue(page.contains("/mobs/template?agent=" + AGENT + "&amp;kind=special"), "modèle SPECIAL dans « Nouveau profil »");
        assertTrue(page.contains("&amp;kind=boss-quest") && page.contains("&amp;kind=boss-community"), "modèles BOSS");
        assertTrue(page.contains(">Exporter</a>") && page.contains("&amp;profile=rpgquest:swamp_king"));
        assertTrue(page.contains("Modifier avec une IA") && page.contains("/mobs/import?agent=" + AGENT + "&amp;from=rpgquest:swamp_king"));
        assertTrue(page.contains("ne couvre pas le butin"), "le formulaire ne prétend pas tout couvrir");
    }

    @Test
    void templatesAndProfilesDownloadAsYamlFiles() throws Exception {
        HttpResponse<String> t = get("/mobs/template?agent=" + AGENT + "&kind=boss-community");
        assertEquals(200, t.statusCode());
        assertTrue(t.headers().firstValue("Content-Disposition").orElse("").contains("lodyquests-modele-boss-community.yml"));
        assertTrue(t.body().startsWith("# modèle boss communautaire\nid: \"\""));
        HttpResponse<String> p = get("/mobs/template?agent=" + AGENT + "&profile=rpgquest:swamp_king");
        assertEquals(200, p.statusCode());
        assertTrue(p.headers().firstValue("Content-Disposition").orElse("").contains("swamp_king.yml"));
        assertEquals(CURRENT, p.body());
        assertEquals(303, get("/mobs/template?agent=" + AGENT + "&kind=autre").statusCode());
    }

    @Test
    void promptCarriesTheTemplateAndTheServerValues() throws Exception {
        String page = get("/mobs/import?agent=" + AGENT + "&kind=boss-community").body();
        String prompt = textarea(page, "f-mob-ai-prompt");
        assertTrue(prompt.contains("ÉCRIS ICI ta demande en français"));
        assertTrue(prompt.contains("le Wild est « wild »") && prompt.contains("hub, wild"));
        assertTrue(prompt.contains("plains, swamp") && prompt.contains("ZOMBIE, HUSK, CREEPER"));
        assertTrue(prompt.contains("badge_boss (Badge)") && prompt.contains("table:coffre"));
        assertTrue(prompt.contains("à NE PAS utiliser : swamp_king"));
        assertTrue(prompt.contains("boss-mode: COMMUNITY"), "le modèle du moteur est embarqué");

        String edit = textarea(get("/mobs/import?agent=" + AGENT + "&from=rpgquest:swamp_king").body(), "f-mob-ai-prompt");
        assertTrue(edit.contains("PROFIL ACTUEL À MODIFIER") && edit.contains("SPLIT_ON_HIT"), "capacité avancée transmise");
        assertTrue(edit.contains("Garde exactement la même valeur pour « id »"));
    }

    @Test
    void randomDraftFillsTheImportAreaWithoutCreatingAnything() throws Exception {
        String page = get("/mobs/import?agent=" + AGENT + "&random=boss-community").body();
        String yaml = textarea(page, "f-mob-yaml");
        assertTrue(yaml.contains("category: BOSS") && yaml.contains("boss-mode: COMMUNITY") && yaml.contains("worlds: [wild]"));
        assertFalse(yaml.contains("id: swamp_king"));
        assertTrue(page.contains("est encore créé"), "brouillon annoncé");
        assertTrue(store.recentActions(AGENT, 50).stream().noneMatch(a -> a.type().startsWith("mob.definition.import")));
    }

    @Test
    void checkThenCreateSendsTheVerifiedFileNotTheBrowserOne() throws Exception {
        String yaml = "id: boss_zombie_entrainement\ncategory: BOSS\nname: \"Zombie d'entraînement\"\n";
        HttpResponse<String> r = post("/mobs/import", "step=check&agent=" + AGENT + "&yaml=" + enc(yaml));
        assertEquals(303, r.statusCode());
        AgentActionRow check = latest("mob.definition.import.check");
        assertEquals(yaml.strip(), check.params().get("yaml"));
        assertTrue(r.headers().firstValue("Location").orElse("").contains("check=" + check.id()));

        assertTrue(get("/mobs/import?agent=" + AGENT + "&check=" + check.id()).body().contains("Vérification en cours"));
        result(check, "{\"valid\":true,\"errors\":[],\"warnings\":[\"Zones non vérifiées\"],\"id\":\"rpgquest:boss_zombie_entrainement\","
                + "\"category\":\"BOSS\",\"bossMode\":\"COMMUNITY\",\"displayName\":\"Zombie d'entraînement\",\"entityType\":\"ZOMBIE\","
                + "\"canonical\":" + json(yaml) + ",\"existingFile\":\"\",\"existingCanonical\":\"\",\"existingSha\":\"\"}");
        String page = get("/mobs/import?agent=" + AGENT + "&check=" + check.id()).body();
        assertTrue(page.contains("Nouveau profil") && page.contains("Créer ce profil") && page.contains("Zones non vérifiées"));
        assertTrue(page.contains("Aucune créature n'apparaît"));

        HttpResponse<String> apply = post("/mobs/import", "step=apply&agent=" + AGENT + "&check=" + check.id()
                + "&yaml=" + enc("id: autre_chose\n"));
        assertTrue(apply.headers().firstValue("Location").orElse("").startsWith("/mobs?agent="));
        AgentActionRow sent = latest("mob.definition.import.apply");
        assertEquals(yaml.strip(), sent.params().get("yaml"), "le YAML vient de la vérification, pas du navigateur");
        assertEquals("", sent.params().get("expected_sha"));
        assertEquals("false", sent.params().get("replace"));
        assertEquals(check.id(), sent.params().get("check_id"));

        post("/mobs/import", "step=apply&agent=" + AGENT + "&check=" + check.id());
        assertEquals(1, count("mob.definition.import.apply"), "un double clic n'envoie rien en double");
        assertTrue(store.recentActions(AGENT, 50).stream().noneMatch(a -> a.type().contains("spawn")), "aucune apparition");
    }

    @Test
    void replacingNeedsTheCheckboxAndCarriesTheVerifiedFingerprint() throws Exception {
        String yaml = CURRENT.replace("health: 20.0", "health: 40.0");
        post("/mobs/import", "step=check&agent=" + AGENT + "&yaml=" + enc(yaml));
        AgentActionRow check = latest("mob.definition.import.check");
        result(check, "{\"valid\":true,\"errors\":[],\"warnings\":[],\"id\":\"rpgquest:swamp_king\",\"category\":\"BOSS\","
                + "\"bossMode\":\"QUEST\",\"displayName\":\"Roi\",\"entityType\":\"ZOMBIE\",\"canonical\":" + json(yaml)
                + ",\"existingFile\":\"swamp_king.yml\",\"existingCanonical\":" + json(CURRENT) + ",\"existingSha\":\"" + SHA + "\"}");
        String page = get("/mobs/import?agent=" + AGENT + "&check=" + check.id()).body();
        assertTrue(page.contains("existe déjà") && page.contains("Remplacer ce profil"));
        assertTrue(page.contains("Différences avec le profil actuel (1)"), page);
        assertTrue(page.contains("<code>health</code>") && page.contains("20.0") && page.contains("40.0"));
        assertTrue(page.contains("name=\"replace\" value=\"true\" required"));

        HttpResponse<String> refused = post("/mobs/import", "step=apply&agent=" + AGENT + "&check=" + check.id());
        assertTrue(refused.headers().firstValue("Location").orElse("").contains("err="));
        assertEquals(0, count("mob.definition.import.apply"), "sans la case, rien n'est envoyé");

        post("/mobs/import", "step=apply&agent=" + AGENT + "&check=" + check.id() + "&replace=true");
        AgentActionRow sent = latest("mob.definition.import.apply");
        assertEquals(SHA, sent.params().get("expected_sha"));
        assertEquals("true", sent.params().get("replace"));
    }

    @Test
    void aRefusedFileShowsItsErrorsAndOffersNoConfirmation() throws Exception {
        post("/mobs/import", "step=check&agent=" + AGENT + "&yaml=" + enc("fly-speed: 3\n"));
        AgentActionRow check = latest("mob.definition.import.check");
        result(check, "{\"valid\":false,\"errors\":[\"Propriété inconnue « fly-speed » : le moteur ne la lit pas.\","
                + "\"« id » est obligatoire.\"],\"warnings\":[],\"id\":\"\",\"category\":\"\",\"bossMode\":\"\","
                + "\"displayName\":\"\",\"entityType\":\"\",\"canonical\":\"\",\"existingFile\":\"\",\"existingCanonical\":\"\",\"existingSha\":\"\"}");
        String page = get("/mobs/import?agent=" + AGENT + "&check=" + check.id()).body();
        assertTrue(page.contains("Fichier refusé") && page.contains("fly-speed"));
        assertFalse(page.contains("Créer ce profil"));
        assertTrue(textarea(page, "f-mob-yaml").contains("fly-speed: 3"), "le fichier reste à corriger");
        post("/mobs/import", "step=apply&agent=" + AGENT + "&check=" + check.id());
        assertEquals(0, count("mob.definition.import.apply"));
    }

    /** Bug du 19/10 : vérification SUCCESS/VALID mais page restée sur « en cours » (meta refresh inopérant). */
    @Test
    void aPendingCheckIsFollowedByPanelJsAndReloadsOnAnyEnd() throws Exception {
        post("/mobs/import", "step=check&agent=" + AGENT + "&yaml=" + enc("id: lent\n"));
        AgentActionRow check = latest("mob.definition.import.check");
        String page = get("/mobs/import?agent=" + AGENT + "&check=" + check.id()).body();
        assertTrue(page.contains("Vérification en cours"));
        assertTrue(page.contains("data-pa-track-action=\"" + check.id() + "\" data-pa-track-reload=\"terminal\""), page);
        assertFalse(page.contains("http-equiv=\"refresh\""), "plus de meta refresh : il ne rechargeait pas la page");
        assertTrue(page.contains("href=\"/mobs/import?agent=" + AGENT + "&amp;check=" + check.id() + "\">Actualiser maintenant"),
                "lien manuel sans ancre");
        assertTrue(textarea(page, "f-mob-yaml").contains("id: lent"), "brouillon conservé pendant l'attente");

        String panelJs = get("/assets/panel.js").body();
        assertTrue(panelJs.contains("data-pa-track-reload") && panelJs.contains("reloadOnTerminal"));
    }

    @Test
    void aCheckFinishedBeforeTheFirstPageShowsItsResultDirectly() throws Exception {
        post("/mobs/import", "step=check&agent=" + AGENT + "&yaml=" + enc("id: rapide\n"));
        AgentActionRow check = latest("mob.definition.import.check");
        result(check, "{\"valid\":true,\"errors\":[],\"warnings\":[],\"id\":\"rpgquest:rapide\",\"category\":\"SPECIAL\","
                + "\"bossMode\":\"\",\"displayName\":\"Rapide\",\"entityType\":\"ZOMBIE\",\"canonical\":\"id: rapide\\n\","
                + "\"existingFile\":\"\",\"existingCanonical\":\"\",\"existingSha\":\"\"}");
        String page = get("/mobs/import?agent=" + AGENT + "&check=" + check.id()).body();
        assertTrue(page.contains("Créer ce profil") && page.contains("Nouveau profil"));
        assertFalse(page.contains("Vérification en cours") || page.contains("data-pa-track-reload"));
    }

    @Test
    void aSlowCheckSaysTheServerIsNotAnsweringButKeepsWaiting() {
        String id = store.createAction(AGENT, "mob.definition.import.check", Map.of("yaml", "id: x"), "test");
        AgentActionRow row = store.action(id).orElseThrow();
        MobImportPages.View v = new MobImportPages.View(AGENT, null, "special", "", "id: x", "", row, java.util.Optional.empty());
        String fresh = MobImportPages.pendingCheck(v, row, row.createdAt().plusSeconds(5));
        assertTrue(fresh.contains("Vérification en cours") && !fresh.contains("Revenir au fichier"));
        String slow = MobImportPages.pendingCheck(v, row, row.createdAt().plus(MobImportPages.SLOW_CHECK).plusSeconds(1));
        assertTrue(slow.contains("ne répond pas encore") && slow.contains("Revenir au fichier"));
        assertTrue(slow.contains("data-pa-track-reload=\"terminal\""), "on continue d'attendre");
    }

    @Test
    void anExpiredOrFailedCheckOffersRetryAndKeepsTheDraft() throws Exception {
        post("/mobs/import", "step=check&agent=" + AGENT + "&yaml=" + enc("id: perdu\n"));
        AgentActionRow expired = latest("mob.definition.import.check");
        store.recordResult(expired.id(), AGENT, AgentActionStatus.EXPIRED, "", "", "{}", Instant.now());
        String page = get("/mobs/import?agent=" + AGENT + "&check=" + expired.id()).body();
        assertTrue(page.contains("Délai dépassé") && page.contains("Revoir le fichier et vérifier à nouveau"));
        assertFalse(page.contains("Créer ce profil") || page.contains("data-pa-track-action=\"" + expired.id()));
        assertTrue(textarea(page, "f-mob-yaml").contains("id: perdu"), "brouillon conservé");

        post("/mobs/import", "step=check&agent=" + AGENT + "&yaml=" + enc("id: casse\n"));
        AgentActionRow failed = latest("mob.definition.import.check");
        store.recordResult(failed.id(), AGENT, AgentActionStatus.FAILED, "ERROR", "lecteur indisponible", "{}", Instant.now());
        page = get("/mobs/import?agent=" + AGENT + "&check=" + failed.id()).body();
        assertTrue(page.contains("La vérification n'a pas pu avoir lieu") && page.contains("lecteur indisponible"));
        assertTrue(page.contains("Retour aux profils") && textarea(page, "f-mob-yaml").contains("id: casse"));
    }

    @Test
    void aDoubleClickOnVerifyOrCreateSendsOneActionOnly() throws Exception {
        String importPage = get("/mobs/import?agent=" + AGENT).body();
        Matcher token = Pattern.compile("name=\"submit_token\" value=\"([^\"]+)\"").matcher(importPage);
        assertTrue(token.find(), "le formulaire « Vérifier » porte un jeton");
        String form = "step=check&agent=" + AGENT + "&submit_token=" + token.group(1) + "&yaml=" + enc("id: double\n");
        post("/mobs/import", form);
        HttpResponse<String> again = post("/mobs/import", form);
        AgentActionRow check = latest("mob.definition.import.check");
        assertEquals(1, count("mob.definition.import.check"), "un seul envoi");
        assertTrue(again.headers().firstValue("Location").orElse("").contains("check=" + check.id()),
                "le second clic retombe sur la vérification déjà lancée");

        result(check, "{\"valid\":true,\"errors\":[],\"warnings\":[],\"id\":\"rpgquest:double\",\"category\":\"SPECIAL\","
                + "\"bossMode\":\"\",\"displayName\":\"D\",\"entityType\":\"ZOMBIE\",\"canonical\":\"id: double\\n\","
                + "\"existingFile\":\"\",\"existingCanonical\":\"\",\"existingSha\":\"\"}");
        String page = get("/mobs/import?agent=" + AGENT + "&check=" + check.id()).body();
        Matcher applyToken = Pattern.compile("value=\"apply\">\\s*<input type=\"hidden\" name=\"submit_token\" value=\"([^\"]+)\"")
                .matcher(page);
        assertTrue(applyToken.find(), "le formulaire « Créer » porte un jeton");
        String apply = "step=apply&agent=" + AGENT + "&check=" + check.id() + "&submit_token=" + applyToken.group(1);
        post("/mobs/import", apply);
        HttpResponse<String> second = post("/mobs/import", apply);
        assertEquals(1, count("mob.definition.import.apply"));
        assertTrue(second.headers().firstValue("Location").orElse("").startsWith("/mobs?agent="));
    }

    @Test
    void importActionsNeedTheMobWritePermission() {
        assertEquals(Permission.MOB_WRITE, AgentActionCatalog.spec("mob.definition.import.check").orElseThrow().permission());
        assertEquals(Permission.MOB_WRITE, AgentActionCatalog.spec("mob.definition.import.apply").orElseThrow().permission());
        assertEquals(List.of("mob.list"), AgentActionCatalog.spec("mob.definition.import.apply").orElseThrow().refreshTypes());
        assertFalse(AgentActionCatalog.validate("mob.definition.import.check", Map.of("yaml", "  ")).valid());
        assertFalse(AgentActionCatalog.validate("mob.definition.import.check",
                Map.of("yaml", "x".repeat(AgentActionCatalog.MAX_MOB_PROFILE_CHARS + 1))).valid());
        assertFalse(AgentActionCatalog.validate("mob.definition.import.apply",
                Map.of("yaml", "id: x", "expected_sha", "pas-une-empreinte")).valid());
    }

    // ---- outils --------------------------------------------------------------------------------

    private void seed(String type, Map<String, String> params, String details) {
        String id = store.createAction(AGENT, type, params, "test");
        result(store.action(id).orElseThrow(), details);
    }

    private void result(AgentActionRow row, String details) {
        store.recordResult(row.id(), AGENT, AgentActionStatus.SUCCESS, "1", "ok",
                "{\"status\":\"SUCCESS\",\"message\":\"ok\",\"details\":" + details + "}", Instant.now());
    }

    private AgentActionRow latest(String type) {
        return store.recentActions(AGENT, 100).stream().filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private long count(String type) {
        return store.recentActions(AGENT, 100).stream().filter(a -> a.type().equals(type)).count();
    }

    private static String textarea(String html, String id) {
        Matcher m = Pattern.compile("<textarea id=\"" + id + "\"[^>]*>(.*?)</textarea>", Pattern.DOTALL).matcher(html);
        assertTrue(m.find(), "zone " + id + " absente");
        return m.group(1).replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static String json(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token, false);
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

    /** POST authentifié : le jeton CSRF de la session est ajouté. */
    private HttpResponse<String> post(String p, String form) throws Exception {
        return post(p, form + "&_csrf=" + csrf(get("/mobs/import?agent=" + AGENT).body()), false);
    }

    private HttpResponse<String> post(String p, String form, boolean ignored) throws Exception {
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
