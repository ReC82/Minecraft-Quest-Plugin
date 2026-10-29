package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import com.lodygames.rpgquest.building.model.BuildingSite;
import com.lodygames.rpgquest.building.model.BuildingSiteAnchor;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.database.BuildingPlacementRepository;
import com.lodygames.rpgquest.database.BuildingSiteRepository;
import com.lodygames.rpgquest.database.DatabaseManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #246 — capturer une construction posée comme nouveau bâtiment, sur une vraie base SQLite.
 *
 * <p>Fixture isolée : une hutte de test posée sur un emplacement de test. Aucun bâtiment réel du
 * serveur n'est concerné. Le moteur et le monde sont simulés, ce qui permet de vérifier les
 * garanties du ticket — rien d'écrit pendant l'aperçu, rien d'écrasé, l'original et le placement
 * intacts, les refus — sans WorldEdit.</p>
 */
class BuildingCaptureServiceTest {

    private static final long TIMEOUT = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private BuildingSiteService sites;
    private BuildingLibrary library;
    private FakeGateway gateway;
    private FakeWorld world;
    private BuildingPlacementService placements;
    private BuildingCaptureService capture;
    private Path buildings;

    /** Moteur simulé : garde les fichiers écrits et ce qui a été vidé. */
    private static final class FakeGateway implements SchematicGateway {
        final List<String> calls = new ArrayList<>();
        final Set<String> files = new LinkedHashSet<>();
        final Map<String, Dimensions> dimensions = new HashMap<>();
        boolean libraryCaptureFails;
        BuildingFootprint lastLibraryCapture;
        List<int[]> lastEmptied = List.of();

        @Override public boolean available() {
            return true;
        }

        @Override public String unavailableReason() {
            return "";
        }

        @Override public boolean has(String fileName) {
            return files.contains(fileName);
        }

        @Override public Optional<String> fingerprint(String fileName) {
            return files.contains(fileName) ? Optional.of("sha-" + fileName) : Optional.empty();
        }

        @Override public Outcome write(Blueprint blueprint, String fileName) {
            files.add(fileName);
            return Outcome.success();
        }

        @Override public Optional<Dimensions> inspect(String fileName) {
            return files.contains(fileName)
                    ? Optional.ofNullable(dimensions.getOrDefault(fileName, new Dimensions(7, 6, 5)))
                    : Optional.empty();
        }

        @Override public Outcome capture(BuildingFootprint footprint, String fileName) {
            calls.add("capture:" + fileName);
            files.add(fileName);
            return Outcome.success();
        }

        @Override public Outcome captureForLibrary(BuildingFootprint footprint, String fileName,
                                                   Collection<int[]> emptied) {
            calls.add("library:" + fileName);
            if (libraryCaptureFails) {
                return Outcome.failure("disque plein");
            }
            lastLibraryCapture = footprint;
            lastEmptied = List.copyOf(emptied);
            files.add(fileName);
            dimensions.put(fileName, new Dimensions(footprint.sizeX(), footprint.sizeY(),
                    footprint.sizeZ()));
            return Outcome.success();
        }

        @Override public Outcome discard(String fileName) {
            calls.add("discard:" + fileName);
            files.remove(fileName);
            return Outcome.success();
        }

        @Override public Outcome paste(PasteOrder order) {
            calls.add("paste:" + order.schematic());
            return Outcome.success();
        }

        @Override public Outcome restore(String fileName, BuildingFootprint footprint) {
            calls.add("restore:" + fileName);
            return Outcome.success();
        }
    }

    /** Monde simulé : le relevé est fixé par le test. */
    private static final class FakeWorld implements WorldProbe {
        CaptureSurvey survey = new CaptureSurvey(true, "", 120L,
                List.of(new CaptureSurvey.ContainerSpot(101, 71, 201, "chest",
                                CaptureSurvey.ContentFate.HIDDEN_DROPPED),
                        new CaptureSurvey.ContainerSpot(102, 71, 201, "barrel",
                                CaptureSurvey.ContentFate.EMPTY)),
                Map.of("item_frame", 2, "painting", 1), List.of("oak_planks", "cobblestone"));
        final List<BuildingFootprint> surveyed = new ArrayList<>();

        @Override public boolean loaded(String world) {
            return "world_hub".equals(world);
        }

        @Override public OptionalInt minHeight(String world) {
            return OptionalInt.of(-64);
        }

        @Override public OptionalInt maxHeight(String world) {
            return OptionalInt.of(320);
        }

        @Override public OptionalLong countNonAir(BuildingFootprint footprint) {
            return OptionalLong.of(0);
        }

        @Override public Optional<CaptureSurvey> survey(BuildingFootprint footprint) {
            surveyed.add(footprint);
            return Optional.ofNullable(survey);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT, TimeUnit.SECONDS);
        sites = new BuildingSiteService(new BuildingSiteRepository(database));
        sites.load().get(TIMEOUT, TimeUnit.SECONDS);
        buildings = tempDir.resolve("buildings");
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
                version: 1
                """);
        library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.reload();
        gateway = new FakeGateway();
        gateway.files.add("test_hut_01.schem");
        world = new FakeWorld();
        placements = new BuildingPlacementService(new BuildingPlacementRepository(database), sites,
                library, gateway, world);
        placements.load().get(TIMEOUT, TimeUnit.SECONDS);
        capture = new BuildingCaptureService(placements, sites, library, gateway, world,
                Clock.systemUTC(), null, Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private BuildingSite site(int x, Facing facing) throws Exception {
        return sites.create("world_hub", new BuildingSiteAnchor(x, 70, 200), facing, "Test capture",
                "test").get(TIMEOUT, TimeUnit.SECONDS).site();
    }

    /** Une hutte de test posée face à l'est : rotation de 90°, emprise 5 × 6 × 7. */
    private BuildingPlacement placedHut(int x) throws Exception {
        BuildingSite created = site(x, Facing.EAST);
        var result = placements.place(created.id(), "test_hut_01", "test").get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(result.placed(), result.error());
        return result.placement();
    }

    private static BuildingCaptureService.Request request(String siteId, String newId) {
        return new BuildingCaptureService.Request(siteId, BuildingCaptureService.Margins.NONE, newId,
                "Hutte décorée", "Variante retouchée en jeu.", "maisons");
    }

    // ---- Aperçu ------------------------------------------------------------------------------------

    @Test
    void previewProposesThePersistedFootprintAndWritesNothing() throws Exception {
        BuildingPlacement placed = placedHut(100);
        List<String> before = List.copyOf(gateway.calls);

        BuildingCaptureService.Preview preview = capture.preview(request(placed.siteId(), "hutte_deco"));

        assertTrue(preview.capturable(), preview.refusals().toString());
        assertEquals(placed.footprint(), preview.region(), "bornes par défaut = emprise posée exacte");
        assertEquals(before, gateway.calls, "l'aperçu ne sollicite pas le moteur");
        assertFalse(Files.exists(buildings.resolve("hutte_deco.yml")));
        assertTrue(library.find("hutte_deco").isEmpty());
        // Hutte NORTH tournée de 90° : la façade posée regarde l'est.
        assertEquals(Facing.EAST, preview.front());
        assertArrayEquals(new int[] {placed.anchorX() - placed.minX(), placed.anchorY() - placed.minY(),
                placed.anchorZ() - placed.minZ()}, preview.localAnchor());
        assertFalse(preview.token().isEmpty());
    }

    @Test
    void previewStatesTheContainerAndEntityPolicyBeforeConfirmation() throws Exception {
        BuildingPlacement placed = placedHut(100);

        BuildingCaptureService.Preview preview = capture.preview(request(placed.siteId(), "hutte_deco"));

        // Issue #903 : un coffre plein et un tonneau vide ne sont pas une alarme — rien ne change à
        // l'œil. Ils sont décrits (CaptureContent.groups), les entités en note discrète.
        assertTrue(preview.warnings().isEmpty(), preview.warnings().toString());
        CaptureSurvey survey = preview.survey().orElseThrow();
        assertEquals(List.of(CaptureSurvey.ContentFate.HIDDEN_DROPPED, CaptureSurvey.ContentFate.EMPTY),
                CaptureContent.groups(survey).stream().map(CaptureContent.Group::fate).toList());
        assertTrue(CaptureContent.entityNote(survey).startsWith("2 × cadre, 1 × tableau."),
                CaptureContent.entityNote(survey));
        assertEquals("", CaptureContent.acknowledgement(survey), "aucune perte visible à acquitter");
    }

    /** La grande librairie (#903) : livres visibles gardés, objets d'étagère perdus. */
    private void libraryLikeSurvey() {
        world.survey = new CaptureSurvey(true, "", 898L,
                List.of(new CaptureSurvey.ContainerSpot(101, 71, 201, "chiseled_bookshelf",
                                CaptureSurvey.ContentFate.VISUAL_KEPT),
                        new CaptureSurvey.ContainerSpot(102, 71, 201, "lectern",
                                CaptureSurvey.ContentFate.VISUAL_KEPT),
                        new CaptureSurvey.ContainerSpot(103, 71, 201, "spruce_shelf",
                                CaptureSurvey.ContentFate.VISUAL_LOST)),
                Map.of("item_frame", 4, "turtle", 1), List.of("spruce_planks"));
    }

    @Test
    void displayedItemsThatWouldDisappearAreAWarningAndNeedAnExplicitAcknowledgement() throws Exception {
        BuildingPlacement placed = placedHut(100);
        libraryLikeSurvey();
        var request = request(placed.siteId(), "librairie_v2");

        BuildingCaptureService.Preview preview = capture.preview(request);

        assertTrue(preview.capturable(), preview.refusals().toString());
        assertTrue(preview.warnings().stream().anyMatch(w -> w.startsWith("1 bloc(s) perdront les objets")
                && w.contains("1 × étagère en sapin")), preview.warnings().toString());
        BuildingCaptureService.Result refused = capture.capture(request, "admin", preview.token());
        assertFalse(refused.captured());
        assertTrue(refused.error().contains("cochez l'acquittement"), refused.error());
        assertFalse(gateway.calls.contains("library:librairie_v2.schem"), "rien n'a été écrit sans accord");
        assertTrue(library.find("librairie_v2").isEmpty());

        var accepted = new BuildingCaptureService.Request(placed.siteId(), BuildingCaptureService.Margins.NONE,
                "librairie_v2", "Hutte décorée", "Variante retouchée en jeu.", "maisons", true);
        BuildingCaptureService.Result result = capture.capture(accepted, "admin", preview.token());

        assertTrue(result.captured(), result.error());
        assertTrue(result.effects().stream().anyMatch(e -> e.contains("2 bloc(s) gardent leurs livres visibles")),
                result.effects().toString());
        assertTrue(result.effects().stream().anyMatch(e -> e.startsWith("Entités non incluses : 4 × cadre, 1 × tortue")),
                result.effects().toString());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(buildings.resolve("librairie_v2.yml").toFile());
        assertEquals(2, yaml.getInt("provenance.books-kept-as-decor"));
        assertEquals(1, yaml.getInt("provenance.displayed-items-lost"));
        assertEquals("4 × cadre, 1 × tortue", yaml.getString("provenance.entities-omitted"));
    }

    @Test
    void booksKeptAsDecorNeedNoAcknowledgement() throws Exception {
        BuildingPlacement placed = placedHut(100);
        world.survey = new CaptureSurvey(true, "", 50L,
                List.of(new CaptureSurvey.ContainerSpot(101, 71, 201, "chiseled_bookshelf",
                        CaptureSurvey.ContentFate.VISUAL_KEPT)), Map.of(), List.of("oak_planks"));
        var request = request(placed.siteId(), "librairie_decor");

        var preview = capture.preview(request);
        assertTrue(preview.warnings().isEmpty(), preview.warnings().toString());
        assertTrue(capture.capture(request, "admin", preview.token()).captured());
    }

    // ---- Capture -----------------------------------------------------------------------------------

    @Test
    void captureAddsANewBuildingAndLeavesTheOriginalAndThePlacementUntouched() throws Exception {
        BuildingPlacement placed = placedHut(100);
        String originalYaml = Files.readString(buildings.resolve("test_hut_01.yml"));
        var request = request(placed.siteId(), "hutte_deco");
        String token = capture.preview(request).token();

        BuildingCaptureService.Result result = capture.capture(request, "admin", token);

        assertTrue(result.captured(), result.error());
        BuildingDefinition variant = library.find("hutte_deco").orElseThrow();
        assertEquals("Hutte décorée", variant.name());
        assertEquals("hutte_deco.schem", variant.schematic());
        assertEquals(Facing.EAST, variant.front());
        assertEquals(placed.footprint().sizeX(), variant.sizeX());
        assertEquals(placed.footprint().sizeZ(), variant.sizeZ());
        assertEquals(1, variant.version());
        assertEquals(placed.footprint(), gateway.lastLibraryCapture, "les blocs RÉELS de l'emprise posée");
        assertEquals(2, gateway.lastEmptied.size(), "chaque conteneur est capturé vide");
        assertFalse(gateway.calls.stream().anyMatch(c -> c.startsWith("paste:") && gateway.calls.indexOf(c)
                > gateway.calls.indexOf("library:hutte_deco.schem")), "aucun collage pendant la capture");

        assertEquals(originalYaml, Files.readString(buildings.resolve("test_hut_01.yml")), "original intact");
        BuildingPlacement still = placements.at(placed.siteId()).orElseThrow();
        assertEquals("test_hut_01", still.buildingId(), "le placement reste référencé à l'original");
        assertEquals(placed.buildingVersion(), still.buildingVersion());

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(buildings.resolve("hutte_deco.yml").toFile());
        assertEquals("capture", yaml.getString("provenance.kind"));
        assertEquals(placed.siteId(), yaml.getString("provenance.site"));
        assertEquals("test_hut_01", yaml.getString("provenance.building"));
        assertEquals(90, yaml.getInt("provenance.rotation"));
        assertEquals("maisons", yaml.getString("category"));
        assertEquals(2, yaml.getInt("provenance.containers-emptied"));
    }

    @Test
    void theVariantCanBePlacedOnAnotherSiteThroughTheExistingFlow() throws Exception {
        BuildingPlacement placed = placedHut(100);
        var request = request(placed.siteId(), "hutte_deco");
        assertTrue(capture.capture(request, "admin", capture.preview(request).token()).captured());
        BuildingSite other = site(300, Facing.EAST);

        BuildingPlacementService.Preview preview = placements.preview(other.id(), "hutte_deco");

        assertTrue(preview.placeable(), preview.refusals().toString());
        assertEquals(0, preview.rotationDegrees(), "même orientation que la source : aucune rotation");
        assertEquals(placed.footprint().sizeX(), preview.footprint().sizeX());
        assertEquals(placed.footprint().sizeZ(), preview.footprint().sizeZ());
        // L'ancre tombe au même endroit relatif que dans la construction capturée.
        assertEquals(placed.anchorX() - placed.minX(), 300 - preview.footprint().minX());
    }

    @Test
    void anExistingIdOrFileIsNeverOverwritten() throws Exception {
        BuildingPlacement placed = placedHut(100);

        var taken = capture.preview(request(placed.siteId(), "test_hut_01"));
        assertFalse(taken.capturable());
        assertTrue(taken.firstRefusal().contains("déjà pris"), taken.firstRefusal());

        gateway.files.add("orphan.schem");
        var orphan = capture.preview(request(placed.siteId(), "orphan"));
        assertFalse(orphan.capturable());
        assertTrue(orphan.refusals().stream().anyMatch(r -> r.contains("schematics/orphan.schem")));
    }

    @Test
    void aStaleTokenOrAMissingTokenCapturesNothing() throws Exception {
        BuildingPlacement placed = placedHut(100);
        var request = request(placed.siteId(), "hutte_deco");

        var stale = capture.capture(request, "admin", "0123456789abcdef0123456789abcdef");
        var missing = capture.capture(request, "admin", null);

        assertFalse(stale.captured());
        assertTrue(stale.error().contains("plus à jour"), stale.error());
        assertFalse(missing.captured());
        assertFalse(gateway.calls.contains("library:hutte_deco.schem"));
        assertTrue(library.find("hutte_deco").isEmpty());
    }

    @Test
    void aSecondCaptureWithTheSameIdIsRefused() throws Exception {
        BuildingPlacement placed = placedHut(100);
        var request = request(placed.siteId(), "hutte_deco");
        String token = capture.preview(request).token();
        assertTrue(capture.capture(request, "admin", token).captured());

        var again = capture.capture(request, "admin", token);

        assertFalse(again.captured(), "double envoi : le second est refusé");
        assertTrue(again.error().contains("déjà pris"), again.error());
    }

    @Test
    void aCaptureOverlappingANeighbourBuildingIsRefused() throws Exception {
        BuildingPlacement placed = placedHut(100);
        BuildingPlacement neighbour = placedHut(100 + placed.footprint().sizeX() + 2);
        int gap = neighbour.minX() - placed.maxX();
        var widened = new BuildingCaptureService.Request(placed.siteId(),
                new BuildingCaptureService.Margins(0, gap, 0, 0, 0, 0), "hutte_large", "Large", "", "");

        var preview = capture.preview(widened);

        assertFalse(preview.capturable());
        assertTrue(preview.refusals().stream().anyMatch(r -> r.contains(neighbour.siteId())),
                preview.refusals().toString());
    }

    @Test
    void marginsAreBoundedAndMayNotExcludeTheAnchor() throws Exception {
        BuildingPlacement placed = placedHut(100);

        var tooWide = capture.preview(new BuildingCaptureService.Request(placed.siteId(),
                new BuildingCaptureService.Margins(17, 0, 0, 0, 0, 0), "a_b", "A", "", ""));
        var noAnchor = capture.preview(new BuildingCaptureService.Request(placed.siteId(),
                new BuildingCaptureService.Margins(0, 0, -(placed.anchorY() - placed.minY()) - 1, 0, 0, 0),
                "a_b", "A", "", ""));

        assertFalse(tooWide.capturable());
        assertTrue(tooWide.firstRefusal().contains("hors limites"), tooWide.firstRefusal());
        assertFalse(noAnchor.capturable());
        assertTrue(noAnchor.refusals().stream().anyMatch(r -> r.contains("ancre")), noAnchor.refusals().toString());
    }

    @Test
    void anUnloadedChunkRefusesTheCaptureInsteadOfCopyingVoid() throws Exception {
        BuildingPlacement placed = placedHut(100);
        world.survey = CaptureSurvey.incomplete("Le chunk 6/12 n'est pas chargé.");

        var preview = capture.preview(request(placed.siteId(), "hutte_deco"));

        assertFalse(preview.capturable());
        assertTrue(preview.refusals().contains("Le chunk 6/12 n'est pas chargé."));
    }

    @Test
    void anEngineFailureLeavesTheLibraryUnchanged() throws Exception {
        BuildingPlacement placed = placedHut(100);
        gateway.libraryCaptureFails = true;
        var request = request(placed.siteId(), "hutte_deco");

        var result = capture.capture(request, "admin", capture.preview(request).token());

        assertFalse(result.captured());
        assertTrue(result.error().contains("disque plein"));
        assertTrue(library.find("hutte_deco").isEmpty());
        assertFalse(Files.exists(buildings.resolve("hutte_deco.yml")));
        assertNull(result.definition());
    }

    @Test
    void anEmptySiteHasNothingToCapture() throws Exception {
        BuildingSite empty = site(500, Facing.NORTH);

        var preview = capture.preview(request(empty.id(), "rien"));

        assertFalse(preview.capturable());
        assertTrue(preview.firstRefusal().contains("rien à capturer"), preview.firstRefusal());
    }
}
