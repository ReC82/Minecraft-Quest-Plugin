package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.ai.AiProviderSettings;
import com.lodygames.rpgquest.panel.ai.AiSettingsStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore;
import com.lodygames.rpgquest.panel.json.Json;
import com.lodygames.rpgquest.panel.support.TestConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issues #265 / #267 — l'Atelier de création sur un vrai serveur HTTP, avec un fournisseur d'IA
 * bouchonné qui <strong>compte</strong> ses appels.
 *
 * <p>Reprend toutes les garanties de #256 (aucun appel payant sur une navigation, jeton à usage unique,
 * versions, reprise après redémarrage, récupération sans IA) dans le parcours sans emplacement, et
 * ajoute ce que #265 exige : parcours guidé sans jargon, import non destructif, anciennes adresses.</p>
 */
class WorkshopFlowTest {

    private static final String SITES = "{\"total\":1,\"worlds\":[\"world_hub\"],\"sites\":[{"
            + "\"id\":\"buildsite_0009\",\"name\":\"Bibliothèque du Hub\",\"description\":\"\","
            + "\"aiBrief\":\"Bibliothèque médiévale fantastique. Bâtiment sur deux étages, pierre "
            + "claire et bois sombre, façade à l'ouest. Grande salle de lecture, escalier "
            + "intérieur.\",\"world\":\"world_hub\",\"x\":784,\"y\":67,\"z\":-693,"
            + "\"facing\":\"WEST\",\"status\":\"EMPTY\",\"createdBy\":\"Lody\","
            + "\"createdAt\":\"2026-10-11T18:12:06Z\",\"worldLoaded\":true}]}";

    private static final String BRIEF = "Une bibliothèque médiévale sur deux étages, en pierre claire "
            + "et bois sombre, avec un escalier intérieur et des fenêtres.";

    private static final String PLAN = "id: bibliotheque_v2\nname: \"Bibliothèque\"\n"
            + "kind: habitable\nfront: WEST\nsize:\n  x: 19\n  y: 14\n  z: 19\nsteps:\n"
            + "  - op: box\n    from: [0, 0, 0]\n    to: [18, 0, 18]\n    block: minecraft:stone_bricks";

    private static final String AUDIT_OK = "{\"written\":false,\"sizeX\":19,\"sizeY\":14,"
            + "\"sizeZ\":19,\"warnings\":[],\"metrics\":{\"reachableFloors\":1,\"lightSources\":80,"
            + "\"solidCells\":2107,\"glazedCells\":22,\"floors\":1,\"glazedFacades\":3},"
            + "\"kind\":\"bâtiment habitable\",\"code\":\"OK\",\"blocks\":2107,"
            + "\"buildingId\":\"bibliotheque_v2\",\"codes\":[],\"exists\":false,"
            + "\"message\":\"Plan conforme.\"}";

    private static final String AUDIT_REFUSED = "{\"code\":\"NO_ENTRANCE\",\"codes\":[\"NO_ENTRANCE\"],"
            + "\"message\":\"Aucune entrée sur la façade avant.\",\"buildingId\":\"bibliotheque_v2\","
            + "\"blocks\":1500,\"warnings\":[],\"metrics\":{\"floors\":1,\"reachableFloors\":1}}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private HttpServer ai;
    private final AtomicInteger aiCalls = new AtomicInteger();
    /** Le dernier corps reçu par le faux fournisseur : ce que l'IA a réellement lu. */
    private volatile String lastAiRequest = "";
    /** #747 : la réponse du faux fournisseur ; {@code null} = le plan habituel. */
    private volatile String aiContent;
    /** #747 : réponses à rendre dans l'ordre, avant {@link #aiContent}. */
    private final java.util.Queue<String> aiAnswers = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private String dbPath;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        if (ai != null) {
            ai.stop(0);
        }
    }

    // ---- Créer sans emplacement ------------------------------------------------------------------

    @Test
    void aProjectIsCreatedWithoutAnySiteAndWithoutCallingTheAi() throws Exception {
        start();
        String index = get("/buildings/workshop").body();
        assertTrue(index.contains("Atelier de création"), excerpt(index));
        assertTrue(index.contains("Aucun emplacement n&#39;est nécessaire") || index.contains("Aucun emplacement n'est nécessaire"));

        String project = createProject("Bibliothèque", BRIEF);

        String page = get(project).body();
        assertTrue(page.contains("aria-current=\"step\""), "les étapes sont affichées");
        assertTrue(page.contains("Créer un plan avec l&#39;IA") || page.contains("Créer un plan avec l'IA"));
        assertEquals(0, aiCalls.get(), "créer un projet est gratuit");
        assertTrue(get("/buildings/workshop").body().contains("Prêt à demander un plan"));
    }

    @Test
    void aProjectWithoutDescriptionAsksForOneAndNeverCallsTheAi() throws Exception {
        start();
        String project = createProject("Tour", "");
        String page = get(project).body();
        assertTrue(page.contains("Décrivez d&#39;abord le bâtiment") || page.contains("Décrivez d'abord le bâtiment"));

        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=propose&id=" + id(project)
                + "&provider=openai&request_token=t1");
        assertEquals(0, aiCalls.get());
    }

    // ---- Aucun appel payant sur une navigation ------------------------------------------------

    @Test
    void aPaidPlanBecomesAVersionAndNoNavigationCallsTheAiAgain() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String withDraft = propose(project);
        assertEquals(1, aiCalls.get());

        String page = get(withDraft).body();
        assertTrue(mainCard(page).contains("Vérification en cours"),
                "#282 : un plan reçu part aussitôt en vérification, sans clic");
        get(withDraft);
        get("/buildings/workshop");
        get(project);
        assertEquals(1, aiCalls.get(), "reprendre, recharger, revenir : jamais facturé");
    }

    /** #655 : la vérification en cours est suivie jusqu'à son état terminal, sans clic « Rafraîchir ». */
    @Test
    void aPendingVerificationIsTrackedUntilItsTerminalState() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        String card = mainCard(get(withDraft).body());
        assertTrue(card.contains("Vérification en cours"), card);
        AgentActionRow audit = latest("building.plan.audit");
        assertTrue(card.contains("data-pa-track-action=\"" + audit.id() + "\" data-pa-track-reload=\"terminal\""),
                card);
    }

    /**
     * #655 — Boulangerie de Central : la description demande l'entrée à l'est, le sélecteur dit
     * ouest. Aucun geste payant ne part ; la page dit pourquoi et comment trancher.
     */
    @Test
    void aBriefThatContradictsTheEntranceSelectorSuspendsEveryPaidGesture() throws Exception {
        start();
        String project = createProject("Boulangerie", "Petite boulangerie médiévale. Façade principale et "
                + "entrée à l’EST, arrière à l’OUEST.");
        String page = get(project).body();
        assertTrue(page.contains("Façade d&#39;entrée contradictoire") || page.contains("Façade d'entrée contradictoire"),
                excerpt(page));
        for (String action : List.of("propose", "design", "restart", "design_write")) {
            HttpResponse<String> res = post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action="
                    + action + "&id=" + id(project) + "&provider=openai&consent=yes&confirm=yes&quality=STANDARD"
                    + "&request_token=" + token(page) + "-" + action);
            assertEquals(303, res.statusCode(), action);
            assertTrue(decoded(location(res)).contains("Rien n'a été demandé à l'IA"), action + " : "
                    + decoded(location(res)));
        }
        assertEquals(0, aiCalls.get(), "aucun appel facturé");
    }

    @Test
    void resubmittingTheSamePaidFormNeverCallsTwice() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String page = get(project).body();
        String form = "_csrf=" + csrf(page) + "&_action=propose&id=" + id(project) + "&provider=openai"
                + "&request_token=" + token(page);

        String first = location(post("/buildings/workshop/project", form));
        String second = location(post("/buildings/workshop/project", form));

        assertEquals(1, aiCalls.get());
        assertEquals(draftId(first), draftId(second));
        assertTrue(decoded(second).contains("Aucun nouvel appel"));
    }

    // ---- #739 : le journal IA téléchargeable ------------------------------------------------------

    @Test
    void theAiJournalIsDownloadedFromTheProjectPageSurvivesARestartAndIsReservedToAiUsers() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String page = get(project).body();
        assertTrue(page.contains("/buildings/workshop/journal?id=" + id(project)), "bouton sur la fiche du projet");
        assertTrue(page.contains("Télécharger le journal IA"));
        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=propose&id=" + id(project)
                + "&provider=openai&request_token=" + token(page));
        assertEquals(1, aiCalls.get());

        HttpResponse<String> res = get("/buildings/workshop/journal?id=" + id(project));
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; charset=utf-8"));
        String disposition = res.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.matches("attachment; filename=\"journal-ia-bibliotheque-v2-\\d{8}-\\d{6}\\.txt\""),
                disposition);
        String text = res.body();
        assertTrue(text.contains("NOTICE") && text.contains("INDEX CHRONOLOGIQUE"), text);
        assertTrue(text.contains("MANUEL — CRÉER UN PLAN"));
        assertTrue(text.contains("REQUÊTE") && text.contains("RÉPONSE"));
        assertTrue(text.contains("[rôle : system]") && text.contains("[rôle : user]"));
        assertTrue(text.contains("[rôle : assistant] choices[0].message.content"), "la réponse reçue, entière");
        assertFalse(text.contains("sk-test"), "aucun secret dans l'export");
        int events = text.split("\nn°", -1).length;

        restart();
        String again = get("/buildings/workshop/journal?id=" + id(project)).body();
        assertEquals(events, again.split("\nn°", -1).length, "le journal survit au redémarrage du panel");

        // Un testeur (sans AI_USE) ne lit pas le journal.
        post("/users/create", "_csrf=" + csrf(get("/users").body()) + "&username=tess&password="
                + enc("Tess-Password-2026!") + "&role=TESTER");
        jar.clear();
        post("/login", "username=tess&password=" + enc("Tess-Password-2026!") + "&_csrf=" + csrf(get("/login").body()));
        HttpResponse<String> denied = get("/buildings/workshop/journal?id=" + id(project));
        assertEquals(403, denied.statusCode());
        assertFalse(denied.body().contains("JOURNAL IA"));
    }

    // ---- #747 (23/10/2026) : réponse sans plan, reprise avec le même contexte ---------------------

    /**
     * Le parcours de l'Église du hub, sans appel payant : « Améliorer avec l'IA » reçoit « I'm sorry,
     * I can't assist with that. ». Aucune version n'est créée, la version source reste active, la page
     * le dit en rouge et propose « Réessayer avec le même contexte » — qui renvoie la même version
     * source, le même mode et les mêmes consignes, et le journal le prouve pièce par pièce.
     */
    @Test
    void aRefusedImprovementKeepsTheLastPlanAndIsRetriedWithTheSameContext() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String withDraft = propose(project);
        String source = draftId(withDraft);
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(withDraft).body();

        aiContent = "I'm sorry, I can't assist with that.";
        HttpResponse<String> refused = post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=correct&id=" + id(project) + "&draft=" + source + "&version="
                + field(formAround(page, "value=\"correct\""), "name=\"version\" value=\"([^\"]+)\"")
                + "&provider=openai&mode=improve&notes=" + enc("Garder la tour.") + "&request_token=" + token(page));
        assertEquals(303, refused.statusCode());
        String back = location(refused);
        assertEquals(source, draftId(back), "la version source reste affichée et active");
        assertTrue(decoded(back).contains("err=") && decoded(back).contains("Refus du modèle"), decoded(back));
        assertFalse(back.contains("ok="), "pas de bandeau vert");
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, id(project)).size(), "aucune version créée");
        assertEquals(2, aiCalls.get());

        String failed = get(back).body();
        assertTrue(failed.contains("Le dernier appel à l") && failed.contains("Réessayer avec le même contexte"), excerpt(failed));
        String retryForm = formAround(failed, "name=\"retry_of\"");
        assertTrue(retryForm.contains("value=\"improve\"") && retryForm.contains("Garder la tour."), retryForm);
        assertTrue(retryForm.contains("name=\"draft\" value=\"" + source + "\""), retryForm);
        assertTrue(failed.contains("Repartir de zéro"), "repartir de zéro reste un geste séparé");
        String journal = get("/buildings/workshop/journal?id=" + id(project)).body();
        assertTrue(journal.contains("APPEL ÉCHOUÉ : Refus du modèle"), journal);
        assertTrue(journal.contains("version active conservée : V-" + source.substring(0, 8)), journal);
        assertEquals(2, aiCalls.get(), "afficher l'échec ne relance rien");

        aiContent = null;
        HttpResponse<String> retried = post("/buildings/workshop/project", "_csrf=" + csrf(failed)
                + "&_action=correct&id=" + id(project) + "&draft=" + source
                + "&version=" + field(retryForm, "name=\"version\" value=\"([^\"]+)\"")
                + "&mode=" + field(retryForm, "name=\"mode\" value=\"([^\"]+)\"")
                + "&retry_of=" + field(retryForm, "name=\"retry_of\" value=\"([^\"]+)\"")
                + "&notes=" + enc("Garder la tour.") + "&provider=openai&request_token=" + token(retryForm));
        assertEquals(3, aiCalls.get());
        BuildingPlanDraft created = drafts().find(draftId(location(retried))).orElseThrow();
        assertEquals(source, created.parentId(), "la reprise repart de la version source");
        assertTrue(lastAiRequest.contains("Garder la tour."), "les consignes repartent");
        String after = get("/buildings/workshop/journal?id=" + id(project)).body();
        assertTrue(after.contains("Reprise de l'appel échoué"), after);
        Matcher same = Pattern.compile("Reprise de l'appel échoué \\S+ avec le même contexte — identiques : ([^\n]+)")
                .matcher(after);
        assertTrue(same.find(), after);
        assertTrue(same.group(1).contains("previousPlan") && same.group(1).contains("refusal")
                && same.group(1).contains("images") && same.group(1).contains("mode"), same.group(1));
        assertFalse(same.group(1).contains("DIFFÉRENTES"), same.group(1));
    }

    /** Une réponse coupée par la limite de longueur ou vide n'est pas non plus une version (#747). */
    @Test
    void anEmptyAnswerCreatesNoVersionEvenForANewPlan() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        aiContent = "";
        String page = get(project).body();
        HttpResponse<String> res = post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=propose&id=" + id(project) + "&provider=openai&request_token=" + token(page));
        assertTrue(decoded(location(res)).contains("Réponse vide"), decoded(location(res)));
        assertTrue(drafts().forProject(TestConfig.AGENT_ID, id(project)).isEmpty());
        String failed = get(project).body();
        assertTrue(failed.contains("Réessayer avec le même contexte"), excerpt(failed));
        assertEquals(1, aiCalls.get());
    }

    // ---- Vérifier, en français ---------------------------------------------------------------

    @Test
    void aRefusedPlanIsExplainedInPlainFrenchWithoutCodesInTheMainCard() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification est partie d'elle-même avec le plan.
        assertTrue(mainCard(get(withDraft).body()).contains("Vérification en cours"));

        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(withDraft).body();
        String main = mainCard(page);
        assertTrue(main.contains("pas de porte utilisable"), main);
        assertTrue(main.contains("Améliorer avec l&#39;IA") || main.contains("Améliorer avec l'IA"));
        // #747 : la reprise montre ce qui partira — mémoire, conformité, images — et sa nature.
        assertTrue(main.contains("Nature de la reprise") && main.contains("Mémoire des tentatives"), main);
        assertTrue(main.contains("Aucune image de référence attachée au projet"), main);
        for (String jargon : new String[] {"NO_ENTRANCE", "PLAN_INVALID", "YAML", "audit", "brief"}) {
            assertFalse(main.contains(jargon), "jargon dans le parcours principal : " + jargon + " — "
                    + main.substring(Math.max(0, main.indexOf(jargon) - 200), Math.min(main.length(), main.indexOf(jargon) + 100)));
        }
        assertTrue(page.contains("NO_ENTRANCE"), "le code brut reste dans les détails techniques");
        assertEquals(1, aiCalls.get());
    }

    @Test
    void aVerifiedPlanOffersToSaveInTheLibraryAndSaysWhenItDiffersFromTheDescription() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification et l'aperçu partent d'eux-mêmes avec le plan.
        answer(latest("building.plan.audit"), AUDIT_OK);

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Enregistrer dans la bibliothèque"), main);
        assertTrue(main.contains("ne correspond pas tout à fait à votre description"),
                "deux étages demandés, un seul mesuré");
        assertTrue(main.contains("demande 2 niveaux"), main);
    }

    /** La grille d'un aperçu : deux niveaux, un mur en L, l'entrée à l'ouest. */
    private static final String PREVIEW_WITH_GRID = "{\"code\":\"OK\",\"front\":\"WEST\",\"plans\":[],"
            + "\"elevations\":[],\"voxels\":{\"palette\":[{\"symbol\":\"A\",\"block\":\"stone_bricks\","
            + "\"role\":\"#\",\"shape\":\"full\"},{\"symbol\":\"B\",\"block\":\"glass_pane\",\"role\":\"o\","
            + "\"shape\":\"full\"}],\"layers\":[[\"AA\",\"AB\"],[\"A.\",\"A.\"]],\"omittedReason\":\"\"}}";

    /**
     * Issue #273 : vérifier, c'est aussi voir. La vérification demande l'aperçu (gratuit) sur la même
     * version, et la page dessine la grille que le serveur a renvoyée — sans aucun appel à l'IA.
     */
    @Test
    void aVerificationAlsoDrawsTheBuildingFromTheServerGrid() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification et l'aperçu partent d'eux-mêmes avec le plan.
        assertEquals(1, store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals("building.plan.preview")).count(), "l'aperçu part avec la vérification");
        assertTrue(get(withDraft).body().contains("Aperçu en cours de calcul"));

        answer(latest("building.plan.audit"), AUDIT_OK);
        answer(latest("building.plan.preview"), PREVIEW_WITH_GRID);
        String page = get(withDraft).body();
        assertTrue(page.contains("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"iso-view\""), "le bâtiment est dessiné");
        assertTrue(page.contains("Vu depuis le sud-ouest"), "par défaut, la vue montre l'entrée");
        assertTrue(page.contains("L&#39;entrée (côté ouest) est la face à gauche")
                || page.contains("L'entrée (côté ouest) est la face à gauche"), "et le dit");
        assertTrue(page.contains("fill-opacity"), "la vitre est translucide");
        assertTrue(page.contains("stone bricks"), "la légende nomme les matériaux");
        assertFalse(mainCard(page).contains("<svg xmlns"), "l'aperçu est à part, l'action principale reste seule");

        String turned = get(withDraft + "&view=2&cut=0").body();
        assertTrue(turned.contains("Vu depuis le nord-ouest"), "on tourne autour du bâtiment");
        assertTrue(turned.contains("tout ce qui dépasse le niveau 0 est retiré"), "on voit l'intérieur");
        assertTrue(get(withDraft + "&view=99&cut=-7").body().contains("Vu depuis le sud-ouest"),
                "une valeur hors bornes revient à la vue par défaut");
        assertEquals(1, aiCalls.get(), "tourner et couper n'appellent jamais l'IA");
    }

    @Test
    void anOlderPluginWithoutTheGridSaysWhatIsMissing() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification et l'aperçu partent d'eux-mêmes avec le plan.
        answer(latest("building.plan.preview"), "{\"code\":\"OK\",\"plans\":[],\"elevations\":[]}");
        String page = get(withDraft).body();
        assertTrue(page.contains("version plus récente du plugin"), "le manque est dit, pas maquillé");
        assertFalse(page.contains("class=\"iso-view\""));
    }

    @Test
    void aTakenLibraryNameBlocksSavingAndSaysWhatToDo() throws Exception {
        start();
        String library = "{\"buildings\":[{\"id\":\"bibliotheque_v2\",\"name\":\"V1\",\"sizeX\":19,"
                + "\"sizeY\":14,\"sizeZ\":19,\"front\":\"WEST\",\"schematic\":\"b.schem\","
                + "\"schematicPresent\":true,\"version\":1}],\"total\":1}";
        String lid = store.createAction(TestConfig.AGENT_ID, "building.definition.list", Map.of(), "owner");
        answer(store.action(lid).orElseThrow(), library.replace("{\"buildings\"", "{\"code\":\"OK\",\"buildings\""));
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification et l'aperçu partent d'eux-mêmes avec le plan.
        answer(latest("building.plan.audit"), AUDIT_OK);

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("porte déjà le nom"), main);
        assertFalse(main.contains("value=\"generate\""), "aucun bouton qui remplacerait l'autre bâtiment");
    }

    @Test
    void savingInTheLibraryNeverReplacesAnything() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification et l'aperçu partent d'eux-mêmes avec le plan.
        answer(latest("building.plan.audit"), AUDIT_OK);

        gesture(get(withDraft).body(), "generate");

        AgentActionRow queued = latest("building.generate");
        assertEquals(PLAN, queued.params().get("plan"));
        assertFalse(queued.params().containsKey("replace"), "jamais de remplacement depuis le parcours");
        answer(queued, "{\"code\":\"OK\",\"buildingId\":\"bibliotheque_v2\"}");
        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Enregistré dans la bibliothèque"), main);
        assertTrue(main.contains("Choisir un emplacement"));
    }

    /** #747 : un refus dont le seul défaut est contournable, tel que le plugin le déclare. */
    private static final String AUDIT_ACCEPTABLE = "{\"code\":\"ARCHITECTURE_REFUSED\","
            + "\"codes\":[\"UNSUPPORTED_BLOCK\"],\"message\":\"Une lanterne pend sans support.\","
            + "\"buildingId\":\"bibliotheque_v2\",\"blocks\":1500,\"warnings\":[],"
            + "\"acceptable\":{\"UNSUPPORTED_BLOCK\":\"peut tomber en objet\"},"
            + "\"metrics\":{\"floors\":1,\"reachableFloors\":1}}";

    /**
     * #747 (23/10/2026) : « si on a un aperçu on devrait pouvoir le construire avec un avertissement ».
     * Le défaut et son effet sont affichés, la case est obligatoire, les codes partent au serveur, et
     * la version enregistrée reste « avec avertissements ».
     */
    @Test
    void aCompleteGridWithAcceptableDefectsCanBeSavedOnlyAfterExplicitConfirmation() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_ACCEPTABLE);
        String page = get(withDraft).body();
        assertTrue(page.contains("Enregistrer malgré les avertissements"), mainCard(page));
        assertTrue(page.contains("peut tomber en objet"), "l'effet en jeu est affiché");
        String form = formAround(page, "name=\"accept_defects\"");
        String base = "_csrf=" + csrf(page) + "&_action=generate"
                + "&id=" + field(form, "name=\"id\" value=\"([^\"]+)\"")
                + "&draft=" + field(form, "name=\"draft\" value=\"([^\"]+)\"")
                + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&plan=" + enc(unescape(field(form, "name=\"plan\" value=\"([^\"]*)\"")))
                + "&accept_defects=UNSUPPORTED_BLOCK";

        post("/buildings/workshop/project", base);
        assertTrue(store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .noneMatch(a -> "building.generate".equals(a.type())), "sans la case, rien ne part");
        post("/buildings/workshop/project", base.replace("UNSUPPORTED_BLOCK", "DARK_AREA") + "&accept_confirm=yes");
        assertTrue(store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .noneMatch(a -> "building.generate".equals(a.type())), "d'autres défauts que ceux vérifiés : refus");

        post("/buildings/workshop/project", base + "&accept_confirm=yes");
        AgentActionRow queued = latest("building.generate");
        assertEquals("UNSUPPORTED_BLOCK", queued.params().get("accept_defects"));
        store.recordResult(queued.id(), TestConfig.AGENT_ID, AgentActionStatus.SUCCESS, "", "ok",
                "{\"action_id\":\"" + queued.id() + "\",\"status\":\"SUCCESS\",\"message\":\"ok\","
                        + "\"details\":{\"code\":\"GENERATED_WITH_WARNINGS\",\"buildingId\":\"bibliotheque_v2\","
                        + "\"message\":\"AVEC AVERTISSEMENTS ACCEPTÉS (UNSUPPORTED_BLOCK)\"}}", Instant.now());
        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Enregistré avec avertissements"), main);
        assertTrue(main.contains("Version non conforme"), main);
        assertEquals(1, aiCalls.get(), "aucun appel à l'IA pour enregistrer");
    }

    /** #747 : une impossibilité (plan illisible) n'offre jamais l'enregistrement malgré défauts. */
    @Test
    void anImpossiblePlanNeverOffersToSaveWithWarnings() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        assertFalse(get(withDraft).body().contains("Enregistrer malgré les avertissements"));
    }

    /** Le nom technique du PROJET s'impose au plan de l'IA — sans quoi on viserait la V1. */
    @Test
    void theProjectNameReplacesTheModelsIdentifierAsANewVersion() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String auditId = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", PLAN.replace("id: bibliotheque_v2", "id: bibliotheque_du_hub")), "owner");
        answer(store.action(auditId).orElseThrow(), AUDIT_OK);
        String page = get(project).body();
        String recovered = location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=recover&id=" + id(project) + "&action_id=" + auditId + "&confirm=1"));

        gesture(get(recovered).body(), "generate");

        AgentActionRow queued = latest("building.generate");
        assertTrue(queued.params().get("plan").startsWith("id: bibliotheque_v2\n"), queued.params().get("plan"));
        BuildingPlanDraft d = drafts().find(draftId(recovered)).orElseThrow();
        assertEquals(2, d.version(), "l'alignement est une nouvelle version, tracée");
        assertEquals(2, drafts().revisions(d.id()).size());
    }

    /**
     * #747 — Cabane du Hub (23/10/2026) : « pièce vide » puis, dans « Améliorer », un lit et un coffre.
     * La consigne part comme EXIGENCE ACTUALISÉE prioritaire, une version acceptée n'est plus dite
     * « refusée », et la consigne repart avec la reprise suivante, à côté de la nouvelle. L'essai avec un
     * autre modèle ne vaut que pour son appel.
     */
    @Test
    void ownerInstructionsBecomeLastingPriorityRequirementsAndATrialModelIsUsedOnlyForItsCall() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_OK);
        String page = get(withDraft).body();
        String form = formAround(page, "value=\"correct\"");
        assertTrue(form.contains("Essai avec un autre modèle"), "le choix d'essai est proposé");

        String first = location(post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=correct&id="
                + id(withDraft) + "&draft=" + draftId(withDraft) + "&version=1&provider=openai&request_token="
                + token(form) + "&notes=" + enc("Ajoute un lit rouge et un coffre.")
                + "&trial_model=gpt-5-mini&trial_max_tokens=16000"));
        assertEquals(2, aiCalls.get());
        assertTrue(lastAiRequest.contains("\"model\":\"gpt-5-mini\""), lastAiRequest.substring(0, 200));
        assertTrue(lastAiRequest.contains("\"max_completion_tokens\":16000"));
        assertTrue(lastAiRequest.contains("EXIGENCES ACTUALISÉES PAR L'ADMINISTRATEUR"));
        assertTrue(lastAiRequest.contains("PASSÉ LES CONTRÔLES DU SERVEUR"), "une version acceptée n'est pas « refusée »");
        assertFalse(lastAiRequest.contains("A ÉTÉ REFUSÉE PAR LE SERVEUR"));

        answer(latest("building.plan.audit"), AUDIT_OK);
        page = get(first).body();
        form = formAround(page, "value=\"correct\"");
        assertTrue(page.contains("exigence(s) actualisée(s) conservée(s)"), "l'écran dit ce qui repart");
        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=correct&id=" + id(withDraft)
                + "&draft=" + draftId(first) + "&version=1&provider=openai&request_token=" + token(form)
                + "&notes=" + enc("Ajoute aussi un four."));
        assertEquals(3, aiCalls.get());
        assertTrue(lastAiRequest.contains("\"model\":\"gpt-4o\""), "sans essai : le modèle configuré");
        assertTrue(lastAiRequest.contains("Ajoute un lit rouge et un coffre.")
                && lastAiRequest.contains("Ajoute aussi un four."), "les deux consignes repartent");
        int block = lastAiRequest.indexOf("EXIGENCES ACTUALISÉES");
        assertTrue(lastAiRequest.indexOf("Ajoute un lit rouge", block) < lastAiRequest.indexOf("Ajoute aussi un four", block),
                "de la plus ancienne à la plus récente");
    }

    /** #747 (24/10) : un envoi sans texte de plan ne crée jamais de révision vide. */
    @Test
    void anEmptySubmittedPlanNeverBecomesARevision() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        String page = get(withDraft).body();
        String shown = location(post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=audit&id="
                + id(withDraft) + "&draft=" + draftId(withDraft) + "&version=1&plan="));
        BuildingPlanDraft d = drafts().find(draftId(withDraft)).orElseThrow();
        assertEquals(1, d.version(), "aucune révision");
        assertEquals(PLAN, d.plan());
        assertTrue(get(shown).body().contains("Le plan envoyé est vide"));
    }

    /**
     * #747 (24/10) : version acceptée, description non réalisée (torches murales demandées, aucune posée) —
     * « Améliorer » part sans consigne, avec les écarts mesurés comme motif, et rien n'est rangé comme consigne.
     */
    @Test
    void anAcceptedVersionMissingARequirementIsImprovedWithItsMeasuredGapsAsMotive() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", "Une petite bibliothèque de plain-pied en pierre, "
                + "éclairée par deux torches fixées aux murs."));
        answer(latest("building.plan.audit"), AUDIT_OK);
        String page = get(withDraft).body();
        assertTrue(mainCard(page).contains("la description n&#39;est pas entièrement réalisée")
                || mainCard(page).contains("la description n'est pas entièrement réalisée"), mainCard(page));
        String form = formAround(page, "value=\"correct\"");
        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=correct&id=" + id(withDraft)
                + "&draft=" + draftId(withDraft) + "&version=1&provider=openai&request_token=" + token(form));
        assertEquals(2, aiCalls.get(), "l'amélioration est partie sans consigne");
        assertTrue(lastAiRequest.contains("la description n'est pas réalisée") && lastAiRequest.contains("Torches murales"),
                lastAiRequest.substring(0, Math.min(400, lastAiRequest.length())));
        assertFalse(lastAiRequest.contains("EXIGENCES ACTUALISÉES PAR L'ADMINISTRATEUR"), "aucune consigne n'a été inventée");
    }

    /** #747 : un modèle d'essai mal formé est refusé avant tout appel. */
    @Test
    void aMalformedTrialModelIsRefusedWithoutAnyCall() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(withDraft).body();
        String form = formAround(page, "value=\"correct\"");
        String shown = location(post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=correct&id="
                + id(withDraft) + "&draft=" + draftId(withDraft) + "&version=1&provider=openai&request_token="
                + token(form) + "&trial_model=" + enc("gpt 5; rm")));
        assertEquals(1, aiCalls.get());
        assertTrue(get(shown).body().contains("Modèle d&#39;essai invalide")
                || get(shown).body().contains("Modèle d'essai invalide"));
    }

    // ---- Versions, redémarrage --------------------------------------------------------------

    @Test
    void aStaleManualEditIsRefusedAndTheTypedTextIsKept() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        String tabA = get(withDraft).body();
        String tabB = get(withDraft).body();
        editPlan(tabA, "save", PLAN + "\n# onglet A");

        HttpResponse<String> late = editPlan(tabB, "audit", PLAN + "\n# onglet B");

        assertEquals(200, late.statusCode());
        assertTrue(late.body().contains("Vos modifications n&#39;ont pas été enregistrées")
                || late.body().contains("Vos modifications n'ont pas été enregistrées"));
        assertTrue(late.body().contains("# onglet B"));
        assertTrue(drafts().find(draftId(withDraft)).orElseThrow().plan().endsWith("# onglet A"));
    }

    @Test
    void aProjectAndItsVerificationSurviveAPanelRestart() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification et l'aperçu partent d'eux-mêmes avec le plan.
        answer(latest("building.plan.audit"), AUDIT_OK);

        restart();

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Enregistrer dans la bibliothèque"), main);
        assertEquals(1, aiCalls.get());
    }

    @Test
    void anImprovementIsANewVersionAndLeavesTheCurrentOneUntouched() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        // #282 : la vérification et l'aperçu partent d'eux-mêmes avec le plan.
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(withDraft).body();
        String form = formAround(page, "value=\"correct\"");
        assertTrue(form.contains("Aucune entrée sur la façade avant."), "motif prérempli");

        String body = "_csrf=" + csrf(page) + "&_action=correct&id=" + id(withDraft) + "&draft="
                + draftId(withDraft) + "&version=1&provider=openai&request_token=" + token(form)
                + "&reason=" + enc("Ajouter une porte.");
        String next = location(post("/buildings/workshop/project", body));
        post("/buildings/workshop/project", body);

        assertEquals(2, aiCalls.get());
        assertNotEquals(draftId(withDraft), draftId(next));
        assertEquals(BuildingPlanDraft.Origin.AI_CORRECTION, drafts().find(draftId(next)).orElseThrow().origin());
        assertEquals(1, drafts().find(draftId(withDraft)).orElseThrow().version());
    }

    // ---- #282 : plus d'intermédiaire entre le validateur et l'IA -------------------------------

    /**
     * Le parcours exact du 12/10/2026 (« Maison du bibliothécaire ») : génération → audit en échec
     * PLAN_INVALID → « Améliorer avec l'IA » SANS commentaire → nouvel audit, sans que personne ne
     * recopie l'erreur du serveur.
     */
    @Test
    void anAiCorrectionWithoutAnyCommentSendsTheServerDiagnosisAndIsVerifiedAgain() throws Exception {
        start();
        String withDraft = propose(createProject("Maison du bibliothécaire", BRIEF));
        assertEquals(1, aiCalls.get());

        // Vérification et aperçu sont partis d'eux-mêmes, avec les bornes du projet.
        AgentActionRow firstAudit = latest("building.plan.audit");
        assertEquals("19", firstAudit.params().get("max_width"));
        assertEquals("19", firstAudit.params().get("max_depth"), "#305 : un formulaire sans Z reste carré");
        assertEquals("14", firstAudit.params().get("max_height"));
        assertEquals(1, store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals("building.plan.preview")).count());
        assertTrue(mainCard(get(withDraft).body()).contains("Vérification en cours"));

        // Le refus réel, avec un message plus long que les 500 caractères du résultat d'action.
        String diagnosis = "Plan refusé : Instruction 18 (GABLE) : la zone 0/5/0 → 10/8/10 sort du "
                + "volume 11 × 8 × 11. " + "Détail géométrique. ".repeat(40);
        agentAnswers(firstAudit.id(), "FAILED", diagnosis.substring(0, 200), "{\"code\":\"PLAN_INVALID\","
                + "\"codes\":[\"PLAN_INVALID\"],\"message\":" + Json.write(diagnosis) + ",\"warnings\":[],"
                + "\"metrics\":{}}");

        String page = get(withDraft).body();
        String form = formAround(page, "value=\"correct\"");
        assertTrue(form.contains("Instruction 18 (GABLE)"), "le diagnostic est joint d'office");
        assertTrue(form.contains("(facultatif)"), "le commentaire est facultatif");

        String body = "_csrf=" + csrf(page) + "&_action=correct&id=" + id(withDraft) + "&draft="
                + draftId(withDraft) + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&provider=openai&request_token=" + token(form) + "&notes=";
        String next = location(post("/buildings/workshop/project", body));
        assertEquals(2, aiCalls.get(), "la correction est partie sans commentaire");
        assertTrue(lastAiRequest.contains("Instruction 18 (GABLE)"), "l'IA a reçu le diagnostic exact");
        assertTrue(lastAiRequest.contains("tail géométrique. Détail"),
                "le diagnostic complet, pas les 500 premiers caractères");
        assertNotEquals(draftId(withDraft), draftId(next));

        // La nouvelle version est revérifiée d'elle-même.
        AgentActionRow secondAudit = latest("building.plan.audit");
        assertNotEquals(firstAudit.id(), secondAudit.id());
        assertEquals(draftId(next), drafts().linkOf(secondAudit.id()).orElseThrow().draftId());
    }

    @Test
    void withoutAnyAuditAnEmptyCommentIsRefusedAndNothingIsPaid() throws Exception {
        start();
        String withDraft = propose(createProject("Maison", BRIEF));
        String page = get(withDraft).body();
        String body = "_csrf=" + csrf(page) + "&_action=correct&id=" + id(withDraft) + "&draft="
                + draftId(withDraft) + "&version=1&provider=openai&request_token=fresh-token&notes=";
        String back = decoded(location(post("/buildings/workshop/project", body)));
        assertTrue(back.contains("pas encore été vérifiée"), back);
        assertEquals(1, aiCalls.get());
    }

    /** Une réparation sûre calculée par le plugin devient une version, sans clic ni appel à l'IA. */
    @Test
    void anEngineRepairBecomesAVerifiedVersionWithoutAClickOrAnAiCall() throws Exception {
        start();
        String withDraft = propose(createProject("Maison du bibliothécaire", BRIEF));
        AgentActionRow audit = latest("building.plan.audit");
        String repaired = PLAN.replace("bibliotheque_v2", "bibliotheque_v2") + "\n  - op: box\n"
                + "    from: [1, 1, 1]\n    block: minecraft:lantern";
        String details = "{\"code\":\"PLAN_INVALID\",\"codes\":[\"PLAN_INVALID\"],\"message\":\"toit trop haut\","
                + "\"warnings\":[],\"metrics\":{},\"repair\":{\"plan\":" + Json.write(repaired) + ","
                + "\"changes\":[\"Instruction 18 (gable) : zone ramenée au volume.\"],\"codes\":[],"
                + "\"accepted\":true}}";
        agentAnswers(audit.id(), "FAILED", "toit trop haut", details);

        List<BuildingPlanDraft> versions = drafts().forProject(TestConfig.AGENT_ID, id(withDraft));
        BuildingPlanDraft fixed = versions.stream()
                .filter(d -> d.origin() == BuildingPlanDraft.Origin.ENGINE_REPAIR).findFirst().orElseThrow();
        assertEquals(repaired, fixed.plan());
        assertEquals(draftId(withDraft), fixed.parentId());
        assertEquals(1, aiCalls.get(), "aucun appel à l'IA");
        AgentActionRow reaudit = latest("building.plan.audit");
        assertEquals(fixed.id(), drafts().linkOf(reaudit.id()).orElseThrow().draftId(),
                "la version réparée est revérifiée d'elle-même");

        // La page du projet s'ouvre sur la version réparée, et dit ce qui a été fait.
        String page = get("/buildings/workshop/project?id=" + id(withDraft)).body();
        assertTrue(page.contains("réparée automatiquement par le moteur"), excerpt(page));
        assertTrue(page.contains("zone ramenée au volume"));

        // Un résultat rejoué ne crée pas de seconde réparation.
        agentAnswers(audit.id(), "FAILED", "toit trop haut", details);
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, id(withDraft)).stream()
                .filter(d -> d.origin() == BuildingPlanDraft.Origin.ENGINE_REPAIR).count());

        // Et la réparation n'est jamais réparée à son tour : ce qui reste est le travail de l'IA.
        agentAnswers(reaudit.id(), "FAILED", "encore", details);
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, id(withDraft)).stream()
                .filter(d -> d.origin() == BuildingPlanDraft.Origin.ENGINE_REPAIR).count());
    }

    /** La conception automatique : enveloppe annoncée, consentement exigé, jamais lancée deux fois. */
    @Test
    void anAutomaticDesignNeedsConsentToAnAnnouncedEnvelopeAndRunsOnce() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String page = get(project).body();
        assertTrue(page.contains("Conception automatique"), "la carte est proposée");
        assertTrue(page.contains("Enveloppe : 8 appel(s) à l&#39;IA au plus")
                || page.contains("Enveloppe : 8 appel(s) à l'IA au plus"), "l'enveloppe est annoncée");

        String form = formAround(page, "value=\"design\"");
        String body = "_csrf=" + csrf(page) + "&_action=design&id=" + id(project) + "&provider=openai"
                + "&quality=SOIGNE&request_token=" + token(form);
        String refused = decoded(location(post("/buildings/workshop/project", body)));
        assertTrue(refused.contains("acceptation de l'enveloppe"), refused);
        assertEquals(0, aiCalls.get(), "rien n'est dépensé sans consentement");

        String launched = decoded(location(post("/buildings/workshop/project", body + "&consent=yes")));
        assertTrue(launched.contains("Conception automatique lancée (Soigné, 8 appel(s) au plus)"), launched);
        String again = decoded(location(post("/buildings/workshop/project", body + "&consent=yes")));
        assertTrue(again.contains("déjà été lancée"), again);
        var runs = new com.lodygames.rpgquest.panel.building.BuildingDesignRunStore(dbPath);
        assertEquals(8, runs.latest(id(project)).orElseThrow().maxCalls());
    }

    // ---- #283 : images de référence et nouvelle conception ---------------------------------------

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13,
        'I', 'H', 'D', 'R', 1, 2, 3};

    private HttpResponse<String> upload(String page, String projectId, String name, byte[] data) throws Exception {
        return upload("/buildings/workshop/image", page, projectId, name, data);
    }

    private HttpResponse<String> upload(String path, String page, String projectId, String name, byte[] data)
            throws Exception {
        String boundary = "----rpgquest283";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf(page)
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\n" + projectId
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + name
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())));
    }

    @Test
    void aReferenceImageIsUploadedShownAndSentAsPixelsToTheAi() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String page = get(project).body();
        assertTrue(page.contains("Images de référence"));
        String back = decoded(location(upload(page, id(project), "facade.png", PNG)));
        assertTrue(back.contains("Image de référence ajoutée"), back);

        page = get(project).body();
        String src = unescape(field(page, "<img src=\"([^\"]+)\""));
        HttpResponse<String> img = get(src);
        assertEquals(200, img.statusCode());
        assertEquals("image/png", img.headers().firstValue("Content-Type").orElse(""));
        assertEquals("nosniff", img.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(404, get(src.replace("project=" + id(project), "project=autre")).statusCode(),
                "jamais servie hors de son projet");

        String refused = decoded(location(upload(page, id(project), "piege.png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))));
        assertTrue(refused.contains("n'est pas une image"), refused);

        propose(project);
        assertTrue(lastAiRequest.contains("data:image/png;base64,"
                + java.util.Base64.getEncoder().encodeToString(PNG)), "les pixels partent avec la demande");
        assertTrue(lastAiRequest.contains("RÉFÉRENCES DE STYLE"));
    }

    /** #283 : la limite est affichée et un envoi trop lourd revient sur le projet, avec un message clair. */
    @Test
    void anOversizedImageComesBackToItsProjectWithAClearMessage() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String page = get(project).body();
        assertTrue(page.contains("4 Mio au plus par image, 4 images par projet"), page);
        String action = unescape(field(page, "action=\"(/buildings/workshop/image[^\"]*)\""));
        assertEquals("/buildings/workshop/image?id=" + id(project), action, "le projet voyage dans l'URL");

        byte[] heavy = new byte[com.lodygames.rpgquest.panel.building.ReferenceImageStore.MAX_BYTES + 128 * 1024];
        System.arraycopy(PNG, 0, heavy, 0, PNG.length);
        String back = location(upload(action, page, id(project), "planche.png", heavy));
        assertTrue(back.startsWith("/buildings/workshop/project?id=" + id(project)), back);
        assertTrue(decoded(back).contains("Image trop lourde : 4 Mio au plus"), decoded(back));
        assertTrue(get(project).body().contains("(facultatif, 0 / 4)"), "rien n'a été enregistré");
    }

    @Test
    void restartingFromTheDescriptionCreatesAnIndependentVersionAndKeepsTheOthers() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(withDraft).body();
        String form = formAround(page, "value=\"restart\"");
        String body = "_csrf=" + csrf(page) + "&_action=restart&id=" + id(withDraft) + "&draft="
                + draftId(withDraft) + "&provider=openai&request_token=" + token(form);
        String refused = decoded(location(post("/buildings/workshop/project", body)));
        assertTrue(refused.contains("Cochez la confirmation"), refused);
        assertEquals(1, aiCalls.get());

        String next = location(post("/buildings/workshop/project", body + "&confirm=yes"));
        assertEquals(2, aiCalls.get());
        BuildingPlanDraft fresh = drafts().find(draftId(next)).orElseThrow();
        assertEquals("", fresh.parentId(), "aucun héritage du plan précédent");
        assertEquals(BuildingPlanDraft.Origin.AI_PROPOSAL, fresh.origin());
        assertFalse(lastAiRequest.contains("PROPOSITION PRÉCÉDENTE"), "ni plan ni erreur transmis");
        assertTrue(drafts().find(draftId(withDraft)).isPresent(), "l'ancienne version reste");
    }

    /**
     * #283 (lot B, 24/10/2026) : les images de référence repartent en pixels à CHAQUE étape manuelle —
     * plan, « Améliorer avec l'IA », « Recommencer depuis ma description » — et restent attachées au
     * projet après une nouvelle conception. Le journal DEV le montrait ; aucun test ne le gardait.
     */
    @Test
    void referenceImagesAreKeptAndSentAsPixelsAtEveryManualStepIncludingRestart() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        upload(get(project).body(), id(project), "facade.png", PNG);
        String pixels = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(PNG);

        String withDraft = propose(project);
        assertTrue(lastAiRequest.contains(pixels), "plan : les pixels partent");
        answer(latest("building.plan.audit"), AUDIT_REFUSED);

        String page = get(withDraft).body();
        String form = formAround(page, "value=\"correct\"");
        String improved = location(post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=correct&id="
                + id(withDraft) + "&draft=" + draftId(withDraft) + "&version=1&provider=openai&request_token="
                + token(form)));
        assertEquals(2, aiCalls.get());
        assertTrue(lastAiRequest.contains(pixels), "amélioration : les pixels repartent");
        answer(latest("building.plan.audit"), AUDIT_REFUSED);

        page = get(improved).body();
        form = formAround(page, "value=\"restart\"");
        String next = location(post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=restart&id="
                + id(withDraft) + "&draft=" + draftId(improved) + "&provider=openai&request_token=" + token(form)
                + "&confirm=yes"));
        assertEquals(3, aiCalls.get());
        assertTrue(lastAiRequest.contains(pixels), "recommencer : les pixels repartent");
        assertFalse(lastAiRequest.contains("PROPOSITION PRÉCÉDENTE"), "ni plan ni erreur transmis");
        assertEquals("", drafts().find(draftId(next)).orElseThrow().parentId());
        assertTrue(get(next).body().contains("(facultatif, 1 / 4)"), "l'image reste attachée au projet");
    }

    /**
     * #747 — parcours réel du 22/10/2026 : la mémoire envoyée avec « Améliorer avec l'IA » ne contenait
     * que les versions déjà améliorées une fois (relevées dans le journal). Elle couvre désormais toutes
     * les versions jugées du projet, y compris celle qui n'a jamais été améliorée elle-même.
     */
    @Test
    void theMemorySentWithAnImprovementCoversEveryJudgedVersionOfTheProject() throws Exception {
        start();
        String first = propose(createProject("Bibliothèque", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String page = get(first).body();
        String restart = formAround(page, "value=\"restart\"");
        String second = location(post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=restart&id="
                + id(first) + "&draft=" + draftId(first) + "&provider=openai&request_token=" + token(restart)
                + "&confirm=yes"));
        answer(latest("building.plan.audit"), AUDIT_REFUSED);

        page = get(second).body();
        String form = formAround(page, "value=\"correct\"");
        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=correct&id=" + id(second) + "&draft="
                + draftId(second) + "&version=1&provider=openai&mode=repair&request_token=" + token(form));

        assertEquals(3, aiCalls.get());
        assertTrue(lastAiRequest.contains("MÉMOIRE DES TENTATIVES"), lastAiRequest);
        assertTrue(lastAiRequest.contains("V-" + draftId(first).substring(0, 8)),
                "la première version, jamais améliorée, fait partie de la mémoire");
        assertTrue(lastAiRequest.contains("V-" + draftId(second).substring(0, 8) + " (version transmise)"));
        assertTrue(lastAiRequest.contains("MODE : RÉPARATION TECHNIQUE"), "le mode choisi est respecté");
    }

    // ---- Reprendre l'existant -------------------------------------------------------------------

    /** Le cas réel : la description de buildsite_0009 et ses deux brouillons de la Bibliothèque. */
    @Test
    void aSiteBriefAndItsDraftsAreImportedWithoutTouchingTheSite() throws Exception {
        start();
        BuildingPlanDraftStore legacy = drafts();
        String d1 = legacy.create(new BuildingPlanDraftStore.NewDraft(TestConfig.AGENT_ID, "buildsite_0009",
                PLAN, BuildingPlanDraft.Origin.RECOVERED, "", "7a1ab38d", "", "", "", "", "", "", 19, 14, "owner")).id();
        String d2 = legacy.create(new BuildingPlanDraftStore.NewDraft(TestConfig.AGENT_ID, "buildsite_0009",
                PLAN, BuildingPlanDraft.Origin.AI_PROPOSAL, "", "", "openai", "OpenAI", "gpt-4o", "", "", "", 19, 14,
                "owner")).id();
        long actionsBefore = store.recentActions(TestConfig.AGENT_ID, 100).size();

        String index = get("/buildings/workshop").body();
        assertTrue(index.contains("Reprendre dans l&#39;Atelier") || index.contains("Reprendre dans l'Atelier"));
        assertTrue(index.contains("2 plan(s)"), excerpt(index));

        String imported = location(post("/buildings/workshop/import", "_csrf=" + csrf(index) + "&site=buildsite_0009"));
        assertTrue(imported.startsWith("/buildings/workshop/project?id="), imported);
        var project = new com.lodygames.rpgquest.panel.building.BuildingProjectStore(dbPath).find(id(imported)).orElseThrow();
        assertTrue(project.brief().contains("Bâtiment sur deux étages"), "description copiée telle quelle");
        assertEquals("WEST", project.front());
        assertEquals(project.id(), drafts().find(d1).orElseThrow().projectId());
        assertEquals(project.id(), drafts().find(d2).orElseThrow().projectId());
        assertEquals("buildsite_0009", drafts().find(d1).orElseThrow().siteId(), "l'origine reste notée");
        assertEquals(actionsBefore, store.recentActions(TestConfig.AGENT_ID, 100).size(),
                "aucune action envoyée au serveur : la fiche de l'emplacement n'est pas touchée");
        assertEquals(0, aiCalls.get());

        String again = location(post("/buildings/workshop/import", "_csrf=" + csrf(index) + "&site=buildsite_0009"));
        assertEquals(project.id(), id(again), "importer deux fois ne crée pas deux projets");
        assertFalse(get("/buildings/workshop").body().contains("name=\"site\" value=\"buildsite_0009\""));
    }

    @Test
    void oldGeneratorAddressesLeadToTheWorkshop() throws Exception {
        start();
        String before = location(get("/buildings/plan?site=buildsite_0009"));
        assertTrue(before.startsWith("/buildings/workshop?ok="), before);

        String index = get("/buildings/workshop").body();
        String imported = location(post("/buildings/workshop/import", "_csrf=" + csrf(index) + "&site=buildsite_0009"));
        assertEquals(id(imported), id(location(get("/buildings/plan?site=buildsite_0009"))));

        HttpResponse<String> forged = post("/buildings/plan", "_csrf=" + csrf(index) + "&_action=propose&site="
                + "buildsite_0009&provider=openai&request_token=x");
        assertEquals(303, forged.statusCode());
        assertEquals(0, aiCalls.get(), "un ancien formulaire ne déclenche rien");
    }

    @Test
    void aPlanIsRecoveredFromAnActionIntoAProjectWithoutCallingTheAi() throws Exception {
        start();
        String project = createProject("Bibliothèque", BRIEF);
        String auditId = store.createAction(TestConfig.AGENT_ID, "building.plan.audit", Map.of("plan", PLAN), "owner");
        answer(store.action(auditId).orElseThrow(), AUDIT_OK);

        String actionPage = get("/actions/" + auditId).body();
        assertTrue(actionPage.contains("Examiner l&#39;import dans ce projet")
                || actionPage.contains("Examiner l'import dans ce projet"), excerpt(actionPage));
        // #308 : depuis la fiche d'action, on passe par l'examen ; seule la confirmation copie.
        String examined = location(post("/buildings/workshop/project", "_csrf=" + csrf(actionPage)
                + "&_action=recover&id=" + id(project) + "&action_id=" + auditId));
        assertTrue(examined.contains("import=" + auditId), examined);
        assertTrue(drafts().forProject(TestConfig.AGENT_ID, id(project)).isEmpty(), "rien copié sans confirmation");
        String recovered = location(post("/buildings/workshop/project", "_csrf=" + csrf(actionPage)
                + "&_action=recover&id=" + id(project) + "&action_id=" + auditId + "&confirm=1"));

        String main = mainCard(get(recovered).body());
        assertTrue(main.contains("Enregistrer dans la bibliothèque"), "le verdict d'origine est repris : " + main);
        assertEquals(0, aiCalls.get());
        assertTrue(get("/actions/" + auditId).body().contains("Retour à ce brouillon"));
    }

    // ---- #308 : historique du projet isolé, import d'ailleurs explicite ------------------------

    /** Un projet neuf a un historique vide ; les plans orphelins de l'agent sont dans l'import, replié. */
    @Test
    void aNewProjectHasAnEmptyHistoryAndOrphanPlansStayInTheSeparateImport() throws Exception {
        start();
        String orphan = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", PLAN.replace("id: bibliotheque_v2", "id: bibliotheque_du_hub")), "owner");
        answer(store.action(orphan).orElseThrow(), AUDIT_OK);
        String project = createProject("Estrade d'Elias", BRIEF);

        for (int pass = 0; pass < 2; pass++) {
            String page = get(project).body();
            String history = section(page, "id=\"ws-history\"");
            assertTrue(history.contains("il est neuf"), history);
            assertFalse(history.contains("bibliotheque_du_hub"), "un plan étranger n'est jamais dans l'historique");
            String imports = section(page, "id=\"ws-import\"");
            assertTrue(imports.contains("bibliotheque_du_hub"), imports);
            assertTrue(imports.contains("Examiner avant import"), imports);
            assertFalse(imports.startsWith("<details class=\"npc-registry\" id=\"ws-import\" open"),
                    "l'import est replié par défaut");
            assertTrue(drafts().forProject(TestConfig.AGENT_ID, id(project)).isEmpty());
            restart();
        }
    }

    // ---- #828 : identifier et retrouver une version dans l'historique ------------------------------

    /**
     * #828 — TC-428 : chaque version porte son identifiant court, son statut réel (bloqué,
     * avertissements acceptables, non vérifiée) ; une version se retrouve par son identifiant court,
     * par un lien direct sans projet, et la version affichée est mise en évidence. Rien n'est payé,
     * enregistré ni posé.
     */
    @Test
    void everyVersionShowsItsShortIdAndRealStatusAndIsFoundByThatId() throws Exception {
        start();
        String project = createProject("Église", BRIEF);
        String withDraft = propose(project);
        answer(latest("building.plan.audit"), AUDIT_REFUSED);
        String blocked = draftId(withDraft);
        String acceptable = extraDraft(id(project), AUDIT_ACCEPTABLE);
        String unchecked = extraDraft(id(project), null);
        String conforming = extraDraft(id(project), AUDIT_OK);
        String invalid = extraDraft(id(project), "{\"code\":\"PLAN_INVALID\",\"codes\":[\"PLAN_INVALID\"],"
                + "\"message\":\"Plan illisible.\",\"blocks\":0,\"warnings\":[]}");
        int actions = store.recentActions(TestConfig.AGENT_ID, 500).size();

        String history = section(get(project).body(), "id=\"ws-history\"");
        for (String d : List.of(blocked, acceptable, unchecked, conforming, invalid)) {
            assertTrue(history.contains("data-copy=\"V-" + d.substring(0, 8) + "\""), history);
        }
        assertTrue(history.contains("Bloqué") && history.contains("NO_ENTRANCE"), history);
        assertTrue(history.contains("Avertissements acceptables") && history.contains("UNSUPPORTED_BLOCK"), history);
        assertTrue(history.contains("Non vérifiée"), history);
        assertTrue(history.contains("> Conforme</span>"), history);
        assertTrue(history.contains("PLAN_INVALID"), history);
        assertFalse(itemOf(history, invalid).contains("Avertissements acceptables"),
                "PLAN_INVALID n'est jamais contournable");

        String shown = get("/buildings/workshop/project?id=" + id(project) + "&draft=V-"
                + acceptable.substring(0, 8).toUpperCase()).body();
        String h = section(shown, "id=\"ws-history\"");
        assertTrue(h.contains("affichée : V-" + acceptable.substring(0, 8)), h);
        assertTrue(h.contains("ws-version--open\" id=\"ws-v-" + acceptable.substring(0, 8) + "\" aria-current"), h);
        assertTrue(h.contains("Reprendre V-" + blocked.substring(0, 8)), "le bouton nomme la version chargée");
        assertFalse(h.contains("Reprendre V-" + acceptable.substring(0, 8)), "la version affichée n'est pas à reprendre");

        String missing = get("/buildings/workshop/project?id=" + id(project) + "&draft=V-ffffffff").body();
        assertTrue(missing.contains("introuvable dans l&#39;historique de ce projet")
                || missing.contains("introuvable dans l'historique de ce projet"), excerpt(missing));

        HttpResponse<String> deep = get("/buildings/workshop/project?draft=V-" + acceptable.substring(0, 8));
        assertEquals(303, deep.statusCode());
        assertEquals(id(project), id(location(deep)));
        assertEquals(acceptable, draftId(location(deep)));
        String absent = decoded(location(get("/buildings/workshop/project?draft=V-ffffffff")));
        assertTrue(absent.contains("introuvable dans les projets de l'Atelier"), absent);

        assertEquals(1, aiCalls.get(), "consulter l'historique n'appelle jamais l'IA");
        assertEquals(actions, store.recentActions(TestConfig.AGENT_ID, 500).size(),
                "ni vérification, ni enregistrement, ni pose");
    }

    private static String itemOf(String history, String draftId) {
        int at = history.indexOf("id=\"ws-v-" + draftId.substring(0, 8) + "\"");
        assertTrue(at > 0, "version absente : " + draftId);
        return history.substring(at, history.indexOf("</li>", at));
    }

    /** Une version de plus dans le projet, vérifiée par {@code audit} (ou jamais si {@code null}). */
    private String extraDraft(String projectId, String audit) {
        BuildingPlanDraft d = drafts().create(new BuildingPlanDraftStore.NewDraft(TestConfig.AGENT_ID, "",
                PLAN, BuildingPlanDraft.Origin.AI_CORRECTION, "", "", "openai", "OpenAI", "gpt-4o", "", "", "",
                19, 19, 14, "owner", projectId));
        if (audit != null) {
            String action = store.createAction(TestConfig.AGENT_ID, "building.plan.audit", Map.of("plan", PLAN),
                    "owner");
            drafts().link(d.id(), action, BuildingPlanDraftStore.Check.AUDIT, d.version());
            answer(store.action(action).orElseThrow(), audit);
        }
        return d.id();
    }

    /** L'examen montre provenance, dimensions, orientation et incompatibilités — sans rien écrire. */
    @Test
    void theImportPreviewShowsProvenanceAndConflictsWithoutWritingAnything() throws Exception {
        start();
        String auditId = store.createAction(TestConfig.AGENT_ID, "building.plan.audit",
                Map.of("plan", PLAN.replace("front: WEST", "front: NORTH").replace("y: 14", "y: 30")), "owner");
        String project = createProject("Estrade d'Elias", BRIEF);

        String page = get(project + "&import=" + auditId).body();

        String imports = section(page, "id=\"ws-import\"");
        assertTrue(imports.contains("Examen avant import"), imports);
        assertTrue(imports.contains("historique global"), imports);
        assertTrue(imports.contains("19 × 19 × 30 (largeur X × longueur Z × hauteur Y)"), imports);
        assertTrue(imports.contains("dépasse la hauteur maximale du projet (14)"), imports);
        assertTrue(imports.contains("Entrée orientée NORTH ; le projet attend WEST."), imports);
        assertTrue(imports.contains("Importer comme nouvelle version de ce projet"), imports);
        assertTrue(drafts().forProject(TestConfig.AGENT_ID, id(project)).isEmpty(), "examiner n'écrit rien");
        assertEquals(0, aiCalls.get());
    }

    /** Importer le plan d'un autre projet : une copie tracée ; la source et son verdict restent à elle. */
    @Test
    void importingFromAnotherProjectCopiesAndLeavesTheSourceUntouched() throws Exception {
        start();
        String lily = propose(createProject("Maison de Lily", BRIEF));
        BuildingPlanDraft source = drafts().find(draftId(lily)).orElseThrow();
        String auditId = latest("building.plan.audit").id();
        assertEquals(source.id(), drafts().linkOf(auditId).orElseThrow().draftId());
        int calls = aiCalls.get();
        String estrade = createProject("Estrade d'Elias", "Une estrade simple en chêne.");

        String preview = section(get(estrade + "&import=" + auditId).body(), "id=\"ws-import\"");
        assertTrue(preview.contains("Maison de Lily"), "la provenance nomme le projet source : " + preview);
        String page = get(estrade).body();
        String copied = location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=recover&id=" + id(estrade) + "&action_id=" + auditId + "&confirm=1"));
        String again = location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=recover&id=" + id(estrade) + "&action_id=" + auditId + "&confirm=1"));

        BuildingPlanDraft copy = drafts().find(draftId(copied)).orElseThrow();
        assertEquals(id(estrade), copy.projectId());
        assertEquals(BuildingPlanDraft.Origin.RECOVERED, copy.origin());
        assertEquals(source.plan(), copy.plan());
        assertEquals(copy.id(), draftId(again), "un second import ramène à la même copie");
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, id(estrade)).size());
        BuildingPlanDraft after = drafts().find(source.id()).orElseThrow();
        assertEquals(id(lily), after.projectId(), "la source ne change pas de projet");
        assertEquals(source.version(), after.version());
        assertEquals(source.id(), drafts().linkOf(auditId).orElseThrow().draftId(), "le verdict reste à la source");
        assertEquals(1, drafts().forProject(TestConfig.AGENT_ID, id(lily)).size());
        assertTrue(get(estrade).body().contains("Une estrade simple en chêne."), "le brief de destination est gardé");
        assertEquals(calls, aiCalls.get(), "aucun appel à l'IA");
    }

    /** Le serveur refuse le plan d'un autre agent, même avec un identifiant forgé. */
    @Test
    void aForeignAgentsPlanIsRefusedServerSide() throws Exception {
        start();
        String foreign = store.createAction("autre-agent", "building.plan.audit", Map.of("plan", PLAN), "owner");
        String project = createProject("Estrade d'Elias", BRIEF);
        String page = get(project).body();

        String refused = location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=recover&id=" + id(project) + "&action_id=" + foreign + "&confirm=1"));

        assertTrue(decoded(refused).contains("rien n'a été importé"), decoded(refused));
        assertTrue(drafts().forProject(TestConfig.AGENT_ID, id(project)).isEmpty());
        assertFalse(get(project + "&import=" + foreign).body().contains("Examen avant import"));
    }

    /** Le bloc {@code <details>} qui commence par {@code marker}. */
    private static String section(String page, String marker) {
        int at = page.indexOf(marker);
        assertTrue(at > 0, "section introuvable : " + marker);
        int start = page.lastIndexOf("<details", at);
        int end = page.indexOf("</details>", at);
        return page.substring(start, end);
    }

    @Test
    void theDiagnosticIsCopyableAndCarriesNoSecret() throws Exception {
        start();
        String withDraft = propose(createProject("Bibliothèque", BRIEF));
        String page = get(withDraft).body();
        String diag = field(page, "data-copy=\"([^\"]*)\"><i[^>]*></i>Copier le diagnostic");
        assertTrue(diag.contains("Diagnostic Atelier PlugAdmin"), diag);
        assertTrue(diag.contains("bibliotheque_v2"), "le plan est inclus");
        assertFalse(diag.contains("sk-test"), "jamais la clé d'API");
    }

    // ---- Harnais -------------------------------------------------------------------------------

    // ---- #289 : la conception détaillée, consultable et modifiable --------------------------------

    /** Rédigée par l'IA (un appel), modifiable à la main sous contrôle de version, suivie par le plan. */
    @Test
    void theDetailedDesignIsWrittenEditedAndFollowedByThePlan() throws Exception {
        start();
        String project = createProjectWithoutDesign("Maison de Lily", BRIEF);
        String page = get(project).body();
        assertTrue(page.contains("Conception détaillée — pas encore rédigée"), "la carte est proposée");
        String form = formAround(page, "value=\"design_write\"");
        String back = location(post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=design_write&id="
                + id(project) + "&provider=openai&request_token=" + token(form)));
        assertEquals(1, aiCalls.get(), "un seul appel");
        assertTrue(lastAiRequest.contains("CORRESPONDANCE AVEC LE BRIEF"), "le prompt de conception");
        assertTrue(decoded(back).contains("Conception rédigée (version 1)"), decoded(back));

        // Modifier à la main : une version de retard est refusée, le texte saisi reste affiché.
        page = get(project).body();
        assertTrue(page.contains("Conception détaillée — version 1"), "la conception est consultable");
        HttpResponse<String> stale = post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=design_save&id=" + id(project) + "&design_version=0&design="
                + enc("Chambre de Lily à l'étage, lit rose, tapis rose."));
        assertTrue(stale.body().contains("La conception a changé"), excerpt(stale.body()));
        assertTrue(stale.body().contains("Chambre de Lily à l&#39;étage"), "le texte saisi n'est pas perdu");
        post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=design_save&id=" + id(project)
                + "&design_version=1&design=" + enc("Chambre de Lily à l'étage, lit rose, tapis rose."));
        assertTrue(get(project).body().contains("Conception détaillée — version 2"));

        // Le plan demandé ensuite traduit la conception, avec le brief.
        propose(project);
        assertTrue(lastAiRequest.contains("LA CONCEPTION DÉTAILLÉE À TRADUIRE"), "le plan suit la conception");
        assertTrue(lastAiRequest.contains("lit rose, tapis rose"), "la version modifiée à la main");
    }

    /**
     * #747 (23/10/2026) : concevoir AVANT de placer. Sans conception en vigueur, « Créer un plan » la fait
     * d'abord rédiger (un appel, journalisé), puis le plan la traduit avec son contrat de volumes.
     */
    @Test
    void creatingAPlanWithoutADesignWritesTheStructuredDesignFirst() throws Exception {
        start();
        String project = createProjectWithoutDesign("Chapelle", "Une petite chapelle avec une tour-clocher et des bancs.");
        String nextAnswer = """
                0. VOLUMES ET ÉLÉMENTS
                VOLUME nef | rôle: corps principal | x 2..10 | z 2..12 | y 0..6 | toit: deux pans axe x | relié à: tour par arche
                VOLUME tour | rôle: tour-clocher | x 4..8 | z 13..17 | y 0..12 | toit: pointu | relié à: nef par arche
                ÉLÉMENT Bancs | volume: nef | position: x 3..9, z 4..10, y 1 | blocs: minecraft:oak_stairs
                """;
        aiAnswers.add(nextAnswer);
        String back = decoded(propose(project));

        assertEquals(2, aiCalls.get(), "la conception, puis le plan");
        assertTrue(back.contains("ok="), back);
        assertTrue(lastAiRequest.contains("LA CONCEPTION DÉTAILLÉE À TRADUIRE"), "le plan suit la conception");
        assertTrue(lastAiRequest.contains("CONTRAT DE VOLUMES") && lastAiRequest.contains("- tour (tour-clocher) : "
                + "x 4...8, z 13...17, y 0...12"), "le contrat de volumes part avec le plan");
        assertTrue(get(project).body().contains("Conception détaillée — version 1"), "la conception est conservée");
        String journal = get("/buildings/workshop/journal?id=" + id(project)).body();
        assertTrue(journal.contains("MANUEL — CONCEPTION AVANT LE PLAN"), journal);
        assertTrue(journal.contains("Conception structurée transmise : 2 volume(s), 1 élément(s), aucune lacune relevée."),
                journal);

        // Un second plan suit la conception en vigueur : aucun nouvel appel de conception.
        propose(project);
        assertEquals(3, aiCalls.get(), "un seul appel de plus");
    }

    // ---- #290 : reprendre, republier, retirer puis réenregistrer ---------------------------------

    /** Publier, reprendre, modifier puis republier le MÊME bâtiment, sous contrôle de version. */
    @Test
    void aPublishedBuildingIsResumedAndRepublishedUnderVersionControl() throws Exception {
        start();
        String withDraft = propose(createProject("Maison de Lily", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_OK);
        gesture(get(withDraft).body(), "generate");
        answer(latest("building.generate"), "{\"code\":\"OK\",\"buildingId\":\"bibliotheque_v2\",\"version\":1}");

        String done = mainCard(get(withDraft).body());
        assertTrue(done.contains("Enregistré dans la bibliothèque — version 1"), done);
        assertTrue(done.contains("Reprendre la conception"), "la conception reste modifiable : " + done);

        // Reprendre : une nouvelle version, sans appel à l'IA, revérifiée d'elle-même.
        String page = get(withDraft).body();
        String form = formAround(page, "value=\"resume\"");
        String resumed = location(post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=resume&id=" + id(withDraft) + "&draft=" + field(form, "name=\"draft\" value=\"([^\"]+)\"")));
        assertNotEquals(draftId(withDraft), draftId(resumed));
        assertEquals(BuildingPlanDraft.Origin.RESUMED, drafts().find(draftId(resumed)).orElseThrow().origin());
        assertEquals(1, aiCalls.get(), "reprendre ne coûte aucun appel");
        AgentActionRow reaudit = latest("building.plan.audit");
        assertEquals(draftId(resumed), drafts().linkOf(reaudit.id()).orElseThrow().draftId());
        answer(reaudit, AUDIT_OK);

        // Les actions d'amélioration sont revenues, et la publication est un geste explicite.
        String save = get(resumed).body();
        assertTrue(save.contains("Conception automatique"), "conception automatique disponible");
        assertTrue(mainCard(save).contains("Publier une nouvelle version"), mainCard(save));
        String publish = formAround(save, "name=\"republish\" value=\"yes\"");
        String common = "_csrf=" + csrf(save) + "&_action=generate&id=" + id(resumed)
                + "&draft=" + field(publish, "name=\"draft\" value=\"([^\"]+)\"")
                + "&version=" + field(publish, "name=\"version\" value=\"([^\"]+)\"")
                + "&plan=" + enc(unescape(field(publish, "name=\"plan\" value=\"([^\"]*)\"")))
                + "&republish=yes&expected_version=" + field(publish, "name=\"expected_version\" value=\"([^\"]+)\"");
        long saves = store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals("building.generate")).count();
        String refused = decoded(location(post("/buildings/workshop/project", common)));
        assertTrue(refused.contains("Cochez la confirmation"), refused);
        assertEquals(saves, store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals("building.generate")).count(), "rien n'est parti sans la case");

        post("/buildings/workshop/project", common + "&republish_confirm=yes");
        AgentActionRow republish = latest("building.generate");
        assertEquals("true", republish.params().get("replace"));
        assertEquals("1", republish.params().get("expected_version"), "la version vue part avec la demande");
    }

    /** Après un retrait, l'Atelier le dit, ne propose plus de poser, et permet de réenregistrer. */
    @Test
    void aRetiredBuildingIsShownAsSuchAndCanBeRegisteredAgain() throws Exception {
        start();
        String withDraft = propose(createProject("Maison du bibliothécaire", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_OK);
        gesture(get(withDraft).body(), "generate");
        answer(latest("building.generate"), "{\"code\":\"OK\",\"buildingId\":\"bibliotheque_v2\",\"version\":1}");
        Thread.sleep(5);
        String retire = store.createAction(TestConfig.AGENT_ID, "building.definition.retire",
                Map.of("id", "bibliotheque_v2", "confirm", "true", "by", "owner"), "owner");
        answer(store.action(retire).orElseThrow(), "{\"code\":\"OK\",\"id\":\"bibliotheque_v2\"}");

        restart();
        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("Retiré de la bibliothèque"), main);
        assertFalse(main.contains("Choisir un emplacement"), "plus de pose proposée : " + main);
        assertTrue(main.contains("Réenregistrer dans la bibliothèque"), main);
        assertTrue(main.contains("Reprendre la conception"), main);
        assertTrue(get("/buildings/workshop").body().contains("Retiré de la bibliothèque"), "la liste aussi");

        gesture(get(withDraft).body(), "generate");
        AgentActionRow again = latest("building.generate");
        assertFalse(again.params().containsKey("replace"), "l'identifiant est libre : pas de remplacement");
    }

    /** Depuis la Bibliothèque : le projet producteur est nommé avec son identifiant, et repris en un clic. */
    @Test
    void theLibraryNamesTheProducingProjectAndOffersToResumeIt() throws Exception {
        start();
        String withDraft = propose(createProject("Maison de Lily", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_OK);
        gesture(get(withDraft).body(), "generate");
        answer(latest("building.generate"), "{\"code\":\"OK\",\"buildingId\":\"bibliotheque_v2\",\"version\":1}");
        String list = store.createAction(TestConfig.AGENT_ID, "building.definition.list", Map.of(), "owner");
        answer(store.action(list).orElseThrow(), "{\"code\":\"OK\",\"engineAvailable\":true,\"buildings\":["
                + "{\"id\":\"bibliotheque_v2\",\"name\":\"Maison de Lily\",\"version\":1,\"schematic\":"
                + "\"bibliotheque_v2.schem\",\"schematicPresent\":true,\"sizeX\":19,\"sizeY\":10,\"sizeZ\":19}]}");

        String library = get("/buildings/library").body();
        assertTrue(library.contains("Produit par le projet « <a href=\"/buildings/workshop/project?id=" + id(withDraft)),
                excerpt(library));
        assertTrue(library.contains("bibliotheque_v2</code>"), "l'identifiant, pour lever toute homonymie");
        String form = formAround(library, "value=\"resume\"");
        String resumed = location(post("/buildings/workshop/project", "_csrf=" + csrf(library)
                + "&_action=resume&id=" + field(form, "name=\"id\" value=\"([^\"]+)\"")));
        assertEquals(BuildingPlanDraft.Origin.RESUMED, drafts().find(draftId(resumed)).orElseThrow().origin());
        assertEquals(draftId(withDraft), drafts().find(draftId(resumed)).orElseThrow().parentId(),
                "reprise de la version qui a été enregistrée");
    }

    // ---- #292 : descriptions longues, échec d'enregistrement visible ---------------------------

    /**
     * La Maison de Lily : audit conforme, puis l'enregistrement échoue. Le motif exact s'affiche
     * dans l'Atelier, avec l'action utile — plus de bandeau vert seul.
     */
    @Test
    void aFailedWriteIsShownWithItsExactReasonAndARetry() throws Exception {
        start();
        String withDraft = propose(createProject("Maison de Lily", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_OK);
        gesture(get(withDraft).body(), "generate");
        AgentActionRow save = latest("building.generate");
        String full = "Le schematic n'a pas pu être écrit : Écriture du schematic impossible : disque plein."
                + " Aucune définition n'a été créée. (Échec d'écriture : le plan avait passé la validation "
                + "de ses blocs ; réessayer, ou vérifier WorldEdit et le disque du serveur.)";
        agentAnswers(save.id(), "FAILED", full.substring(0, 60), "{\"code\":\"SCHEMATIC_FAILED\",\"message\":"
                + Json.write(full) + "}");

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("L'enregistrement a échoué (SCHEMATIC_FAILED)"), main);
        assertTrue(main.contains("disque plein"), "le motif complet, pas un extrait : " + main);
        assertTrue(main.contains("Réessayer l&#39;enregistrement"), main);
        assertFalse(main.contains("Enregistré dans la bibliothèque"), main);
        assertEquals(1, aiCalls.get(), "un échec d'écriture ne coûte aucun appel");
    }

    /** #644 (26/10) : la republication d'un bâtiment posé est refusée — aucun appel IA, consigne claire. */
    @Test
    void aProtectedPlacedVersionIsAConflictNotACorrection() throws Exception {
        start();
        String withDraft = propose(createProject("Église", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_OK);
        gesture(get(withDraft).body(), "generate");
        String refusal = "« Église » est posé sur l'emplacement « buildsite_0016 » et la version posée n'a pas "
                + "d'empreinte mémorisée : rien ne prouve qu'elle serait conservée. Rien n'a été écrit.";
        agentAnswers(latest("building.generate").id(), "FAILED", refusal, "{\"code\":\"PLACED_VERSION_PROTECTED\","
                + "\"message\":" + Json.write(refusal) + "}");

        String main = mainCard(get(withDraft).body());
        assertTrue(main.contains("L'enregistrement a échoué (PLACED_VERSION_PROTECTED)"), main);
        assertTrue(main.contains("buildsite_0016"), main);
        assertTrue(main.contains("version posée doit rester intacte"), main);
        assertEquals(1, aiCalls.get(), "un refus de protection ne coûte aucun appel");
    }

    /** Un échec de CONTENU part vers l'IA tel quel, sans commentaire à écrire. */
    @Test
    void aContentFailureAtSaveIsSentToTheAiWithoutAComment() throws Exception {
        start();
        String withDraft = propose(createProject("Maison de Lily", BRIEF));
        answer(latest("building.plan.audit"), AUDIT_OK);
        gesture(get(withDraft).body(), "generate");
        String refusal = "Plan refusé : Bloc inconnu — le bloc « minecraft:bed » n'existe pas en Minecraft "
                + "1.21.11 : il n'existe qu'en version colorée. Employé par instruction 17 (box minecraft:bed "
                + "de [9, 1, 13] à [10, 1, 14]).";
        agentAnswers(latest("building.generate").id(), "FAILED", refusal, "{\"code\":\"PLAN_INVALID\","
                + "\"message\":" + Json.write(refusal) + "}");

        String page = get(withDraft).body();
        assertTrue(mainCard(page).contains("Le plan doit être corrigé"), mainCard(page));
        String form = formAround(page, "value=\"correct\"");
        String body = "_csrf=" + csrf(page) + "&_action=correct&id=" + id(withDraft) + "&draft="
                + draftId(withDraft) + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&provider=openai&request_token=" + token(form) + "&notes=";
        location(post("/buildings/workshop/project", body));
        assertEquals(2, aiCalls.get(), "la correction est partie sans commentaire");
        assertTrue(lastAiRequest.contains("ÉCHOUÉ (PLAN_INVALID)"), "l'IA sait que l'enregistrement a échoué");
        assertTrue(lastAiRequest.contains("minecraft:bed"), "avec le motif exact");
    }

    /** 12 000 caractères, retours à la ligne compris, sans rien couper jusqu'à l'IA. */
    @Test
    void aTwelveThousandCharacterDescriptionIsKeptWholeUpToTheAi() throws Exception {
        start();
        StringBuilder brief = new StringBuilder();
        while (brief.toString().replace("\r\n", "\n").length() < 11_900) {
            brief.append("La chambre de Lily, lit rose, tapis, fleurs aux fenêtres.\r\n");
        }
        brief.append("FIN-DU-BRIEF");
        String normalised = brief.toString().replace("\r\n", "\n").trim();
        assertTrue(brief.length() > 12_000 && normalised.length() <= 12_000,
                "le texte brut dépasse avec les \\r\\n, pas une fois normalisé");
        String project = createProject("Maison de Lily", brief.toString());
        String page = get(project).body();
        assertTrue(page.contains("FIN-DU-BRIEF"), "la description est conservée en entier");
        assertTrue(page.contains(normalised.length() + "</span> / 12000"), "compteur exact");

        propose(project);
        assertTrue(lastAiRequest.contains("FIN-DU-BRIEF"), "l'IA reçoit la description entière");

        String form = get("/buildings/workshop/new").body();
        HttpResponse<String> refused = post("/buildings/workshop/new", "_csrf=" + csrf(form)
                + "&name=Trop&brief=" + enc("x".repeat(12_001))
                + "&front=WEST&max_width=19&max_height=14&building_id=trop_long");
        assertTrue(refused.body().contains("12001 caractères pour 12000 au plus"), excerpt(refused.body()));
        assertTrue(refused.body().contains("Rien n&#39;a été coupé"), "aucune troncature silencieuse");
    }

    // ---- #305 : emprise rectangulaire -------------------------------------------------------------

    /** Signalement du 13/10/2026 : une estrade 13 × 9 × 7, pas forcée dans un carré 13 × 13. */
    @Test
    void aRectangularProjectKeepsItsThreeLimitsUpToTheAiAndTheServer() throws Exception {
        start();
        String form = get("/buildings/workshop/new").body();
        assertTrue(form.contains("name=\"max_depth\""), "un champ Z distinct");
        HttpResponse<String> res = post("/buildings/workshop/new", "_csrf=" + csrf(form) + "&name=" + enc("Estrade")
                + "&brief=" + enc(BRIEF) + "&front=WEST&max_width=13&max_depth=9&max_height=7"
                + "&building_id=bibliotheque_v2");
        assertEquals(303, res.statusCode(), res.body());
        String project = location(res);

        String page = get(project).body();
        String edit = formAround(page, "value=\"project_save\"");
        assertTrue(edit.contains("name=\"max_width\" min=\"5\" max=\"64\" step=\"1\" required value=\"13\""), edit);
        assertTrue(edit.contains("name=\"max_depth\" min=\"5\" max=\"64\" step=\"1\" required value=\"9\""), edit);
        assertTrue(edit.contains("name=\"max_height\" min=\"4\" max=\"64\" step=\"1\" required value=\"7\""), edit);

        String withDraft = propose(project);
        assertTrue(lastAiRequest.contains("13 blocs en x (largeur), 9 blocs en z (longueur)"), lastAiRequest);
        BuildingPlanDraft draft = drafts().find(draftId(withDraft)).orElseThrow();
        assertEquals(java.util.List.of(13, 9, 7), java.util.List.of(draft.maxWidth(), draft.maxDepth(), draft.maxHeight()));
        AgentActionRow audit = latest("building.plan.audit");
        assertEquals("13", audit.params().get("max_width"));
        assertEquals("9", audit.params().get("max_depth"));
        assertEquals("7", audit.params().get("max_height"));
        assertEquals("9", latest("building.plan.preview").params().get("max_depth"));

        restart();
        String again = formAround(get(project).body(), "value=\"project_save\"");
        assertTrue(again.contains("required value=\"9\""), "Z conservé après redémarrage");
    }

    @Test
    void anOutOfRangeDepthIsRefusedNamingTheAxisAndNothingIsSaved() throws Exception {
        start();
        String project = createProject("Estrade", BRIEF);
        String page = get(project).body();
        String edit = formAround(page, "value=\"project_save\"");
        HttpResponse<String> res = post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=project_save&id=" + id(project) + "&project_version="
                + field(edit, "name=\"project_version\" value=\"([^\"]+)\"") + "&name=" + enc("Estrade")
                + "&brief=" + enc(BRIEF) + "&front=WEST&max_width=13&max_depth=abc&max_height=7"
                + "&building_id=bibliotheque_v2");
        String shown = get(location(res)).body();
        assertTrue(shown.contains("Longueur maximale (Z) invalide"), excerpt(shown));
        assertTrue(formAround(shown, "value=\"project_save\"").contains("name=\"max_width\" min=\"5\" max=\"64\" "
                + "step=\"1\" required value=\"19\""), "rien n'a été enregistré");
    }

    private void start() throws Exception {
        ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.createContext("/v1/chat/completions", exchange -> {
            aiCalls.incrementAndGet();
            lastAiRequest = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            String queued = aiAnswers.poll();
            String content = queued != null ? queued : aiContent;
            message.put("content", content == null ? "```yaml\n" + PLAN + "\n```" : content);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("model", "gpt-4o");
            response.put("choices", List.of(Map.of("message", message)));
            response.put("usage", Map.of("prompt_tokens", 1200, "completion_tokens", 900));
            byte[] bytes = Json.write(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ai.start();
        dbPath = tmp.resolve("cp.db").toString();
        new AiSettingsStore(dbPath).save(new AiProviderSettings("openai", true, "sk-test",
                "http://127.0.0.1:" + ai.getAddress().getPort(), "", 4000, 5), "test");
        boot();
        seedSites();
    }

    private void boot() throws Exception {
        store = new AgentStore(dbPath);
        app = new PanelApp(TestConfig.withAgent(dbPath, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)),
                store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + enc(TestConfig.OWNER_PASSWORD) + "&_csrf=" + token);
    }

    /** Arrêt complet du panel puis nouvelle instance sur la même base : sessions perdues, données non. */
    private void restart() throws Exception {
        app.stop();
        app = null;
        boot();
    }

    private void seedSites() throws Exception {
        String id = store.createAction(TestConfig.AGENT_ID, "building.site.list", Map.of(), "owner");
        answer(store.action(id).orElseThrow(), SITES);
    }

    private void answer(AgentActionRow action, String details) {
        String status = details.contains("\"code\":\"OK\"") || details.contains("\"sites\"")
                ? "SUCCESS" : "FAILED";
        String message = details.contains("NO_ENTRANCE") ? "Aucune entrée sur la façade avant."
                : "ok";
        store.recordResult(action.id(), TestConfig.AGENT_ID, AgentActionStatus.valueOf(status),
                "", message, "{\"action_id\":\"" + action.id() + "\",\"status\":\"" + status
                        + "\",\"message\":\"" + message + "\",\"details\":" + details + "}",
                Instant.now());
    }

    /**
     * Répond comme le vrai agent : livraison par le poll, puis résultat par l'API de l'agent. C'est
     * ce chemin — et lui seul — qui déclenche l'automatisation de l'Atelier (#282).
     */
    private void agentAnswers(String actionId, String status, String message, String details)
            throws Exception {
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String result = Json.write(Map.of("action_id", actionId, "status", status, "value", status,
                "message", message, "details", Json.parseObject(details)));
        HttpResponse<String> res = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + actionId
                        + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(result)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    /**
     * Un projet doté d'une conception saisie à la main (gratuite) : « Créer un plan » n'a donc pas à la
     * faire rédiger d'abord (#747) — ces parcours portent sur le plan, un appel = un plan.
     */
    private String createProject(String name, String brief) throws Exception {
        String project = createProjectWithoutDesign(name, brief);
        String page = get(project).body();
        HttpResponse<String> saved = post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=design_save&id=" + id(project) + "&design_version=0&design="
                + enc("Conception de test saisie à la main."));
        assertEquals(303, saved.statusCode(), excerpt(saved.body()));
        return project;
    }

    private String createProjectWithoutDesign(String name, String brief) throws Exception {
        String page = get("/buildings/workshop/new").body();
        HttpResponse<String> res = post("/buildings/workshop/new", "_csrf=" + csrf(page) + "&name=" + enc(name)
                + "&brief=" + enc(brief) + "&front=WEST&max_width=19&max_height=14&building_id=bibliotheque_v2");
        assertEquals(303, res.statusCode(), res.body());
        return location(res);
    }

    private String propose(String project) throws Exception {
        String page = get(project).body();
        HttpResponse<String> res = post("/buildings/workshop/project", "_csrf=" + csrf(page)
                + "&_action=propose&id=" + id(project) + "&provider=openai&request_token=" + token(page));
        assertEquals(303, res.statusCode());
        return location(res);
    }

    /** Soumet un geste gratuit tel que la page le rend (formulaire de l'action principale). */
    private HttpResponse<String> gesture(String page, String action) throws Exception {
        String form = formAround(page, "name=\"_action\" value=\"" + action + "\"");
        return post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=" + action
                + "&id=" + field(form, "name=\"id\" value=\"([^\"]+)\"")
                + "&draft=" + field(form, "name=\"draft\" value=\"([^\"]+)\"")
                + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&plan=" + enc(unescape(field(form, "name=\"plan\" value=\"([^\"]*)\""))));
    }

    /** Soumet l'éditeur du plan brut (détails techniques) avec un texte donné. */
    private HttpResponse<String> editPlan(String page, String action, String plan) throws Exception {
        String form = formAround(page, "id=\"ws-plan\"");
        return post("/buildings/workshop/project", "_csrf=" + csrf(page) + "&_action=" + action
                + "&id=" + field(form, "name=\"id\" value=\"([^\"]+)\"")
                + "&draft=" + field(form, "name=\"draft\" value=\"([^\"]+)\"")
                + "&version=" + field(form, "name=\"version\" value=\"([^\"]+)\"")
                + "&plan=" + enc(plan));
    }

    /** La carte de l'action principale : ce qu'un utilisateur lit en premier. */
    private static String mainCard(String page) {
        int start = page.indexOf("ws-main");
        int end = page.indexOf("Le bâtiment souhaité", start);
        int preview = page.indexOf("ws-preview", start);
        if (preview > start && preview < end) {
            end = preview;
        }
        assertTrue(start > 0 && end > start, "carte principale introuvable");
        return page.substring(start, end);
    }

    private static String id(String location) {
        return field(location, "[?&]id=([^&#]+)");
    }

    private BuildingPlanDraftStore drafts() {
        return new BuildingPlanDraftStore(dbPath);
    }

    private AgentActionRow latest(String type) {
        return store.recentActions(TestConfig.AGENT_ID, 200).stream()
                .filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private static String draftId(String location) {
        return field(location, "[?&]draft=([^&#]+)");
    }

    private static String location(HttpResponse<String> res) {
        return res.headers().firstValue("Location").orElse("");
    }

    private static String decoded(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String token(String html) {
        return field(html, "name=\"request_token\" value=\"([^\"]+)\"");
    }

    private static String field(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (!m.find()) {
            throw new IllegalStateException("introuvable : " + regex + " dans " + excerpt(text));
        }
        return m.group(1);
    }

    private static String formAround(String page, String needle) {
        int at = page.indexOf(needle);
        assertTrue(at > 0, "marqueur introuvable : " + needle + " — " + excerpt(page));
        int open = page.lastIndexOf("<form", at);
        int close = page.indexOf("</form>", at);
        return page.substring(open, close);
    }

    private static String unescape(String raw) {
        return raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    private static String excerpt(String page) {
        int at = page.indexOf("<main");
        String from = at >= 0 ? page.substring(at) : page;
        return from.substring(0, Math.min(3000, from.length()));
    }

    private static String enc(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        return field(html, "name=\"_csrf\" value=\"([^\"]+)\"");
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
        int fragment = p.indexOf('#');
        return URI.create("http://127.0.0.1:" + port + (fragment >= 0 ? p.substring(0, fragment) : p));
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
