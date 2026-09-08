package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Éditeur guidé de quêtes / stories (#46) — bout en bout HTTP : rendu du formulaire, auth,
 * ajout de ligne par round-trip serveur, enregistrement whitelisté, conflit de hash, id
 * traversant refusé, CSRF, permission.
 */
class ContentEditorPagesTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();
    private Path contentRoot;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- tests ----------------------------------------------------------------------

    @Test
    void newQuestFormRendersGuidedSections() throws Exception {
        start(true);
        String body = get("/quests/new").body();
        assertTrue(body.contains("Créer une quête"));
        assertTrue(body.contains("Général") && body.contains("Objectifs") && body.contains("Récompenses"));
        assertTrue(body.contains("name=\"_csrf\""), "jeton CSRF injecté");
        assertTrue(body.contains("<datalist id=\"dl-entity\""), "datalist entités présente");
        assertTrue(body.contains("value=\"add_obj:0\""), "bouton ajouter un objectif");
        assertFalse(body.contains("%CSRF%"), "placeholder CSRF remplacé");
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        start(true);
        jar.clear();
        HttpResponse<String> res = get("/quests/new");
        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").contains("/login"));
    }

    @Test
    void addObjectiveButtonAddsARow() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = baseQuestForm(token) + "&_action=add_obj:0";
        String body = post("/quests/save", form).body();
        assertTrue(body.contains("obj.0.1.kind"), "un second objectif a été ajouté");
    }

    @Test
    void validObjectiveAndRewardSaveToSource() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = baseQuestForm(token)
                + "&rew.0.kind=EXPERIENCE&rew.0.amount=100"
                + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(303, res.statusCode());
        assertEquals("/quests/edit/test_quest?saved=1", res.headers().firstValue("Location").orElse(""));

        Path file = contentRoot.resolve("quests/test_quest.yml");
        assertTrue(Files.exists(file), "fichier créé dans la source");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("id: rpgquest:test_quest"));
        assertTrue(yaml.contains("type: KILL_ENTITY") && yaml.contains("entity: SPIDER") && yaml.contains("amount: 5"));
        assertTrue(yaml.contains("type: EXPERIENCE"));

        // ré-ouverture : le formulaire repart du fichier
        String reopened = get("/quests/edit/test_quest").body();
        assertTrue(reopened.contains("Modifier une quête"));
        assertTrue(reopened.contains("value=\"SPIDER\""));
    }

    @Test
    void saveIsRejectedWhenValidationHasErrors() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        // titre vide -> ERREUR bloquante
        String form = "id=broken_quest&title=&description=x&category=x&icon=BOOK"
                + "&step.0.id=step_1&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=SPIDER&obj.0.0.amount=5"
                + "&_csrf=" + token + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("erreur") || res.body().contains("ERREUR"));
        assertFalse(Files.exists(contentRoot.resolve("quests/broken_quest.yml")), "aucun fichier écrit");
    }

    @Test
    void staleHashIsDetectedAsConflict() throws Exception {
        start(true);
        // 1) créer
        String token = csrf(get("/quests/new").body());
        post("/quests/save", baseQuestForm(token) + "&_action=save");
        Path file = contentRoot.resolve("quests/test_quest.yml");
        assertTrue(Files.exists(file));

        // 2) ouvrir l'éditeur -> récupère expectedSha
        String editBody = get("/quests/edit/test_quest").body();
        String expectedSha = attr(editBody, "expectedSha");
        String token2 = csrf(editBody);

        // 3) le fichier change sous nos pieds
        Files.writeString(file, Files.readString(file) + "\n# modif externe\n");

        // 4) tentative d'enregistrement avec l'ancien hash
        String form = "id=test_quest&title=Titre&description=desc&category=tutorial&icon=BOOK"
                + "&step.0.id=step_1&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=CREEPER&obj.0.0.amount=3"
                + "&slug=test_quest&expectedSha=" + expectedSha + "&_csrf=" + token2 + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().toLowerCase().contains("conflit"), "conflit de version signalé");
        assertTrue(Files.readString(file).contains("modif externe"), "fichier non écrasé");
    }

    @Test
    void traversingIdIsRefused() throws Exception {
        start(true);
        String token = csrf(get("/quests/new").body());
        String form = "id=" + URLEncoder.encode("../../evil", StandardCharsets.UTF_8)
                + "&title=T&description=d&category=c&icon=BOOK"
                + "&step.0.id=step_1&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=SPIDER&obj.0.0.amount=1"
                + "&_csrf=" + token + "&_action=save";
        HttpResponse<String> res = post("/quests/save", form);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("invalide"));
        assertFalse(Files.exists(tmp.resolve("evil.yml")));
        assertFalse(Files.exists(contentRoot.resolve("evil.yml")));
    }

    @Test
    void badCsrfIsRejected() throws Exception {
        start(true);
        HttpResponse<String> res = post("/quests/save",
                baseQuestForm("not-the-real-token") + "&_action=save");
        assertEquals(403, res.statusCode());
    }

    @Test
    void readOnlyWorkspaceShowsBannerAndBlocksSave() throws Exception {
        start(false); // pas de sous-dossier quests/ -> non inscriptible
        String body = get("/quests/new").body();
        assertTrue(body.contains("lecture seule"));
        String token = csrf(body);
        HttpResponse<String> res = post("/quests/save", baseQuestForm(token) + "&_action=save");
        assertEquals(200, res.statusCode());
        assertFalse(Files.exists(contentRoot.resolve("quests/test_quest.yml")));
    }

    @Test
    void storyEditorCreatesOrderedChain() throws Exception {
        start(true);
        String token = csrf(get("/stories/new").body());
        String form = "id=test_story&name=Histoire+d%27essai&q.0=premiers_pas&q.1=first_steps"
                + "&_csrf=" + token + "&_action=save";
        HttpResponse<String> res = post("/stories/save", form);
        assertEquals(303, res.statusCode());
        assertEquals("/stories/edit/test_story?saved=1", res.headers().firstValue("Location").orElse(""));
        String yaml = Files.readString(contentRoot.resolve("stories/test_story.yml"));
        assertTrue(yaml.contains("id: test_story"));
        assertTrue(yaml.indexOf("premiers_pas") < yaml.indexOf("first_steps"), "ordre conservé");
    }

    @Test
    void readOnlyRoleLacksContentWritePermission() {
        PermissionService p = new PermissionService();
        assertFalse(p.can(Role.READ_ONLY.name(), Permission.QUEST_CONTENT_WRITE));
        assertTrue(p.can(Role.OWNER.name(), Permission.QUEST_CONTENT_WRITE));
        assertTrue(p.can(Role.CONTENT_EDITOR.name(), Permission.STORY_CONTENT_WRITE));
    }

    // ---- helpers ----------------------------------------------------------------------

    private String baseQuestForm(String csrf) {
        return "id=test_quest&title=" + URLEncoder.encode("Quête d'essai", StandardCharsets.UTF_8)
                + "&description=" + URLEncoder.encode("Une quête de test.", StandardCharsets.UTF_8)
                + "&category=tutorial&icon=BOOK"
                + "&step.0.id=step_1"
                + "&obj.0.0.kind=KILL_ENTITY&obj.0.0.entity=SPIDER&obj.0.0.amount=5"
                + "&_csrf=" + csrf;
    }

    private void start(boolean withSubdirs) throws Exception {
        contentRoot = tmp.resolve("content");
        Files.createDirectories(contentRoot);
        if (withSubdirs) {
            Files.createDirectories(contentRoot.resolve("quests"));
            Files.createDirectories(contentRoot.resolve("stories"));
        }
        String db = tmp.resolve("cp.db").toString();
        app = new PanelApp(
                TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1", contentRoot.toString()),
                new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
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
            throw new IllegalStateException("pas de jeton _csrf dans la page");
        }
        return m.group(1);
    }

    private static String attr(String html, String name) {
        Matcher m = Pattern.compile("name=\"" + Pattern.quote(name) + "\" value=\"([^\"]*)\"").matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("champ « " + name + " » absent");
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
