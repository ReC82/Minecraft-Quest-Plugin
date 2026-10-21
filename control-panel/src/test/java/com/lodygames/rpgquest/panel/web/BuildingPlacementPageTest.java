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
 * Issue #213, lot « placement » — la bibliothèque et le parcours de pose, vus du navigateur.
 *
 * <p>Comme pour #227, les formulaires sont <strong>extraits de la page rendue</strong> et soumis
 * tels quels. Écrire le corps de requête à la main vérifierait le service, pas le parcours : un
 * champ caché oublié ou mal nommé passerait inaperçu.</p>
 *
 * <p>Ce qui est réellement vérifié ici : <strong>rien n'est posé sans aperçu, et aucun aperçu ne
 * pose</strong>. L'emprise est la seule information qui dise ce qui va être écrasé, et le bouton de
 * pose n'apparaît qu'après l'avoir montrée.</p>
 */
class BuildingPlacementPageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    /** Un emplacement vide, orienté à l'est, et un second occupé. */
    private static final String SITES = "{"
            + "\"total\":2,\"worlds\":[\"world_hub\"],"
            + "\"sites\":["
            + "{\"id\":\"buildsite_0001\",\"name\":\"Test hutte\",\"description\":\"\","
            + "\"world\":\"world_hub\",\"x\":712,\"y\":67,\"z\":-702,\"facing\":\"EAST\","
            + "\"status\":\"EMPTY\",\"createdBy\":\"Lody\","
            + "\"createdAt\":\"2026-10-08T18:00:00Z\",\"worldLoaded\":true},"
            + "{\"id\":\"buildsite_0002\",\"name\":\"Déjà bâtie\",\"description\":\"\","
            + "\"world\":\"world_hub\",\"x\":800,\"y\":70,\"z\":-800,\"facing\":\"NORTH\","
            + "\"status\":\"OCCUPIED\",\"createdBy\":\"Lody\","
            + "\"createdAt\":\"2026-10-08T18:05:00Z\",\"worldLoaded\":true}"
            + "],"
            + "\"placements\":["
            + "{\"siteId\":\"buildsite_0002\",\"buildingId\":\"test_hut_01\","
            + "\"buildingName\":\"Hutte de test\",\"world\":\"world_hub\","
            + "\"anchorX\":800,\"anchorY\":70,\"anchorZ\":-800,\"rotation\":0,"
            + "\"minX\":797,\"minY\":69,\"minZ\":-800,\"maxX\":803,\"maxY\":74,\"maxZ\":-796,"
            + "\"placedBy\":\"Lody\",\"placedAt\":\"2026-10-08T19:00:00Z\",\"restorable\":true}"
            + "]}";

    private static final String LIBRARY = "{"
            + "\"engineAvailable\":true,\"engineReason\":\"\",\"problems\":[],"
            + "\"buildings\":["
            + "{\"id\":\"test_hut_01\",\"name\":\"Hutte de test\","
            + "\"description\":\"Hutte de validation.\","
            + "\"sizeX\":7,\"sizeY\":6,\"sizeZ\":5,\"anchorX\":3,\"anchorY\":1,\"anchorZ\":0,"
            + "\"front\":\"NORTH\",\"materials\":[\"cobblestone\",\"oak_planks\"],"
            + "\"schematic\":\"test_hut_01.schem\",\"schematicPresent\":true,\"version\":1}"
            + "]}";

    /** Aperçu posable pour buildsite_0001 : façade nord amenée à l'est, donc 90°. */
    private static final String PREVIEW_OK = "{"
            + "\"placeable\":true,\"siteId\":\"buildsite_0001\",\"siteName\":\"Test hutte\","
            + "\"siteFacing\":\"EAST\",\"world\":\"world_hub\","
            + "\"anchorX\":712,\"anchorY\":67,\"anchorZ\":-702,"
            + "\"buildingId\":\"test_hut_01\",\"buildingName\":\"Hutte de test\","
            + "\"sizeX\":7,\"sizeY\":6,\"sizeZ\":5,\"front\":\"NORTH\",\"rotation\":90,"
            + "\"minX\":708,\"minY\":66,\"minZ\":-705,"
            + "\"maxX\":712,\"maxY\":71,\"maxZ\":-699,"
            + "\"blockCount\":210,\"nonAirBlocks\":42,"
            + "\"refusals\":[],\"warnings\":[]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- La bibliothèque -----------------------------------------------------------------------

    /** Sans relevé, on ne prétend pas « aucun bâtiment » : on dit de rafraîchir. */
    @Test
    void withoutAReadingTheLibrarySaysToRefreshRatherThanClaimingItIsEmpty() throws Exception {
        start();

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Aucun relevé chargé"), "doit inviter à rafraîchir");
        assertFalse(page.contains("Aucun bâtiment en bibliothèque"),
                "ne doit pas affirmer que la bibliothèque est vide");
    }

    @Test
    void theLibraryShowsTheBuildingWithItsDimensionsAnchorAndReferenceFacing() throws Exception {
        start();
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Hutte de test"), "le nom");
        assertTrue(page.contains("test_hut_01"), "l'identifiant");
        assertTrue(page.contains("7 × 5 × 6"), "largeur × profondeur × hauteur");
        assertTrue(page.contains("3 / 1 / 0"), "l'ancre");
        assertTrue(page.contains("nord"), "la façade de référence, en français");
        assertTrue(page.contains("test_hut_01.schem"), "le nom du fichier");
        assertTrue(page.contains("schematic présent"));
        assertTrue(page.contains("210"), "le volume en blocs");
    }

    @Test
    void theLibraryFiltersPortalArchesAndShowsTheirPassage() throws Exception {
        start();
        seed("building.definition.list", LIBRARY.replace("\"version\":1}", "\"version\":1},"
                + "{\"id\":\"arche_test\",\"name\":\"Arche de test\",\"description\":\"\","
                + "\"sizeX\":5,\"sizeY\":6,\"sizeZ\":2,\"anchorX\":2,\"anchorY\":0,\"anchorZ\":0,"
                + "\"front\":\"NORTH\",\"materials\":[],\"schematic\":\"arche_test.schem\","
                + "\"schematicPresent\":true,\"version\":1,\"kind\":\"portal\",\"passage\":\"1..3 / 1..4 / 0..1\"}"));

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Portails · 1"), "filtre « Portails »");
        assertTrue(page.contains("Bâtiments · 1"), "la hutte, sans type déclaré, est un bâtiment");
        assertTrue(page.contains("data-filter-cat=\"portal\""), "arche filtrable");
        assertTrue(page.contains("Arche de portail") && page.contains("1..3 / 1..4 / 0..1"), "type et passage");
    }

    // ---- #284 : retirer un bâtiment de la bibliothèque --------------------------------------------

    /** Trois cas réels : un exemple embarqué posé, un bâtiment libre, et le refus d'un bâtiment posé. */
    private static final String LIBRARY_284 = "{"
            + "\"engineAvailable\":true,\"engineReason\":\"\",\"problems\":[],"
            + "\"buildings\":["
            + "{\"id\":\"test_hut_01\",\"name\":\"Hutte de test\",\"description\":\"\","
            + "\"sizeX\":7,\"sizeY\":6,\"sizeZ\":5,\"anchorX\":3,\"anchorY\":1,\"anchorZ\":0,"
            + "\"front\":\"NORTH\",\"materials\":[],\"schematic\":\"test_hut_01.schem\","
            + "\"schematicPresent\":true,\"version\":1,\"bundled\":true},"
            + "{\"id\":\"bibliotheque_v1\",\"name\":\"Bibliothèque V1\",\"description\":\"\","
            + "\"sizeX\":19,\"sizeY\":14,\"sizeZ\":19,\"anchorX\":9,\"anchorY\":0,\"anchorZ\":9,"
            + "\"front\":\"WEST\",\"materials\":[],\"schematic\":\"bibliotheque_v1.schem\","
            + "\"schematicPresent\":true,\"version\":3,\"bundled\":false}"
            + "]}";

    @Test
    void theLibraryOffersToRetireOnlyWhatCanReallyBeRetired() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY_284);
        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("value=\"building.definition.retire\""), "un bâtiment libre peut être retiré");
        assertTrue(page.contains("name=\"id\" value=\"bibliotheque_v1\""));
        assertTrue(page.contains("version 3"), "la version visée est dite");
        assertTrue(page.contains("archivés"), "et ce qu'il advient des fichiers");
        assertTrue(page.contains("Exemple fourni avec le plugin"), "l'exemple embarqué explique son refus");
        assertFalse(page.contains("name=\"id\" value=\"test_hut_01\""), "pas de formulaire pour lui");
    }

    @Test
    void retiringNeedsConsentRecordsTheAuthorAndRefusesAPlacedBuildingBeforeTheQueue() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY_284);
        String token = csrf(get("/buildings/library?agent=" + TestConfig.AGENT_ID).body());
        String base = "_csrf=" + token + "&type=building.definition.retire&agent=" + TestConfig.AGENT_ID
                + "&return=/buildings/library";

        long before = count("building.definition.retire");
        post("/agents/action", base + "&id=bibliotheque_v1");
        assertEquals(before, count("building.definition.retire"), "sans case cochée, rien ne part");

        post("/agents/action", base + "&id=test_hut_01&confirm=true");
        assertEquals(before, count("building.definition.retire"),
                "un bâtiment posé (buildsite_0002) est refusé avant la file");

        post("/agents/action", base + "&id=bibliotheque_v1&confirm=true&by=quelquun");
        assertEquals(before + 1, count("building.definition.retire"));
        AgentActionRow queued = queued("building.definition.retire");
        assertEquals("bibliotheque_v1", queued.params().get("id"));
        assertEquals("true", queued.params().get("confirm"));
        assertEquals(TestConfig.OWNER_USERNAME, queued.params().get("by"),
                "l'auteur est la session, jamais un champ du formulaire");
    }

    /** La bibliothèque est en lecture seule : aucun formulaire d'écriture ne doit y figurer. */
    /** #269 : l'ancre affichée est celle de CE bâtiment, pas une convention unique. */
    @Test
    void theLibraryDescribesEachBuildingsRealAnchor() throws Exception {
        start();
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("tombe au milieu de la façade d&#39;entrée, 1 bloc au-dessus du bas")
                || page.contains("tombe au milieu de la façade d'entrée, 1 bloc au-dessus du bas"), "la hutte");
        assertFalse(page.contains("centre de la porte, au niveau du sol"), "l'ancienne phrase, fausse pour l'Atelier");
    }

    @Test
    void theLibraryOffersNoWriteFormAtAll() throws Exception {
        start();
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("building.definition.create"));
        assertFalse(page.contains("building.definition.update"));
        assertFalse(page.contains("building.definition.delete"));
        assertTrue(page.contains("ne se modifie pas"), "et la page explique pourquoi");
        assertTrue(page.contains("nouvelle version dans l'Atelier de création"), "et quoi faire");
    }

    /**
     * Une définition sans fichier reste <strong>visible</strong>, marquée absente.
     *
     * <p>La définition est du contenu correct : c'est l'artefact qui manque. La cacher laisserait
     * l'administrateur devant une bibliothèque vide sans comprendre pourquoi la pose est refusée.
     * </p>
     */
    @Test
    void aDefinitionWithoutItsFileIsStillShownAndExplained() throws Exception {
        start();
        seed("building.definition.list",
                LIBRARY.replace("\"schematicPresent\":true", "\"schematicPresent\":false"));

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Hutte de test"), "la fiche reste affichée");
        assertTrue(page.contains("schematic absent"));
        assertTrue(page.contains("rpgadmin building generate"), "et on dit comment le produire");
    }

    @Test
    void anUnavailableEngineIsAnnouncedWithoutHidingTheLibrary() throws Exception {
        start();
        seed("building.definition.list", LIBRARY
                .replace("\"engineAvailable\":true", "\"engineAvailable\":false")
                .replace("\"engineReason\":\"\"",
                        "\"engineReason\":\"WorldEdit n'est pas installé sur ce serveur.\""));

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Moteur de schematics indisponible"));
        assertTrue(page.contains("WorldEdit n'est pas installé"));
        assertTrue(page.contains("Hutte de test"), "la bibliothèque reste consultable");
    }

    @Test
    void aRefusedFileIsReportedByName() throws Exception {
        start();
        seed("building.definition.list", LIBRARY.replace("\"problems\":[]",
                "\"problems\":[\"casse.yml : Section « size » absente.\"]"));

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("casse.yml"), "le fichier est nommé");
        assertTrue(page.contains("Fichier refusé"));
    }

    // ---- Associer un bâtiment ------------------------------------------------------------------

    @Test
    void anEmptySiteOffersToChooseABuilding() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Choisir un bâtiment"));
        assertTrue(page.contains("building.placement.preview"));
        assertTrue(page.contains("Hutte de test"), "le bâtiment est proposé dans la liste");
        assertTrue(page.contains("Rien n'est écrit dans le monde à cette étape"),
                "et la page le dit");
    }

    /** Deux bâtiments : la présélection n'est prouvée que si ce n'est pas le premier de la liste. */
    private static final String LIBRARY_TWO = LIBRARY.replace("]}", ","
            + "{\"id\":\"tour_de_guet\",\"name\":\"Tour de guet\",\"description\":\"\","
            + "\"sizeX\":5,\"sizeY\":12,\"sizeZ\":5,\"anchorX\":2,\"anchorY\":1,\"anchorZ\":0,"
            + "\"front\":\"SOUTH\",\"materials\":[\"stone_bricks\"],"
            + "\"schematic\":\"tour_de_guet.schem\",\"schematicPresent\":true,\"version\":1}"
            + "]}");

    /**
     * Issue #268 : on part d'un bâtiment de la bibliothèque pour le poser. Le lien mène aux
     * emplacements avec ce bâtiment déjà choisi — sans rien envoyer au serveur.
     */
    @Test
    void placingFromTheLibraryPreselectsTheBuildingOnFreeSites() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY_TWO);

        String library = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(library.contains("href=\"/buildings/sites?place=tour_de_guet\""),
                "chaque bâtiment propose « Placer sur un emplacement »");
        assertTrue(library.contains("Placer sur un emplacement"));

        long before = count("building.placement.preview");
        String sites = get("/buildings/sites?agent=" + TestConfig.AGENT_ID + "&place=tour_de_guet").body();
        assertTrue(sites.contains("Poser « Tour de guet »"), "la page dit ce qu'on est venu faire");
        assertTrue(sites.contains("(1 sur cette page)"), "un seul emplacement libre sur deux");
        // Le formulaire de pose de l'emplacement libre — l'emplacement occupé, lui, garde son
        // bâtiment actuel présélectionné dans « Réorienter », et c'est voulu.
        int at = sites.indexOf("value=\"building.placement.preview\"");
        String choose = sites.substring(sites.lastIndexOf("<form", at), sites.indexOf("</form>", at));
        assertTrue(choose.contains("<option value=\"tour_de_guet\" selected>"),
                "le bâtiment choisi est présélectionné");
        assertFalse(choose.contains("<option value=\"test_hut_01\" selected>"), "et lui seul");
        assertEquals(before, count("building.placement.preview"), "rien n'est envoyé en arrivant");
    }

    @Test
    void anUnknownBuildingToPlaceIsIgnored() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY_TWO);

        String sites = get("/buildings/sites?agent=" + TestConfig.AGENT_ID
                + "&place=%3Cscript%3E").body();
        assertFalse(sites.contains("Poser « "), "aucun bandeau pour un bâtiment inconnu");
        assertFalse(sites.contains("\" selected>Tour"), "aucune présélection");
    }

    /** Le vrai formulaire de choix part bien avec l'emplacement et le bâtiment. */
    @Test
    void theRealChooseFormQueuesAPreviewForThatSiteAndBuilding() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        HttpResponse<String> res = submitRealForm("building.placement.preview", Map.of());

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("")
                .startsWith("/buildings/sites?"), "retour sur la page, pas sur /agents");
        AgentActionRow queued = queued("building.placement.preview");
        assertEquals("buildsite_0001", queued.params().get("id"));
        assertEquals("test_hut_01", queued.params().get("building"));
        // Un aperçu n'écrit rien : il n'a aucune raison d'exiger une confirmation.
        assertFalse(queued.params().containsKey("confirm"), queued.params().toString());
    }

    @Test
    void withoutAnyBuildingTheSiteSaysSoRatherThanOfferingAnEmptyList() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY.replace(
                LIBRARY.substring(LIBRARY.indexOf("\"buildings\":[")), "\"buildings\":[]}"));

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Aucun bâtiment posable"));
        assertFalse(page.contains("building.placement.place"));
    }

    @Test
    void withoutTheEngineNoSiteOffersToPlaceAnything() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY
                .replace("\"engineAvailable\":true", "\"engineAvailable\":false")
                .replace("\"engineReason\":\"\"", "\"engineReason\":\"WorldEdit absent.\""));

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Aucun bâtiment ne peut être posé"));
        assertFalse(page.contains("building.placement.preview"),
                "pas de bouton qui promettrait ce qu'il ne peut pas tenir");
    }

    // ---- L'aperçu ------------------------------------------------------------------------------

    @Test
    void thePreviewShowsRotationFootprintAndBlockCount() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_OK);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Aperçu de la pose"));
        assertTrue(page.contains("90°"), "la rotation calculée");
        assertTrue(page.contains("708..712 / 66..71 / -705..-699"), "l'emprise exacte");
        assertTrue(page.contains("210"), "le nombre de blocs");
        assertTrue(page.contains("42"), "les blocs non-air déjà présents");
        assertTrue(page.contains("5 × 7 × 6"),
                "l'emprise réelle : largeur et profondeur échangées à 90°");
        assertTrue(page.contains("largeur et profondeur échangées"), "et on l'explique");
    }

    /** Le bouton de pose n'existe qu'après un aperçu. C'est la garantie du parcours. */
    @Test
    void thePlaceButtonAppearsOnlyAfterAPreview() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        String before = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();
        assertFalse(before.contains("building.placement.place"),
                "sans aperçu, aucun bouton de pose");

        seed("building.placement.preview", PREVIEW_OK);
        String after = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(after.contains("building.placement.place"));
        assertTrue(after.contains("Placer dans le monde"));
    }

    /**
     * Un aperçu calculé pour un autre emplacement ne s'affiche pas ici.
     *
     * <p>Sans cette vérification, la fiche de {@code buildsite_0001} montrerait l'emprise de
     * {@code buildsite_0002} et proposerait de poser — avec les mauvais chiffres sous les yeux.</p>
     */
    @Test
    void aPreviewComputedForAnotherSiteIsNotShownHere() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview",
                PREVIEW_OK.replace("\"siteId\":\"buildsite_0001\"",
                        "\"siteId\":\"buildsite_0404\""));

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("Aperçu de la pose"),
                "l'aperçu ne vaut que pour l'emplacement qu'il nomme");
        assertFalse(page.contains("building.placement.place"));
    }

    /** Un aperçu refusé montre ses motifs et ne propose pas de poser. */
    @Test
    void aRefusedPreviewShowsItsReasonsAndOffersNoPlaceButton() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_OK
                .replace("\"placeable\":true", "\"placeable\":false")
                .replace("\"refusals\":[]",
                        "\"refusals\":[\"L'emprise dépasse le plafond du monde (71 ≥ 70).\"]"));

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("dépasse le plafond du monde"));
        assertTrue(page.contains("La pose est refusée tant que"));
        assertFalse(page.contains("building.placement.place"));
    }

    @Test
    void aWarningIsShownWithoutBlockingThePlacement() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_OK.replace("\"warnings\":[]",
                "\"warnings\":[\"La zone contient déjà 200 blocs non-air sur 210.\"]"));

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("200 blocs non-air"));
        assertTrue(page.contains("building.placement.place"),
                "un avertissement n'empêche pas de poser");
    }

    @Test
    void anUncountedZoneSaysSoRatherThanShowingZero() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview",
                PREVIEW_OK.replace("\"nonAirBlocks\":42", "\"nonAirBlocks\":-1"));

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("non calculé"), "ne pas savoir n'est pas « zéro »");
    }

    // ---- La pose -------------------------------------------------------------------------------

    /** Le vrai formulaire de pose : il porte l'emplacement, le bâtiment, et la confirmation. */
    @Test
    void theRealPlaceFormCarriesTheSiteTheBuildingAndTheConfirmation() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_OK);

        HttpResponse<String> res = submitRealForm("building.placement.place", Map.of());

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("")
                .startsWith("/buildings/sites?"));
        AgentActionRow queued = queued("building.placement.place");
        assertEquals("buildsite_0001", queued.params().get("id"));
        assertEquals("test_hut_01", queued.params().get("building"));
        assertEquals("true", queued.params().get("confirm"),
                "écrire dans le monde exige une confirmation explicite");
    }

    /** La pose est déclarée sensible : la page exige une case cochée. */
    @Test
    void thePlaceFormRequiresAnExplicitConsentCheckbox() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_OK);

        Map<String, String> fields = realFormFields(
                get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body(),
                "building.placement.place");

        assertTrue(fields.containsKey("confirm"), fields.toString());
    }

    @Test
    void submittingThePlaceFormTwiceQueuesTwoRequestsAndNothingElse() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_OK);

        submitRealForm("building.placement.place", Map.of());
        submitRealForm("building.placement.place", Map.of());

        // Le panel ne décide pas de l'idempotence : c'est le serveur qui refuse la seconde pose,
        // et la base qui la refuserait de toute façon. Ce qui se vérifie ici, c'est qu'aucune
        // autre couche n'est touchée au passage.
        assertEquals(2, count("building.placement.place"));
        assertEquals(0, count("building.placement.rollback"));
        assertEquals(0, count("building.site.delete"));
    }

    // ---- L'emplacement occupé ------------------------------------------------------------------

    @Test
    void anOccupiedSiteShowsWhatItCarries() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Bâtiment posé"));
        assertTrue(page.contains("797..803 / 69..74 / -800..-796"), "l'emprise réellement occupée");
        assertTrue(page.contains("occupé"), "l'état de la fiche");
        // Issue #234 : le libellé a changé par conception. « Annuler la pose » décrivait un retour
        // en arrière sur la DERNIÈRE opération ; l'action rend désormais le terrain d'ORIGINE et
        // libère l'emplacement, ce qui n'est pas la même promesse.
        // Issue #239 : « Libérer l'emplacement » se lisait mal et se confondait avec la suppression
        // du repère ; l'action dit désormais exactement ce qu'elle fait.
        assertTrue(page.contains("Retirer le bâtiment et restaurer le terrain"),
                "la libération doit être nommée pour ce qu'elle fait");
        assertTrue(page.contains("building.placement.rollback"));
        // Et les deux orientations, l'une sous l'autre : intention du site contre fait posé.
        assertTrue(page.contains("Orientation souhaitée du site"), "l'intention de l'emplacement");
        assertTrue(page.contains("Orientation du bâtiment posé"), "le fait réellement posé");
    }

    /** Un emplacement occupé ne propose pas d'en choisir un autre : il faut d'abord retirer. */
    @Test
    void anOccupiedSiteDoesNotOfferToChooseAnotherBuilding() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();
        // buildsite_0002 est occupé : son bloc de fiche ne doit pas contenir de formulaire d'aperçu.
        String occupied = page.substring(page.indexOf("Déjà bâtie"));

        assertFalse(occupied.contains("building.placement.preview"),
                "l'emplacement occupé ne propose pas d'affectation");
    }

    @Test
    void theRealRollbackFormCarriesTheSiteAndTheConfirmation() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        HttpResponse<String> res = submitRealForm("building.placement.rollback",
                Map.of("confirm_site", "buildsite_0002"));

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("")
                .startsWith("/buildings/sites?"));
        AgentActionRow queued = queued("building.placement.rollback");
        assertEquals("buildsite_0002", queued.params().get("id"));
        assertEquals("true", queued.params().get("confirm"));
    }

    /** Issue #239 : confirmation forte — sans l'identifiant retapé, rien ne part. */
    @Test
    void theRollbackNeedsTheSiteIdentifierRetyped() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        submitRealForm("building.placement.rollback", Map.of());
        submitRealForm("building.placement.rollback", Map.of("confirm_site", "buildsite_0001"));

        assertEquals(0, count("building.placement.rollback"), "un autre identifiant ne vaut pas");
    }

    /**
     * Issue #239 : le repère d'un emplacement OCCUPÉ ne se supprime pas — le bâtiment resterait dans
     * le monde sans aucun moyen de rendre le terrain. L'écran le dit, et un POST forgé est refusé.
     */
    @Test
    void anOccupiedSiteCannotLoseItsMarkerBeforeItsBuilding() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();
        String occupied = page.substring(page.indexOf("Déjà bâtie"));
        assertTrue(occupied.contains("Un bâtiment est encore posé ici"), "la raison est dite");

        String token = csrf(page);
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&agent=" + TestConfig.AGENT_ID
                + "&type=building.site.delete&return=/buildings/sites&id=buildsite_0002&confirm=true");
        assertTrue(java.net.URLDecoder.decode(res.headers().firstValue("Location").orElse(""),
                java.nio.charset.StandardCharsets.UTF_8).contains("Un bâtiment est encore posé"));
        assertEquals(0, count("building.site.delete"), "rien n'est parti vers le serveur");
    }

    /**
     * Issue #239 : un rôle sans le droit de restaurer ne voit pas un vide — il lit pourquoi, et qui
     * peut le faire. Un bouton qui disparaît sans un mot ressemblait à une régression.
     */
    @Test
    void aRoleWithoutRollbackReadsWhyInsteadOfSeeingNothing() throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);
        String users = get("/users").body();
        post("/users/create", "_csrf=" + csrf(users) + "&username=batisseur"
                + "&password=" + enc("mot-de-passe-batisseur-1") + "&role=BUILDER");
        jar.clear();
        String token = csrf(get("/login").body());
        post("/login", "username=batisseur&password=" + enc("mot-de-passe-batisseur-1") + "&_csrf=" + token);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Retirer le bâtiment et restaurer le terrain"), "l'action est nommée");
        assertTrue(page.contains("Votre rôle ne permet pas de retirer un bâtiment posé"), page.length() + "");
        assertFalse(page.contains("value=\"building.placement.rollback\""), "mais aucun formulaire");
    }

    /**
     * Sans sauvegarde, le bouton de retour arrière est <strong>absent</strong>, pas grisé.
     *
     * <p>Un bouton présent et voué à échouer est pire qu'un message : il laisse croire qu'on peut
     * défaire. Et remettre de l'air dans l'emprise détruirait le terrain d'origine.</p>
     */
    @Test
    void aPlacementWithoutBackupOffersNoRollbackButtonAndSaysWhy() throws Exception {
        start();
        seed("building.site.list",
                SITES.replace("\"restorable\":true", "\"restorable\":false"));
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        assertTrue(page.contains("Aucune sauvegarde du terrain d&#39;avant la pose")
                        || page.contains("Aucune sauvegarde du terrain d'avant la pose"),
                "le refus doit être écrit, et dire qu'il porte sur le TERRAIN");
        assertTrue(page.contains("détruirait le terrain d&#39;origine")
                        || page.contains("détruirait le terrain d'origine"));
        assertFalse(page.contains("building.placement.rollback"),
                "aucun bouton qui serait voué à échouer");
    }

    /**
     * L'avertissement qui compte, et la promesse exacte.
     *
     * <p>Issue #234 : la promesse a changé. Ce n'est plus « la zone telle qu'elle était avant la
     * pose » mais « le terrain d'origine, celui d'avant le premier bâtiment ». La distinction est
     * tout l'objet du ticket, donc l'écran doit la porter.</p>
     */
    @Test
    void theFreeZoneWarnsThatLaterWorkWillBeOverwrittenAndPromisesTheOriginalTerrain()
            throws Exception {
        start();
        seed("building.site.list", SITES);
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();

        // Issue #239 : mêmes promesses, en français courant.
        assertTrue(page.contains("depuis la pose est aussi effacé"),
                "le risque doit être écrit noir sur blanc");
        assertTrue(page.contains("avant sa première pose"),
                "la promesse porte sur le terrain d'ORIGINE, et dit de quel état il s'agit");
        assertTrue(page.contains("redevient libre pour une autre construction"),
                "un emplacement libéré ne doit pas être une impasse");
        assertTrue(page.contains("est <strong>conservé</strong>"),
                "et il dit que le repère reste — ce n'est pas une suppression");
    }

    // ---- Harnais -------------------------------------------------------------------------------

    /** Soumet le formulaire tel que la page le rend, en ne remplaçant que ce qu'on fournit. */
    private HttpResponse<String> submitRealForm(String actionType, Map<String, String> filled)
            throws Exception {
        String page = get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body();
        Map<String, String> fields = new LinkedHashMap<>(realFormFields(page, actionType));
        fields.putAll(filled);
        StringBuilder body = new StringBuilder();
        fields.forEach((k, v) -> {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(enc(k)).append('=').append(enc(v));
        });
        return post("/agents/action", body.toString());
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
            Matcher selects = Pattern.compile("<select\\b[^>]*>(.*?)</select>", Pattern.DOTALL)
                    .matcher(form);
            while (selects.find()) {
                String name = attribute(selects.group(0), "name");
                if (name == null) {
                    continue;
                }
                Matcher options = Pattern.compile("<option\\b[^>]*>").matcher(selects.group(1));
                String chosen = "";
                boolean first = true;
                while (options.find()) {
                    String option = options.group(0);
                    String value = unescape(attribute(option, "value") == null ? ""
                            : attribute(option, "value"));
                    if (first) {
                        chosen = value;
                        first = false;
                    }
                    if (option.contains("selected")) {
                        chosen = value;
                    }
                }
                fields.put(name, chosen);
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
                .replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    /** Enfile une action, la livre à l'agent, et renvoie un succès avec le détail fourni. */
    private void seed(String type, String details) throws Exception {
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent="
                + TestConfig.AGENT_ID + "&return=/buildings/sites"
                + (type.startsWith("building.placement") ? "&id=buildsite_0001"
                        + "&building=test_hut_01&confirm=true" : ""));
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
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
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
