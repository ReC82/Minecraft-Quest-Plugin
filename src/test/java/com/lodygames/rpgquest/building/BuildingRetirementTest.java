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
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #284 — retirer un bâtiment de la bibliothèque : refus nommés, archivage, et surtout
 * <strong>pas de réapparition au redémarrage</strong>. Aucun bâtiment réel n'est touché : tout se
 * passe dans un dossier temporaire.
 */
class BuildingRetirementTest {

    @TempDir
    Path tmp;

    private Path buildings;
    private Path schematics;
    private BuildingLibrary library;
    private final List<BuildingPlacement> placed = new ArrayList<>();
    private BuildingRetirement retirement;

    @BeforeEach
    void setUp() throws Exception {
        buildings = tmp.resolve("buildings");
        schematics = tmp.resolve("schematics");
        Files.createDirectories(schematics);
        library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.start();   // dépose les deux exemples embarqués, comme au vrai démarrage
        Files.writeString(buildings.resolve("bibliotheque_essai.yml"), definition("bibliotheque_essai",
                "Bibliothèque d'essai", "bibliotheque_essai.schem"));
        Files.writeString(schematics.resolve("bibliotheque_essai.schem"), "schem");
        library.reload();
        retirement = new BuildingRetirement(library, schematics, () -> List.copyOf(placed),
                Logger.getLogger("test"), Clock.fixed(Instant.parse("2026-10-12T17:30:00Z"), ZoneOffset.UTC));
    }

    private static String definition(String id, String name, String schematic) {
        return "id: " + id + "\nname: \"" + name + "\"\nschematic: " + schematic
                + "\nsize:\n  x: 9\n  y: 8\n  z: 7\nanchor:\n  x: 4\n  y: 0\n  z: 3\nfront: NORTH\n";
    }

    private static BuildingPlacement placement(String site, String building) {
        return new BuildingPlacement(site, building, 1, "", "world_hub", 0, 64, 0, 0, 0, 64, 0, 8, 71, 6,
                "backup_" + site + ".schem", "owner", Instant.EPOCH);
    }

    @Test
    void anUnusedBuildingIsArchivedAndDoesNotComeBackAfterARestart() throws Exception {
        var outcome = retirement.retire("bibliotheque_essai", "owner");
        assertEquals(BuildingRetirement.Code.RETIRED, outcome.code(), outcome.message());
        assertTrue(library.find("bibliotheque_essai").isEmpty());
        assertFalse(Files.exists(buildings.resolve("bibliotheque_essai.yml")));
        assertFalse(Files.exists(schematics.resolve("bibliotheque_essai.schem")));

        Path archive = buildings.resolve(".retired").resolve("bibliotheque_essai_20261012-173000");
        assertTrue(Files.exists(archive.resolve("bibliotheque_essai.yml")), "restaurable");
        assertTrue(Files.exists(archive.resolve("bibliotheque_essai.schem")), "restaurable");
        assertTrue(Files.readString(archive.resolve("retired.yml")).contains("retired_by: \"owner\""));

        // Le redémarrage : nouvelle bibliothèque sur le même dossier, avec dépôt des exemples.
        BuildingLibrary restarted = new BuildingLibrary(buildings, Logger.getLogger("test"));
        restarted.start();
        assertTrue(restarted.find("bibliotheque_essai").isEmpty(), "il ne réapparaît pas");
        assertTrue(restarted.find("test_hut_01").isPresent(), "les autres sont intacts");
    }

    @Test
    void aPlacedBuildingIsRefusedAndNamesTheSites() {
        placed.add(placement("buildsite_0012", "bibliotheque_essai"));
        var outcome = retirement.retire("bibliotheque_essai", "owner");
        assertEquals(BuildingRetirement.Code.IN_USE, outcome.code());
        assertTrue(outcome.message().contains("buildsite_0012"), outcome.message());
        assertTrue(library.find("bibliotheque_essai").isPresent(), "rien n'a été modifié");
        assertTrue(Files.exists(buildings.resolve("bibliotheque_essai.yml")));
    }

    @Test
    void aBundledExampleIsRefusedBecauseItWouldBeRecreated() {
        var outcome = retirement.retire("test_hut_01", "owner");
        assertEquals(BuildingRetirement.Code.BUNDLED, outcome.code());
        assertTrue(outcome.message().contains("recréé au prochain démarrage"), outcome.message());
        assertTrue(library.find("test_hut_01").isPresent());
    }

    @Test
    void aSchematicSharedWithAnotherBuildingIsKept() throws Exception {
        Files.writeString(buildings.resolve("jumelle.yml"), definition("jumelle", "Jumelle",
                "bibliotheque_essai.schem"));
        library.reload();
        var outcome = retirement.retire("bibliotheque_essai", "owner");
        assertEquals(BuildingRetirement.Code.RETIRED, outcome.code(), outcome.message());
        assertTrue(Files.exists(schematics.resolve("bibliotheque_essai.schem")), "toujours utilisé");
        assertTrue(library.find("jumelle").isPresent());
    }

    @Test
    void anUnknownBuildingChangesNothing() throws Exception {
        long before;
        try (Stream<Path> files = Files.list(buildings)) {
            before = files.count();
        }
        assertEquals(BuildingRetirement.Code.NOT_FOUND, retirement.retire("inconnu", "owner").code());
        try (Stream<Path> files = Files.list(buildings)) {
            assertEquals(before, files.count());
        }
    }
}
