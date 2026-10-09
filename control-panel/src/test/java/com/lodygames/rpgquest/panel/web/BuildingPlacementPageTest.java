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

    /** La bibliothèque est en lecture seule : aucun formulaire d'écriture ne doit y figurer. */
    @Test
    void theLibraryOffersNoWriteFormAtAll() throws Exception {
        start();
        seed("building.definition.list", LIBRARY);

        String page = get("/buildings/library?agent=" + TestConfig.AGENT_ID).body();

        assertFalse(page.contains("building.definition.create"));
        assertFalse(page.contains("building.definition.update"));
        assertFalse(page.contains("building.definition.delete"));
        assertTrue(page.contains("lecture seule"), "et la page explique pourquoi");
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
        assertTrue(page.contains("Libérer l'emplacement")
                        || page.contains("Libérer l&#39;emplacement"),
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

        HttpResponse<String> res = submitRealForm("building.placement.rollback", Map.of());

        assertEquals(303, res.statusCode());
        assertTrue(res.headers().firstValue("Location").orElse("")
                .startsWith("/buildings/sites?"));
        AgentActionRow queued = queued("building.placement.rollback");
        assertEquals("buildsite_0002", queued.params().get("id"));
        assertEquals("true", queued.params().get("confirm"));
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

        assertTrue(page.contains("Aucune sauvegarde du terrain n&#39;est associée")
                        || page.contains("Aucune sauvegarde du terrain n'est associée"),
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

        assertTrue(page.contains("après</em> la pose sera également écrasé"),
                "le risque doit être écrit noir sur blanc");
        assertTrue(page.contains("terrain d&#39;origine") || page.contains("terrain d'origine"),
                "la promesse porte sur le terrain d'ORIGINE");
        assertTrue(page.contains("avant le premier bâtiment"),
                "et elle dit explicitement de quel état il s'agit");
        assertTrue(page.contains("immédiatement réutilisable"),
                "un emplacement libéré ne doit pas être une impasse");
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
