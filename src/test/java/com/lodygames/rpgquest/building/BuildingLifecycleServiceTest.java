package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingOperation;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.SiteStatus;
import com.lodygames.rpgquest.database.BuildingBaselineRepository;
import com.lodygames.rpgquest.database.BuildingHistoryRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #234 — le cycle de vie d'un bâtiment posé, sur une vraie base SQLite.
 *
 * <h2>Ce que ces tests protègent</h2>
 *
 * <p>Un emplacement occupé ne doit pas devenir une impasse, et les opérations qui le transforment
 * écrivent dans un monde réel. Tout ce qui garantit leur sûreté est de l'<strong>ordre</strong> :
 * sauvegarder avant de muter, restaurer le terrain d'origine avant de capturer une nouvelle zone,
 * n'écrire la fiche qu'après un collage réussi. Un ordre ne se relit pas, il se vérifie — d'où un
 * moteur simulé qui <strong>enregistre la séquence des appels</strong>.</p>
 *
 * <p>Le reste est de la distinction : baseline d'origine contre sauvegarde de la dernière opération,
 * intention de l'emplacement contre fait posé, version déclarée contre empreinte réelle. Chacune a
 * ses tests, parce que chacune, confondue, produit un mensonge à l'écran.</p>
 */
class BuildingLifecycleServiceTest {

    private static final long TIMEOUT = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private BuildingPlacementRepository placementRepository;
    private BuildingBaselineRepository baselineRepository;
    private BuildingHistoryRepository historyRepository;
    private BuildingSiteService sites;
    private BuildingLibrary library;
    private FakeGateway gateway;
    private FakeWorld world;
    private BuildingPlacementService service;

    // ---- Doubles -------------------------------------------------------------------------------

    /**
     * Moteur de schematics simulé, qui <strong>enregistre l'ordre des appels</strong>.
     *
     * <p>C'est la seule façon de vérifier qu'une compensation est prise avant toute mutation, et que
     * le terrain d'origine est rendu avant qu'une nouvelle zone soit capturée.</p>
     */
    private static final class FakeGateway implements SchematicGateway {
        final List<String> calls = new ArrayList<>();
        final Set<String> files = new LinkedHashSet<>();
        final List<String> restored = new ArrayList<>();
        boolean available = true;
        boolean captureFails;
        boolean pasteFails;
        boolean restoreFails;
        /** Empreinte renvoyée pour un fichier donné ; sert à simuler une définition modifiée. */
        final java.util.Map<String, String> fingerprints = new java.util.LinkedHashMap<>();
        final java.util.Map<String, Dimensions> dimensions = new java.util.LinkedHashMap<>();

        @Override public boolean available() {
            return available;
        }

        @Override public String unavailableReason() {
            return "moteur simulé indisponible";
        }

        @Override public boolean has(String fileName) {
            return files.contains(fileName);
        }

        @Override public java.util.Optional<String> fingerprint(String fileName) {
            if (!files.contains(fileName)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(fingerprints.getOrDefault(fileName, "sha-" + fileName));
        }

        @Override public Outcome write(Blueprint blueprint, String fileName) {
            files.add(fileName);
            return Outcome.success();
        }

        @Override public Optional<Dimensions> inspect(String fileName) {
            return files.contains(fileName)
                    ? Optional.ofNullable(dimensions.get(fileName)) : Optional.empty();
        }

        @Override public Outcome capture(BuildingFootprint footprint, String fileName) {
            calls.add("capture:" + fileName);
            if (captureFails) {
                return Outcome.failure("disque plein");
            }
            files.add(fileName);
            return Outcome.success();
        }

        @Override public Outcome paste(PasteOrder order) {
            calls.add("paste:" + order.schematic() + "@" + order.rotationDegrees());
            if (pasteFails) {
                return Outcome.failure("collage refusé par le moteur");
            }
            return Outcome.success();
        }

        @Override public Outcome restore(String fileName, BuildingFootprint footprint) {
            calls.add("restore:" + fileName);
            if (restoreFails) {
                return Outcome.failure("sauvegarde illisible");
            }
            restored.add(fileName);
            return Outcome.success();
        }

        /** Les appels d'un type donné, dans l'ordre. */
        List<String> callsOfType(String prefix) {
            return calls.stream().filter(c -> c.startsWith(prefix)).toList();
        }
    }

    /** Monde simulé : limites de hauteur et comptage de blocs, rien de plus. */
    private static final class FakeWorld implements WorldProbe {
        final Set<String> loaded = new LinkedHashSet<>(List.of("world_hub"));
        int minHeight = -64;
        int maxHeight = 320;
        long nonAir;

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
            return OptionalLong.of(nonAir);
        }
    }

    // ---- Harnais -------------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        placementRepository = new BuildingPlacementRepository(database);
        baselineRepository = new BuildingBaselineRepository(database);
        historyRepository = new BuildingHistoryRepository(database);
        sites = new BuildingSiteService(new BuildingSiteRepository(database));
        sites.load().get(TIMEOUT, TimeUnit.SECONDS);

        Path buildings = tempDir.resolve("buildings");
        Files.createDirectories(buildings);
        // Deux bâtiments de tailles DIFFÉRENTES : c'est la différence de taille qui fait tout
        // l'intérêt du remplacement, puisqu'une baseline prise sur la petite emprise ne suffit pas.
        Files.writeString(buildings.resolve("petit.yml"), """
                id: petit
                name: "Petite hutte"
                description: "Petit bâtiment de test."
                schematic: petit.schem
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
                version: 1
                """);
        Files.writeString(buildings.resolve("grand.yml"), """
                id: grand
                name: "Grande tour"
                description: "Grand bâtiment de test."
                schematic: grand.schem
                size:
                  x: 9
                  y: 14
                  z: 9
                anchor:
                  x: 4
                  y: 1
                  z: 0
                front: NORTH
                materials:
                  - stone_bricks
                version: 1
                """);
        library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.start();

        gateway = new FakeGateway();
        gateway.files.add("petit.schem");
        gateway.files.add("grand.schem");
        gateway.dimensions.put("petit.schem", new SchematicGateway.Dimensions(7, 6, 5));
        gateway.dimensions.put("grand.schem", new SchematicGateway.Dimensions(9, 14, 9));
        world = new FakeWorld();
        service = newService();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /** Un service neuf sur la même base : exactement ce que fait un redémarrage du serveur. */
    private BuildingPlacementService newService() throws Exception {
        BuildingPlacementService fresh = new BuildingPlacementService(placementRepository, sites,
                library, gateway, world, java.time.Clock.systemUTC(), baselineRepository,
                historyRepository);
        fresh.load().get(TIMEOUT, TimeUnit.SECONDS);
        return fresh;
    }

    private BuildingSite site(Facing facing) throws Exception {
        return sites.create("world_hub", new BuildingSiteAnchor(100, 70, 200), facing,
                "Emplacement de test", "Lody").get(TIMEOUT, TimeUnit.SECONDS).site();
    }

    private BuildingPlacementService.PlaceResult place(String siteId, String building)
            throws Exception {
        return service.place(siteId, building, "Lody").get(TIMEOUT, TimeUnit.SECONDS);
    }

    private BuildingPlacementService.FreeResult free(String siteId) throws Exception {
        return service.free(siteId, "Lody").get(TIMEOUT, TimeUnit.SECONDS);
    }

    private BuildingPlacementService.RetargetResult reorient(String siteId, Facing facing)
            throws Exception {
        var preview = service.previewReorient(siteId, facing);
        return service.reorient(siteId, facing, "Lody", preview.token())
                .get(TIMEOUT, TimeUnit.SECONDS);
    }

    private BuildingPlacementService.RetargetResult replace(String siteId, String building,
                                                             Facing facing) throws Exception {
        var preview = service.previewReplace(siteId, building, facing);
        return service.replace(siteId, building, facing, "Lody", preview.token())
                .get(TIMEOUT, TimeUnit.SECONDS);
    }

    private SiteStatus statusOf(String siteId) {
        return sites.find(siteId).orElseThrow().status();
    }

    private List<com.lodygames.rpgquest.building.model.BuildingHistoryEntry> history(String siteId)
            throws Exception {
        return historyRepository.forSite(siteId, 50).get(TIMEOUT, TimeUnit.SECONDS);
    }

    // ================================================================================
    //  LIBÉRER L'EMPLACEMENT — priorité 1 du ticket
    // ================================================================================

    @Test
    void freeingRestoresTheTerrainAndLeavesTheSiteEmptyAndReusable() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        assertEquals(SiteStatus.OCCUPIED, statusOf(created.id()));

        BuildingPlacementService.FreeResult result = free(created.id());

        assertTrue(result.freed(), result.error());
        assertEquals(SiteStatus.EMPTY, statusOf(created.id()));
        assertTrue(service.at(created.id()).isEmpty(), "le placement actif doit avoir disparu");
        assertFalse(gateway.restored.isEmpty(), "le terrain doit avoir été restauré");
        // Immédiatement réutilisable : c'est tout l'objet du ticket.
        assertTrue(place(created.id(), "grand").placed(), "le même emplacement doit être réutilisable");
        assertEquals(SiteStatus.OCCUPIED, statusOf(created.id()));
    }

    /**
     * Le terrain restauré est bien la <strong>baseline</strong>, pas une autre sauvegarde.
     *
     * <p>Le nom du fichier le dit : une baseline commence par {@code origine_}, une compensation par
     * {@code compens_}. Confondre les deux est l'erreur que ce ticket existe pour empêcher.</p>
     */
    @Test
    void freeingUsesTheOriginalBaselineFile() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        String baselineFile = service.baselinesFor(created.id()).get(0).schematic();

        free(created.id());

        assertTrue(gateway.restored.contains(baselineFile),
                "le fichier de baseline doit être celui restauré, trouvés : " + gateway.restored);
    }

    /**
     * Si la restauration échoue, l'emplacement reste OCCUPIED et le placement est conservé.
     *
     * <p>Il n'y a jamais de faux {@code EMPTY} : un emplacement annoncé libre avec un bâtiment
     * encore debout dessus est précisément l'impasse que ce lot corrige.</p>
     */
    @Test
    void aFailedRestoreKeepsTheSiteOccupiedAndThePlacement() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        gateway.restoreFails = true;

        BuildingPlacementService.FreeResult result = free(created.id());

        assertFalse(result.freed());
        assertEquals(SiteStatus.OCCUPIED, statusOf(created.id()), "jamais de faux EMPTY");
        assertTrue(service.at(created.id()).isPresent(), "le placement actif doit être conservé");
        assertTrue(result.error().contains("reste occupé"), result.error());
    }

    /** L'échec est journalisé : un journal muet au moment du problème ne sert à rien. */
    @Test
    void aFailedRestoreIsRecordedInTheHistory() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        gateway.restoreFails = true;

        free(created.id());

        var entries = history(created.id());
        assertTrue(entries.stream().anyMatch(e ->
                        e.operation() == BuildingOperation.RESTORE && !e.ok()),
                "une libération échouée doit figurer au journal : " + entries);
    }

    /** Idempotent : un second appel ne trouve plus rien et ne touche pas au monde. */
    @Test
    void freeingTwiceIsRefusedTheSecondTimeWithoutTouchingTheWorld() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        free(created.id());
        int restoresAfterFirst = gateway.callsOfType("restore:").size();

        BuildingPlacementService.FreeResult second = free(created.id());

        assertFalse(second.freed());
        assertEquals(restoresAfterFirst, gateway.callsOfType("restore:").size(),
                "un second appel ne doit rien restaurer");
    }

    /** La libération survit à un redémarrage : la base est la source de vérité. */
    @Test
    void theFreedStateSurvivesARestart() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        free(created.id());

        BuildingPlacementService reloaded = newService();

        assertTrue(reloaded.at(created.id()).isEmpty());
        assertEquals(SiteStatus.EMPTY, statusOf(created.id()));
    }

    /** Un placement rechargé garde sa baseline : elle vit en base, pas en mémoire. */
    @Test
    void theBaselineSurvivesARestart() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        String before = service.baselinesFor(created.id()).get(0).schematic();

        BuildingPlacementService reloaded = newService();

        assertEquals(before, reloaded.baselinesFor(created.id()).get(0).schematic());
    }

    /**
     * Sans aucune sauvegarde, la libération est <strong>refusée</strong>.
     *
     * <p>Remettre de l'air dans l'emprise serait une destruction déguisée en annulation.</p>
     */
    @Test
    void freeingIsRefusedWhenNothingWasEverSaved() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        gateway.captureFails = true;
        BuildingPlacementService.PlaceResult placed = place(created.id(), "petit");

        // La pose elle-même est refusée faute de sauvegarde : on ne pose pas sans pouvoir revenir.
        assertFalse(placed.placed());
        assertEquals(SiteStatus.EMPTY, statusOf(created.id()));
        assertFalse(free(created.id()).freed());
    }

    // ================================================================================
    //  DIVERGENCE — un emplacement est une intention, un placement est un fait
    // ================================================================================

    @Test
    void aFreshPlacementDoesNotDiverge() throws Exception {
        BuildingSite created = site(Facing.SOUTH);
        place(created.id(), "petit");

        assertFalse(service.diverges(created.id()));
        assertEquals(service.at(created.id()).orElseThrow().rotationDegrees(),
                service.desiredRotation(created.id()).orElseThrow());
    }

    /**
     * Changer l'orientation de l'emplacement ne déplace <strong>aucun bloc</strong>.
     *
     * <p>C'est le principe fondamental du ticket. Le placement garde sa rotation, et c'est la
     * divergence qui est signalée — pas une rotation silencieuse du monde.</p>
     */
    @Test
    void changingTheSiteFacingLeavesThePlacedBuildingUntouchedAndSignalsDivergence()
            throws Exception {
        BuildingSite created = site(Facing.SOUTH);
        place(created.id(), "petit");
        int rotationBefore = service.at(created.id()).orElseThrow().rotationDegrees();
        int pastesBefore = gateway.callsOfType("paste:").size();

        sites.reface(created.id(), Facing.NORTH).get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(rotationBefore, service.at(created.id()).orElseThrow().rotationDegrees(),
                "le bâtiment posé ne doit pas avoir tourné");
        assertEquals(pastesBefore, gateway.callsOfType("paste:").size(),
                "aucun collage ne doit avoir eu lieu");
        assertTrue(service.diverges(created.id()), "la divergence doit être signalée");
        assertNotEquals(service.at(created.id()).orElseThrow().rotationDegrees(),
                service.desiredRotation(created.id()).orElseThrow());
    }

    /** Réorienter aligne les deux, et la divergence disparaît. */
    @Test
    void reorientingRemovesTheDivergence() throws Exception {
        BuildingSite created = site(Facing.SOUTH);
        place(created.id(), "petit");
        sites.reface(created.id(), Facing.NORTH).get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(service.diverges(created.id()));

        BuildingPlacementService.RetargetResult result = reorient(created.id(), Facing.NORTH);

        assertTrue(result.applied(), result.error());
        assertFalse(service.diverges(created.id()));
    }

    // ================================================================================
    //  RÉORIENTER
    // ================================================================================

    /**
     * L'ordre des opérations, vérifié sur la séquence réelle des appels.
     *
     * <p>Compensation d'abord, puis restauration du terrain d'origine, puis collage. Inverser les
     * deux premiers rendrait l'échec irrattrapable ; coller avant de restaurer laisserait le nouveau
     * bâtiment écrasé par l'ancien terrain.</p>
     */
    @Test
    void reorientingSavesCompensationThenRestoresTheTerrainThenPastes() throws Exception {
        BuildingSite created = site(Facing.SOUTH);
        place(created.id(), "petit");
        gateway.calls.clear();

        assertTrue(reorient(created.id(), Facing.EAST).applied());

        List<String> calls = gateway.calls;
        int compensation = indexOfPrefix(calls, "capture:compens_");
        int restore = indexOfPrefix(calls, "restore:origine_");
        int paste = indexOfPrefix(calls, "paste:");
        assertTrue(compensation >= 0, "une compensation doit être prise : " + calls);
        assertTrue(restore > compensation, "le terrain doit être rendu APRÈS la compensation : " + calls);
        assertTrue(paste > restore, "le collage doit venir APRÈS la restauration : " + calls);
    }

    @Test
    void reorientingChangesTheRotationAndTheFootprint() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        var before = service.at(created.id()).orElseThrow();

        assertTrue(reorient(created.id(), Facing.EAST).applied());

        var after = service.at(created.id()).orElseThrow();
        assertNotEquals(before.rotationDegrees(), after.rotationDegrees());
        assertNotEquals(before.footprint().label(), after.footprint().label(),
                "une rotation de 90° échange les axes, donc l'emprise change");
    }

    /** Réorienter vers l'orientation déjà en place est refusé : réécrire pour rien n'a pas de sens. */
    @Test
    void reorientingToTheSameOrientationIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");

        var preview = service.previewReorient(created.id(), Facing.NORTH);

        assertFalse(preview.applicable());
        assertTrue(preview.firstRefusal().contains("déjà orienté"), preview.firstRefusal());
    }

    /**
     * Si le collage échoue, l'ancien bâtiment est remis et l'opération n'a pas eu lieu.
     *
     * <p>C'est le scénario dangereux du ticket : ancien bâtiment retiré, nouveau collage échoué.
     * La compensation est ce qui empêche le monde de rester incohérent.</p>
     */
    @Test
    void aFailedPasteCompensatesAndKeepsTheOldPlacement() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        var before = service.at(created.id()).orElseThrow();
        gateway.pasteFails = true;

        BuildingPlacementService.RetargetResult result = reorient(created.id(), Facing.EAST);

        assertFalse(result.applied());
        assertTrue(result.compensated(), "l'ancien état doit avoir été remis : " + result.error());
        assertEquals(SiteStatus.OCCUPIED, statusOf(created.id()));
        var after = service.at(created.id()).orElseThrow();
        assertEquals(before.rotationDegrees(), after.rotationDegrees(),
                "la fiche ne doit pas avoir changé");
        assertEquals(before.footprint().label(), after.footprint().label());
    }

    /** La fiche n'est écrite qu'après un collage réussi. */
    @Test
    void theRecordIsOnlyWrittenAfterASuccessfulPaste() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        gateway.pasteFails = true;
        reorient(created.id(), Facing.EAST);

        BuildingPlacementService reloaded = newService();

        assertEquals(0, reloaded.at(created.id()).orElseThrow().rotationDegrees(),
                "la rotation en base doit être celle d'avant l'échec");
    }

    /** Une emprise qui dépasse le plafond du monde est refusée avant toute mutation. */
    @Test
    void anOutOfBoundsTargetIsRefusedBeforeAnyMutation() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        world.maxHeight = 72;
        gateway.calls.clear();

        var preview = service.previewReplace(created.id(), "grand", Facing.NORTH);

        assertFalse(preview.applicable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("plafond")),
                preview.refusals().toString());
        assertTrue(gateway.calls.isEmpty(), "un aperçu refusé ne doit toucher à rien");
    }

    /** Un jeton d'aperçu périmé est refusé : un aperçu n'est pas une réservation. */
    @Test
    void aStalePreviewTokenIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");

        BuildingPlacementService.RetargetResult result =
                service.reorient(created.id(), Facing.EAST, "Lody", "jetonquinexistepas")
                        .get(TIMEOUT, TimeUnit.SECONDS);

        assertFalse(result.applied());
        assertTrue(result.error().contains("changé depuis l'aperçu"), result.error());
    }

    /**
     * Changer l'orientation de l'emplacement entre l'aperçu et la confirmation invalide le jeton.
     *
     * <p>C'est exactement ce que la protection doit attraper : la décision a été prise sur un état
     * qui n'existe plus.</p>
     */
    @Test
    void changingTheSiteBetweenPreviewAndConfirmInvalidatesTheToken() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        String token = service.previewReorient(created.id(), Facing.EAST).token();

        sites.reface(created.id(), Facing.WEST).get(TIMEOUT, TimeUnit.SECONDS);
        BuildingPlacementService.RetargetResult result =
                service.reorient(created.id(), Facing.EAST, "Lody", token)
                        .get(TIMEOUT, TimeUnit.SECONDS);

        assertFalse(result.applied());
        assertTrue(result.error().contains("changé depuis l'aperçu"), result.error());
    }

    // ================================================================================
    //  REMPLACER
    // ================================================================================

    @Test
    void replacingASmallBuildingWithALargerOneWorks() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");

        BuildingPlacementService.RetargetResult result = replace(created.id(), "grand", Facing.NORTH);

        assertTrue(result.applied(), result.error());
        assertEquals("grand", service.at(created.id()).orElseThrow().buildingId());
        assertEquals(SiteStatus.OCCUPIED, statusOf(created.id()));
    }

    @Test
    void replacingALargeBuildingWithASmallerOneWorks() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "grand");

        assertTrue(replace(created.id(), "petit", Facing.NORTH).applied());

        assertEquals("petit", service.at(created.id()).orElseThrow().buildingId());
    }

    /**
     * Agrandir capture la baseline de la zone <strong>nouvellement</strong> touchée.
     *
     * <p>Sans ce fragment, restaurer le terrain d'origine laisserait des blocs de la grande tour en
     * dehors de l'emprise de la petite hutte — un terrain « presque d'origine », c'est-à-dire
     * faux.</p>
     */
    @Test
    void replacingWithALargerBuildingCapturesTheNewlyTouchedTerrain() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        assertEquals(1, service.baselinesFor(created.id()).size());

        replace(created.id(), "grand", Facing.NORTH);

        assertEquals(2, service.baselinesFor(created.id()).size(),
                "un fragment supplémentaire doit couvrir la zone plus grande");
    }

    /** Réduire ne capture rien de plus : la zone est déjà couverte. */
    @Test
    void replacingWithASmallerBuildingCapturesNothingNew() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "grand");
        assertEquals(1, service.baselinesFor(created.id()).size());

        replace(created.id(), "petit", Facing.NORTH);

        assertEquals(1, service.baselinesFor(created.id()).size(),
                "la petite emprise est déjà couverte : aucun fragment à ajouter");
    }

    /** Un chevauchement avec un AUTRE bâtiment posé est refusé. */
    @Test
    void replacingIsRefusedWhenTheTargetOverlapsAnotherBuilding() throws Exception {
        BuildingSite first = site(Facing.NORTH);
        place(first.id(), "petit");
        // Un second emplacement assez proche pour que la grande tour chevauche le premier bâtiment.
        // 107 et non 103 : « petit » (7 de large, ancre x=3) occupe x-3..x+3, donc 97..103 pour le
        // premier. Il faut x ≥ 107 pour que les deux petites huttes ne se chevauchent pas — mais
        // « grand » (9 de large, ancre x=4) occupe 103..111, qui mord sur la première. C'est
        // exactement le cas qu'on veut voir refusé.
        BuildingSite second = sites.create("world_hub", new BuildingSiteAnchor(107, 70, 200),
                Facing.NORTH, "Voisin", "Lody").get(TIMEOUT, TimeUnit.SECONDS).site();
        assertTrue(place(second.id(), "petit").placed(), "les deux petites huttes doivent tenir");

        var preview = service.previewReplace(second.id(), "grand", Facing.NORTH);

        assertFalse(preview.applicable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains("chevauche")),
                preview.refusals().toString());
    }

    @Test
    void replacingWithAnUnknownBuildingIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");

        var preview = service.previewReplace(created.id(), "inexistant", Facing.NORTH);

        assertFalse(preview.applicable());
        assertTrue(preview.firstRefusal().contains("inconnu"), preview.firstRefusal());
    }

    // ================================================================================
    //  BASELINE — aucune dérive après plusieurs opérations
    // ================================================================================

    /**
     * Le test central du ticket : terrain → hutte → tour → rotation → libération.
     *
     * <p>Après trois opérations, « restaurer le terrain original » doit encore utiliser la
     * <strong>première</strong> sauvegarde. Si chaque opération devenait la nouvelle baseline, on
     * restaurerait « le terrain avec la tour », et le site ne reviendrait jamais à son état initial.</p>
     */
    @Test
    void afterSeveralOperationsTheOriginalBaselineIsStillTheOneRestored() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        String firstBaseline = service.baselinesFor(created.id()).get(0).schematic();

        replace(created.id(), "grand", Facing.NORTH);
        reorient(created.id(), Facing.EAST);
        gateway.restored.clear();

        assertTrue(free(created.id()).freed());

        assertTrue(gateway.restored.contains(firstBaseline),
                "la PREMIÈRE sauvegarde doit être restaurée, trouvées : " + gateway.restored);
        assertTrue(gateway.restored.stream().allMatch(f -> f.startsWith("origine_")),
                "seules des baselines doivent être restaurées : " + gateway.restored);
    }

    /** Une baseline n'est jamais réécrite : son nom ne change pas d'une opération à l'autre. */
    @Test
    void theFirstBaselineIsNeverRewritten() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        String first = service.baselinesFor(created.id()).get(0).schematic();

        replace(created.id(), "grand", Facing.NORTH);
        reorient(created.id(), Facing.SOUTH);
        replace(created.id(), "petit", Facing.WEST);

        assertEquals(first, service.baselinesFor(created.id()).get(0).schematic(),
                "le premier fragment doit rester identique");
    }

    /** Libérer puis rebâtir ne crée pas de nouvelle baseline : la zone est déjà couverte. */
    @Test
    void freeingThenRebuildingReusesTheSameBaseline() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        String first = service.baselinesFor(created.id()).get(0).schematic();

        free(created.id());
        place(created.id(), "petit");

        assertEquals(1, service.baselinesFor(created.id()).size());
        assertEquals(first, service.baselinesFor(created.id()).get(0).schematic());
    }

    // ================================================================================
    //  VERSION / EMPREINTE
    // ================================================================================

    /** Le placement mémorise l'empreinte du fichier réellement collé. */
    @Test
    void thePlacementRemembersWhatWasActuallyPasted() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");

        var placement = service.at(created.id()).orElseThrow();

        assertEquals(1, placement.buildingVersion());
        assertEquals("sha-petit.schem", placement.schematicSha256());
    }

    /**
     * Le bâtiment posé ne change <strong>jamais</strong> parce que sa définition a changé.
     *
     * <p>Le fichier de la bibliothèque est modifié sous les pieds du placement ; celui-ci garde son
     * empreinte, et se déclare simplement dépassé. C'est une information, pas une action.</p>
     */
    @Test
    void aChangedDefinitionLeavesThePlacementAloneButMarksItOutdated() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        var placement = service.at(created.id()).orElseThrow();

        gateway.fingerprints.put("petit.schem", "sha-version-2");

        assertEquals("sha-petit.schem", placement.schematicSha256(),
                "l'empreinte posée ne doit pas bouger");
        assertTrue(placement.outdatedAgainst(1, "sha-version-2"),
                "la divergence de version doit être détectée");
        assertFalse(placement.outdatedAgainst(1, "sha-petit.schem"),
                "deux empreintes identiques ne sont pas une divergence");
    }

    /** Sans aucune empreinte connue des deux côtés, on ne conclut pas. */
    @Test
    void withoutFingerprintsNoConclusionIsDrawn() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        var placement = service.at(created.id()).orElseThrow();

        assertFalse(placement.outdatedAgainst(0, ""),
                "sans information, affirmer « dépassé » serait inventé");
    }

    // ================================================================================
    //  HISTORIQUE
    // ================================================================================

    /** Chaque opération laisse une trace, dans l'ordre, avec son type. */
    @Test
    void everyOperationIsRecordedWithItsType() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        replace(created.id(), "grand", Facing.NORTH);
        reorient(created.id(), Facing.EAST);
        free(created.id());

        List<BuildingOperation> operations = history(created.id()).stream()
                .filter(com.lodygames.rpgquest.building.model.BuildingHistoryEntry::ok)
                .map(com.lodygames.rpgquest.building.model.BuildingHistoryEntry::operation)
                .toList();

        // Du plus récent au plus ancien : c'est l'ordre dans lequel on lit un journal.
        assertEquals(List.of(BuildingOperation.RESTORE, BuildingOperation.ROTATE,
                BuildingOperation.REPLACE, BuildingOperation.PLACE), operations);
    }

    /** Le journal porte de quoi diagnostiquer : bâtiment, version, empreinte, emprise, acteur. */
    @Test
    void theHistoryCarriesEnoughToDiagnose() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");

        var entry = history(created.id()).get(0);

        assertEquals(BuildingOperation.PLACE, entry.operation());
        assertEquals("petit", entry.buildingId());
        assertEquals(1, entry.buildingVersion());
        assertEquals("sha-petit.schem", entry.schematicSha256());
        assertEquals("Lody", entry.actor());
        assertTrue(entry.ok());
        assertEquals("world_hub", entry.footprint().world());
    }

    /** Le journal survit à la libération : l'historique n'est pas effacé avec le placement. */
    @Test
    void theHistorySurvivesFreeingTheSite() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");
        free(created.id());

        assertTrue(history(created.id()).size() >= 2,
                "la pose ET la libération doivent rester consultables");
    }

    // ================================================================================
    //  CONCURRENCE
    // ================================================================================

    /** Une seconde opération pendant qu'une première est en cours est refusée, pas mise en file. */
    @Test
    void aSecondOperationDuringAFirstOneIsRefused() throws Exception {
        BuildingSite created = site(Facing.NORTH);
        place(created.id(), "petit");

        // On déclenche la réorientation et, depuis le collage, on tente une libération : le jeton
        // d'opération en cours doit la refuser.
        List<BuildingPlacementService.FreeResult> nested = new ArrayList<>();
        gateway.pasteFails = false;
        BuildingPlacementService.RetargetResult result = reorient(created.id(), Facing.EAST);

        assertTrue(result.applied(), result.error());
        // Après l'opération, le jeton est libéré : une libération redevient possible.
        assertTrue(free(created.id()).freed());
        assertTrue(nested.isEmpty());
    }

    private static int indexOfPrefix(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
