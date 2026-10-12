package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Issue #234 — la fiche d'un emplacement OCCUPÉ, telle que le navigateur la reçoit.
 *
 * <h2>Ce qui est vérifié ici, et pourquoi par HTTP</h2>
 *
 * <p>Les pages sont <strong>réellement rendues</strong> et les relevés arrivent par le vrai chemin
 * de l'agent. C'est ce qui permet de vérifier ce que l'administrateur <em>lit</em>, et non ce que le
 * code croit produire — le défaut qui avait produit #227.</p>
 *
 * <p>Deux propriétés comptent plus que les autres : la fiche doit distinguer
 * <strong>l'intention</strong> de l'emplacement du <strong>fait</strong> posé, et le bouton qui
 * écrit dans le monde ne doit exister qu'<strong>après</strong> un aperçu applicable, en portant le
 * jeton de cet aperçu.</p>
 */
class BuildingLifecyclePageTest {

    @TempDir
    Path tmp;

    private PanelApp app;
    private AgentStore store;
    private int port;
    private HttpClient client;
    private final Map<String, String> jar = new LinkedHashMap<>();

    /** Un emplacement occupé, dont l'orientation a changé APRÈS la pose : la divergence du ticket. */
    private static final String SITES_DIVERGENT = sites("NORTH", 180, true, false);
    /** Le même, aligné : aucune divergence à signaler. */
    private static final String SITES_ALIGNED = sites("NORTH", 0, false, false);
    /** Le même, avec une définition qui a changé depuis la pose. */
    private static final String SITES_OUTDATED = sites("NORTH", 0, false, true);

    private static String sites(String facing, int placedRotation, boolean diverges,
                                boolean outdated) {
        return "{"
                + "\"total\":1,\"worlds\":[\"world_hub\"],"
                + "\"sites\":["
                + "{\"id\":\"buildsite_0002\",\"name\":\"Déjà bâtie\",\"description\":\"\","
                + "\"world\":\"world_hub\",\"x\":800,\"y\":70,\"z\":-800,\"facing\":\"" + facing
                + "\",\"status\":\"OCCUPIED\",\"createdBy\":\"Lody\","
                + "\"createdAt\":\"2026-10-08T18:05:00Z\",\"worldLoaded\":true}"
                + "],"
                + "\"placements\":["
                + "{\"siteId\":\"buildsite_0002\",\"buildingId\":\"test_hut_01\","
                + "\"buildingName\":\"Hutte de test\",\"world\":\"world_hub\","
                + "\"anchorX\":800,\"anchorY\":70,\"anchorZ\":-800,"
                + "\"rotation\":" + placedRotation + ","
                + "\"minX\":797,\"minY\":69,\"minZ\":-800,\"maxX\":803,\"maxY\":74,"
                + "\"maxZ\":-796,"
                + "\"placedBy\":\"Lody\",\"placedAt\":\"2026-10-08T19:00:00Z\","
                + "\"restorable\":true,"
                + "\"buildingVersion\":1,\"schematicSha\":\"abc123def456789\","
                + "\"libraryVersion\":" + (outdated ? 2 : 1) + ","
                + "\"librarySha\":\"" + (outdated ? "999888777666" : "abc123def456789") + "\","
                + "\"outdated\":" + outdated + ","
                + "\"desiredRotation\":" + (diverges ? 0 : placedRotation) + ","
                + "\"diverges\":" + diverges + ","
                + "\"restoreSource\":\"terrain d'origine (1 sauvegarde)\"}"
                + "]}";
    }

    /**
     * Issue #239 — un emplacement OCCUPÉ dont le relevé ne porte AUCUN placement.
     *
     * <p>C'est l'état réel du serveur avant le correctif : l'exécuteur ne sérialisait pas les
     * bâtiments posés. La fiche retombait alors sur la branche « emplacement vide » et réaffichait
     * un aperçu de pose périmé, bouton « Placer dans le monde » compris.</p>
     */
    private static final String SITES_OCCUPIED_NO_PLACEMENT = "{"
            + "\"total\":1,\"worlds\":[\"world_hub\"],"
            + "\"sites\":["
            + "{\"id\":\"buildsite_0002\",\"name\":\"Déjà bâtie\",\"description\":\"\","
            + "\"world\":\"world_hub\",\"x\":800,\"y\":70,\"z\":-800,\"facing\":\"SOUTH\","
            + "\"status\":\"OCCUPIED\",\"createdBy\":\"Lody\","
            + "\"createdAt\":\"2026-10-08T18:05:00Z\",\"worldLoaded\":true}"
            + "],\"placements\":[]}";

    /** Le même emplacement, mais VIDE : l'aperçu doit continuer d'être proposé. */
    private static final String SITES_EMPTY_SOUTH = "{"
            + "\"total\":1,\"worlds\":[\"world_hub\"],"
            + "\"sites\":["
            + "{\"id\":\"buildsite_0002\",\"name\":\"Terrain libre\",\"description\":\"\","
            + "\"world\":\"world_hub\",\"x\":800,\"y\":70,\"z\":-800,\"facing\":\"SOUTH\","
            + "\"status\":\"EMPTY\",\"createdBy\":\"Lody\","
            + "\"createdAt\":\"2026-10-08T18:05:00Z\",\"worldLoaded\":true}"
            + "],\"placements\":[]}";

    /** Le même emplacement vide, orienté NORTH : l'aperçu calculé pour NORTH y est donc valable. */
    private static final String SITES_EMPTY_NORTH =
            SITES_EMPTY_SOUTH.replace("\"facing\":\"SOUTH\"", "\"facing\":\"NORTH\"");

    /**
     * Un aperçu de POSE calculé alors que l'emplacement était orienté NORTH (issue #239).
     *
     * <p>Distinct de {@link #PREVIEW_OK}, qui est un aperçu de <em>remplacement</em> : celui-ci est
     * le relevé de {@code building.placement.preview}, dont les champs portent l'orientation du site
     * au moment du calcul.</p>
     */
    private static final String PREVIEW_STALE_NORTH = "{"
            + "\"placeable\":true,\"siteId\":\"buildsite_0002\",\"siteName\":\"Terrain libre\","
            + "\"siteFacing\":\"NORTH\","
            + "\"buildingId\":\"test_hut_01\",\"buildingName\":\"Hutte de test\","
            + "\"front\":\"NORTH\",\"sizeX\":7,\"sizeY\":6,\"sizeZ\":5,"
            + "\"rotation\":0,"
            + "\"anchorX\":800,\"anchorY\":70,\"anchorZ\":-800,"
            + "\"minX\":797,\"minY\":69,\"minZ\":-800,\"maxX\":803,\"maxY\":74,"
            + "\"maxZ\":-796,\"blockCount\":210,\"nonAirCount\":120,"
            + "\"refusals\":[],\"warnings\":[]}";

    private static final String LIBRARY = "{"
            + "\"engineAvailable\":true,\"engineReason\":\"\",\"problems\":[],"
            + "\"buildings\":["
            + "{\"id\":\"test_hut_01\",\"name\":\"Hutte de test\",\"description\":\"Hutte.\","
            + "\"sizeX\":7,\"sizeY\":6,\"sizeZ\":5,\"anchorX\":3,\"anchorY\":1,\"anchorZ\":0,"
            + "\"front\":\"NORTH\",\"materials\":[\"cobblestone\"],"
            + "\"schematic\":\"test_hut_01.schem\",\"schematicPresent\":true,\"version\":1},"
            + "{\"id\":\"test_watchtower_01\",\"name\":\"Tour de garde de test\","
            + "\"description\":\"Tour.\","
            + "\"sizeX\":9,\"sizeY\":14,\"sizeZ\":9,\"anchorX\":4,\"anchorY\":1,\"anchorZ\":0,"
            + "\"front\":\"NORTH\",\"materials\":[\"stone_bricks\"],"
            + "\"schematic\":\"test_watchtower_01.schem\",\"schematicPresent\":true,"
            + "\"version\":1}"
            + "]}";

    /** Un aperçu de remplacement APPLICABLE, avec son jeton. */
    private static final String PREVIEW_OK = "{"
            + "\"applicable\":true,\"operation\":\"REPLACE\",\"site_id\":\"buildsite_0002\","
            + "\"site_facing\":\"NORTH\",\"requested_facing\":\"EAST\","
            + "\"current_building_id\":\"test_hut_01\","
            + "\"current_building_name\":\"Hutte de test\",\"current_rotation\":0,"
            + "\"current_footprint\":\"797..803 / 69..74 / -800..-796\","
            + "\"current_block_count\":210,"
            + "\"target_building_id\":\"test_watchtower_01\","
            + "\"target_building_name\":\"Tour de garde de test\",\"target_rotation\":90,"
            + "\"target_footprint\":\"796..804 / 69..82 / -804..-796\","
            + "\"target_block_count\":1134,\"target_size\":\"9 × 9 × 14\","
            + "\"target_non_air\":42,\"overlapping\":true,"
            + "\"restore_source\":\"terrain d'origine (1 sauvegarde)\",\"restorable\":true,"
            + "\"refusals\":[],\"warnings\":[\"La nouvelle emprise sort de l'ancienne.\"],"
            + "\"token\":\"0123456789abcdef\"}";

    /** Un aperçu REFUSÉ : aucun bouton d'écriture ne doit apparaître. */
    private static final String PREVIEW_REFUSED = "{"
            + "\"applicable\":false,\"operation\":\"REPLACE\",\"site_id\":\"buildsite_0002\","
            + "\"site_facing\":\"NORTH\",\"requested_facing\":\"EAST\","
            + "\"current_building_id\":\"test_hut_01\","
            + "\"current_building_name\":\"Hutte de test\",\"current_rotation\":0,"
            + "\"current_footprint\":\"797..803 / 69..74 / -800..-796\","
            + "\"current_block_count\":210,"
            + "\"target_building_id\":\"test_watchtower_01\","
            + "\"target_building_name\":\"Tour de garde de test\",\"target_rotation\":90,"
            + "\"target_footprint\":\"796..804 / 69..82 / -804..-796\","
            + "\"target_block_count\":1134,\"target_size\":\"9 × 9 × 14\","
            + "\"target_non_air\":-1,\"overlapping\":true,"
            + "\"restore_source\":\"terrain d'origine (1 sauvegarde)\",\"restorable\":true,"
            + "\"refusals\":[\"L'emprise dépasse le plafond du monde.\"],\"warnings\":[],"
            + "\"token\":\"\"}";

    private static final String HISTORY = "{"
            + "\"site_id\":\"buildsite_0002\",\"lines\":["
            + "{\"operation\":\"REPLACE\",\"operation_label\":\"Remplacement\","
            + "\"building_id\":\"test_hut_01\",\"building_version\":1,\"sha\":\"abc123def456\","
            + "\"rotation\":0,\"footprint\":\"797..803 / 69..74 / -800..-796\","
            + "\"actor\":\"Lody\",\"at\":\"2026-10-09T20:00:00Z\",\"ok\":true,"
            + "\"detail\":\"Remplacement appliqué.\"},"
            + "{\"operation\":\"ROTATE\",\"operation_label\":\"Réorientation\","
            + "\"building_id\":\"test_hut_01\",\"building_version\":1,\"sha\":\"abc123def456\","
            + "\"rotation\":90,\"footprint\":\"797..803 / 69..74 / -800..-796\","
            + "\"actor\":\"Lody\",\"at\":\"2026-10-09T19:30:00Z\",\"ok\":false,"
            + "\"detail\":\"Le collage a échoué — l'ancien bâtiment a été remis en place.\"}"
            + "]}";

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    // ---- L'intention contre le fait -------------------------------------------------------------

    /**
     * Le principe fondamental du ticket, lu à l'écran.
     *
     * <p>Deux lignes distinctes, et un avertissement qui dit explicitement qu'<strong>aucun bloc
     * n'a été modifié</strong>. Afficher une seule orientation laisserait croire que le monde suit
     * le champ.</p>
     */
    @Test
    void aDivergentSiteShowsBothOrientationsAndSaysNothingWasMoved() throws Exception {
        start();
        seed("building.site.list", SITES_DIVERGENT);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertTrue(page.contains("Orientation souhaitée du site"), "l'intention");
        assertTrue(page.contains("Orientation du bâtiment posé"), "le fait");
        assertTrue(page.contains("a changé après le placement"), "la divergence doit être dite");
        assertTrue(page.contains("n&#39;a pas été modifié")
                        || page.contains("n'a pas été modifié"),
                "l'écran doit affirmer qu'aucun bloc n'a bougé");
    }

    @Test
    void anAlignedSiteShowsNoDivergenceWarning() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertFalse(page.contains("a changé après le placement"),
                "aucun avertissement quand les deux orientations concordent");
    }

    // ---- Issue #239 : un emplacement occupé ne retombe jamais sur la branche « vide » ----------

    /**
     * Le défaut exact du ticket, rejoué par la vraie page.
     *
     * <p>Site OCCUPIED, relevé sans placement : l'écran proposait un aperçu de pose <em>périmé</em>
     * et son bouton « Placer dans le monde ». Un geste de plus et l'on posait un second bâtiment sur
     * un emplacement déjà occupé.</p>
     */
    @Test
    void anOccupiedSiteWithoutPlacementDataNeverOffersToPlaceAgain() throws Exception {
        start();
        seed("building.site.list", SITES_OCCUPIED_NO_PLACEMENT);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertFalse(page.contains("Placer dans le monde"),
                "aucune pose ne doit être proposée sur un emplacement occupé");
        assertFalse(page.contains("Aperçu de la pose"),
                "l'ancien aperçu de pose initiale doit être masqué");
        assertFalse(page.contains("name=\"type\" value=\"building.placement.place\""),
                "le formulaire de pose ne doit même pas exister dans la page");
        // Et il le DIT, au lieu de laisser une section vide.
        assertTrue(page.contains("Bâtiment posé"), "la section attendue reste présente");
        assertTrue(page.contains("ne porte pas le détail du bâtiment posé"),
                "l'écran doit expliquer pourquoi il ne peut rien montrer");
        assertTrue(page.contains("issue #239"),
                "et nommer la cause, pour qu'on sache qu'un JAR à jour est nécessaire");
    }

    /** Même dans ce cas dégradé, les deux orientations restent deux lignes distinctes. */
    @Test
    void anOccupiedSiteWithoutPlacementStillSeparatesIntentionFromFact() throws Exception {
        start();
        seed("building.site.list", SITES_OCCUPIED_NO_PLACEMENT);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertTrue(page.contains("Orientation souhaitée du site"), "l'intention");
        assertTrue(page.contains("Orientation réellement posée"), "le fait");
        assertTrue(page.contains("inconnue — absente du relevé")
                        || page.contains("inconnue &mdash; absente du relevé"),
                "le fait inconnu est dit comme tel, jamais remplacé par l'intention");
        assertTrue(page.contains("ne sont jamais modifiés par un changement d&#39;orientation")
                        || page.contains("ne sont jamais modifiés par un changement d'orientation"),
                "la garantie « changer l'orientation ne touche aucun bloc » reste énoncée");
    }

    /** Le symptôme survivait au rafraîchissement : deux lectures de la page donnent le même état. */
    @Test
    void theDegradedStatePersistsIdenticallyAcrossARefresh() throws Exception {
        start();
        seed("building.site.list", SITES_OCCUPIED_NO_PLACEMENT);
        seed("building.definition.list", LIBRARY);

        String first = fiche();
        String second = fiche();

        for (String page : new String[] {first, second}) {
            assertFalse(page.contains("Placer dans le monde"));
            assertTrue(page.contains("ne porte pas le détail du bâtiment posé"));
        }
    }

    /** Un emplacement VIDE n'est pas affecté : l'aperçu doit continuer d'être proposé. */
    @Test
    void anEmptySiteStillOffersThePreviewAndThePlacement() throws Exception {
        start();
        seed("building.site.list", SITES_EMPTY_SOUTH);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertTrue(page.contains("Choisir un bâtiment"),
                "le parcours de pose reste intact sur un emplacement vide");
        assertFalse(page.contains("ne porte pas le détail du bâtiment posé"),
                "aucun diagnostic de placement manquant sur un emplacement vide");
    }

    /**
     * Issue #239 — un aperçu calculé pour une AUTRE orientation est périmé.
     *
     * <p>L'aperçu capture l'orientation du site au moment du calcul et en déduit sa rotation. Si le
     * site a tourné depuis, cette rotation est fausse : l'écran affichait pourtant le libellé
     * courant du site collé à l'orientation capturée — d'où le « sud NORTH » rapporté en jeu — et
     * proposait tout de même de poser.</p>
     */
    @Test
    void aPreviewComputedForAnotherOrientationIsRejectedAndSaysWhy() throws Exception {
        start();
        // Site VIDE orienté SOUTH, mais l'aperçu a été calculé pour NORTH (voir PREVIEW_OK).
        seed("building.site.list", SITES_EMPTY_SOUTH);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_STALE_NORTH);

        String page = fiche();

        assertTrue(page.contains("a changé depuis le dernier aperçu"),
                "l'écran doit dire que l'aperçu ne vaut plus");
        assertFalse(page.contains("Placer dans le monde"),
                "un aperçu périmé ne doit jamais rester posable");
        assertFalse(page.contains("Aperçu de la pose"),
                "la carte d'aperçu périmée est écartée, pas réaffichée");
    }

    /** Et le mélange « sud NORTH » ne peut plus se produire : chaque valeur est nommée. */
    @Test
    void thePreviewCardNamesEachOrientationInsteadOfJuxtaposingThem() throws Exception {
        start();
        seed("building.site.list", SITES_EMPTY_NORTH);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.preview", PREVIEW_STALE_NORTH);

        String page = fiche();

        assertTrue(page.contains("Aperçu de la pose"), "ici l'aperçu est valable");
        assertTrue(page.contains("Orientation de l&#39;aperçu")
                        || page.contains("Orientation de l'aperçu"),
                "l'orientation POUR LAQUELLE l'aperçu a été calculé est nommée");
        assertTrue(page.contains("au moment du calcul"), "et datée dans son intitulé");
        assertFalse(page.contains("nord</strong> <code class=\"tid\">SOUTH"),
                "jamais deux orientations juxtaposées sans étiquette");
    }

    // ---- Version posée contre version de bibliothèque -------------------------------------------

    /** La version posée est affichée avec son empreinte : ce qui a réellement été collé. */
    @Test
    void thePlacedVersionAndItsFingerprintAreShown() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertTrue(page.contains("Version posée"));
        assertTrue(page.contains("v1"));
        assertTrue(page.contains("abc123def456"), "les douze premiers caractères de l'empreinte");
    }

    /**
     * Une définition modifiée depuis la pose est <strong>annoncée</strong>, jamais appliquée.
     *
     * <p>Le bâtiment posé ne change pas parce que sa définition a changé : l'écran le dit et
     * renvoie vers un geste explicite.</p>
     */
    @Test
    void anOutdatedDefinitionIsAnnouncedButNeverAppliedAutomatically() throws Exception {
        start();
        seed("building.site.list", SITES_OUTDATED);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertTrue(page.contains("version plus récente"), "la divergence de version doit être dite");
        assertTrue(page.contains("jamais modifié tout seul"),
                "l'écran doit affirmer qu'aucune mise à jour n'est automatique");
        assertTrue(page.contains("v2"), "la version de la bibliothèque");
    }

    // ---- L'aperçu, puis seulement la confirmation ------------------------------------------------

    /** Sans aperçu, aucun bouton d'écriture : un aperçu n'est pas une formalité. */
    @Test
    void withoutAPreviewThereIsNoWriteButton() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertTrue(page.contains("building.placement.retarget.preview"),
                "le formulaire d'aperçu doit être proposé");
        assertFalse(page.contains("value=\"building.placement.replace\""),
                "aucun bouton de remplacement avant un aperçu");
        assertFalse(page.contains("value=\"building.placement.reorient\""),
                "aucun bouton de réorientation avant un aperçu");
        assertTrue(page.contains("Calculez un aperçu"), "et l'écran doit le dire");
    }

    /** Avec un aperçu applicable, la confirmation apparaît — et porte le jeton. */
    @Test
    void anApplicablePreviewUnlocksTheConfirmationCarryingItsToken() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.retarget.preview", PREVIEW_OK);

        String page = fiche();

        assertTrue(page.contains("Aperçu — Remplacement"));
        assertTrue(page.contains("797..803 / 69..74 / -800..-796"), "l'ancienne emprise");
        assertTrue(page.contains("796..804 / 69..82 / -804..-796"), "la nouvelle emprise");
        assertTrue(page.contains("value=\"building.placement.replace\""),
                "la confirmation doit être proposée");
        assertTrue(page.contains("name=\"token\" value=\"0123456789abcdef\""),
                "le jeton de l'aperçu doit voyager avec la confirmation");
        assertTrue(page.contains("name=\"facing\" value=\"EAST\""),
                "l'orientation demandée doit être renvoyée telle quelle");
    }

    /** Un aperçu refusé n'ouvre rien, et ses motifs sont affichés. */
    @Test
    void aRefusedPreviewShowsItsReasonsAndUnlocksNothing() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.retarget.preview", PREVIEW_REFUSED);

        String page = fiche();

        assertTrue(page.contains("dépasse le plafond du monde"), "le motif doit être lisible");
        assertFalse(page.contains("value=\"building.placement.replace\""),
                "un aperçu refusé n'ouvre aucun bouton d'écriture");
        assertTrue(page.contains("Cet aperçu est refusé"));
    }

    /** « Inconnu » et « 0 » sont deux réponses différentes, et l'écran les distingue. */
    @Test
    void anUncountedZoneSaysUnknownRatherThanZero() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.retarget.preview", PREVIEW_REFUSED);

        String page = fiche();

        assertTrue(page.contains("inconnu (monde ou chunk déchargé)"),
                "un comptage impossible ne doit pas s'afficher « 0 »");
    }

    /** L'aperçu annonce le recouvrement des emprises : c'est ce qui dit ce qui sera écrasé. */
    @Test
    void thePreviewAnnouncesWhetherTheFootprintsOverlap() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.retarget.preview", PREVIEW_OK);

        String page = fiche();

        assertTrue(page.contains("Recouvrement des emprises"));
        assertTrue(page.contains("une partie de la zone est commune"));
    }

    // ---- La libération, visuellement séparée ----------------------------------------------------

    @Test
    void theFreeActionIsInItsOwnDangerZoneAndNamesItsSource() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);

        String page = fiche();

        assertTrue(page.contains("danger-zone"), "les actions dangereuses sont séparées");
        assertTrue(page.contains("Retirer le bâtiment et restaurer le terrain"));
        assertTrue(page.contains("terrain d&#39;origine (1 sauvegarde)")
                        || page.contains("terrain d'origine (1 sauvegarde)"),
                "l'écran doit dire D'OÙ viendra le terrain");
        assertTrue(page.contains("value=\"building.placement.rollback\""));
    }

    // ---- Le journal -----------------------------------------------------------------------------

    @Test
    void theHistoryShowsSuccessesAndFailuresWithTheirDetail() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.history", HISTORY);

        String page = fiche();

        assertTrue(page.contains("Journal des opérations"));
        assertTrue(page.contains("Remplacement"), "une opération réussie");
        assertTrue(page.contains("Réorientation"), "une opération échouée");
        assertTrue(page.contains("réussie") && page.contains("échec"),
                "les deux résultats doivent être distingués");
        assertTrue(page.contains("l&#39;ancien bâtiment a été remis en place")
                        || page.contains("l'ancien bâtiment a été remis en place"),
                "le détail d'un échec compte plus que celui d'un succès");
    }

    /** Un journal relevé pour un AUTRE emplacement n'est pas affiché ici. */
    @Test
    void aHistoryFromAnotherSiteIsNotShown() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);
        seed("building.placement.history", HISTORY.replace("buildsite_0002", "buildsite_0099"));

        String page = fiche();

        assertTrue(page.contains("Aucun relevé pour cet emplacement"),
                "des opérations attribuées au mauvais emplacement seraient pires qu'aucune");
    }

    // ---- Formulaires réels ----------------------------------------------------------------------

    /**
     * Le formulaire d'aperçu est soumis <strong>tel que la page le rend</strong>.
     *
     * <p>Écrire le corps à la main porterait toujours le bon chemin de retour, même si la page en
     * émettait un refusé — c'est exactement le défaut de #227.</p>
     */
    @Test
    void theRealPreviewFormIsAcceptedAndReturnsToTheBuildingsPage() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);

        HttpResponse<String> response = submitRealForm("building.placement.retarget.preview",
                Map.of("id", "buildsite_0002", "building", "test_watchtower_01",
                        "facing", "EAST"));

        assertEquals(303, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElse("")
                        .startsWith("/buildings/sites"),
                "le retour doit ramener sur la page des emplacements");
    }

    /** Une orientation invalide est refusée par la liste blanche, pas transmise au serveur de jeu. */
    @Test
    void anInvalidFacingIsRejectedByTheWhitelist() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);

        HttpResponse<String> response = submitRealForm("building.placement.retarget.preview",
                Map.of("id", "buildsite_0002", "building", "test_hut_01",
                        "facing", "NORTHWEST"));

        assertEquals(303, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElse("").contains("err"),
                "une orientation hors liste blanche doit être refusée : "
                        + response.headers().firstValue("Location").orElse(""));
    }

    /** Sans jeton CSRF, rien ne passe — avant même la validation des paramètres. */
    @Test
    void aMissingCsrfTokenIsRefused() throws Exception {
        start();
        seed("building.site.list", SITES_ALIGNED);
        seed("building.definition.list", LIBRARY);

        HttpResponse<String> response = post("/agents/action",
                "type=building.placement.reorient&agent=" + TestConfig.AGENT_ID
                        + "&return=/buildings/sites&id=buildsite_0002&facing=EAST");

        assertEquals(403, response.statusCode());
    }

    // ---- Harnais --------------------------------------------------------------------------------

    private String fiche() throws Exception {
        return get("/buildings/sites?agent=" + TestConfig.AGENT_ID
                + "&site=buildsite_0002").body();
    }

    /** Soumet le formulaire tel que la page le rend, en ne remplaçant que ce qu'on fournit. */
    private HttpResponse<String> submitRealForm(String actionType, Map<String, String> filled)
            throws Exception {
        String page = fiche();
        Map<String, String> fields = new LinkedHashMap<>(realFormFields(page, actionType));
        fields.putAll(filled);
        StringBuilder body = new StringBuilder();
        fields.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(k).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return post("/agents/action", body.toString());
    }

    /** Les champs cachés réellement émis par le formulaire de ce type d'action. */
    private static Map<String, String> realFormFields(String page, String actionType) {
        Matcher forms = Pattern.compile("<form[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(page);
        while (forms.find()) {
            String body = forms.group(1);
            if (!body.contains("value=\"" + actionType + "\"")) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher inputs = Pattern.compile("<input\\b[^>]*>").matcher(body);
            while (inputs.find()) {
                String tag = inputs.group();
                Matcher name = Pattern.compile("\\bname=\"([^\"]*)\"").matcher(tag);
                Matcher value = Pattern.compile("\\bvalue=\"([^\"]*)\"").matcher(tag);
                if (name.find()) {
                    fields.put(name.group(1), value.find() ? value.group(1) : "");
                }
            }
            return fields;
        }
        throw new IllegalStateException("aucun formulaire « " + actionType + " » dans la page");
    }

    private void seed(String type, String details) throws Exception {
        String token = csrf(get("/buildings/sites?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent="
                + TestConfig.AGENT_ID + "&return=/buildings/sites"
                + (type.startsWith("building.placement")
                        ? "&id=buildsite_0002&building=test_hut_01&facing=EAST&confirm=true" : ""));
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
                    .map(e -> e.getKey() + "=" + e.getValue()).reduce((x, y) -> x + "; " + y)
                    .orElse(""));
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
