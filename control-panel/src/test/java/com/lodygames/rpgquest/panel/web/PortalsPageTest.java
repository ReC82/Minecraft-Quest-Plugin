package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.audit.InMemoryAuditLog;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.http.Http;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issues #564 / #565 / #566 (#228-P1/P2/P3) — page « Portails » d'après le relevé portal.list, et sa gestion. */
class PortalsPageTest {

    private static final String WILD_GUARD = "{\"code\":\"WILD_WARNING\",\"title\":\"Avertissement avant d'entrer "
            + "dans le Wild\",\"rules\":[\"Avant chaque départ, une fenêtre Minecraft demande de confirmer.\"],"
            + "\"texts\":[{\"label\":\"Message\",\"value\":\"Le Wild est une zone dangereuse.\"}]}";
    private static final String CLAIMS_GUARD = "{\"code\":\"CLAIMS_ACCESS\",\"title\":\"Accès au monde des "
            + "claims\",\"rules\":[\"Moyen de retour garanti : une Pierre de retour est remise avant le départ.\"],"
            + "\"texts\":[]}";

    static final String RELEVE = "{\"roles\":{\"hub\":\"world_hub\",\"wild\":\"wild\",\"claims\":\"claims\"},"
            + "\"portals\":[{\"id\":\"hub_to_wild\",\"name\":\"Portail vers le Wild\",\"kind\":\"WORLD_PORTAL\","
            + "\"enabled\":true,\"file\":\"hub_to_wild.yml\",\"world\":\"world_hub\",\"worldLoaded\":true,"
            + "\"worldRole\":\"HUB\",\"min\":\"737, 66, -673\",\"max\":\"740, 69, -673\",\"size\":\"4 × 4 × 1\","
            + "\"destinationWorld\":\"wild\",\"destinationLoaded\":true,\"destinationRole\":\"WILD\","
            + "\"destinationId\":\"\",\"destinationPoint\":\"\",\"strategy\":\"RANDOM_SAFE\",\"arrival\":["
            + "\"Point sûr aléatoire entre 500 et 5000 blocs du point d'arrivée du monde.\"],\"trigger\":["
            + "\"Téléportation immédiate en entrant dans la zone.\"],\"guards\":[" + WILD_GUARD + "],"
            + "\"conditions\":[],\"issues\":[]},"
            + "{\"id\":\"hub_to_claims\",\"name\":\"Portail vers les Claims\",\"kind\":\"WORLD_PORTAL\","
            + "\"enabled\":true,\"file\":\"hub_to_claims.yml\",\"world\":\"world_hub\",\"worldLoaded\":true,"
            + "\"worldRole\":\"HUB\",\"min\":\"730, 66, -676\",\"max\":\"732, 69, -676\",\"size\":\"3 × 4 × 1\","
            + "\"destinationWorld\":\"claims\",\"destinationLoaded\":false,\"destinationRole\":\"CLAIMS\","
            + "\"destinationId\":\"\",\"destinationPoint\":\"\",\"strategy\":\"CLAIM_HOME\",\"arrival\":[],"
            + "\"trigger\":[],\"guards\":[" + CLAIMS_GUARD + "],\"conditions\":[],\"issues\":[{\"code\":"
            + "\"PORTAL_DESTINATION_NOT_LOADED\",\"severity\":\"error\",\"message\":\"Le monde d'arrivée « claims » "
            + "n'est pas chargé.\"}]}],"
            + "\"issues\":[{\"code\":\"PORTAL_FILE_SKIPPED\",\"severity\":\"error\",\"message\":\"Fichier "
            + "world-portals/broken.yml ignoré au chargement.\"}]}";

    @TempDir
    Path tmp;

    private PanelApp app;
    private int port;
    private HttpClient client;
    private AgentStore store;
    private final Map<String, String> jar = new LinkedHashMap<>();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void thePageIsInTheWorldNavigationGroupAndInvitesToRefresh() throws Exception {
        start();
        login();
        String page = get("/portals?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("href=\"/portals\""), "entrée de navigation");
        assertTrue(page.contains(">Monde<"), "groupe de navigation Monde");
        assertTrue(page.contains("Aucun relevé chargé"), "aucun portail inventé");
        assertTrue(page.contains("value=\"portal.list\""), "seul Rafraîchir est proposé");
    }

    @Test
    void portalsShowRealZonesStrategiesGuardsAndProblems() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE);
        String page = get("/portals?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("hub_to_wild") && page.contains("hub_to_claims"));
        assertTrue(page.contains("737, 66, -673") && page.contains("740, 69, -673"), "vraies coordonnées");
        assertTrue(page.contains("point sûr aléatoire") && page.contains("claim du joueur, sinon point sûr"));
        assertTrue(page.contains("Avertissement Wild") && page.contains("Accès Claims + retour"), "gardes visibles");
        assertTrue(page.contains("Avec problème · 1"));
        assertTrue(page.contains("Fichier de portail ignoré"), "fichier ignoré affiché");
        assertTrue(page.contains("href=\"/worlds?agent="), "lien vers Mondes");
    }

    @Test
    void anEmptySearchOrFilterResultHasAnExplicitMessage() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE);
        String page = get("/portals?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("data-filter-empty=\"portals\" data-empty=\"Aucun portail\""),
                "message vide de recherche, caché tant qu'une carte s'affiche");
        assertTrue(page.contains("<p class=\"empty\" role=\"status\" hidden data-filter-empty"));
        assertTrue(page.contains("data-filter-chip=\"disabled\" data-empty=\"Aucun portail désactivé\""));
        assertTrue(page.contains("data-filter-chip=\"issue\" data-empty=\"Aucun portail avec problème\""));
    }

    @Test
    void theFicheShowsGuardTextsAndLinksToTheWorldsPage() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE);
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild&file=hub_to_wild.yml").body();
        assertTrue(wild.contains("1 · Résumé et diagnostic") && wild.contains("3 · Emplacement") && wild.contains("4 × 4 × 1 blocs"));
        assertTrue(wild.contains("world-portals/hub_to_wild.yml"));
        assertTrue(wild.contains("entre 500 et 5000 blocs"));
        assertTrue(wild.contains("« Le Wild est une zone dangereuse. »"), "texte actuel de l'avertissement");
        assertTrue(wild.contains("/worlds?agent=" + TestConfig.AGENT_ID + "&amp;world=wild"), "lien vers le monde");
        String claims = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_claims").body();
        assertTrue(claims.contains("Pierre de retour"));
        assertTrue(claims.contains("Monde de destination non chargé") && claims.contains("/docs/portails#"), claims);
        String missing = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=inconnu").body();
        assertTrue(missing.contains("n'apparaît pas dans le dernier relevé"));
    }

    /** Relevé #565 : champs de gestion (état, empreinte, essentiel), un brouillon et la liste des mondes. */
    static final String RELEVE_565 = RELEVE.replace("\"conditions\":[],\"issues\":[]},",
            "\"conditions\":[],\"issues\":[],\"state\":\"ACTIVE\",\"storedName\":\"\",\"site\":\"\","
                    + "\"essential\":true,\"editable\":true,\"fingerprint\":\"aaaaaaaaaaaaaaaaaaaaaaaa\","
                    + "\"minX\":737,\"minY\":66,\"minZ\":-673,\"maxX\":740,\"maxY\":69,\"maxZ\":-673},"
                    + "{\"id\":\"arche_marche\",\"name\":\"Arche du marché\",\"kind\":\"WORLD_PORTAL\","
                    + "\"enabled\":false,\"file\":\"arche_marche.yml\",\"world\":\"world_hub\",\"worldLoaded\":true,"
                    + "\"worldRole\":\"HUB\",\"min\":\"699, 64, -650\",\"max\":\"701, 67, -650\",\"size\":\"3 × 4 × 1\","
                    + "\"destinationWorld\":\"test_world\",\"destinationLoaded\":true,\"destinationRole\":\"\","
                    + "\"strategy\":\"WORLD_SPAWN\",\"arrival\":[],\"trigger\":[],\"guards\":[],\"conditions\":[],"
                    + "\"issues\":[{\"code\":\"PORTAL_DRAFT\",\"severity\":\"info\",\"message\":\"Brouillon.\"}],"
                    + "\"state\":\"DRAFT\",\"storedName\":\"Arche du marché\",\"site\":\"buildsite_0007\","
                    + "\"siteName\":\"Place du marché\",\"width\":3,\"height\":4,\"depth\":1,\"essential\":false,"
                    + "\"editable\":true,\"fingerprint\":\"bbbbbbbbbbbbbbbbbbbbbbbb\",\"minX\":699,\"minY\":64,"
                    + "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650},")
            .replace("\"issues\":[{\"code\":\"PORTAL_FILE_SKIPPED\"",
                    "\"worlds\":[\"claims\",\"test_world\",\"wild\",\"world_hub\"],\"history\":[{\"at\":"
                            + "\"2026-10-21T05:00:00Z\",\"actor\":\"owner\",\"operation\":\"CREATE_DRAFT\","
                            + "\"portal\":\"arche_marche\",\"before\":\"\",\"after\":\"état DRAFT\"}],"
                            + "\"issues\":[{\"code\":\"PORTAL_FILE_SKIPPED\"");

    /** Issue #566 : le brouillon arrive sur un point fixe de test_world. */
    static final String RELEVE_566 = RELEVE_565
            .replace("\"destinationRole\":\"\",\"strategy\":\"WORLD_SPAWN\",\"arrival\":[]",
                    "\"destinationRole\":\"\",\"destinationPoint\":\"test_world 100.5, 64.0, -20.5 (regard 90°)\","
                            + "\"strategy\":\"FIXED_POINT\",\"arrival\":[\"Point fixe : 100.5, 64.0, -20.5.\"]")
            .replace("\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650}",
                    "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650,\"pointX\":100.5,\"pointY\":64.0,"
                            + "\"pointZ\":-20.5,\"pointYaw\":90.0,\"pointPitch\":0.0,\"returnOf\":\"\","
                            + "\"returnedBy\":[]}");

    @Test
    void theFicheOfADraftOffersArrivalVerificationAndReturn() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_566);
        String draft = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(draft.contains("test_world 100.5, 64.0, -20.5 (regard 90°)"), "point affiché");
        assertTrue(draft.contains("name=\"dest_mode\" value=\"set\""), "formulaire d'arrivée");
        assertTrue(draft.contains("<option value=\"FIXED_POINT\" selected>"), "stratégie actuelle présélectionnée");
        assertTrue(draft.contains("name=\"point_x\" value=\"100.5\"") && draft.contains("name=\"point_yaw\" value=\"90\""));
        assertTrue(draft.contains("value=\"portal.verify\"") && draft.contains("Vérifier sans appliquer"));
        assertTrue(draft.contains("return_of=arche_marche"), "préparer le portail retour");
        assertTrue(draft.contains("<select name=\"destination_world\" required><option value=\"claims\">claims</option>"
                + "<option value=\"test_world\" selected>"), "monde d'arrivée actuel présélectionné");
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild").body();
        assertTrue(wild.contains("Désactiver le portail avant de changer son arrivée"), "arrivée verrouillée si actif");
        assertFalse(wild.contains("name=\"dest_mode\""));
    }

    @Test
    void theLastVerificationIsShownAndFlaggedWhenThePortalChangedSince() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_566);
        runAction("portal.verify", "&id=arche_marche", "{\"portal\":\"arche_marche\",\"verdict\":\"WARNING\","
                + "\"fingerprint\":\"cccccccccccccccccccccccc\",\"checks\":[{\"code\":\"ARRIVAL_SAFETY\","
                + "\"status\":\"UNKNOWN\",\"title\":\"Sécurité de l'arrivée\",\"message\":\"Non vérifiable "
                + "maintenant.\"}]}");
        String page = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(page.contains("Non vérifiable maintenant.") && page.contains(">À regarder<"), page);
        assertTrue(page.contains("précède la dernière modification"), "empreinte différente de celle du relevé");
        assertTrue(page.contains("Vérifier à nouveau"));
    }

    @Test
    void theReturnFormIsPrefilledFromTheOutboundPortal() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_566);
        String page = get("/portals?agent=" + TestConfig.AGENT_ID + "&return_of=arche_marche").body();
        assertTrue(page.contains("Portail retour de <code>arche_marche</code>"), page);
        assertTrue(page.contains("name=\"return_of\" value=\"arche_marche\""));
        assertTrue(page.contains("<select name=\"world\" required><option value=\"claims\">claims</option>"
                + "<option value=\"test_world\" selected>"), "départ = monde d'arrivée de l'aller");
        assertTrue(page.contains("<option value=\"world_hub\" selected>"), "arrivée = monde de départ de l'aller");
    }

    @Test
    void arrivalChangesAndVerificationsAreQueuedWithValidatedParams() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=portal.update&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche_marche&expected=bbbbbbbbbbbbbbbbbbbbbbbb&zone_mode=keep&dest_mode=set"
                + "&destination_world=test_world&strategy=FIXED_POINT&point_x=100,5&point_y=64&point_z=-20.5"
                + "&point_yaw=90&confirm=true");
        post("/agents/action", "_csrf=" + token + "&type=portal.verify&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche_marche");
        String queued = pollActions();
        assertTrue(queued.contains("\"dest_mode\":\"set\"") && queued.contains("\"point_x\":\"100.5\""), queued);
        assertTrue(queued.contains("portal.verify"), queued);
    }

    @Test
    void aFixedPointWithoutCoordinatesIsRefusedBeforeQueueing() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=portal.update&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche_marche&expected=bbbbbbbbbbbbbbbbbbbbbbbb&zone_mode=keep&dest_mode=set"
                + "&destination_world=test_world&strategy=FIXED_POINT&point_x=1&confirm=true");
        assertFalse(pollActions().contains("portal."), "rien mis en file");
    }

    @Test
    void theOwnerGetsADraftFormAndNoTeleportTest() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_565);
        String page = get("/portals?agent=" + TestConfig.AGENT_ID).body();
        assertTrue(page.contains("Créer un portail en brouillon") && page.contains("value=\"portal.create\""));
        assertTrue(page.contains("<option value=\"test_world\">"), "mondes chargés proposés en arrivée");
        assertTrue(page.contains("Brouillons · 1") && page.contains(">Brouillon<"));
        assertFalse(page.contains("Téléporter") || page.contains("player.teleport"), "aucun test de téléportation");
    }

    @Test
    void theFicheOfAnEssentialActivePortalOffersOnlyGuardedActions() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_565);
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild").body();
        assertTrue(wild.contains("value=\"portal.disable\"") && !wild.contains("value=\"portal.enable\""));
        assertTrue(wild.contains("name=\"expected\" value=\"aaaaaaaaaaaaaaaaaaaaaaaa\""), "empreinte du relevé");
        assertTrue(wild.contains("name=\"confirm_essential\""), "seconde confirmation des portails essentiels");
        assertTrue(wild.contains("Désactiver le portail avant de déplacer"), "zone verrouillée tant qu'il est actif");
        assertFalse(wild.contains("value=\"portal.delete\""), "un portail actif ne se supprime pas");
        String draft = get("/portals?agent=" + TestConfig.AGENT_ID + "&focus=arche_marche").body();
        assertTrue(draft.contains("value=\"portal.enable\"") && draft.contains("value=\"portal.delete\""));
        assertTrue(draft.contains("buildsite_0007") && draft.contains("Place du marché"));
        assertTrue(draft.contains("Brouillon créé") && draft.contains("Modifications depuis PlugAdmin"), "journal");
        assertFalse(draft.contains("name=\"confirm_essential\""));
    }

    @Test
    void aDraftCreationIsQueuedWithValidatedParamsAndTheSessionAsAuthor() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=portal.create&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=Arche_Marche&name=Arche&zone_mode=site&site=buildsite_0007&width=3&height=4"
                + "&depth=1&destination_world=wild&strategy=random_safe&by=pirate&confirm=true");
        String queued = pollActions();
        assertTrue(queued.contains("portal.create"), queued);
        assertTrue(queued.contains("\"id\":\"arche_marche\"") && queued.contains("\"strategy\":\"RANDOM_SAFE\""));
        assertTrue(queued.contains("\"by\":\"owner\"") && !queued.contains("pirate"), "auteur = session");
    }

    @Test
    void portalMutationsWithoutConfirmationOrFingerprintAreRefused() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=portal.create&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche&zone_mode=site&site=buildsite_0007&width=3&height=4&depth=1"
                + "&destination_world=wild");
        post("/agents/action", "_csrf=" + token + "&type=portal.disable&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=hub_to_wild&confirm=true");
        post("/agents/action", "_csrf=" + token + "&type=portal.create&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche&zone_mode=site&site=buildsite_0007&width=99&height=4&depth=1"
                + "&destination_world=wild&confirm=true");
        assertFalse(pollActions().contains("portal."), "rien mis en file");
    }

    @Test
    void portalWritesAreOwnerOnlyByDefault() {
        for (com.lodygames.rpgquest.panel.authz.Role role : com.lodygames.rpgquest.panel.authz.Role.values()) {
            if (role != com.lodygames.rpgquest.panel.authz.Role.OWNER) {
                assertFalse(role.has(com.lodygames.rpgquest.panel.authz.Permission.PORTAL_EDIT), role.name());
                assertFalse(role.has(com.lodygames.rpgquest.panel.authz.Permission.PORTAL_ENABLE), role.name());
            }
        }
    }

    @Test
    void aSiteUsedByAPortalCannotBeDeletedFromThePanel() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_565);
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        HttpResponse<String> res = post("/agents/action", "_csrf=" + token + "&type=building.site.delete&agent="
                + TestConfig.AGENT_ID + "&return=/buildings/sites&id=buildsite_0007&confirm=true");
        assertTrue(res.headers().firstValue("Location").orElse("").contains("arche_marche"));
        assertFalse(pollActions().contains("building.site.delete"));
    }

    // ---- Issue #567 : conditions d'accès, simulation, test de téléportation -----------------------

    /** Relevé #567 : le brouillon porte un niveau, une condition de quête et un délai. */
    static final String RELEVE_567 = RELEVE_565.replace(
            "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650}",
            "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650,\"access\":{\"level\":\"5\","
                    + "\"levelMessage\":\"<red>Niveau 5.</red>\",\"cooldown\":30,\"cooldownMessage\":\"\","
                    + "\"rules\":[{\"type\":\"QUEST_STATE\",\"quest\":\"rpgquest:crystal_hunt\",\"state\":"
                    + "\"COMPLETED\",\"message\":\"<red>Termine la chasse.</red>\",\"label\":\"Quête "
                    + "rpgquest:crystal_hunt à l'état COMPLETED\"}]}}")
            .replace("\"guards\":[],\"conditions\":[],",
                    "\"guards\":[],\"conditions\":[{\"label\":\"Niveau RPG minimum\",\"value\":\"5\"},"
                            + "{\"label\":\"Condition\",\"value\":\"Quête rpgquest:crystal_hunt à l'état COMPLETED\"}],");

    @Test
    void theDraftFicheEditsAccessConditionsAndOffersASimulation() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_567);
        String draft = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(draft.contains("Quête rpgquest:crystal_hunt à l&#39;état COMPLETED"), "condition lisible");
        assertTrue(draft.contains("value=\"portal.access\""), "formulaire des conditions");
        assertTrue(draft.contains("name=\"level\" min=\"1\" max=\"1000\" step=\"1\" value=\"5\""), draft);
        assertTrue(draft.contains("<option value=\"QUEST_STATE\" selected>"), "ligne existante préremplie");
        assertTrue(draft.contains("name=\"cond_0_quest\"") && draft.contains("value=\"rpgquest:crystal_hunt\""));
        assertTrue(draft.contains("name=\"cond_1_type\"") && !draft.contains("name=\"cond_2_type\""),
                "conditions actuelles + une ligne vide (deux au moins)");
        assertTrue(draft.contains("value=\"portal.simulate\"") && draft.contains("Simuler (sans téléporter)"));
        assertTrue(draft.contains("Téléporter pour test") && draft.contains("Aucun personnage Minecraft n'est lié"),
                "le test ne déplace que le personnage lié ; sans lien, aucun formulaire");
        assertFalse(draft.contains("value=\"portal.test\""));
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild").body();
        assertTrue(wild.contains("Désactiver le portail avant de changer ses conditions d'accès."));
        assertFalse(wild.contains("value=\"portal.access\""), "portail actif : conditions verrouillées");
    }

    @Test
    void accessConditionsAreQueuedOnlyWithOfferedTypes() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        String base = "_csrf=" + token + "&type=portal.access&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche_marche&expected=bbbbbbbbbbbbbbbbbbbbbbbb&confirm=true";
        post("/agents/action", base + "&cond_0_type=HAS_INVENTORY_SPACE");
        assertFalse(pollActions().contains("portal.access"), "type non proposé : rien en file");
        post("/agents/action", base + "&level=5&cooldown=30&level_message=" + URLEncoder.encode("<red>N5</red>",
                StandardCharsets.UTF_8) + "&cond_0_type=QUEST_STATE&cond_0_quest=crystal_hunt&cond_0_state=COMPLETED"
                + "&cond_1_type=&cond_1_quest=ignoree&cond_2_type=HAS_MAIN_CLAIM&cond_2_negate=true");
        String queued = pollActions();
        assertTrue(queued.contains("\"cond_0_type\":\"QUEST_STATE\"") && queued.contains("\"level\":\"5\""), queued);
        assertTrue(queued.contains("\"cond_2_negate\":\"true\"") && !queued.contains("ignoree"), queued);
    }

    @Test
    void aTestTeleportNeedsALinkedCharacterAndTheCopiedId() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=portal.test&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche_marche&confirm_id=arche_marche&confirm=true&player=Alex");
        assertFalse(pollActions().contains("portal.test"), "sans personnage lié : refusé, jamais un autre joueur");
        assertFalse(com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("portal.test", new java.util.HashMap<>(
                Map.of("id", "arche_marche", "confirm", "true", "player", "Alex", "confirm_id", "autre"))).valid(),
                "identifiant non recopié : refusé");
        assertTrue(com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("portal.test", new java.util.HashMap<>(
                Map.of("id", "arche_marche", "confirm", "true", "player", "Alex", "confirm_id", "ARCHE_MARCHE")))
                .valid());
    }

    @Test
    void theLastSimulationIsShownWithEachCheck() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_567);
        runAction("portal.simulate", "&id=arche_marche&player=Alex", "{\"portal\":\"arche_marche\",\"player\":"
                + "\"Alex\",\"allowed\":false,\"code\":\"REFUSED\",\"checks\":[{\"code\":\"LEVEL\",\"label\":"
                + "\"Niveau RPG 5 minimum (actuel : 7)\",\"passed\":true,\"refusal\":\"Niveau 5.\"},{\"code\":"
                + "\"CONDITION\",\"label\":\"Quête rpgquest:crystal_hunt à l'état COMPLETED\",\"passed\":false,"
                + "\"refusal\":\"Termine la chasse.\"}],\"notes\":[\"Simulation seulement : personne n'est "
                + "téléporté.\"]}");
        String page = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(page.contains(">REFUSÉ<"), page);
        assertTrue(page.contains("Niveau RPG 5 minimum (actuel : 7)") && page.contains("« Termine la chasse. »"));
        assertTrue(page.contains("Simulation seulement : personne n&#39;est téléporté.")
                || page.contains("Simulation seulement : personne n'est téléporté."), "notes du serveur");
    }

    // ---- Issue #569 : messages et avertissement par portail ------------------------------------------

    /** Relevé #569 : le brouillon a un titre d'avertissement, une bienvenue et « ne plus afficher » retiré. */
    static final String RELEVE_569 = RELEVE_565.replace(
            "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650}",
            "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650,\"messages\":{\"welcome\":"
                    + "\"<green>Bienvenue au marché.</green>\",\"preparing\":\"\",\"success\":\"\",\"unavailable\":\"\","
                    + "\"unsafe\":\"\",\"refusal\":\"\",\"warningMode\":\"ALWAYS\",\"title\":\"<gold>Marché</gold>\","
                    + "\"text\":\"\",\"hint\":\"\",\"enter\":\"\",\"enterHide\":\"\",\"cancel\":\"\",\"cancelText\":\"\","
                    + "\"allowHide\":false,\"custom\":true},\"messageDefaults\":{\"title\":\"<gold>⚠ Avertissement</gold>\","
                    + "\"text\":\"<white>Ce portail mène vers une zone dangereuse. Voulez-vous continuer ?</white>\","
                    + "\"hint\":\"\",\"enter\":\"<green>Entrer</green>\",\"enterHide\":\"<yellow>Entrer et ne plus "
                    + "afficher cet avertissement</yellow>\",\"cancel\":\"<red>Annuler</red>\",\"cancelText\":"
                    + "\"<gray>Vous restez ici.</gray>\",\"preparing\":\"<aqua>Recherche d'un point d'arrivée sûr… "
                    + "Téléportation en préparation.</aqua>\",\"success\":\"<green>Téléportation réussie.</green>\","
                    + "\"unavailable\":\"<red>Le monde de destination de ce portail n'est pas chargé.</red>\","
                    + "\"unsafe\":\"<red>Arrivée non sûre.</red>\"},\"warns\":true}");

    @Test
    void theDraftFicheEditsMessagesAndPreviewsTheWarning() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_569);
        String draft = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(draft.contains("value=\"portal.messages\""), "formulaire des messages");
        assertTrue(draft.contains("Aperçu de l&#39;avertissement") || draft.contains("Aperçu de l'avertissement"),
                draft);
        assertTrue(draft.contains(">Marché</span>"), "titre personnalisé dans l'aperçu");
        assertTrue(draft.contains("Ce portail mène vers une zone dangereuse"), "texte par défaut dans l'aperçu");
        assertFalse(draft.contains("name=\"warn_allow_hide\" value=\"true\" checked"), "case décochée");
        assertTrue(draft.contains("<option value=\"ALWAYS\" selected>"), "mode prérempli");
        assertTrue(draft.contains("Vide = « Téléportation réussie. »"), "texte actuel rappelé sous le champ");
        assertTrue(draft.contains("Bienvenue au marché.") && draft.contains(">personnalisé<"));
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild").body();
        assertFalse(wild.contains("value=\"portal.messages\""), "portail actif : messages verrouillés");
    }

    @Test
    void messagesAreQueuedOnlyWithAKnownModeAndAnExplicitHideChoice() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        String base = "_csrf=" + token + "&type=portal.messages&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche_marche&expected=bbbbbbbbbbbbbbbbbbbbbbbb&confirm=true";
        post("/agents/action", base + "&warning_mode=PARFOIS");
        assertFalse(pollActions().contains("portal.messages"), "mode inconnu : rien en file");
        post("/agents/action", base + "&warn_text=" + "x".repeat(501));
        assertFalse(pollActions().contains("portal.messages"), "texte trop long : rien en file");
        post("/agents/action", base + "&warning_mode=always&msg_welcome=" + URLEncoder.encode(
                "<green>Salut</green>", StandardCharsets.UTF_8));
        String queued = pollActions();
        assertTrue(queued.contains("\"warning_mode\":\"ALWAYS\"") && queued.contains("\"warn_allow_hide\":\"false\""),
                queued);
        assertTrue(queued.contains("Salut"), queued);
    }

    /** Issue #568 : l'arche de test est posée (rotation 90°) sur l'emplacement du brouillon, pas encore liée. */
    static final String RELEVE_568 = RELEVE_565.replace(
            "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650}",
            "\"minZ\":-650,\"maxX\":701,\"maxY\":67,\"maxZ\":-650,\"construction\":{\"linked\":\"\","
                    + "\"status\":\"NONE\",\"message\":\"\",\"placed\":\"arche_test\",\"placedName\":\"Arche de test\","
                    + "\"rotation\":90,\"linkable\":true,\"zone\":\"700,64,-651 → 700,67,-649\",\"refusal\":\"\"}}");

    /** Issue #568 : la même arche, liée, puis libérée — lien rompu. */
    static final String RELEVE_568_BROKEN = RELEVE_568.replace("\"linked\":\"\",\"status\":\"NONE\",\"message\":\"\","
                    + "\"placed\":\"arche_test\",\"placedName\":\"Arche de test\",\"rotation\":90,\"linkable\":true",
            "\"linked\":\"arche_test\",\"status\":\"BUILDING_MISSING\",\"message\":\"L'arche liée n'est plus posée.\","
                    + "\"placed\":\"\",\"placedName\":\"\",\"rotation\":0,\"linkable\":false");

    /** Retour propriétaire du 22/10 sur #566 : une colonne, résumé et tests en tête, formulaires repliés. */
    @Test
    void theFicheIsOneColumnWithSummaryAndToolsFirstAndFoldedEditors() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_569.replace("\"maxZ\":-650,\"messages\":", "\"maxZ\":-650,\"construction\":"
                + "{\"linked\":\"arche_test\",\"status\":\"OK\",\"message\":\"\",\"placed\":\"arche_test\","
                + "\"placedName\":\"Arche de test\",\"rotation\":90,\"linkable\":true,\"zone\":\"700,64,-651 → "
                + "700,67,-649\",\"refusal\":\"\"},\"messages\":"));
        String draft = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(draft.contains("Arche de test"), "arche liée dans le relevé");
        assertFalse(draft.contains("class=\"w-cols\""), "plus de colonne droite isolée");
        String[] order = {"1 · Résumé et diagnostic", "2 · Vérifier et tester", "3 · Emplacement", "4 · Construction",
                "5 · Téléportation et arrivée", "6 · Règles appliquées par le serveur", "7 · Messages et avertissement",
                "8 · Nom et suppression"};
        int at = -1;
        for (String title : order) {
            int next = draft.indexOf(title);
            assertTrue(next > at, "numérotation continue, dans l'ordre : " + title);
            at = next;
        }
        int tools = draft.indexOf("id=\"portal-tests\"");
        assertTrue(tools < draft.indexOf("id=\"portal-verify\"") && draft.indexOf("id=\"portal-verify\"")
                < draft.indexOf("id=\"portal-simulate\"") && draft.indexOf("id=\"portal-simulate\"")
                < draft.indexOf("id=\"portal-location\""), "Vérifier et Simuler regroupés dans un seul bloc");
        assertTrue(draft.indexOf("value=\"portal.enable\"") < tools, "activation dans le résumé");
        for (String editor : List.of("Modifier la zone d'activation", "Modifier l'arrivée",
                "Modifier les conditions d'accès", "Modifier les messages et l'avertissement", "Renommer")) {
            assertTrue(draft.contains("<summary>" + Icons.icon("edit") + Http.esc(editor) + "</summary>"),
                    "formulaire replié : " + editor);
        }
        assertFalse(draft.contains("<details class=\"p-edit\" open"), "aucun formulaire ouvert par défaut");
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild").body();
        assertFalse(wild.contains("<summary>" + Icons.icon("edit") + "Modifier la zone"), "portail actif : rien à déplier");
        assertTrue(wild.contains("Désactiver le portail avant de déplacer"));
    }

    @Test
    void theConstructionBlockOffersToLinkThePlacedArchWithoutActivating() throws Exception {
        start();
        login();
        runAction("portal.list", RELEVE_568);
        String draft = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(draft.contains("4 · Construction") && draft.contains("5 · Téléportation et arrivée")
                && draft.contains("7 · Nom et suppression"), "blocs numérotés");
        assertTrue(draft.contains("Arche de test") && draft.contains("rotation 90°"), "arche posée");
        assertTrue(draft.contains("700,64,-651 → 700,67,-649"), "zone que donnerait « Lier »");
        assertTrue(draft.contains("value=\"portal.building.link\""), "formulaire Lier");
        assertTrue(draft.contains("Le portail reste en brouillon"), "lier n'active pas");
        assertFalse(draft.contains("value=\"portal.building.unlink\""), "rien à délier");
        String wild = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=hub_to_wild").body();
        assertFalse(wild.contains("value=\"portal.building.link\""), "zone saisie : aucune construction à lier");

        runAction("portal.list", RELEVE_568_BROKEN);
        String broken = get("/portals?agent=" + TestConfig.AGENT_ID + "&portal=arche_marche").body();
        assertTrue(broken.contains("Lien rompu") && broken.contains("plus posée"), broken);
        assertTrue(broken.contains("value=\"portal.building.unlink\""), "délier sans démolir");
        assertFalse(broken.contains("value=\"portal.building.link\""), "rien de posé : rien à lier");
    }

    @Test
    void linkIsQueuedOnlyWithTheFingerprintAndTheConfirmation() throws Exception {
        start();
        login();
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        String base = "_csrf=" + token + "&type=portal.building.link&agent=" + TestConfig.AGENT_ID
                + "&return=/portals&id=arche_marche";
        post("/agents/action", base + "&confirm=true");
        assertFalse(pollActions().contains("portal.building.link"), "sans empreinte : rien en file");
        post("/agents/action", base + "&expected=bbbbbbbbbbbbbbbbbbbbbbbb");
        assertFalse(pollActions().contains("portal.building.link"), "sans confirmation : rien en file");
        post("/agents/action", base + "&expected=bbbbbbbbbbbbbbbbbbbbbbbb&confirm=true");
        String queued = pollActions();
        assertTrue(queued.contains("portal.building.link") && queued.contains("\"expected\":\"bbbbbbbbbbbbbbbbbbbbbbbb\""),
                queued);
    }

    private String pollActions() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private void runAction(String type, String details) throws Exception {
        runAction(type, "", details);
    }

    private void runAction(String type, String extraParams, String details) throws Exception {
        String token = csrf(get("/portals?agent=" + TestConfig.AGENT_ID).body());
        post("/agents/action", "_csrf=" + token + "&type=" + type + "&agent=" + TestConfig.AGENT_ID
                + "&return=/portals" + extraParams);
        HttpResponse<String> poll = client.send(HttpRequest.newBuilder(uri("/agent/v1/actions"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(poll.body());
        if (!m.find()) {
            throw new IllegalStateException("aucune action en file : " + poll.body());
        }
        client.send(HttpRequest.newBuilder(uri("/agent/v1/actions/" + m.group(1) + "/result"))
                .header("Authorization", "Bearer " + TestConfig.AGENT_TOKEN)
                .header("X-Agent-Id", TestConfig.AGENT_ID)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"status\":\"SUCCESS\",\"message\":\"ok\",\"details\":" + details + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        String db = tmp.resolve("cp.db").toString();
        store = new AgentStore(db);
        app = new PanelApp(TestConfig.withAgent(db, "http://127.0.0.1:1/admin/v1"),
                new InMemoryAuditLog(), new BridgeClient(Duration.ofMillis(300), Duration.ofMillis(400)), store);
        port = app.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        jar.clear();
        store.saveHeartbeat(new com.lodygames.rpgquest.panel.agent.HeartbeatRecord(
                TestConfig.AGENT_ID, "dev", Instant.now(), Instant.now().toString(), "1",
                "RPGQuest", "0.1.0-SNAPSHOT", "ONLINE", 0, 20, 60, "{}", "{}"));
    }

    private void login() throws Exception {
        String token = csrf(get("/login").body());
        post("/login", "username=" + TestConfig.OWNER_USERNAME + "&password="
                + URLEncoder.encode(TestConfig.OWNER_PASSWORD, StandardCharsets.UTF_8) + "&_csrf=" + token);
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
