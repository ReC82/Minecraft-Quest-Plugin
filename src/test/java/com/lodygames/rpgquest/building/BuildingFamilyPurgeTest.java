package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingPlacement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #644 — supprimer un bâtiment avec toutes ses versions. Tout se passe dans un dossier
 * temporaire : aucun bâtiment réel n'est touché.
 */
class BuildingFamilyPurgeTest {

    private static final String ID = "boulangerie_essai";

    @TempDir
    Path tmp;

    private Path buildings;
    private Path schematics;
    private BuildingLibrary library;
    private final List<BuildingPlacement> placed = new ArrayList<>();
    private final Map<String, List<String>> portals = new HashMap<>();
    private BuildingFamilyPurge purge;

    @BeforeEach
    void setUp() throws Exception {
        buildings = tmp.resolve("buildings");
        schematics = tmp.resolve("schematics");
        Files.createDirectories(schematics);
        library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.start();
        Files.writeString(buildings.resolve(ID + ".yml"), definition(ID, "Boulangerie d'essai", ID + ".schem", 3));
        Files.writeString(schematics.resolve(ID + ".schem"), "schem v3");
        // Deux versions remplacées par la régénération (#94), une version archivée (#284).
        for (String v : List.of("v1_20261001-100000", "v2_20261002-100000")) {
            Path dir = Files.createDirectories(buildings.resolve(".history").resolve(ID).resolve(v));
            Files.writeString(dir.resolve(ID + ".yml"), "old");
            Files.writeString(dir.resolve(ID + ".schem"), "old");
        }
        Path retired = Files.createDirectories(buildings.resolve(".retired").resolve(ID + "_20261003-100000"));
        Files.writeString(retired.resolve("retired.yml"), "id: " + ID + "\nversion: 2\n");
        // Sauvegardes de terrain : jamais dans la famille.
        Files.writeString(schematics.resolve("backup_place_1.schem"), "terrain");
        library.reload();
        purge = new BuildingFamilyPurge(library, schematics, () -> List.copyOf(placed),
                id -> portals.getOrDefault(id, List.of()), Logger.getLogger("test"),
                Clock.fixed(Instant.parse("2026-10-22T00:30:00Z"), ZoneOffset.UTC));
    }

    private static String definition(String id, String name, String schematic, int version) {
        return "id: " + id + "\nname: \"" + name + "\"\nschematic: " + schematic + "\nversion: " + version
                + "\nsize:\n  x: 9\n  y: 8\n  z: 7\nanchor:\n  x: 4\n  y: 0\n  z: 3\nfront: NORTH\n";
    }

    private static BuildingPlacement placement(String site, String building) {
        return new BuildingPlacement(site, building, 1, "", "world_hub", 0, 64, 0, 0, 0, 64, 0, 8, 71, 6,
                "backup_" + site + ".schem", "owner", Instant.EPOCH);
    }

    @Test
    void thePreviewListsEveryVersionAndChangesNothing() throws Exception {
        var preview = purge.preview(ID);
        assertTrue(preview.purgeable(), preview.blockers().toString());
        assertEquals(4, preview.versions().size(), preview.versions().toString());
        assertEquals(BuildingFamilyPurge.Kind.CURRENT, preview.versions().get(0).kind());
        assertEquals(List.of("buildings/" + ID + ".yml", "schematics/" + ID + ".schem"),
                preview.versions().get(0).paths());
        assertEquals(2, preview.versions().get(1).version());
        assertEquals(5, preview.fileCount());
        // Lecture seule : tout est encore là.
        assertTrue(library.find(ID).isPresent());
        assertTrue(Files.exists(buildings.resolve(".history").resolve(ID).resolve("v1_20261001-100000")));
        assertFalse(Files.exists(buildings.resolve(".purging")));
    }

    @Test
    void anUnusedBuildingIsErasedWithAllItsVersionsAndDoesNotComeBack() throws Exception {
        var outcome = purge.purge(ID, ID, "owner");
        assertEquals(BuildingFamilyPurge.Code.PURGED, outcome.code(), outcome.message());
        assertTrue(library.find(ID).isEmpty());
        assertFalse(Files.exists(buildings.resolve(ID + ".yml")));
        assertFalse(Files.exists(schematics.resolve(ID + ".schem")));
        assertFalse(Files.exists(buildings.resolve(".history").resolve(ID)));
        assertFalse(Files.exists(buildings.resolve(".retired").resolve(ID + "_20261003-100000")));
        assertFalse(Files.exists(buildings.resolve(".purging")), "aucun reste en attente");
        assertTrue(Files.exists(schematics.resolve("backup_place_1.schem")), "sauvegarde de terrain intacte");
        assertTrue(Files.readString(buildings.resolve(".audit").resolve("purges.log"))
                .contains("purge id=" + ID + " by=owner versions=4 erased=true"));

        BuildingLibrary restarted = new BuildingLibrary(buildings, Logger.getLogger("test"));
        restarted.start();
        assertTrue(restarted.find(ID).isEmpty(), "il ne réapparaît pas au redémarrage");
        assertTrue(restarted.find("test_hut_01").isPresent(), "les autres sont intacts");
        assertEquals(BuildingFamilyPurge.Code.NOT_FOUND, purge.purge(ID, ID, "owner").code(), "double clic sans effet");
    }

    @Test
    void aPlacedBuildingIsRefusedAndNothingMoves() {
        placed.add(placement("place_du_marche", ID));
        var outcome = purge.purge(ID, ID, "owner");
        assertEquals(BuildingFamilyPurge.Code.BLOCKED, outcome.code());
        assertTrue(outcome.message().contains("place_du_marche"), outcome.message());
        assertEverythingIsStillThere();
    }

    @Test
    void aBuildingLinkedToAPortalIsRefused() {
        portals.put(ID, List.of("« portail_nord » (brouillon)"));
        var outcome = purge.purge(ID, ID, "owner");
        assertEquals(BuildingFamilyPurge.Code.BLOCKED, outcome.code());
        assertTrue(outcome.message().contains("portail_nord"), outcome.message());
        assertEverythingIsStillThere();
    }

    @Test
    void aWrongConfirmationChangesNothing() {
        assertEquals(BuildingFamilyPurge.Code.CONFIRMATION_MISMATCH, purge.purge(ID, "boulangerie", "owner").code());
        assertEquals(BuildingFamilyPurge.Code.CONFIRMATION_MISMATCH, purge.purge(ID, null, "owner").code());
        assertEverythingIsStillThere();
    }

    @Test
    void aBundledExampleIsRefused() {
        assertEquals(BuildingFamilyPurge.Code.BUNDLED, purge.purge("test_hut_01", "test_hut_01", "owner").code());
        assertTrue(library.find("test_hut_01").isPresent());
    }

    @Test
    void aSchematicSharedWithAnotherBuildingIsKept() throws Exception {
        Files.writeString(buildings.resolve("boulangerie_bis.yml"),
                definition("boulangerie_bis", "Boulangerie bis", ID + ".schem", 1));
        library.reload();
        var outcome = purge.purge(ID, ID, "owner");
        assertEquals(BuildingFamilyPurge.Code.PURGED, outcome.code(), outcome.message());
        assertTrue(Files.exists(schematics.resolve(ID + ".schem")), "le second bâtiment garde son schematic");
        assertTrue(library.find("boulangerie_bis").isPresent());
    }

    @Test
    void anotherFileDeclaringTheSameIdBlocksThePurge() throws Exception {
        Files.writeString(buildings.resolve("copie.yml"), definition(ID, "Copie", ID + ".schem", 1));
        var outcome = purge.purge(ID, ID, "owner");
        assertEquals(BuildingFamilyPurge.Code.BLOCKED, outcome.code());
        assertTrue(outcome.message().contains("copie.yml"), outcome.message());
    }

    @Test
    void homonymPrefixesAndUnknownEntriesAreNotPartOfTheFamily() throws Exception {
        Path other = Files.createDirectories(buildings.resolve(".retired").resolve(ID + "_bis_20261003-100000"));
        Files.writeString(other.resolve("retired.yml"), "id: " + ID + "_bis\n");
        Path unknown = Files.createDirectories(buildings.resolve(".history").resolve(ID).resolve("notes"));
        var outcome = purge.purge(ID, ID, "owner");
        assertEquals(BuildingFamilyPurge.Code.PURGED, outcome.code(), outcome.message());
        assertTrue(Files.exists(other), "un bâtiment au nom proche n'est pas touché");
        assertTrue(Files.exists(unknown), "une entrée non reconnue est conservée");
    }

    @Test
    void anArchivedOnlyFamilyCanStillBeErased() throws Exception {
        Files.delete(buildings.resolve(ID + ".yml"));
        library.reload();
        var preview = purge.preview(ID);
        assertFalse(preview.inLibrary());
        assertEquals(3, preview.versions().size());
        assertEquals(BuildingFamilyPurge.Code.PURGED, purge.purge(ID, ID, "owner").code());
        assertTrue(Files.exists(schematics.resolve(ID + ".schem")), "hors famille sans définition courante");
    }

    @Test
    void anUnfinishedErasureIsPartOfTheFamilyAndIsFinishedNextTime() throws Exception {
        Path pending = Files.createDirectories(buildings.resolve(".purging").resolve(ID + "_20261021-100000"));
        Files.writeString(pending.resolve("0_reste.yml"), "reste");
        var preview = purge.preview(ID);
        assertTrue(preview.versions().stream().anyMatch(v -> v.kind() == BuildingFamilyPurge.Kind.PENDING));
        assertEquals(BuildingFamilyPurge.Code.PURGED, purge.purge(ID, ID, "owner").code());
        assertFalse(Files.exists(buildings.resolve(".purging")));
    }

    @Test
    void aMoveFailingHalfwayPutsEverythingBack() throws Exception {
        Path history = buildings.resolve(".history").resolve(ID);
        // Un dossier non modifiable : la version courante est déjà déplacée quand l'échec survient.
        assertTrue(history.toFile().setWritable(false));
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(history), "exécuté en root");
        try {
            var outcome = purge.purge(ID, ID, "owner");
            assertEquals(BuildingFamilyPurge.Code.FAILED, outcome.code(), outcome.message());
            assertTrue(outcome.message().contains("tout a été remis en place"), outcome.message());
        } finally {
            history.toFile().setWritable(true);
        }
        assertEverythingIsStillThere();
    }

    private void assertEverythingIsStillThere() {
        assertTrue(library.find(ID).isPresent());
        assertTrue(Files.exists(buildings.resolve(ID + ".yml")));
        assertTrue(Files.exists(schematics.resolve(ID + ".schem")));
        assertTrue(Files.exists(buildings.resolve(".history").resolve(ID).resolve("v2_20261002-100000")));
        assertTrue(Files.exists(buildings.resolve(".retired").resolve(ID + "_20261003-100000")));
        assertFalse(Files.exists(buildings.resolve(".purging")));
        assertFalse(Files.exists(buildings.resolve(".audit")));
    }
}
