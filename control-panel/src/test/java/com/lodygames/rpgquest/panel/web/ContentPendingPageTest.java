package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
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
 * Issue #47 — la page « Changements en attente » et la publication groupée.
 *
 * <h2>Ce que ces tests protègent</h2>
 *
 * <p>Deux promesses du ticket, et elles sont faciles à trahir sans le voir :</p>
 * <ol>
 *   <li>une publication groupée n'est <strong>que</strong> l'orchestration de publications
 *       individuelles sûres — chacune garde ses deux empreintes, donc sa détection de conflit ;</li>
 *   <li>un échec sur une ressource <strong>ne doit pas</strong> empêcher les autres de partir.</li>
 * </ol>
 *
 * <p>Comme pour #227, les formulaires sont extraits de la page <strong>réellement rendue</strong> et
 * soumis tels quels : c'est la seule façon de prouver que les empreintes affichées sont bien celles
 * qui voyagent.</p>
 */
class ContentPendingPageTest {

    private static final String Q_PUBLIER = "id: rpgquest:a_publier\ntitle: \"À publier\"\n";
    private static final String Q_MODIFIEE = "id: rpgquest:modifiee\ntitle: \"Modifiée\"\n";
    private static final String Q_SYNC = "id: rpgquest:deja_sync\ntitle: \"Déjà synchronisée\"\n";
    private static final String S_STORY = "id: une_story\nname: \"Une story\"\n";
    private static final String D_DIALOGUE = "id: rpgquest:un_dialogue\nnodes: {}\n";

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
        Files.writeString(contentRoot.resolve("quests").resolve("a_publier.yml"), Q_PUBLIER);
        Files.writeString(contentRoot.resolve("quests").resolve("modifiee.yml"), Q_MODIFIEE);
        Files.writeString(contentRoot.resolve("quests").resolve("deja_sync.yml"), Q_SYNC);
        Files.writeString(contentRoot.resolve("stories").resolve("une_story.yml"), S_STORY);
        Files.writeString(contentRoot.resolve("dialogues").resolve("un_dialogue.yml"), D_DIALOGUE);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    /**
     * L'état DEV du scénario : {@code a_publier} absente, {@code modifiee} et {@code un_dialogue}
     * différentes, {@code deja_sync} identique et chargée, et un fichier présent seulement sur DEV.
     */
    private String scenarioDevState() {
        return "{\"files\":["
                + file("quests", "modifiee", sha("autre contenu sur dev"))
                + "," + file("quests", "deja_sync", sha(Q_SYNC))
                + "," + file("quests", "venue_du_jar", sha("contenu du jar"))
                + "," + file("dialogues", "un_dialogue", sha("dialogue different sur dev"))
                + "],\"runtimeIds\":{"
                + "\"quests\":[\"rpgquest:modifiee\",\"rpgquest:deja_sync\","
                + "\"rpgquest:venue_du_jar\"],"
                + "\"stories\":[],\"dialogues\":[\"rpgquest:un_dialogue\"]},"
                + "\"runtimeHash\":\"h1\"}";
    }

    // ---- Le rendu ------------------------------------------------------------------------------

    /** Chaque ressource est nommée avec son état : jamais un simple compteur. */
    @Test
    void thePageNamesEveryResourceThatNeedsADecision() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("a_publier"), "source uniquement");
        assertTrue(page.contains("modifiee"), "différente");
        assertTrue(page.contains("une_story"), "source uniquement");
        assertTrue(page.contains("un_dialogue"), "différent");
        assertTrue(page.contains("venue_du_jar"), "hors source, en lecture seule");
        assertTrue(page.contains("Absent de DEV"));
        assertTrue(page.contains("Brouillon non publié"));
        assertTrue(page.contains("Hors source"));
    }

    /**
     * Issue #798 — dès l'arrivée du relevé (sans afficher aucune page), une ressource identique des
     * deux côtés gagne sa version commune ; une ressource différente n'en reçoit pas.
     */
    @Test
    void aReadingRecordsTheCommonVersionOfIdenticalResourcesAsSoonAsItArrives() throws Exception {
        start();
        assertTrue(store.devReference(TestConfig.AGENT_ID, "quests", "deja_sync").isEmpty());

        seed("content.dev.state", scenarioDevState());

        assertEquals(sha(Q_SYNC), store.devReference(TestConfig.AGENT_ID, "quests", "deja_sync")
                .orElseThrow().devSha());
        assertEquals(sha("autre contenu sur dev"), store.devReference(TestConfig.AGENT_ID, "quests", "modifiee")
                .orElseThrow().devSha(), "la référence d'un brouillon n'est pas déplacée");
        assertTrue(store.devReference(TestConfig.AGENT_ID, "quests", "venue_du_jar").isEmpty(),
                "hors source : rien à retenir");
    }

    /** Ce qui est synchronisé n'apparaît pas : la page liste ce qui demande une décision. */
    @Test
    void aSynchronizedResourceIsNotListed() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();
        String table = page.substring(page.indexOf("pending-table"));

        assertFalse(table.contains("deja_sync"), "déjà synchronisée : rien à décider");
    }

    @Test
    void withoutADevReadingThePageSaysSoAndAssertsNothing() throws Exception {
        start();

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("n'est pas encore relevé"));
        assertTrue(page.contains("État DEV"), "et propose de le relever");
    }

    @Test
    void whenEverythingIsSynchronizedThePageSaysSo() throws Exception {
        start();
        // Tout identique et chargé.
        seed("content.dev.state", "{\"files\":["
                + file("quests", "a_publier", sha(Q_PUBLIER))
                + "," + file("quests", "modifiee", sha(Q_MODIFIEE))
                + "," + file("quests", "deja_sync", sha(Q_SYNC))
                + "," + file("stories", "une_story", sha(S_STORY))
                + "," + file("dialogues", "un_dialogue", sha(D_DIALOGUE))
                + "],\"runtimeIds\":{"
                + "\"quests\":[\"rpgquest:a_publier\",\"rpgquest:modifiee\",\"rpgquest:deja_sync\"],"
                + "\"stories\":[\"une_story\"],\"dialogues\":[\"rpgquest:un_dialogue\"]},"
                + "\"runtimeHash\":\"h\"}");

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Tout est synchronisé"));
        assertFalse(page.contains("Publier la sélection"));
    }

    // ---- Filtres -------------------------------------------------------------------------------

    @Test
    void theFamilyFilterKeepsOnlyThatFamily() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID + "&kind=stories").body();
        String table = page.substring(page.indexOf("pending-table"));

        assertTrue(table.contains("une_story"));
        assertFalse(table.contains("a_publier"), "les quêtes sont filtrées");
        assertFalse(table.contains("un_dialogue"));
    }

    @Test
    void theStateFilterKeepsOnlyThatState() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&state=source_only").body();
        String table = page.substring(page.indexOf("pending-table"));

        assertTrue(table.contains("a_publier"));
        assertTrue(table.contains("une_story"));
        assertFalse(table.contains("modifiee"), "les différentes sont filtrées");
    }

    @Test
    void theSearchFiltersByIdentifier() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID + "&qs=story").body();
        String table = page.substring(page.indexOf("pending-table"));

        assertTrue(table.contains("une_story"));
        assertFalse(table.contains("a_publier"));
    }

    @Test
    void aFilterMatchingNothingSaysSoRatherThanShowingEverything() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&qs=nexistepas").body();

        assertTrue(page.contains("Aucune ressource ne correspond"));
        assertFalse(page.contains("pending-table"));
    }

    /** Les filtres sont des liens : l'URL reste partageable, et il n'y a aucun JavaScript. */
    @Test
    void theFiltersAreLinksAndThePageCarriesNoScript() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        // L'esperluette est échappée dans un href : c'est du HTML correct, et c'est donc la forme
        // qu'il faut chercher.
        assertTrue(page.contains("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&amp;kind=quests"), "les filtres sont des liens GET partageables");
        // La CSP du panel est « default-src 'self' » : les scripts de MÊME ORIGINE sont permis (la
        // mise en page charge Bootstrap), c'est le script INLINE qui est interdit. On vérifie donc
        // l'absence d'inline et de gestionnaires d'événements, pas l'absence de toute balise script.
        int at = page.indexOf("pending-table");
        String mine = at < 0 ? page : page.substring(Math.max(0, at - 4000));
        assertFalse(mine.matches("(?s).*<script(?![^>]*\\bsrc=)[^>]*>.*"),
                "aucun script inline dans la partie que nous produisons");
        assertFalse(mine.contains("onclick="), "aucun gestionnaire d'événement en attribut");
        assertFalse(mine.contains("onchange="));
    }

    // ---- Publication groupée -------------------------------------------------------------------

    /**
     * Le vrai formulaire : chaque ligne porte <strong>ses propres</strong> empreintes.
     *
     * <p>C'est ce qui fait qu'un lot n'est que l'orchestration de publications individuelles.</p>
     */
    @Test
    void eachSelectableRowCarriesItsOwnTwoHashes() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        Map<String, String> fields = batchFormFields();

        assertEquals(sha(Q_PUBLIER), fields.get("src_quests/a_publier"));
        assertEquals("", fields.get("dev_quests/a_publier"), "absente de DEV");
        assertEquals(sha(Q_MODIFIEE), fields.get("src_quests/modifiee"));
        assertEquals(sha("autre contenu sur dev"), fields.get("dev_quests/modifiee"));
        assertTrue(fields.containsKey("sel_quests/a_publier"));
        assertTrue(fields.containsKey("_csrf"));
        // Et rien de global ni de dangereux.
        assertFalse(fields.containsKey("yaml"));
        assertFalse(fields.containsKey("all"));
    }

    /** Une ressource hors source n'est pas sélectionnable : il n'y a rien à envoyer. */
    @Test
    void aRuntimeOnlyRowIsNotSelectable() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        Map<String, String> fields = batchFormFields();

        assertFalse(fields.containsKey("sel_quests/venue_du_jar"));
    }

    /**
     * Issue #427 — trois sélections produisent UNE action de lot, jugée d'un bloc par le moteur.
     * Publier ressource par ressource faisait juger des états intermédiaires (pack Lily).
     */
    @Test
    void selectingThreeResourcesQueuesOneBatch() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        HttpResponse<String> res = submitBatch(List.of("quests/a_publier", "quests/modifiee",
                "stories/une_story"));

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("")
                .startsWith("/content/pending?"), res.headers().firstValue("Location").orElse(""));
        assertEquals(0, count("content.publish"), "plus de publications individuelles");
        assertEquals(1, count("content.publish.batch"));

        // Chaque ressource porte SES paramètres, dont le YAML joint côté serveur, dans l'ordre.
        AgentActionRow batch = batchRow();
        Map<String, String> p = batch.params();
        assertEquals("3", p.get("count"));
        assertEquals("quests", p.get("kind_0"));
        assertEquals("a_publier", p.get("id_0"));
        assertEquals(Q_PUBLIER, p.get("yaml_0"));
        assertEquals("", p.get("expected_dev_sha_0"));
        assertEquals(sha("autre contenu sur dev"), p.get("expected_dev_sha_1"));
        assertEquals(S_STORY, p.get("yaml_2"));
        assertEquals("true", p.get("confirm"));
        assertFalse(p.containsKey("dry_run"));
        assertFalse(p.keySet().stream().anyMatch(k -> k.startsWith("expected_source_sha")),
                "l'empreinte source est vérifiée par le panel, jamais transmise");
    }

    /** « Vérifier » part comme un lot en vérification seule. */
    @Test
    void theCheckButtonQueuesADryRun() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        Map<String, String> body = select(batchFormFields(), List.of("quests/a_publier"));
        body.put("mode", "check");

        post("/content/pending/publish", encode(body));

        assertEquals(1, count("content.publish.batch"));
        assertEquals("true", batchRow().params().get("dry_run"));
    }

    /** Il n'y a volontairement pas de « publier tout » : on publie ce qu'on a coché. */
    @Test
    void thereIsNoPublishEverythingButton() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Publier la sélection"));
        assertTrue(page.contains("Vérifier"));
        assertFalse(page.toLowerCase(java.util.Locale.ROOT).contains("publier tout"));
        assertTrue(page.contains("rien ne change en jeu"), "la page dit que c'est tout ou rien");
    }

    @Test
    void submittingWithNoSelectionPublishesNothingAndSaysSo() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        HttpResponse<String> res = submitBatch(List.of());

        assertEquals(303, res.statusCode());
        assertTrue(decode(res.headers().firstValue("Location").orElse(""))
                .contains("Aucune ressource sélectionnée"));
        assertEquals(0, count("content.publish.batch"));
    }

    /**
     * Une ressource dont la source a changé refuse le LOT entier, et la page la nomme : on ne
     * publie pas « ce qui passe » d'un pack (#427).
     */
    @Test
    void oneStaleResourceRefusesTheWholeBatchAndIsNamed() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        Map<String, String> fields = new LinkedHashMap<>(batchFormFields());

        Files.writeString(contentRoot.resolve("quests").resolve("modifiee.yml"),
                Q_MODIFIEE + "# changée entre-temps\n");

        HttpResponse<String> res = post("/content/pending/publish",
                encode(select(fields, List.of("quests/a_publier", "quests/modifiee",
                        "stories/une_story"))));

        String location = decode(res.headers().firstValue("Location").orElse(""));
        assertTrue(location.contains("Rien n'a été publié"), location);
        assertTrue(location.contains("modifiee"), "la ressource fautive est nommée : " + location);
        assertEquals(0, count("content.publish.batch"));
    }

    /** Une clé de ressource forgée refuse le lot. */
    @Test
    void aForgedResourceKeyIsRefused() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        Map<String, String> fields = new LinkedHashMap<>(batchFormFields());
        for (String forged : List.of("quests/../../etc/passwd", "npcs/truc", "quests/",
                "/absolu", "quests/MAJUSCULE")) {
            Map<String, String> body = select(fields, List.of("quests/a_publier"));
            body.put("sel_" + forged, "1");
            body.put("src_" + forged, sha(Q_PUBLIER));
            body.put("dev_" + forged, "");

            post("/content/pending/publish", encode(body));
        }

        assertEquals(0, count("content.publish.batch"), "aucun lot forgé n'est parti");
    }

    /** Une famille hors liste blanche est refusée, même en lot. */
    @Test
    void aForgedFamilyIsRefusedEvenInABatch() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        Map<String, String> body = new LinkedHashMap<>();
        body.put("_csrf", csrf(get("/content/pending?agent=" + TestConfig.AGENT_ID).body()));
        body.put("agent", TestConfig.AGENT_ID);
        for (String kind : List.of("npcs", "items", "mobs", "worlds", "config")) {
            body.put("sel_" + kind + "/truc", "1");
            body.put("src_" + kind + "/truc", sha("x"));
            body.put("dev_" + kind + "/truc", "");
        }

        post("/content/pending/publish", encode(body));

        assertEquals(0, count("content.publish.batch"));
    }

    /**
     * Une même ressource ne peut pas figurer deux fois dans le même lot.
     *
     * <p>Garanti par construction : la case à cocher porte la clé de la ressource comme
     * <strong>nom de champ</strong>, et un formulaire n'a qu'une valeur par nom.</p>
     */
    @Test
    void theSameResourceCannotBeQueuedTwiceInOneBatch() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        Map<String, String> fields = new LinkedHashMap<>(batchFormFields());
        Map<String, String> body = select(fields, List.of("quests/a_publier"));

        String raw = encode(body) + "&sel_quests%2Fa_publier=1&sel_quests%2Fa_publier=1";
        post("/content/pending/publish", raw);

        assertEquals(1, count("content.publish.batch"));
        assertEquals("1", batchRow().params().get("count"), "une seule ressource dans le lot");
    }

    // ---- Vue « pack » (issue #427) -------------------------------------------------------------

    /** La vue pack ne montre que les ressources du pack, déjà cochées, avec un seul bouton. */
    @Test
    void thePackViewPreselectsOnlyThePackResources() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&pack=" + enc("quests/a_publier,stories/une_story")).body();

        assertTrue(page.contains("Mettre l'histoire en jeu"), page);
        assertTrue(Pattern.compile("name=\"sel_quests/a_publier\" value=\"1\" checked").matcher(page).find());
        assertTrue(Pattern.compile("name=\"sel_stories/une_story\" value=\"1\" checked").matcher(page).find());
        assertFalse(page.contains("sel_quests/modifiee"), "rien d'autre que le pack");
        assertTrue(page.contains("name=\"pack\""), "le pack voyage avec le formulaire");
    }

    /**
     * Le parcours complet du propriétaire (#427, validation du 15/10) : vue pack → Vérifier SANS
     * cocher la confirmation → retour sur la MÊME vue pack, sélection intacte, résultat affiché →
     * Mettre l'histoire en jeu → retour sur la vue pack, tout est en jeu.
     */
    @Test
    void theWholePackJourneyStaysOnThePackView() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        String pack = "quests/a_publier,stories/une_story";
        String packUrl = "/content/pending?agent=" + TestConfig.AGENT_ID + "&pack=" + enc(pack);

        // 1) Vérifier : la case de confirmation reste décochée.
        Map<String, String> check = select(formFields(get(packUrl).body()),
                List.of("quests/a_publier", "stories/une_story"));
        check.remove("confirm");
        check.put("mode", "check");
        HttpResponse<String> res = post("/content/pending/publish", encode(check));

        assertEquals(303, res.statusCode());
        String back = res.headers().firstValue("Location").orElse("");
        assertEquals(1, back.chars().filter(c -> c == '?').count(), "une seule chaîne de requête : " + back);
        assertTrue(back.contains("&pack=" + enc(pack)), back);
        assertTrue(back.contains("&pick=" + enc(pack)), back);
        assertEquals("true", batchRow().params().get("dry_run"));

        // 2) La page de retour : vue pack, cases toujours cochées, vérification en cours.
        String waiting = get(back).body();
        assertTrue(waiting.contains("class=\"card pack-publish\""), "toujours la vue pack");
        assertTrue(checked(waiting, "quests/a_publier") && checked(waiting, "stories/une_story"));
        assertTrue(waiting.contains("http-equiv=\"refresh\""), "se recharge tant que le serveur n'a pas répondu");

        respond("content.publish.batch", "SUCCESS", "{\"ok\":true,\"code\":\"CHECKED\",\"dryRun\":true,"
                + "\"filesRestored\":true,\"items\":["
                + "{\"kind\":\"quests\",\"slug\":\"a_publier\",\"status\":\"CHECKED\",\"problems\":[]},"
                + "{\"kind\":\"stories\",\"slug\":\"une_story\",\"status\":\"CHECKED\",\"problems\":[]}]}");
        String verified = get(back).body();
        assertTrue(verified.contains("Vérification réussie"), verified);
        assertFalse(verified.contains("http-equiv=\"refresh\""));
        assertTrue(checked(verified, "quests/a_publier") && checked(verified, "stories/une_story"),
                "la sélection survit à la vérification");
        assertTrue(verified.contains("0/2 en jeu"));

        // 3) Mettre l'histoire en jeu, depuis cette même page.
        Thread.sleep(5);
        HttpResponse<String> published = post("/content/pending/publish", encode(select(
                formFields(verified), List.of("quests/a_publier", "stories/une_story"))));
        String after = published.headers().firstValue("Location").orElse("");
        assertTrue(after.contains("&pack=" + enc(pack)), after);
        assertEquals(2, count("content.publish.batch"));
        respond("content.publish.batch", "SUCCESS", "{\"ok\":true,\"code\":\"PUBLISHED\",\"dryRun\":false,"
                + "\"verifiedAt\":\"2026-10-15T17:00:00Z\",\"items\":["
                + "{\"kind\":\"quests\",\"slug\":\"a_publier\",\"status\":\"CREATED\",\"devShaAfter\":\""
                + sha(Q_PUBLIER) + "\",\"runtimeConfirmed\":true,\"problems\":[]},"
                + "{\"kind\":\"stories\",\"slug\":\"une_story\",\"status\":\"CREATED\",\"devShaAfter\":\""
                + sha(S_STORY) + "\",\"runtimeConfirmed\":true,\"problems\":[]}]}");

        String done = get(after).body();
        assertTrue(done.contains("Histoire mise en jeu"), done);
        assertTrue(done.contains("2/2 en jeu"), "le lot publié compte comme synchronisé avant même le relevé");
        assertFalse(done.contains("sel_quests/a_publier"), "plus rien à cocher");
    }

    /** Publier exige la confirmation, revérifiée côté serveur ; la vue pack et la sélection restent. */
    @Test
    void publishingWithoutTheConfirmationPublishesNothingAndKeepsThePack() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        String pack = "quests/a_publier,stories/une_story";
        Map<String, String> body = select(formFields(get("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&pack=" + enc(pack)).body()), List.of("quests/a_publier"));
        body.remove("confirm");
        body.put("mode", "publish");

        String back = post("/content/pending/publish", encode(body)).headers().firstValue("Location").orElse("");

        assertEquals(0, count("content.publish.batch"));
        assertTrue(back.contains("&pack=" + enc(pack)) && back.contains("&pick=" + enc("quests/a_publier")), back);
        assertTrue(decode(back).contains("Je confirme"), back);
    }

    /** « Vérifier » échappe à la validation HTML de la case de confirmation ; « Publier » non. */
    @Test
    void theCheckButtonDoesNotRequireTheConfirmation() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&pack=" + enc("quests/a_publier,stories/une_story")).body();

        assertTrue(page.contains("value=\"check\" formnovalidate"), page);
        assertFalse(page.contains("value=\"publish\" formnovalidate"));
        assertTrue(Pattern.compile("name=\"confirm\" value=\"true\" required").matcher(page).find());
    }

    /** Une ressource volontairement décochée le reste, et la page prévient que le pack est incomplet. */
    @Test
    void anUncheckedPackResourceStaysUncheckedAndIsFlagged() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&pack=" + enc("quests/a_publier,stories/une_story") + "&pick=" + enc("quests/a_publier")).body();

        assertTrue(checked(page, "quests/a_publier"));
        assertFalse(checked(page, "stories/une_story"));
        assertTrue(page.contains("Non cochée(s)"));
        assertTrue(page.contains("une_story"));
    }

    @Test
    void theReturnUrlCarriesOnlyValidatedContext() {
        assertEquals("/content/pending?agent=dev&pack=quests%2Fa%2Cstories%2Fb&pick=quests%2Fa",
                PanelApp.pendingReturn("dev", "stories/b,quests/a", "quests/a"));
        assertEquals("/content/pending?agent=dev", PanelApp.pendingReturn("dev", "quests/../x", "npcs/a"));
    }

    /** Un filtre de pack forgé est ignoré : la page normale s'affiche. */
    @Test
    void aForgedPackFilterIsIgnored() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID
                + "&pack=" + enc("quests/../x,npcs/truc")).body();

        assertFalse(page.contains("Mettre l'histoire en jeu sur DEV"));
        assertTrue(page.contains("sel_quests/modifiee"));
    }

    /** Le compte rendu d'un lot refusé dit quoi, où et pourquoi — jamais « erreurs listées » à vide. */
    @Test
    void aRefusedBatchExplainsEachProblem() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        submitBatch(List.of("quests/a_publier", "stories/une_story"));
        respond("content.publish.batch", "REJECTED", "{\"ok\":false,\"code\":\"INVALID_CONTENT\","
                + "\"message\":\"Publication refusée par le moteur (INVALID_CONTENT) : rien n'a été mis en jeu.\","
                + "\"dryRun\":false,\"filesRestored\":true,\"items\":["
                + "{\"kind\":\"quests\",\"slug\":\"a_publier\",\"status\":\"REJECTED_BY_ENGINE\","
                + "\"problems\":[\"quests/a_publier.yml : prérequis introuvable pour « rpgquest:a_publier »\"]},"
                + "{\"kind\":\"stories\",\"slug\":\"une_story\",\"status\":\"RESTORED\",\"problems\":[]}],"
                + "\"otherProblems\":[\"quests/autre.yml : champ « steps » manquant\"]}");

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Publication d&#39;un lot de 2 ressource(s)")
                || page.contains("Publication d'un lot de 2 ressource(s)"), page);
        assertTrue(page.contains("refusée par le moteur"));
        assertTrue(page.contains("Fichier quests/a_publier.yml — prérequis introuvable"));
        assertTrue(page.contains("remise comme avant"));
        assertTrue(page.contains("Problèmes hors de ce lot"));
        assertTrue(page.contains("Fichier quests/autre.yml — champ"));
    }

    @Test
    void aMissingCsrfTokenPublishesNothing() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        Map<String, String> body = select(new LinkedHashMap<>(batchFormFields()),
                List.of("quests/a_publier"));
        body.remove("_csrf");

        HttpResponse<String> res = post("/content/pending/publish", encode(body));

        assertEquals(403, res.statusCode());
        assertEquals(0, count("content.publish.batch"));
    }

    @Test
    void getIsRefusedOnThePublishRoute() throws Exception {
        start();

        assertEquals(405, get("/content/pending/publish").statusCode());
    }

    // ---- Résultats par ressource ---------------------------------------------------------------

    /** Le compte rendu nomme chaque ressource, et distingue « en attente » de « réussi ». */
    @Test
    void theOutcomeListShowsEachResourceSeparately() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        submitBatch(List.of("quests/a_publier", "quests/modifiee"));

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Dernières publications"));
        assertTrue(page.contains("a_publier"));
        assertTrue(page.contains("modifiee"));
        assertTrue(page.contains("en attente"), "rien n'est annoncé comme réussi avant traitement");
        assertFalse(page.contains("Publication terminée"),
                "jamais un succès global sans résultat réel");
    }

    // ---- Permissions ---------------------------------------------------------------------------

    /** L'Éditeur de contenu n'a pas le droit de publier : vérifié sur la matrice elle-même. */
    @Test
    void aContentEditorHasNeitherPublishNorRollback() {
        var perms = new com.lodygames.rpgquest.panel.authz.PermissionService();

        assertFalse(perms.canByRoleOnly(Role.CONTENT_EDITOR.name(), Permission.CONTENT_PUBLISH));
        assertFalse(perms.canByRoleOnly(Role.CONTENT_EDITOR.name(), Permission.CONTENT_ROLLBACK));
        assertTrue(perms.canByRoleOnly(Role.CONTENT_EDITOR.name(), Permission.CONTENT_READ),
                "mais il voit l'état");
        assertTrue(perms.canByRoleOnly(Role.ADMIN.name(), Permission.CONTENT_PUBLISH));
        assertTrue(perms.canByRoleOnly(Role.OWNER.name(), Permission.CONTENT_PUBLISH));
    }

    /** Et sur la page réelle : un éditeur voit les états, sans case à cocher ni bouton. */
    @Test
    void aContentEditorSeesTheStatesButCannotPublish() throws Exception {
        start();
        seed("content.dev.state", scenarioDevState());
        // Créé par le propriétaire, via la vraie route de gestion des utilisateurs.
        String token = csrf(get("/users").body());
        post("/users/create", "_csrf=" + token + "&username=editeur47&password="
                + enc("mot-de-passe-de-test-xyz") + "&role=CONTENT_EDITOR");
        jar.clear();
        String loginToken = csrf(get("/login").body());
        post("/login", "username=editeur47&password=" + enc("mot-de-passe-de-test-xyz")
                + "&_csrf=" + loginToken);

        String page = get("/content/pending?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("a_publier"), "il voit les ressources");
        assertTrue(page.contains("Absent de DEV"), "et leur état");
        assertFalse(page.contains("Publier la sélection"), "mais aucun bouton de publication");
        assertFalse(page.contains("sel_quests/a_publier"), "ni de case à cocher");
        assertTrue(page.contains("pas le droit de publier"), "et la page le dit");
    }

    // ---- Harnais -------------------------------------------------------------------------------

    /** Les champs du vrai formulaire de publication groupée, extraits de la page rendue. */
    private Map<String, String> batchFormFields() throws Exception {
        return formFields(get("/content/pending?agent=" + TestConfig.AGENT_ID).body());
    }

    private static boolean checked(String page, String key) {
        return Pattern.compile("name=\"sel_" + Pattern.quote(key) + "\" value=\"1\" checked").matcher(page).find();
    }

    private static Map<String, String> formFields(String page) {
        Matcher forms = Pattern.compile("<form[^>]*action=\"/content/pending/publish\"[^>]*>(.*?)</form>",
                Pattern.DOTALL).matcher(page);
        if (!forms.find()) {
            throw new IllegalStateException("aucun formulaire de publication groupée dans la page");
        }
        String body = forms.group(1);
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher inputs = Pattern.compile("<input\\b[^>]*>").matcher(body);
        while (inputs.find()) {
            String tag = inputs.group(0);
            Matcher n = Pattern.compile("\\bname=\"([^\"]*)\"").matcher(tag);
            if (!n.find()) {
                continue;
            }
            Matcher v = Pattern.compile("\\bvalue=\"([^\"]*)\"").matcher(tag);
            Matcher t = Pattern.compile("\\btype=\"([^\"]*)\"").matcher(tag);
            String type = t.find() ? t.group(1) : "";
            String value = v.find() ? v.group(1) : "";
            // Une case non cochée ne voyage pas : on la retient comme candidate, sans valeur.
            fields.put(n.group(1), "checkbox".equals(type) ? "" : unescape(value));
        }
        return fields;
    }

    /** Construit le corps : les champs cachés, plus les cases réellement cochées. */
    private static Map<String, String> select(Map<String, String> fields, List<String> keys) {
        Map<String, String> body = new LinkedHashMap<>();
        fields.forEach((k, v) -> {
            if (!k.startsWith("sel_")) {
                body.put(k, v);
            }
        });
        for (String key : keys) {
            body.put("sel_" + key, "1");
        }
        // La case « Je confirme », cochée comme le ferait l'utilisateur qui publie.
        if (body.containsKey("confirm")) {
            body.put("confirm", "true");
        }
        return body;
    }

    private HttpResponse<String> submitBatch(List<String> keys) throws Exception {
        return post("/content/pending/publish", encode(select(batchFormFields(), keys)));
    }

    private static String file(String kind, String slug, String sha) {
        return "{\"kind\":\"" + kind + "\",\"slug\":\"" + slug + "\",\"sha256\":\"" + sha
                + "\",\"bytes\":10}";
    }

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

    private static String decode(String raw) {
        return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String unescape(String raw) {
        return raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    /** Enfile une action, la livre, et lui renvoie un succès portant les détails. */
    private void seed(String type, String details) throws Exception {
        String token = csrf(get("/content/pending?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent="
                + TestConfig.AGENT_ID + "&return=/content/pending");
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile(
                "\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"\\s*,\\s*\"type\"\\s*:\\s*\"" + type + "\"")
                .matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action " + type + " : " + poll.body());
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

    private AgentActionRow batchRow() {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals("content.publish.batch")).findFirst().orElseThrow();
    }

    /** Livre la dernière action de ce type et lui renvoie un résultat portant ces détails. */
    private void respond(String type, String status, String details) throws Exception {
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile(
                "\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"\\s*,\\s*\"type\"\\s*:\\s*\"" + Pattern.quote(type) + "\"")
                .matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action " + type + " : " + poll.body());
        }
        String id = m.group(1);
        String result = "{\"action_id\":\"" + id + "\",\"status\":\"" + status + "\",\"value\":null,"
                + "\"message\":\"lot\",\"details\":" + details + "}";
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

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withContentDir(db, "http://127.0.0.1:1/admin/v1",
                contentRoot.toString()),
                new InMemoryAuditLog(),
                new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        // Issue #798 : « modifiee » et « un_dialogue » sont des brouillons — DEV porte encore la
        // version commune connue. Sans elle, la différence serait « Versions différentes ».
        store.adoptDevReference(TestConfig.AGENT_ID, "quests", "modifiee", sha("autre contenu sur dev"), "owner",
                java.time.Instant.now().minusSeconds(3600));
        store.adoptDevReference(TestConfig.AGENT_ID, "dialogues", "un_dialogue", sha("dialogue different sur dev"),
                "owner", java.time.Instant.now().minusSeconds(3600));
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
