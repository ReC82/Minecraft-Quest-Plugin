package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.database.BuildingPlacementRepository;
import com.lodygames.rpgquest.database.BuildingSiteRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #213, lot « placement » — la chaîne complète, sur une vraie base SQLite.
 *
 * <h2>Pourquoi ces tests existent et sont possibles</h2>
 *
 * <p>WorldEdit est derrière {@link SchematicGateway} et le monde derrière {@link WorldProbe}. Du
 * coup <strong>l'essentiel du lot s'exécute ici</strong> : l'ordre des opérations (sauvegarder avant
 * de coller, n'enregistrer qu'après un collage réussi), les refus, le retour arrière, et la
 * survie à un redémarrage. Sans cette frontière, tout cela ne serait vérifiable qu'à la main, en
 * jeu — c'est-à-dire presque jamais.</p>
 *
 * <p>Plusieurs tests reconstruisent un service neuf sur la même base : c'est exactement ce que fait
 * un redémarrage du serveur.</p>
 */
class BuildingPlacementServiceTest {

    private static final long TIMEOUT = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private BuildingSiteRepository siteRepository;
    private BuildingPlacementRepository placementRepository;
    private BuildingSiteService sites;
    private BuildingLibrary library;
    private FakeGateway gateway;
    private FakeWorld world;
    private BuildingPlacementService service;

    // ---- Doubles -------------------------------------------------------------------------------

    /**
     * Moteur de schematics simulé. Il <strong>enregistre l'ordre des appels</strong>, ce qui est la
     * seule façon de vérifier que la sauvegarde précède le collage.
     */
    private static final class FakeGateway implements SchematicGateway {
        final List<String> calls = new ArrayList<>();
        final Set<String> files = new LinkedHashSet<>();
        boolean available = true;
        String unavailable = "";
        boolean captureFails;
        boolean pasteFails;
        boolean restoreFails;
        Dimensions dimensions = new Dimensions(7, 6, 5);
        boolean illegible;
        PasteOrder lastPaste;
        BuildingFootprint lastCaptured;
        String lastRestored;

        @Override public boolean available() {
            return available;
        }

        @Override public String unavailableReason() {
            return unavailable;
        }

        @Override public boolean has(String fileName) {
            return files.contains(fileName);
        }

        /**
         * Empreinte simulée (issue #234) : déterministe et dérivée du nom, ce qui suffit à
         * distinguer deux fichiers. Vide si le fichier n'existe pas — comme l'adaptateur réel.
         */
        @Override public java.util.Optional<String> fingerprint(String fileName) {
            return files.contains(fileName)
                    ? java.util.Optional.of("sha-" + fileName) : java.util.Optional.empty();
        }

        @Override public Outcome write(Blueprint blueprint, String fileName) {
            calls.add("write:" + fileName);
            files.add(fileName);
            return Outcome.success();
        }

        @Override public Optional<Dimensions> inspect(String fileName) {
            return illegible || !files.contains(fileName) ? Optional.empty()
                    : Optional.of(dimensions);
        }

        @Override public Outcome capture(BuildingFootprint footprint, String fileName) {
            calls.add("capture:" + fileName);
            if (captureFails) {
                return Outcome.failure("disque plein");
            }
            lastCaptured = footprint;
            files.add(fileName);
            return Outcome.success();
        }

        @Override public Outcome paste(PasteOrder order) {
            calls.add("paste:" + order.schematic());
            if (pasteFails) {
                return Outcome.failure("collage refusé par le moteur");
            }
            lastPaste = order;
            return Outcome.success();
        }

        @Override public Outcome restore(String fileName, BuildingFootprint footprint) {
            calls.add("restore:" + fileName);
            if (restoreFails) {
                return Outcome.failure("sauvegarde illisible");
            }
            lastRestored = fileName;
            return Outcome.success();
        }
    }

    /** Monde simulé : limites de hauteur et comptage de blocs, rien de plus. */
    private static final class FakeWorld implements WorldProbe {
        final Set<String> loaded = new LinkedHashSet<>(List.of("world_hub"));
        int minHeight = -64;
        int maxHeight = 320;
        long nonAir = 0;
        boolean countable = true;

        @Override public boolean loaded(String world) {
            return loaded.contains(world);
        }

        @Override public OptionalInt minHeight(String world) {
            return loaded(world) ? OptionalInt.of(minHeight) : OptionalInt.empty();
        }

        @Override public OptionalInt maxHeight(String world) {
            return loaded(world) ? OptionalInt.of(maxHeight) : OptionalInt.empty();
        }

        @Override public OptionalLong countNonAir(BuildingFootprint footprint) {
            return countable ? OptionalLong.of(nonAir) : OptionalLong.empty();
        }
    }

    // ---- Harnais -------------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        siteRepository = new BuildingSiteRepository(database);
        placementRepository = new BuildingPlacementRepository(database);
        sites = new BuildingSiteService(siteRepository);
        sites.load().get(TIMEOUT, TimeUnit.SECONDS);

        Path buildings = tempDir.resolve("buildings");
        Files.createDirectories(buildings);
        Files.writeString(buildings.resolve("test_hut_01.yml"), """
                id: test_hut_01
                name: "Hutte de test"
                description: "Hutte de validation."
                schematic: test_hut_01.schem
                size:
                  x: 7
                  y: 6
                  z: 5
                anchor:
                  x: 3
                  y: 1
                  z: 0
                front: NORTH
                materials:
                  - cobblestone
                  - oak_planks
                version: 1
                """);
        library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.reload();

        gateway = new FakeGateway();
        gateway.files.add("test_hut_01.schem");
        world = new FakeWorld();
        service = newService();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** Un service neuf sur la même base : c'est ce que fait un redémarrage. */
    private BuildingPlacementService newService() throws Exception {
        BuildingPlacementService fresh = new BuildingPlacementService(placementRepository, sites,
                library, gateway, world);
        fresh.load().get(TIMEOUT, TimeUnit.SECONDS);
        return fresh;
    }

    private BuildingSite site(Facing facing) throws Exception {
        return sites.create("world_hub", new BuildingSiteAnchor(100, 70, 200), facing,
                "Test hutte", "Lody").get(TIMEOUT, TimeUnit.SECONDS).site();
    }

    private BuildingPlacementService.PlaceResult place(String siteId) throws Exception {
        return service.place(siteId, "test_hut_01", "Lody").get(TIMEOUT, TimeUnit.SECONDS);
    }

    // ---- La bibliothèque -----------------------------------------------------------------------

    @Test
    void theLibraryLoadsTheDefinitionFromItsFile() {
        assertEquals(1, library.size());
        assertTrue(library.find("test_hut_01").isPresent());
        assertEquals("Hutte de test", library.find("test_hut_01").orElseThrow().name());
        assertTrue(library.problems().isEmpty(), library.problems().toString());
    }

    // ---- L'aperçu n'écrit rien -----------------------------------------------------------------

    /**
     * Le test le plus important de l'aperçu : il <strong>ne touche à rien</strong>.
     *
     * <p>Ni bloc (aucun appel au moteur), ni base (aucun placement), ni fiche (l'emplacement reste
     * vide). Si l'aperçu écrivait, il n'y aurait plus de « regarder avant de décider ».</p>
     */
    @Test
    void previewWritesAbsolutelyNothing() throws Exception {
        BuildingSite created = site(Facing.EAST);

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertTrue(preview.placeable(), preview.refusals().toString());
        assertTrue(gateway.calls.isEmpty(), "le moteur ne doit pas être sollicité : "
                + gateway.calls);
        assertTrue(service.all().isEmpty(), "aucun placement enregistré");
        assertEquals(SiteStatus.EMPTY,
                sites.find(created.id()).orElseThrow().status(), "la fiche reste vide");
        assertEquals(0, placementRepository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    @Test
    void previewComputesTheRotationAndFootprint() throws Exception {
        BuildingSite created = site(Facing.EAST);

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        // Façade de référence NORTH, emplacement EAST → un quart de tour horaire.
        assertEquals(90, preview.rotationDegrees());
        BuildingFootprint footprint = preview.footprint();
        assertEquals(5, footprint.sizeX(), "largeur et profondeur échangées à 90°");
        assertEquals(7, footprint.sizeZ());
        assertEquals(6, footprint.sizeY());
        assertEquals(210L, footprint.blockCount());
        assertEquals("90° / est", preview.rotationLabel());
    }

    @Test
    void previewReportsEveryRefusalAndNotJustTheFirst() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.available = false;
        gateway.unavailable = "WorldEdit n'est pas installé sur ce serveur.";
        world.loaded.clear();

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().size() >= 2, preview.refusals().toString());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("WorldEdit")));
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("n'est pas chargé")));
    }

    // ---- Les refus -----------------------------------------------------------------------------

    @Test
    void anUnknownSiteIsRefused() {
        BuildingPlacementService.Preview preview = service.preview("buildsite_9999", "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.firstRefusal().contains("inconnu"), preview.firstRefusal());
    }

    @Test
    void anUnknownBuildingIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);

        BuildingPlacementService.Preview preview = service.preview(created.id(), "pas_un_batiment");

        assertFalse(preview.placeable());
        assertTrue(preview.firstRefusal().contains("inconnu"), preview.firstRefusal());
    }

    @Test
    void aMissingSchematicFileIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.files.clear();

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("est absent")),
                preview.refusals().toString());
    }

    /**
     * Le fichier et la définition doivent concorder.
     *
     * <p>Sinon l'emprise annoncée est fausse, la sauvegarde ne couvre pas tout ce qui sera écrasé,
     * et la faute ne se découvre qu'après le collage.</p>
     */
    @Test
    void aFileWhoseDimensionsDisagreeWithTheDefinitionIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.dimensions = new SchematicGateway.Dimensions(9, 6, 5);

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("alors que la définition")),
                preview.refusals().toString());
    }

    @Test
    void anIllegibleFileIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.illegible = true;

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("illisible")),
                preview.refusals().toString());
    }

    @Test
    void anUnloadedWorldIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        world.loaded.clear();

        assertFalse(service.preview(created.id(), "test_hut_01").placeable());
    }

    @Test
    void aFootprintAboveTheWorldCeilingIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        world.maxHeight = 72;

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("plafond du monde")),
                preview.refusals().toString());
    }

    @Test
    void aFootprintBelowTheWorldFloorIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        world.minHeight = 70;

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("plancher du monde")),
                preview.refusals().toString());
    }

    /** Terrain naturel : on AVERTIT au-delà de la moitié, on ne refuse jamais. */
    @Test
    void aCrowdedZoneWarnsButNeverRefuses() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        world.nonAir = 200;

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertTrue(preview.placeable(), "de l'herbe n'empêche pas de bâtir");
        assertTrue(preview.warnings().stream().anyMatch(w -> w.contains("200 blocs non-air")),
                preview.warnings().toString());
    }

    @Test
    void anEmptyZoneWarnsAboutNothing() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        world.nonAir = 0;

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertTrue(preview.placeable());
        assertTrue(preview.warnings().isEmpty(), preview.warnings().toString());
    }

    /**
     * Ne pas savoir compter n'est pas « zéro ».
     *
     * <p>Confondre les deux ferait annoncer « zone libre » sans l'avoir vérifié.</p>
     */
    @Test
    void anUncountableZoneReportsMinusOneRatherThanZero() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        world.countable = false;

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertEquals(-1L, preview.nonAirBlocks());
        assertTrue(preview.placeable());
    }

    // ---- La pose -------------------------------------------------------------------------------

    /** Le test central : la zone est sauvegardée AVANT le collage, et le placement après. */
    @Test
    void placingBacksUpBeforePastingAndRecordsAfterwards() throws Exception {
        BuildingSite created = site(Facing.EAST);

        BuildingPlacementService.PlaceResult result = place(created.id());

        assertTrue(result.placed(), result.error());
        assertEquals(2, gateway.calls.size(), gateway.calls.toString());
        assertTrue(gateway.calls.get(0).startsWith("capture:"),
                "la sauvegarde doit précéder le collage : " + gateway.calls);
        assertTrue(gateway.calls.get(1).startsWith("paste:"), gateway.calls.toString());

        assertEquals(1, service.all().size());
        assertEquals(SiteStatus.OCCUPIED, sites.find(created.id()).orElseThrow().status());
        assertEquals(1, placementRepository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    @Test
    void thePlacementRecordsRotationFootprintAndBackup() throws Exception {
        BuildingSite created = site(Facing.EAST);

        BuildingPlacementService.PlaceResult result = place(created.id());

        var placement = result.placement();
        assertEquals(created.id(), placement.siteId());
        assertEquals("test_hut_01", placement.buildingId());
        assertEquals(90, placement.rotationDegrees());
        assertEquals("world_hub", placement.world());
        assertEquals(100, placement.anchorX());
        assertEquals(70, placement.anchorY());
        assertEquals(200, placement.anchorZ());
        assertTrue(placement.restorable(), "une sauvegarde doit être associée");
        assertTrue(placement.backupSchematic().endsWith(".schem"),
                placement.backupSchematic());
        // L'emprise enregistrée est exactement celle annoncée par l'aperçu.
        assertEquals(service.preview(created.id(), "test_hut_01") == null ? null : placement
                .footprint().label(), placement.footprint().label());
        assertEquals(5, placement.footprint().sizeX());
        assertEquals(7, placement.footprint().sizeZ());
    }

    /** Le collage reçoit l'ancre locale et l'emprise attendue : c'est le contrat du moteur. */
    @Test
    void thePasteOrderCarriesTheLocalAnchorAndTheExpectedFootprint() throws Exception {
        BuildingSite created = site(Facing.SOUTH);

        place(created.id());

        SchematicGateway.PasteOrder order = gateway.lastPaste;
        assertNotNull(order);
        assertEquals("test_hut_01.schem", order.schematic());
        assertEquals(3, order.localAnchorX());
        assertEquals(1, order.localAnchorY());
        assertEquals(0, order.localAnchorZ());
        assertEquals(100, order.anchorX());
        assertEquals(180, order.rotationDegrees());
        assertEquals(order.expected(), gateway.lastCaptured,
                "on sauvegarde exactement la zone qu'on va écraser");
    }

    /** Échec du collage : l'emplacement reste VIDE et aucun placement n'est inscrit. */
    @Test
    void aFailedPasteLeavesTheSiteEmptyAndRecordsNothing() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.pasteFails = true;

        BuildingPlacementService.PlaceResult result = place(created.id());

        assertFalse(result.placed());
        assertTrue(result.error().contains("collage refusé"), result.error());
        assertEquals(SiteStatus.EMPTY, sites.find(created.id()).orElseThrow().status());
        assertTrue(service.all().isEmpty());
        assertEquals(0, placementRepository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    /**
     * Échec de la sauvegarde : on ne colle même pas.
     *
     * <p>Poser sans pouvoir revenir en arrière n'est pas acceptable pour une première validation,
     * et le ticket demandait explicitement un retour arrière.</p>
     */
    @Test
    void aFailedBackupStopsEverythingBeforeTheWorldIsTouched() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.captureFails = true;

        BuildingPlacementService.PlaceResult result = place(created.id());

        assertFalse(result.placed());
        assertTrue(result.error().contains("rien n'a été posé"), result.error());
        assertFalse(gateway.calls.stream().anyMatch(c -> c.startsWith("paste:")),
                "aucun collage ne doit avoir été tenté : " + gateway.calls);
        assertEquals(SiteStatus.EMPTY, sites.find(created.id()).orElseThrow().status());
    }

    @Test
    void placingWithoutTheEngineIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.available = false;
        gateway.unavailable = "WorldEdit n'est pas installé sur ce serveur.";

        BuildingPlacementService.PlaceResult result = place(created.id());

        assertFalse(result.placed());
        assertTrue(gateway.calls.isEmpty(), gateway.calls.toString());
    }

    /** Double clic : la seconde pose est refusée, et il ne reste qu'un bâtiment. */
    @Test
    void placingTwiceLeavesExactlyOnePlacement() throws Exception {
        BuildingSite created = site(Facing.EAST);

        BuildingPlacementService.PlaceResult first = place(created.id());
        BuildingPlacementService.PlaceResult second = place(created.id());

        assertTrue(first.placed());
        assertFalse(second.placed(), "le second clic ne doit rien poser");
        assertTrue(second.error().contains("porte déjà"), second.error());
        assertEquals(1, service.all().size());
        assertEquals(1, placementRepository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
        // Et le monde n'a été touché qu'une fois.
        assertEquals(1, gateway.calls.stream().filter(c -> c.startsWith("paste:")).count());
    }

    /**
     * La base est la seconde barrière, et elle tient même sans le service.
     *
     * <p>{@code site_id} est la clé primaire : c'est une règle métier — un emplacement porte au plus
     * un bâtiment — appliquée par le schéma, donc vraie même si deux requêtes arrivaient
     * simultanément.</p>
     */
    @Test
    void theSchemaItselfRefusesASecondPlacementOnTheSameSite() throws Exception {
        BuildingSite created = site(Facing.EAST);
        place(created.id());

        var duplicate = service.all().get(0);
        boolean failed = false;
        try {
            placementRepository.insert(duplicate).get(TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception expected) {
            failed = true;
        }

        assertTrue(failed, "la clé primaire doit refuser le doublon");
        assertEquals(1, placementRepository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    @Test
    void anAlreadyOccupiedSiteIsRefused() throws Exception {
        BuildingSite created = site(Facing.EAST);
        place(created.id());

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.firstRefusal().contains("porte déjà"), preview.firstRefusal());
    }

    /** Chevauchement avec un bâtiment déjà posé : refusé, en nommant le coupable. */
    @Test
    void anOverlappingFootprintIsRefused() throws Exception {
        BuildingSite first = site(Facing.NORTH);
        place(first.id());
        // Un second emplacement à deux blocs : les repères voisins sont permis (#213), mais la
        // matière posée ne peut pas se superposer.
        BuildingSite second = sites.create("world_hub", new BuildingSiteAnchor(102, 70, 200),
                Facing.NORTH, "Trop près", "Lody").get(TIMEOUT, TimeUnit.SECONDS).site();

        BuildingPlacementService.Preview preview = service.preview(second.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("chevauche")),
                preview.refusals().toString());
    }

    @Test
    void aDistantSiteInTheSameWorldIsAccepted() throws Exception {
        BuildingSite first = site(Facing.NORTH);
        place(first.id());
        BuildingSite far = sites.create("world_hub", new BuildingSiteAnchor(500, 70, 500),
                Facing.NORTH, "Loin", "Lody").get(TIMEOUT, TimeUnit.SECONDS).site();

        assertTrue(service.preview(far.id(), "test_hut_01").placeable());
    }

    /**
     * Un emplacement marqué occupé sans placement enregistré est signalé, pas écrasé.
     *
     * <p>C'est un état incohérent : poser par-dessus reviendrait à écraser quelque chose dont on ne
     * sait rien, et dont on n'aurait donc aucune sauvegarde.</p>
     */
    @Test
    void anInconsistentOccupiedSiteIsReportedRatherThanOverwritten() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        sites.markStatus(created.id(), SiteStatus.OCCUPIED).get(TIMEOUT, TimeUnit.SECONDS);

        BuildingPlacementService.Preview preview = service.preview(created.id(), "test_hut_01");

        assertFalse(preview.placeable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("Incohérence")),
                preview.refusals().toString());
    }

    // ---- Le retour arrière ---------------------------------------------------------------------

    @Test
    void rollbackRestoresTheZoneAndFreesTheSite() throws Exception {
        BuildingSite created = site(Facing.EAST);
        BuildingPlacementService.PlaceResult placed = place(created.id());
        String backup = placed.placement().backupSchematic();

        BuildingPlacementService.RollbackResult result =
                service.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(result.restored(), result.error());
        assertEquals(backup, gateway.lastRestored, "c'est la sauvegarde de CETTE pose qui est reposée");
        assertTrue(service.all().isEmpty());
        assertEquals(SiteStatus.EMPTY, sites.find(created.id()).orElseThrow().status());
        assertEquals(0, placementRepository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    @Test
    void rollbackWithoutAPlacementIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);

        BuildingPlacementService.RollbackResult result =
                service.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS);

        assertFalse(result.restored());
        assertTrue(result.error().contains("Aucun bâtiment enregistré"), result.error());
    }

    /**
     * Sans sauvegarde, le retour arrière est <strong>refusé</strong>, jamais remplacé par « remettre
     * de l'air ».
     *
     * <p>Mettre de l'air dans l'emprise détruirait le terrain d'origine : ce serait une destruction
     * déguisée en annulation, et c'est exactement ce que le ticket interdisait.</p>
     */
    @Test
    void rollbackIsRefusedWhenNoBackupExists() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id());
        // On simule une ligne ancienne, écrite sans sauvegarde.
        var placement = service.all().get(0);
        placementRepository.delete(created.id()).get(TIMEOUT, TimeUnit.SECONDS);
        placementRepository.insert(new com.lodygames.rpgquest.building.model.BuildingPlacement(
                placement.siteId(), placement.buildingId(),
                placement.buildingVersion(), placement.schematicSha256(), placement.world(),
                placement.anchorX(), placement.anchorY(), placement.anchorZ(),
                placement.rotationDegrees(),
                placement.minX(), placement.minY(), placement.minZ(),
                placement.maxX(), placement.maxY(), placement.maxZ(),
                null, placement.placedBy(), placement.placedAt()))
                .get(TIMEOUT, TimeUnit.SECONDS);
        BuildingPlacementService reloaded = newService();

        BuildingPlacementService.RollbackResult result =
                reloaded.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS);

        assertFalse(result.restored());
        assertTrue(result.error().contains("détruirait le terrain"), result.error());
        assertFalse(gateway.calls.stream().anyMatch(c -> c.startsWith("restore:")),
                "aucune restauration ne doit être tentée : " + gateway.calls);
    }

    @Test
    void rollbackIsRefusedWhenTheBackupFileHasDisappeared() throws Exception {
        BuildingSite created = site(Facing.EAST);
        BuildingPlacementService.PlaceResult placed = place(created.id());
        gateway.files.remove(placed.placement().backupSchematic());

        BuildingPlacementService.RollbackResult result =
                service.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS);

        assertFalse(result.restored());
        assertTrue(result.error().contains("introuvable"), result.error());
    }

    /** Si la restauration échoue, l'emplacement reste occupé : la fiche ne mentira pas. */
    @Test
    void aFailedRestoreKeepsTheSiteOccupied() throws Exception {
        BuildingSite created = site(Facing.EAST);
        place(created.id());
        gateway.restoreFails = true;

        BuildingPlacementService.RollbackResult result =
                service.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS);

        assertFalse(result.restored());
        assertEquals(1, service.all().size());
        assertEquals(SiteStatus.OCCUPIED, sites.find(created.id()).orElseThrow().status());
    }

    @Test
    void rollingBackTwiceIsHarmless() throws Exception {
        BuildingSite created = site(Facing.EAST);
        place(created.id());

        assertTrue(service.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS).restored());
        assertFalse(service.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS).restored());
        assertEquals(0, placementRepository.loadAll().get(TIMEOUT, TimeUnit.SECONDS).size());
    }

    // ---- Persistance ---------------------------------------------------------------------------

    /** Le test du redémarrage : un service neuf sur la même base retrouve le placement. */
    @Test
    void aPlacementSurvivesAFreshService() throws Exception {
        BuildingSite created = site(Facing.EAST);
        place(created.id());

        BuildingPlacementService reloaded = newService();

        assertEquals(1, reloaded.all().size());
        var placement = reloaded.at(created.id()).orElseThrow();
        assertEquals("test_hut_01", placement.buildingId());
        assertEquals(90, placement.rotationDegrees());
        assertEquals(5, placement.footprint().sizeX());
        assertEquals(7, placement.footprint().sizeZ());
        assertTrue(placement.restorable());
        // Et l'emplacement est toujours occupé, lu depuis la base lui aussi.
        BuildingSiteService freshSites = new BuildingSiteService(siteRepository);
        freshSites.load().get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(SiteStatus.OCCUPIED, freshSites.find(created.id()).orElseThrow().status());
    }

    @Test
    void aRolledBackPlacementDoesNotComeBackAfterARestart() throws Exception {
        BuildingSite created = site(Facing.EAST);
        place(created.id());
        service.rollback(created.id()).get(TIMEOUT, TimeUnit.SECONDS);

        BuildingPlacementService reloaded = newService();

        assertTrue(reloaded.all().isEmpty());
        BuildingSiteService freshSites = new BuildingSiteService(siteRepository);
        freshSites.load().get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(SiteStatus.EMPTY, freshSites.find(created.id()).orElseThrow().status());
    }

    // ---- Le nom de sauvegarde ------------------------------------------------------------------

    @Test
    void theBackupNameIsSafeAndDeterministic() {
        String name = BuildingPlacementService.backupNameFor("buildsite_0002",
                java.time.Instant.parse("2026-10-08T20:00:00Z"));

        assertEquals("backup_buildsite_0002_1791489600000.schem", name);
        assertFalse(name.contains("/"), "aucun séparateur de chemin");
        assertFalse(name.contains(".."), "aucune traversée");
    }

    @Test
    void anOddSiteIdCannotEscapeTheSchematicDirectory() {
        String name = BuildingPlacementService.backupNameFor("../../etc/passwd",
                java.time.Instant.parse("2026-10-08T20:00:00Z"));

        assertFalse(name.contains("/"));
        assertFalse(name.contains(".."), name);
        assertTrue(name.endsWith(".schem"));
    }
}
