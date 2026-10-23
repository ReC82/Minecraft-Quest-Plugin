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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #798 — la source de PlugAdmin face à DEV, sur les vrais contenus du 23/10/2026 : Elias
 * (#475 puis #778), Gauthier, Bob, et les deux quêtes retirées.
 *
 * <p>Le défaut d'origine : l'espace de travail de PlugAdmin était resté sur une ancienne branche.
 * Chaque différence avec DEV s'affichait « Enregistré — pas encore en jeu », comme un brouillon à
 * publier — alors que DEV portait la version la plus récente, et qu'une publication groupée aurait
 * remis l'ancienne, ou fait revenir des quêtes retirées. Ces tests verrouillent : des états qui
 * disent QUI a changé, aucune publication sans choix, aucune perte de contenu.</p>
 *
 * <p>Les versions « anciennes » sont des copies figées ({@code fixtures/798}) ; les versions DEV
 * sont les ressources du dépôt, identiques à DEV le 23/10/2026.</p>
 */
class ContentSourceSafetyTest {

    private static final Path RESOURCES = Path.of("..", "src", "main", "resources");
    private static final Path FIXTURES = Path.of("src", "test", "resources", "fixtures", "798");
    private static final List<String> KIT_LOGIC = List.of("type: GIVE_STARTER_KIT", "STARTER_KIT_TIER",
            "%kit_upgrade_requirements%", "quest: rpgquest:kit_tier5");

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

    // ---- Elias -------------------------------------------------------------------------------

    /** Source restée sur l'ancienne branche, DEV en #778, aucune version commune connue. */
    @Test
    void eliasFromTheStaleBranchIsNeverPresentedAsADraftNorPublished() throws Exception {
        String stale = fixture("guide-249.yml");
        String dev = resource("dialogues/guide.yml");
        start(Map.of("dialogues/guide", stale));
        devState(Instant.now().minusSeconds(60), "dialogues", "guide", sha(dev));

        String editor = get("/dialogues/edit/guide").body();
        assertFalse(editor.contains("pas encore en jeu"), "plus de « Enregistré — pas encore en jeu » trompeur");
        assertTrue(editor.contains("PlugAdmin et DEV ont chacun une version différente"), editor);
        assertTrue(editor.contains("Conserver mon brouillon"), "le choix est proposé");
        assertFalse(editor.contains("action=\"/dialogues/publish\""), "pas de mise en jeu directe");

        String pending = pendingPage();
        assertTrue(pending.contains("Versions différentes"));
        assertFalse(pending.contains("name=\"sel_dialogues/guide\""), "jamais publiable en lot");

        HttpResponse<String> forged = submitBatch(Map.of("dialogues/guide", new String[] {sha(stale), sha(dev)}));
        assertTrue(location(forged).contains("version différente"), location(forged));
        assertEquals(0, count("content.publish.batch"), "lot forgé refusé avant l'envoi");
        HttpResponse<String> single = publishAction("dialogues", "guide", sha(stale), sha(dev));
        assertTrue(location(single).contains("version différente"), location(single));
        assertEquals(0, count("content.publish"));
        assertEquals(stale, read("dialogues/guide"), "rien n'a été touché");
    }

    /**
     * Critère d'acceptation : récupérer la version de DEV, modifier une réplique, enregistrer et
     * publier — sans perdre la logique du kit de départ.
     */
    @Test
    void eliasIsRecoveredFromDevEditedAndPublishedWithTheStarterKitLogicIntact() throws Exception {
        String stale = fixture("guide-249.yml");
        String dev = resource("dialogues/guide.yml");
        start(Map.of("dialogues/guide", stale));
        devState(Instant.now().minusSeconds(60), "dialogues", "guide", sha(dev));
        devRead("dialogues", "guide", dev);

        HttpResponse<String> recovered = post("/content/recover", Map.of("agent", TestConfig.AGENT_ID,
                "kind", "dialogues", "slug", "guide", "expected_sha", sha(stale), "expected_dev_sha", sha(dev),
                "return", "/dialogues/edit/guide"));
        assertTrue(location(recovered).contains("Version de DEV récupérée"), location(recovered));
        assertEquals(dev, read("dialogues/guide"), "la version #778 est dans PlugAdmin");
        assertEquals(List.of(stale), backups("guide.yml"), "l'ancienne version est sauvegardée, intacte");
        assertEquals(sha(dev), store.devReference(TestConfig.AGENT_ID, "dialogues", "guide").orElseThrow().devSha());

        String editor = get("/dialogues/edit/guide").body();
        assertFalse(editor.contains("dlg-conflict"), "plus de divergence");
        Map<String, String> f = formFieldsOf(editor, "/dialogues/save");
        String before = f.get("n0_text");
        assertTrue(before != null && !before.isBlank(), "la première réplique est éditable");
        f.put("n0_text", before + " Bonne route !");
        HttpResponse<String> save = post("/dialogues/save", f, "save");
        assertEquals(303, save.statusCode(), problems(save.body()));
        String saved = read("dialogues/guide");
        assertTrue(saved.contains("Bonne route !"));
        KIT_LOGIC.forEach(marker -> assertTrue(saved.contains(marker), "logique du kit gardée : " + marker));
        // Rien d'autre ne change que la réplique modifiée : l'éditeur guidé réécrit les commentaires
        // et les guillemets (dette connue de l'éditeur), jamais une condition, une action ou un nœud.
        assertEquals(logic(dev.replace(before + "\"", before + " Bonne route !\"")), logic(saved));

        String draft = get("/dialogues/edit/guide").body();
        assertTrue(draft.contains("Brouillon enregistré"), "un vrai brouillon, dit comme tel");
        HttpResponse<String> publish = post("/dialogues/publish", formFieldsOf(draft, "/dialogues/publish"), null);
        assertEquals(303, publish.statusCode());
        AgentActionRow action = single("content.publish");
        assertEquals(sha(dev), action.params().get("expected_dev_sha"), "le serveur refusera si DEV a bougé");
        KIT_LOGIC.forEach(marker -> assertTrue(action.params().get("yaml").contains(marker), marker));
    }

    /** Source en #475 (publiée), DEV passé en #778 par un déploiement : DEV est le plus récent. */
    @Test
    void eliasAt475WhileDevRuns778IsNewerOnDevAndCannotBePushedBack() throws Exception {
        String v475 = fixture("guide-475.yml");
        String v778 = resource("dialogues/guide.yml");
        start(Map.of("dialogues/guide", v475));
        store.adoptDevReference(TestConfig.AGENT_ID, "dialogues", "guide", sha(v475), "owner",
                Instant.now().minusSeconds(600));
        devState(Instant.now().minusSeconds(60), "dialogues", "guide", sha(v778));

        String editor = get("/dialogues/edit/guide").body();
        assertTrue(editor.contains("DEV a une version plus récente"), editor);
        assertTrue(editor.contains("Recharger depuis DEV"), "récupérer est proposé");
        assertFalse(editor.contains("action=\"/dialogues/publish\""));
        assertTrue(pendingPage().contains("Plus récent sur DEV"));
        assertTrue(location(publishAction("dialogues", "guide", sha(v475), sha(v778)))
                .contains("version plus récente"));
        assertEquals(0, count("content.publish"));
    }

    // ---- Gauthier ----------------------------------------------------------------------------

    /**
     * Dialogue et quête de Gauthier : l'ancienne version ne part pas en lot ; « Conserver mon
     * brouillon » est un choix explicite, après lequel la publication part avec l'empreinte DEV
     * (le serveur sauvegarde la version remplacée).
     */
    @Test
    void gauthierOldVersionsNeedAnExplicitChoiceBeforeAnyPublication() throws Exception {
        String oldGuard = fixture("guard-plugadmin-avant-778.yml");
        String oldTier = fixture("guard_tier1-249.yml");
        String devGuard = resource("dialogues/guard.yml");
        String devTier = resource("quests/guard_tier1.yml");
        start(Map.of("dialogues/guard", oldGuard, "quests/guard_tier1", oldTier));
        devState(Instant.now().minusSeconds(60), "dialogues", "guard", sha(devGuard), "quests", "guard_tier1",
                sha(devTier));

        String pending = pendingPage();
        assertFalse(pending.contains("name=\"sel_dialogues/guard\""));
        assertFalse(pending.contains("name=\"sel_quests/guard_tier1\""));

        HttpResponse<String> unchecked = post("/content/keep-draft", Map.of("agent", TestConfig.AGENT_ID,
                "kind", "quests", "slug", "guard_tier1", "expected_sha", sha(oldTier), "expected_dev_sha", sha(devTier),
                "return", "/quests"));
        assertTrue(location(unchecked).contains("Cochez"), "sans la case, rien ne change");
        assertTrue(store.devReference(TestConfig.AGENT_ID, "quests", "guard_tier1").isEmpty());

        HttpResponse<String> kept = post("/content/keep-draft", Map.of("agent", TestConfig.AGENT_ID,
                "kind", "quests", "slug", "guard_tier1", "expected_sha", sha(oldTier), "expected_dev_sha", sha(devTier),
                "return", "/quests", "confirm", "true"));
        assertTrue(location(kept).contains("brouillon est conserv"), location(kept));
        assertEquals(oldTier, read("quests/guard_tier1"), "rien n'est écrit dans PlugAdmin");

        String after = pendingPage();
        assertTrue(after.contains("name=\"sel_quests/guard_tier1\""), "redevient un brouillon publiable");
        assertFalse(after.contains("name=\"sel_dialogues/guard\""), "l'autre ressource reste bloquée");
        submitBatch(Map.of("quests/guard_tier1", new String[] {sha(oldTier), sha(devTier)}));
        AgentActionRow batch = single("content.publish.batch");
        assertEquals("guard_tier1", batch.params().get("id_0"));
        assertEquals(sha(devTier), batch.params().get("expected_dev_sha_0"));
    }

    // ---- Bob ---------------------------------------------------------------------------------

    /**
     * Bob identique des deux côtés : la version commune est retenue. Quand un déploiement change
     * ensuite DEV, PlugAdmin le dit (« Plus récent sur DEV ») au lieu de proposer de republier.
     */
    @Test
    void bobChangedOnDevAfterASyncIsDetectedAsNewerOnDev() throws Exception {
        String bob = resource("dialogues/woodcutter_bob.yml");
        String questOld = fixture("woodcutters_request-249.yml");
        String questDev = resource("quests/woodcutters_request.yml");
        start(Map.of("dialogues/woodcutter_bob", bob, "quests/woodcutters_request", questOld));
        devState(Instant.now().minusSeconds(120), "dialogues", "woodcutter_bob", sha(bob), "quests",
                "woodcutters_request", sha(questDev));

        String first = pendingPage();
        assertFalse(first.contains(">woodcutter_bob<"), "identique et chargé : rien à décider");
        assertEquals(sha(bob), store.devReference(TestConfig.AGENT_ID, "dialogues", "woodcutter_bob")
                .orElseThrow().devSha(), "la version commune est retenue");
        assertTrue(first.contains("Versions différentes"), "la quête de Bob diverge sans référence");

        String bobOnDev = bob.replace("Du travail ?", "Du travail, voyageur ?");
        devState(Instant.now().minusSeconds(30), "dialogues", "woodcutter_bob", sha(bobOnDev), "quests",
                "woodcutters_request", sha(questDev));
        assertTrue(get("/dialogues/edit/woodcutter_bob").body().contains("DEV a une version plus récente"));
        assertTrue(location(publishAction("dialogues", "woodcutter_bob", sha(bob), sha(bobOnDev)))
                .contains("version plus récente"));
        assertEquals(0, count("content.publish"));
    }

    // ---- Quêtes retirées ---------------------------------------------------------------------

    @Test
    void firstStepsAndCrystalHuntNeverComeBack() throws Exception {
        String firstSteps = fixture("first_steps-249.yml");
        String crystal = fixture("crystal_hunt-249.yml");
        start(Map.of("quests/first_steps", firstSteps, "quests/crystal_hunt", crystal));
        devState(Instant.now().minusSeconds(60));

        String pending = pendingPage();
        assertTrue(pending.contains("Retiré définitivement"));
        assertFalse(pending.contains("name=\"sel_quests/first_steps\""));
        assertFalse(pending.contains("name=\"sel_quests/crystal_hunt\""));

        HttpResponse<String> batch = submitBatch(Map.of("quests/first_steps", new String[] {sha(firstSteps), ""},
                "quests/crystal_hunt", new String[] {sha(crystal), ""}));
        assertTrue(location(batch).contains("retiré définitivement"), location(batch));
        assertTrue(location(publishAction("quests", "crystal_hunt", sha(crystal), ""))
                .contains("retiré définitivement"));
        assertEquals(0, count("content.publish") + count("content.publish.batch"), "rien n'est parti");

        devRead("quests", "first_steps", firstSteps);
        HttpResponse<String> recover = post("/content/recover", Map.of("agent", TestConfig.AGENT_ID,
                "kind", "quests", "slug", "first_steps", "expected_sha", sha(firstSteps),
                "expected_dev_sha", sha(firstSteps), "return", "/quests"));
        assertTrue(location(recover).contains("retiré définitivement"), "même pas récupérée depuis DEV");
    }

    // ---- Modifications concurrentes ----------------------------------------------------------

    /** DEV ou PlugAdmin a bougé entre l'affichage et le clic : rien n'est remplacé ni adopté. */
    @Test
    void aChoiceMadeOnAStaleScreenChangesNothing() throws Exception {
        String old = fixture("guard_tier1-249.yml");
        String dev = resource("quests/guard_tier1.yml");
        String devLater = dev + "\n# modifié sur DEV entre-temps\n";
        start(Map.of("quests/guard_tier1", old));
        devState(Instant.now().minusSeconds(120), "quests", "guard_tier1", sha(dev));
        devRead("quests", "guard_tier1", dev);
        devState(Instant.now().minusSeconds(10), "quests", "guard_tier1", sha(devLater));

        HttpResponse<String> keep = post("/content/keep-draft", Map.of("agent", TestConfig.AGENT_ID,
                "kind", "quests", "slug", "guard_tier1", "expected_sha", sha(old), "expected_dev_sha", sha(dev),
                "return", "/quests", "confirm", "true"));
        assertTrue(location(keep).contains("a bougé"), location(keep));
        assertTrue(store.devReference(TestConfig.AGENT_ID, "quests", "guard_tier1").isEmpty());

        Files.writeString(content.resolve("quests/guard_tier1.yml"), old + "\n# édité dans PlugAdmin\n");
        HttpResponse<String> recover = post("/content/recover", Map.of("agent", TestConfig.AGENT_ID,
                "kind", "quests", "slug", "guard_tier1", "expected_sha", sha(old), "expected_dev_sha", sha(dev),
                "return", "/quests"));
        assertTrue(location(recover).contains("a changé depuis l"), location(recover));
        assertTrue(read("quests/guard_tier1").endsWith("# édité dans PlugAdmin\n"), "l'édition est gardée");
        assertTrue(backups("guard_tier1.yml").isEmpty(), "rien à sauvegarder : rien n'a été remplacé");
    }

    // ---- Publications ------------------------------------------------------------------------

    /** Brouillon (DEV = version commune) : publication individuelle puis groupée, sans entrave. */
    @Test
    void aRealDraftIsPublishedAloneOrInABatch() throws Exception {
        String dev = resource("quests/guard_tier1.yml");
        String draft = dev.replace("Gauthier", "Gauthier le Brave");
        String tier2 = resource("quests/guard_tier2.yml");
        String tier2Draft = tier2.replace("Gauthier", "Gauthier le Brave");
        start(Map.of("quests/guard_tier1", draft, "quests/guard_tier2", tier2Draft));
        store.adoptDevReference(TestConfig.AGENT_ID, "quests", "guard_tier1", sha(dev), "owner",
                Instant.now().minusSeconds(600));
        store.adoptDevReference(TestConfig.AGENT_ID, "quests", "guard_tier2", sha(tier2), "owner",
                Instant.now().minusSeconds(600));
        devState(Instant.now().minusSeconds(60), "quests", "guard_tier1", sha(dev), "quests", "guard_tier2",
                sha(tier2));

        String pending = pendingPage();
        assertTrue(pending.contains("Brouillon non publié"));
        assertFalse(pending.contains("name=\"sel_quests/guard_tier1\" value=\"1\" checked"), "rien n'est pré-coché");
        assertEquals(303, publishAction("quests", "guard_tier1", sha(draft), sha(dev)).statusCode());
        assertEquals(draft, single("content.publish").params().get("yaml"));

        submitBatch(Map.of("quests/guard_tier1", new String[] {sha(draft), sha(dev)},
                "quests/guard_tier2", new String[] {sha(tier2Draft), sha(tier2)}));
        assertEquals("2", single("content.publish.batch").params().get("count"));
    }

    /**
     * Publication écrite sur DEV puis refusée au rechargement : la source est intacte, l'état ne
     * prétend ni « à jour » ni « modifié par quelqu'un d'autre », et republier reste possible.
     */
    @Test
    void aFailedPublicationLosesNothingAndCanBeRetried() throws Exception {
        String dev = resource("quests/guard_tier1.yml");
        String draft = dev.replace("Gauthier", "Gauthier le Brave");
        start(Map.of("quests/guard_tier1", draft));
        store.adoptDevReference(TestConfig.AGENT_ID, "quests", "guard_tier1", sha(dev), "owner",
                Instant.now().minusSeconds(600));
        devState(Instant.now().minusSeconds(60), "quests", "guard_tier1", sha(dev));
        publishAction("quests", "guard_tier1", sha(draft), sha(dev));
        AgentActionRow action = single("content.publish");
        String details = "{\"ok\":false,\"code\":\"RELOAD_FAILED\",\"kind\":\"quests\",\"slug\":\"guard_tier1\","
                + "\"devShaBefore\":\"" + sha(dev) + "\",\"devShaAfter\":\"" + sha(draft) + "\","
                + "\"runtimeConfirmed\":false,\"backupPath\":\"backups/x.yml\"}";
        store.recordResult(action.id(), TestConfig.AGENT_ID, AgentActionStatus.FAILED, "RELOAD_FAILED", "refus",
                "{\"action_id\":\"" + action.id() + "\",\"status\":\"FAILED\",\"details\":" + details + "}",
                Instant.now());

        assertEquals(draft, read("quests/guard_tier1"), "la source n'a rien perdu");
        String pending = pendingPage();
        assertTrue(pending.contains("Sur DEV, non chargé"), "dit exactement ce qui s'est passé");
        assertFalse(pending.contains("Plus récent sur DEV"));
        assertTrue(pending.contains("name=\"sel_quests/guard_tier1\""), "republier reste possible");
        assertEquals(303, publishAction("quests", "guard_tier1", sha(draft), sha(draft)).statusCode());
        assertEquals(2, count("content.publish"));
    }

    // ---- Harnais -----------------------------------------------------------------------------

    private void start(Map<String, String> files) throws Exception {
        content = tmp.resolve("content");
        for (String kind : ContentWorkspace.KINDS) {
            Files.createDirectories(content.resolve(kind));
        }
        for (Map.Entry<String, String> f : files.entrySet()) {
            Files.writeString(content.resolve(f.getKey() + ".yml"), f.getValue());
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

    /** Un relevé {@code content.dev.state} : triplets famille, identifiant, empreinte ; tout est chargé. */
    private void devState(Instant at, String... triples) {
        StringBuilder files = new StringBuilder();
        Map<String, List<String>> ids = new LinkedHashMap<>();
        for (String kind : List.of("quests", "stories", "dialogues")) {
            ids.put(kind, new ArrayList<>());
        }
        for (int i = 0; i + 2 < triples.length; i += 3) {
            files.append(files.isEmpty() ? "" : ",").append("{\"kind\":\"").append(triples[i]).append("\",\"slug\":\"")
                    .append(triples[i + 1]).append("\",\"sha256\":\"").append(triples[i + 2]).append("\",\"bytes\":10}");
            ids.get(triples[i]).add("\"rpgquest:" + triples[i + 1] + "\"");
        }
        StringBuilder runtime = new StringBuilder();
        ids.forEach((k, v) -> runtime.append(runtime.isEmpty() ? "" : ",").append('"').append(k).append("\":[")
                .append(String.join(",", v)).append(']'));
        String details = "{\"files\":[" + files + "],\"runtimeIds\":{" + runtime + "},\"runtimeHash\":\"h\"}";
        String id = store.createAction(TestConfig.AGENT_ID, "content.dev.state", Map.of(), "owner");
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + details + "}", at);
    }

    /** Une lecture {@code content.dev.read} du fichier DEV, comme « Voir les différences ». */
    private void devRead(String kind, String slug, String text) {
        String id = store.createAction(TestConfig.AGENT_ID, "content.dev.read", Map.of("kind", kind, "id", slug), "owner");
        String details = "{\"kind\":\"" + kind + "\",\"slug\":\"" + slug + "\",\"present\":true,\"tooLarge\":false,"
                + "\"sha256\":\"" + sha(text) + "\",\"text\":" + json(text) + "}";
        store.recordResult(id, TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "present", "ok",
                "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"details\":" + details + "}", Instant.now());
    }

    private String pendingPage() throws Exception {
        return get("/content/pending?agent=" + TestConfig.AGENT_ID).body();
    }

    /** Un lot comme le formulaire l'enverrait : pour chaque clé, ses empreintes source et DEV vues. */
    private HttpResponse<String> submitBatch(Map<String, String[]> picks) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("agent", TestConfig.AGENT_ID);
        form.put("confirm", "true");
        picks.forEach((key, shas) -> {
            form.put("sel_" + key, "1");
            form.put("src_" + key, shas[0]);
            form.put("dev_" + key, shas[1]);
        });
        return post("/content/pending/publish", form);
    }

    /** La publication individuelle des fiches (formulaire générique d'action). */
    private HttpResponse<String> publishAction(String kind, String slug, String sourceSha, String devSha)
            throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("agent", TestConfig.AGENT_ID);
        form.put("type", "content.publish");
        form.put("return", "/quests");
        form.put("kind", kind);
        form.put("id", slug);
        form.put("expected_source_sha", sourceSha);
        form.put("expected_dev_sha", devSha);
        form.put("confirm", "true");
        return post("/agents/action", form);
    }

    private AgentActionRow single(String type) {
        List<AgentActionRow> rows = store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).toList();
        assertFalse(rows.isEmpty(), "aucune action " + type);
        return rows.get(0);
    }

    private long count(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream().filter(a -> a.type().equals(type)).count();
    }

    private String read(String key) throws Exception {
        return Files.readString(content.resolve(key + ".yml"));
    }

    private List<String> backups(String name) throws Exception {
        Path root = tmp.resolve("content-backups");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            for (Path p : walk.filter(p -> p.getFileName().toString().equals(name)).toList()) {
                out.add(Files.readString(p));
            }
        }
        return out;
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name));
    }

    private static String resource(String path) throws Exception {
        return Files.readString(RESOURCES.resolve(path));
    }

    private static String location(HttpResponse<String> res) {
        return URLDecoder.decode(res.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8);
    }

    private static String sha(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** Ce que le navigateur enverrait : champs non désactivés, cases cochées, option choisie. */
    private static Map<String, String> formFieldsOf(String page, String action) {
        int start = page.indexOf("action=\"" + action + "\"");
        assertTrue(start > 0, "formulaire " + action + " absent");
        String f = page.substring(start, page.indexOf("</form>", start));
        Map<String, String> out = new LinkedHashMap<>();
        Matcher in = Pattern.compile("<input([^>]*)>").matcher(f);
        while (in.find()) {
            String a = in.group(1);
            String name = attr(a, "name");
            String type = attr(a, "type");
            if (name == null || a.contains(" disabled") || "submit".equals(type)
                    || ("checkbox".equals(type) && !a.contains(" checked"))) {
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
            Matcher opt = Pattern.compile("<option value=\"([^\"]*)\"[^>]* selected").matcher(se.group(2));
            if (name != null && !se.group(1).contains(" disabled")) {
                out.put(name, opt.find() ? unescape(opt.group(1)) : "");
            }
        }
        return out;
    }

    /** Les lignes porteuses de sens d'un YAML : sans commentaires, lignes vides ni guillemets. */
    private static List<String> logic(String yaml) {
        return yaml.lines().map(l -> l.replace("\"", "")).filter(l -> !l.isBlank() && !l.strip().startsWith("#"))
                .toList();
    }

    /** Les messages d'erreur d'une page refusée, pour un échec lisible. */
    private static String problems(String page) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)class=\"[^\"]*(?:field-error|dlg-warn|alert)[^\"]*\"[^>]*>(.*?)</").matcher(page);
        while (m.find() && out.size() < 8) {
            out.add(m.group(1).replaceAll("<[^>]+>", "").strip());
        }
        return String.join(" | ", out);
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> get(String p) throws Exception {
        return send(HttpRequest.newBuilder(uri(p)).GET());
    }

    private HttpResponse<String> post(String p, Map<String, String> form) throws Exception {
        return post(p, form, null);
    }

    private HttpResponse<String> post(String p, Map<String, String> form, String op) throws Exception {
        Map<String, String> body = new LinkedHashMap<>(form);
        body.putIfAbsent("_csrf", csrf(get("/dialogues").body()));
        if (op != null) {
            body.put("_op", op);
        }
        StringBuilder sb = new StringBuilder();
        body.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)));
        return send(HttpRequest.newBuilder(uri(p)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())));
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            StringBuilder cookie = new StringBuilder();
            jar.forEach((k, v) -> cookie.append(cookie.isEmpty() ? "" : "; ").append(k).append('=').append(v));
            b.header("Cookie", cookie.toString());
        }
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        for (String set : res.headers().allValues("Set-Cookie")) {
            String pair = set.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0) {
                jar.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return res;
    }
}
