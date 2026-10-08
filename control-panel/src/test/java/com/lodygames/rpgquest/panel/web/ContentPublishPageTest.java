package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #47 — publier une quête depuis {@code /quests}, vu du navigateur.
 *
 * <h2>Pourquoi ces tests soumettent les VRAIS formulaires</h2>
 *
 * <p>#227 a démontré qu'un test qui fabrique lui-même un POST ne prouve rien du parcours : il porte
 * exactement les champs que le test croit nécessaires. Ici chaque test <strong>extrait le formulaire
 * rendu par la page</strong> — y compris les deux empreintes cachées — et le soumet tel quel. Un
 * champ oublié, mal nommé, ou une empreinte qui n'arriverait pas jusqu'au formulaire se verrait
 * immédiatement.</p>
 *
 * <p>Ce qui est vérifié au-delà du parcours : <strong>le navigateur n'envoie jamais le contenu ni un
 * chemin</strong>. Le YAML est joint par le panel, côté serveur, au moment d'enfiler l'action.</p>
 */
class ContentPublishPageTest {

    private static final String QUEST_YAML = """
            id: rpgquest:test_publish_quest
            title: "Quête de publication"
            description: "Ressource de test pour #47."
            steps:
              - id: etape1
                objectives:
                  - KILL:ZOMBIE:1
            """;

    /** Relevé {@code quest.list} : la quête n'est PAS sur le serveur. */
    private static final String RUNTIME_WITHOUT_IT = "{\"quests\":[],\"total\":0}";

    @TempDir
    Path tmp;

    private Path contentRoot;
    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        contentRoot = tmp.resolve("resources");
        Files.createDirectories(contentRoot.resolve("quests"));
        Files.createDirectories(contentRoot.resolve("stories"));
        Files.createDirectories(contentRoot.resolve("dialogues"));
        Files.writeString(contentRoot.resolve("quests").resolve("test_publish_quest.yml"),
                QUEST_YAML);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- L'état affiché ------------------------------------------------------------------------

    /**
     * Sans relevé DEV, la page ne prétend rien et le dit.
     *
     * <p>C'est le point de départ de #47 : avant, l'absence de relevé produisait quand même un
     * badge, donc une affirmation sur un serveur qu'on n'avait pas interrogé.</p>
     */
    @Test
    void withoutADevReadingThePageSaysTheStateIsUnknownAndOffersToRead() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("État DEV"), "le bouton de relevé doit être proposé");
        assertTrue(page.contains("n'est pas encore relevé"), "et la page explique pourquoi");
        assertTrue(page.contains("état inconnu") || page.contains("État inconnu"));
        assertFalse(page.contains("name=\"type\" value=\"content.publish\""),
                "aucun bouton Publier tant qu'on ne sait pas où l'on en est");
    }

    /** Le cas de référence : source présente, DEV absent → « Source uniquement » + action claire. */
    @Test
    void aQuestPresentInSourceAndAbsentFromDevIsSourceOnlyAndCanBePublished() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Source uniquement"));
        assertTrue(page.contains("absente du serveur DEV"), "l'explication, pas juste le badge");
        assertTrue(page.contains("name=\"type\" value=\"content.publish\""));
        assertTrue(page.contains("Publier sur DEV"));
        assertTrue(page.contains("Aucun build, aucun redémarrage"), "la promesse du ticket");
    }

    /** Présente des deux côtés, identique, et chargée : « Synchronisé ». */
    @Test
    void anIdenticalAndLoadedQuestIsSynchronized() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState(fileEntry("quests", "test_publish_quest", sha(QUEST_YAML)),
                "[\"rpgquest:test_publish_quest\"]"));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Synchronisé"));
        assertTrue(page.contains("le moteur la voit en jeu"));
    }

    /**
     * Identique mais pas chargée : « Publié, non chargé », et <strong>pas</strong> « Synchronisé ».
     *
     * <p>C'est exactement le mensonge que #47 supprime.</p>
     */
    @Test
    void anIdenticalButUnloadedQuestIsNotShownAsSynchronized() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState(fileEntry("quests", "test_publish_quest", sha(QUEST_YAML)),
                "[]"));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Publié, non chargé"));
        assertTrue(page.contains("le moteur ne le charge pas"));
        assertTrue(page.contains("Republier sur DEV"), "et on propose la bonne action");
    }

    /** Présente des deux côtés mais différente : « Différent ». */
    @Test
    void aModifiedSourceIsShownAsDifferent() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha("autre contenu")),
                "[\"rpgquest:test_publish_quest\"]"));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Différent"));
        assertTrue(page.contains("ne sont pas en jeu"));
        assertTrue(page.contains("Republier sur DEV"));
    }

    // ---- Le vrai formulaire --------------------------------------------------------------------

    /**
     * Le formulaire rendu par la page porte les deux empreintes, et rien d'autre de sensible.
     *
     * <p>Ni chemin, ni contenu : c'est la garantie de sécurité du ticket, et elle se vérifie sur le
     * HTML réellement produit.</p>
     */
    @Test
    void theRealFormCarriesBothHashesAndNeitherPathNorContent() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        Map<String, String> fields = realFormFields(
                get("/quests?agent=" + TestConfig.AGENT_ID).body(), "content.publish");

        assertEquals("quests", fields.get("kind"));
        assertEquals("test_publish_quest", fields.get("id"));
        assertEquals(sha(QUEST_YAML), fields.get("expected_source_sha"), "l'empreinte de la source");
        assertEquals("", fields.get("expected_dev_sha"), "DEV est absente : empreinte vide");
        assertTrue(fields.containsKey("_csrf"));
        assertTrue(fields.containsKey("confirm"), "publier est une mutation sensible");
        // Ce qui ne doit PAS être là.
        assertFalse(fields.containsKey("yaml"), "le navigateur n'envoie jamais le contenu");
        assertFalse(fields.containsKey("path"), "ni un chemin");
        assertFalse(fields.containsKey("file"));
        for (String value : fields.values()) {
            assertFalse(value.contains("/quests/"), "aucun chemin de fichier : " + value);
        }
    }

    /**
     * Soumettre le vrai formulaire enfile l'action, et le <strong>panel</strong> y joint le YAML.
     */
    @Test
    void submittingTheRealFormQueuesTheActionWithTheYamlInjectedServerSide() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        HttpResponse<String> res = submitRealForm("content.publish", Map.of());

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").startsWith("/quests?"),
                "retour sur la page métier, pas sur /agents (#227) : "
                        + res.headers().firstValue("Location"));
        AgentActionRow queued = queued("content.publish");
        assertEquals("quests", queued.params().get("kind"));
        assertEquals("test_publish_quest", queued.params().get("id"));
        assertEquals("", queued.params().get("expected_dev_sha"));
        assertEquals("true", queued.params().get("confirm"));
        // Le contenu a été joint par le serveur, à l'identique de la source.
        assertEquals(QUEST_YAML, queued.params().get("yaml"));
        // Et l'identifiant déclaré dans le YAML, qui diffère du nom de fichier.
        assertEquals("rpgquest:test_publish_quest", queued.params().get("expected_id"));
        // L'empreinte source a joué son rôle côté panel : inutile de l'envoyer au serveur de jeu.
        assertFalse(queued.params().containsKey("expected_source_sha"));
    }

    /**
     * La source a changé entre l'aperçu et la publication : refusé.
     *
     * <p>C'est la moitié de la protection qu'on oublie — #47 l'exige des deux côtés, et sans elle on
     * publierait une version que l'administrateur n'a pas vue.</p>
     */
    @Test
    void aSourceChangedSinceThePreviewIsRefused() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));
        Map<String, String> fields = new LinkedHashMap<>(realFormFields(
                get("/quests?agent=" + TestConfig.AGENT_ID).body(), "content.publish"));

        // Quelqu'un réenregistre la quête entre l'affichage et l'envoi.
        Files.writeString(contentRoot.resolve("quests").resolve("test_publish_quest.yml"),
                QUEST_YAML + "# modifié entre-temps\n");

        HttpResponse<String> res = post("/agents/action", encode(fields));

        assertEquals(303, res.statusCode());
        String location = res.headers().firstValue("Location").orElse("");
        assertTrue(location.startsWith("/quests?"), location);
        assertTrue(location.contains("err="), location);
        assertTrue(java.net.URLDecoder.decode(location, StandardCharsets.UTF_8)
                .contains("source a changé"), location);
        assertEquals(0, count("content.publish"), "rien n'est mis en file");
    }

    /** Une empreinte DEV forgée est refusée avant d'atteindre la file. */
    @Test
    void aMalformedDevHashIsRefused() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        HttpResponse<String> res = submitRealForm("content.publish",
                Map.of("expected_dev_sha", "pas-une-empreinte"));

        assertTrue(java.net.URLDecoder.decode(
                res.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8)
                .contains("Empreinte DEV"), res.headers().firstValue("Location").orElse(""));
        assertEquals(0, count("content.publish"));
    }

    // ---- Sécurité ------------------------------------------------------------------------------

    /**
     * Un identifiant forgé ne passe pas, même en soumettant le formulaire à la main.
     *
     * <p>La liste blanche est côté serveur : le navigateur ne décide ni du dossier ni du fichier.</p>
     */
    @Test
    void aForgedResourceIdIsRefused() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        for (String forged : new String[] {"../../etc/passwd", "a/b", "/absolu", "data.db",
                "..", "Majuscule"}) {
            HttpResponse<String> res = submitRealForm("content.publish", Map.of("id", forged));

            assertEquals(303, res.statusCode(), forged);
            assertTrue(res.headers().firstValue("Location").orElse("").contains("err="), forged);
        }
        assertEquals(0, count("content.publish"), "aucune action enfilée");
    }

    /** Une famille hors liste blanche est refusée : on ne publie que du contenu fichier. */
    @Test
    void aKindOutsideTheWhitelistIsRefused() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        for (String forged : new String[] {"npcs", "items", "mobs", "worlds", "config",
                "../quests", ""}) {
            HttpResponse<String> res = submitRealForm("content.publish", Map.of("kind", forged));

            assertTrue(res.headers().firstValue("Location").orElse("").contains("err="), forged);
        }
        assertEquals(0, count("content.publish"));
    }

    /** Sans jeton CSRF, rien ne part. */
    @Test
    void aMissingCsrfTokenIsRefused() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));
        Map<String, String> fields = new LinkedHashMap<>(realFormFields(
                get("/quests?agent=" + TestConfig.AGENT_ID).body(), "content.publish"));
        fields.remove("_csrf");

        HttpResponse<String> res = post("/agents/action", encode(fields));

        assertEquals(403, res.statusCode());
        assertEquals(0, count("content.publish"));
    }

    /** Publier une ressource absente de la source est refusé : il n'y a rien à envoyer. */
    @Test
    void publishingAResourceMissingFromSourceIsRefused() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));
        Map<String, String> fields = new LinkedHashMap<>(realFormFields(
                get("/quests?agent=" + TestConfig.AGENT_ID).body(), "content.publish"));
        Files.delete(contentRoot.resolve("quests").resolve("test_publish_quest.yml"));

        HttpResponse<String> res = post("/agents/action", encode(fields));

        assertTrue(java.net.URLDecoder.decode(
                res.headers().firstValue("Location").orElse(""), StandardCharsets.UTF_8)
                .contains("n'existe pas dans la source"),
                res.headers().firstValue("Location").orElse(""));
        assertEquals(0, count("content.publish"));
    }

    // ---- Idempotence ---------------------------------------------------------------------------

    /**
     * Double clic : deux demandes partent, et c'est le <strong>serveur</strong> qui tranche.
     *
     * <p>Le panel ne simule pas l'idempotence — il ne peut pas la garantir. Le service de
     * publication détient un verrou par ressource, et la base de contenu est la source de vérité.
     * Ce qui est vérifié ici, c'est qu'aucune autre couche n'est touchée au passage.</p>
     */
    @Test
    void submittingTwiceQueuesTwoRequestsAndNothingElse() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        submitRealForm("content.publish", Map.of());
        submitRealForm("content.publish", Map.of());

        assertEquals(2, count("content.publish"));
        assertEquals(0, count("content.publish.rollback"));
    }

    // ---- Harnais -------------------------------------------------------------------------------

    /** Un relevé {@code content.dev.state} : fichiers DEV + identifiants chargés pour les quêtes. */
    private static String devState(String fileEntryJson, String runtimeQuestIds) {
        String files = "{}".equals(fileEntryJson) ? "[]" : "[" + fileEntryJson + "]";
        return "{\"files\":" + files
                + ",\"runtimeIds\":{\"quests\":" + runtimeQuestIds
                + ",\"stories\":[],\"dialogues\":[]}"
                + ",\"runtimeHash\":\"h1\"}";
    }

    private static String fileEntry(String kind, String slug, String sha) {
        return "{\"kind\":\"" + kind + "\",\"slug\":\"" + slug + "\",\"sha256\":\"" + sha
                + "\",\"bytes\":123}";
    }

    /** La même fonction d'empreinte que le serveur, sinon rien ne concorde. */
    private static String sha(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private HttpResponse<String> submitRealForm(String actionType, Map<String, String> filled)
            throws Exception {
        Map<String, String> fields = new LinkedHashMap<>(realFormFields(
                get("/quests?agent=" + TestConfig.AGENT_ID).body(), actionType));
        fields.putAll(filled);
        return post("/agents/action", encode(fields));
    }

    private static String encode(Map<String, String> fields) {
        StringBuilder body = new StringBuilder();
        fields.forEach((k, v) -> {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(enc(k)).append('=').append(enc(v));
        });
        return body.toString();
    }

    private static Map<String, String> realFormFields(String page, String actionType) {
        Matcher forms = Pattern.compile("<form[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(page);
        while (forms.find()) {
            String form = forms.group(1);
            if (!form.contains("name=\"type\" value=\"" + actionType + "\"")) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher inputs = Pattern.compile("<input\\b[^>]*>").matcher(form);
            while (inputs.find()) {
                String tag = inputs.group(0);
                String name = attribute(tag, "name");
                if (name == null) {
                    continue;
                }
                String value = attribute(tag, "value");
                if ("checkbox".equals(attribute(tag, "type")) && value == null) {
                    value = "on";
                }
                fields.put(name, unescape(value == null ? "" : value));
            }
            Matcher areas = Pattern.compile("<textarea\\b[^>]*>(.*?)</textarea>", Pattern.DOTALL)
                    .matcher(form);
            while (areas.find()) {
                String name = attribute(areas.group(0), "name");
                if (name != null) {
                    fields.put(name, unescape(areas.group(1)));
                }
            }
            return fields;
        }
        throw new IllegalStateException("aucun formulaire « " + actionType + " » dans la page");
    }

    private static String attribute(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String raw) {
        return raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    /** Enfile une action, la livre à l'agent, et renvoie un succès portant les détails fournis. */
    private void seed(String type, String details) throws Exception {
        String token = csrf(get("/quests?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent="
                + TestConfig.AGENT_ID + "&return=/quests");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action à livrer : " + poll.body());
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"SUCCESS\",\"value\":\"ok\","
                + "\"message\":\"relevé\",\"details\":" + details + "}";
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + id + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long count(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).count();
    }

    private AgentActionRow queued(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1",
                contentRoot.toString()),
                new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + token);
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
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
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((x, y) -> x + "; " + y).orElse(""));
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
