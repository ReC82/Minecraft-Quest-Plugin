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

    // ---- Le diff (issue #47, lot A) ---------------------------------------------------------------

    /** Une ressource différente propose de voir les différences, en plus de republier. */
    @Test
    void aDifferentResourceOffersToSeeTheDifferences() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha("autre contenu")),
                "[\"rpgquest:test_publish_quest\"]"));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("name=\"type\" value=\"content.dev.read\""));
        assertTrue(page.contains("Voir les différences"));
        assertTrue(page.contains("Republier sur DEV"), "les deux actions sont proposées");
    }

    /** Une ressource absente de DEV ne propose PAS de comparer : il n'y a rien en face. */
    @Test
    void aSourceOnlyResourceDoesNotOfferAComparison() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState("{}", "[]"));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("name=\"type\" value=\"content.dev.read\""),
                "tout le fichier serait « ajouté » : la comparaison n'apprendrait rien");
        assertTrue(page.contains("Publier sur DEV"));
    }

    /** Le vrai formulaire de comparaison part avec la famille et l'identifiant, et rien d'autre. */
    @Test
    void theRealCompareFormQueuesATargetedRead() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha("autre contenu")),
                "[\"rpgquest:test_publish_quest\"]"));

        HttpResponse<String> res = submitRealForm("content.dev.read", Map.of());

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("").startsWith("/quests?"));
        AgentActionRow queued = queued("content.dev.read");
        assertEquals("quests", queued.params().get("kind"));
        assertEquals("test_publish_quest", queued.params().get("id"));
        assertFalse(queued.params().containsKey("yaml"), "une lecture n'envoie aucun contenu");
    }

    /** Une fois le fichier DEV relu, la page montre un diff lisible, ligne à ligne. */
    @Test
    void onceReadThePageShowsAReadableLineDiff() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        String devText = QUEST_YAML.replace("Quête de publication", "ANCIEN TITRE");
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha(devText)),
                "[\"rpgquest:test_publish_quest\"]"));
        seedRead(devText, sha(devText));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Différences"), "le bloc de comparaison est rendu");
        assertTrue(page.contains("di-del"), "au moins une ligne retirée");
        assertTrue(page.contains("di-add"), "au moins une ligne ajoutée");
        assertTrue(page.contains("ANCIEN TITRE"), "la ligne DEV est montrée");
        assertTrue(page.contains("Quête de publication"), "la ligne source est montrée");
        assertTrue(page.contains("ligne(s) ajoutée(s)"), "et un résumé chiffré");
    }

    /**
     * Le YAML n'est jamais interprété comme du HTML.
     *
     * <p>Un contenu de quête contient du MiniMessage, donc des chevrons. S'ils n'étaient pas
     * échappés, une description pourrait injecter du balisage dans la page du panel.</p>
     */
    @Test
    void theDiffEscapesHtmlAndNeverInterpretsTheYaml() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        String devText = "description: \"<script>alert('x')</script>\"\n";
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha(devText)),
                "[\"rpgquest:test_publish_quest\"]"));
        seedRead(devText, sha(devText));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("<script>alert"), "aucune balise script brute dans la page");
        assertTrue(page.contains("&lt;script&gt;"), "les chevrons sont échappés");
    }

    /** Le diff ne doit jamais faire apparaître un chemin du système de fichiers. */
    @Test
    void theDiffNeverExposesAFilesystemPath() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        String devText = QUEST_YAML.replace("publication", "ancienne");
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha(devText)),
                "[\"rpgquest:test_publish_quest\"]"));
        seedRead(devText, sha(devText));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();
        int at = page.indexOf("Différences");
        String block = page.substring(at, Math.min(page.length(), at + 8000));

        assertFalse(block.contains(contentRoot.toString()), "aucun chemin absolu");
        assertFalse(block.contains("/src/main/resources"), "aucun chemin de dépôt");
        assertFalse(block.contains("plugins/RPGQuest"), "aucun chemin serveur");
    }

    /** Un fichier DEV trop volumineux est annoncé, pas comparé en silence. */
    @Test
    void anOversizedDevFileIsAnnouncedRatherThanCompared() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha("gros")),
                "[\"rpgquest:test_publish_quest\"]"));
        // present = true, tooLarge = true, texte vide : le serveur refuse de le transporter.
        deliverReadResult("{\"kind\":\"quests\",\"slug\":\"test_publish_quest\","
                + "\"present\":true,\"tooLarge\":true,\"sha256\":\"" + sha("gros")
                + "\",\"text\":\"\"}");

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("trop volumineux"), "la limite est annoncée");
        assertFalse(page.contains("di-add"), "et aucun faux diff n'est rendu");
    }

    // ---- Conflit : aucun remplacement aveugle ----------------------------------------------------

    /**
     * En conflit, le bouton de publication n'apparaît <strong>pas</strong> avant d'avoir regardé.
     *
     * <p>« Ne propose pas un remplacement aveugle » : la publication n'est offerte qu'une fois le
     * contenu DEV <em>courant</em> réellement consulté.</p>
     */
    @Test
    void aConflictOffersNoPublishButtonBeforeTheDifferenceHasBeenSeen() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        String devText = QUEST_YAML.replace("publication", "modifiée hors du panel");
        // DEV ne correspond ni à la source ni à notre dernière publication -> conflit.
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha(devText)),
                "[\"rpgquest:test_publish_quest\"]"));
        seedResult("content.publish",
                "{\"ok\":true,\"code\":\"PUBLISHED\",\"kind\":\"quests\","
                        + "\"slug\":\"test_publish_quest\",\"devShaAfter\":\""
                        + sha("une version que nous avions publiée")
                        + "\",\"backupPath\":\"\",\"runtimeConfirmed\":true,"
                        + "\"verifiedAt\":\"2026-10-09T00:00:00Z\"}");

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Conflit"));
        assertTrue(page.contains("modifié hors du panel"));
        assertTrue(page.contains("Voir les différences"), "on propose de regarder");
        assertFalse(page.contains("name=\"type\" value=\"content.publish\""),
                "et PAS de publier avant d'avoir regardé");
        assertTrue(page.contains("n'apparaît qu'ensuite"), "la page explique pourquoi");
    }

    /** Après avoir consulté la version DEV courante, l'écrasement devient possible — et nommé. */
    @Test
    void afterSeeingTheCurrentDevVersionTheOverwriteBecomesAvailableAndIsNamedAsSuch()
            throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        String devText = QUEST_YAML.replace("publication", "modifiée hors du panel");
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha(devText)),
                "[\"rpgquest:test_publish_quest\"]"));
        seedResult("content.publish",
                "{\"ok\":true,\"code\":\"PUBLISHED\",\"kind\":\"quests\","
                        + "\"slug\":\"test_publish_quest\",\"devShaAfter\":\""
                        + sha("une version que nous avions publiée")
                        + "\",\"backupPath\":\"\",\"runtimeConfirmed\":true,"
                        + "\"verifiedAt\":\"2026-10-09T00:00:00Z\"}");
        // L'administrateur consulte la version DEV COURANTE.
        seedRead(devText, sha(devText));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("name=\"type\" value=\"content.publish\""));
        assertTrue(page.contains("Écraser la version DEV"), "le bouton dit ce qu'il fait");
        assertTrue(page.contains("sera perdue"), "et ce qui sera perdu");
    }

    /** Avoir consulté une version DEV ANTÉRIEURE ne vaut pas consentement. */
    @Test
    void havingSeenAnOlderDevVersionDoesNotCountAsConsent() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        String devNow = QUEST_YAML.replace("publication", "version COURANTE hors panel");
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha(devNow)),
                "[\"rpgquest:test_publish_quest\"]"));
        seedResult("content.publish",
                "{\"ok\":true,\"code\":\"PUBLISHED\",\"kind\":\"quests\","
                        + "\"slug\":\"test_publish_quest\",\"devShaAfter\":\""
                        + sha("publiée jadis") + "\",\"backupPath\":\"\","
                        + "\"runtimeConfirmed\":true,\"verifiedAt\":\"2026-10-09T00:00:00Z\"}");
        // On a lu une version PÉRIMÉE : son empreinte ne correspond pas à celle de DEV aujourd'hui.
        seedRead("un contenu DEV périmé\n", sha("un contenu DEV périmé\n"));

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("name=\"type\" value=\"content.publish\""),
                "la lecture périmée ne doit pas débloquer l'écrasement");
        assertTrue(page.contains("n'apparaît qu'ensuite"));
    }

    // ---- Après un retour arrière -----------------------------------------------------------------

    /**
     * Après une restauration, l'état est « Différent » — <strong>pas</strong> « Conflit ».
     *
     * <p>Défaut constaté sur le serveur réel : la référence « ce que nous avons mis sur DEV » ne
     * regardait que les <em>publications</em>. Après une restauration, DEV ne portait donc ni la
     * source ni la dernière publication, et l'écran annonçait « Conflit » — c'est-à-dire
     * « quelqu'un d'autre a touché au fichier », alors que c'était nous, à la demande de
     * l'utilisateur.</p>
     */
    @Test
    void afterARollbackTheStateIsDifferentAndNotAConflict() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        // DEV porte une version ANCIENNE (celle restaurée), la source porte la nouvelle.
        String restored = sha("ancienne version");
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", restored),
                "[\"rpgquest:test_publish_quest\"]"));
        // Et la dernière opération réussie est une RESTAURATION qui a remis cette empreinte.
        seedResult("content.publish.rollback",
                "{\"ok\":true,\"code\":\"RESTORED\",\"kind\":\"quests\","
                        + "\"slug\":\"test_publish_quest\",\"devShaAfter\":\"" + restored
                        + "\",\"backupPath\":\"content-backups/x/quests/test_publish_quest.yml\","
                        + "\"runtimeConfirmed\":true,\"verifiedAt\":\"2026-10-09T00:00:00Z\"}");

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Différent"), "DEV porte volontairement une version antérieure");
        assertFalse(page.contains("Conflit"),
                "ce n'est pas un tiers qui a modifié le fichier, c'est nous");
    }

    /**
     * Après une restauration, aucun bouton de retour arrière.
     *
     * <p>Il n'y a plus rien à défaire : DEV porte déjà la version sauvegardée. Un bouton qui
     * reposerait le même contenu ne ferait rien, et un bouton qui ne fait rien est un bouton qui
     * ment.</p>
     */
    @Test
    void afterARollbackNoFurtherRollbackIsOffered() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        String restored = sha("ancienne version");
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", restored),
                "[\"rpgquest:test_publish_quest\"]"));
        seedResult("content.publish.rollback",
                "{\"ok\":true,\"code\":\"RESTORED\",\"kind\":\"quests\","
                        + "\"slug\":\"test_publish_quest\",\"devShaAfter\":\"" + restored
                        + "\",\"backupPath\":\"content-backups/x/quests/test_publish_quest.yml\","
                        + "\"runtimeConfirmed\":true,\"verifiedAt\":\"2026-10-09T00:00:00Z\"}");

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("name=\"type\" value=\"content.publish.rollback\""),
                "aucun bouton de restauration après une restauration");
        // En revanche, publier à nouveau reste proposé : c'est la suite logique.
        assertTrue(page.contains("name=\"type\" value=\"content.publish\""));
    }

    /** Après une publication, le retour arrière EST proposé — c'est le cas symétrique. */
    @Test
    void afterAPublicationARollbackIsOffered() throws Exception {
        start();
        seed("quest.list", RUNTIME_WITHOUT_IT);
        seed("content.dev.state", devState(
                fileEntry("quests", "test_publish_quest", sha(QUEST_YAML)),
                "[\"rpgquest:test_publish_quest\"]"));
        seedResult("content.publish",
                "{\"ok\":true,\"code\":\"PUBLISHED\",\"kind\":\"quests\","
                        + "\"slug\":\"test_publish_quest\",\"devShaAfter\":\"" + sha(QUEST_YAML)
                        + "\",\"backupPath\":\"content-backups/x/quests/test_publish_quest.yml\","
                        + "\"runtimeConfirmed\":true,\"verifiedAt\":\"2026-10-09T00:00:00Z\"}");

        String page = get("/quests?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Synchronisé"));
        assertTrue(page.contains("name=\"type\" value=\"content.publish.rollback\""));
        assertTrue(page.contains("Restaurer la version précédente"));
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

    /** Simule un {@code content.dev.read} réussi portant ce texte DEV. */
    private void seedRead(String devText, String devSha) throws Exception {
        deliverReadResult("{\"kind\":\"quests\",\"slug\":\"test_publish_quest\","
                + "\"present\":true,\"tooLarge\":false,\"sha256\":\"" + devSha
                + "\",\"text\":" + jsonString(devText) + "}");
    }

    /** Enfile un {@code content.dev.read} pour la ressource de test, puis lui renvoie un résultat. */
    private void deliverReadResult(String details) throws Exception {
        String token = csrf(get("/quests?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=content.dev.read&agent="
                + TestConfig.AGENT_ID + "&return=/quests&kind=quests&id=test_publish_quest");
        deliver("content.dev.read", details);
    }

    /** Encode une chaîne en littéral JSON — le texte contient des guillemets et des retours. */
    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Comme {@link #seed}, mais pour une action qui porte des paramètres de ressource.
     *
     * <p>Nécessaire pour simuler une publication ou une restauration antérieure : la page les
     * retrouve en filtrant sur {@code kind} et {@code id}.</p>
     */
    private void seedResult(String type, String details) throws Exception {
        String token = csrf(get("/quests?agent=" + TestConfig.AGENT_ID).body());
        String extra = "&kind=quests&id=test_publish_quest&confirm=true";
        if (type.equals("content.publish")) {
            extra += "&expected_source_sha=" + sha(QUEST_YAML) + "&expected_dev_sha=";
        }
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent="
                + TestConfig.AGENT_ID + "&return=/quests" + extra);
        deliver(type, details);
    }

    /** Enfile une action, la livre à l'agent, et renvoie un succès portant les détails fournis. */
    private void seed(String type, String details) throws Exception {
        String token = csrf(get("/quests?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent="
                + TestConfig.AGENT_ID + "&return=/quests");
        deliver(type, details);
    }

    /** Livre la prochaine action en attente et lui renvoie un succès portant {@code details}. */
    private void deliver(String details) throws Exception {
        deliver(null, details);
    }

    /**
     * Livre l'action en attente <strong>du type demandé</strong> et lui renvoie un succès.
     *
     * <p>Le type est indispensable, et son absence a produit deux faux échecs : une publication
     * réussie <em>ré-enfile automatiquement</em> les relevés de catalogue ({@code content.dev.state},
     * {@code quest.list}…), donc « la première action en attente » n'est pas celle qu'on vient
     * d'enfiler. Le résultat se posait sur la mauvaise action, et l'état affiché devenait
     * incohérent — exactement le genre de confusion que le produit, lui, évite en filtrant sur
     * {@code kind}/{@code id}.</p>
     */
    private void deliver(String expectedType, String details) throws Exception {
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String id = null;
        Matcher m = Pattern.compile(
                "\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(poll.body());
        while (m.find()) {
            if (expectedType == null || expectedType.equals(m.group(2))) {
                id = m.group(1);
                break;
            }
        }
        if (id == null) {
            // Repli : certaines réponses n'ordonnent pas id avant type.
            Matcher any = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"")
                    .matcher(poll.body());
            if (expectedType == null && any.find()) {
                id = any.group(1);
            }
        }
        if (id == null) {
            throw new IllegalStateException("aucune action « " + expectedType + " » à livrer : "
                    + poll.body());
        }
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
