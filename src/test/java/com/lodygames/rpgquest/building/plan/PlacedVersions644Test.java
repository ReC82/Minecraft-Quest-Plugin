package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.BuildingFamilyPurge;
import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.BuildingRetirement;
import com.lodygames.rpgquest.building.PlacedVersions;
import com.lodygames.rpgquest.building.SchematicGateway;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import com.lodygames.rpgquest.building.model.BuildingPlacement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #644, exigence du 26/10 — une version <strong>posée</strong> en jeu est immuable.
 *
 * <p>Scénario du propriétaire (l'église) : v1 posée → v2 publiée depuis l'Atelier → la v1 reste
 * retrouvable octet pour octet, sa suppression est refusée, et sa restauration (terrain d'origine)
 * reste possible. Tout se passe sur des dossiers temporaires : aucun monde, aucun serveur, aucun
 * bloc. La passerelle simulée écrit de VRAIS fichiers, pour que les empreintes soient réelles.</p>
 */
class PlacedVersions644Test {

    @TempDir
    Path tempDir;

    private Path buildings;
    private Path schematics;
    private BuildingLibrary library;
    private BuildingGenerator generator;
    private PlacedVersions placedVersions;
    private final List<BuildingPlacement> placed = new ArrayList<>();
    private DiskGateway gateway;

    private static final String CHURCH = """
            id: eglise
            name: "Église"
            kind: decoration
            front: NORTH
            size:
              x: 7
              y: 6
              z: 9
            anchor:
              x: 3
              y: 0
              z: 0
            steps:
              - op: box
                from: [0, 0, 0]
                to: [6, 0, 8]
                block: minecraft:stone_bricks
              - op: walls
                from: [0, 1, 0]
                to: [6, 4, 8]
                block: minecraft:stone_bricks
            """;

    @BeforeEach
    void setUp() throws IOException {
        buildings = tempDir.resolve("buildings");
        schematics = tempDir.resolve("schematics");
        Files.createDirectories(schematics);
        library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.reload();
        gateway = new DiskGateway(schematics);
        placedVersions = new PlacedVersions(library, schematics, () -> List.copyOf(placed));
        generator = new BuildingGenerator(library, new SchematicWorkshop(gateway, Logger.getLogger("test")),
                buildings, Logger.getLogger("test"))
                .archivingTo(schematics, Clock.fixed(Instant.parse("2026-10-26T20:00:00Z"), ZoneOffset.UTC))
                .protectingPlaced(placedVersions);
    }

    /** Pose simulée de la version COURANTE : un fait en base, comme le service de placement l'écrit. */
    private BuildingPlacement placeCurrent(String site, String sha) throws IOException {
        Path backup = schematics.resolve("origine_" + site + "_1.schem");
        Files.writeString(backup, "terrain d'origine de " + site);
        BuildingPlacement placement = new BuildingPlacement(site, "eglise",
                library.find("eglise").orElseThrow().version(), sha, "world_hub", 3, 64, 0, 0,
                0, 64, 0, 6, 69, 8, backup.getFileName().toString(), "test", Instant.EPOCH);
        placed.add(placement);
        return placement;
    }

    private String currentSha() throws Exception {
        return sha(schematics.resolve("eglise.schem"));
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test
    @DisplayName("v1 posée → v2 publiée : la v1 est conservée à l'identique, non supprimable, restaurable")
    void placedV1SurvivesTheV2Publication() throws Exception {
        assertTrue(generator.generate(CHURCH, false).ok());
        String v1 = currentSha();
        BuildingPlacement pose = placeCurrent("buildsite_0016", v1);
        assertEquals(PlacedVersions.Where.CURRENT, placedVersions.locate(pose).where());

        BuildingGenerator.Result v2 = generator.generate(CHURCH, true, 1);
        assertTrue(v2.ok(), v2.message());
        assertEquals(2, library.find("eglise").orElseThrow().version());
        assertFalse(v1.equals(currentSha()), "la v2 a bien un autre contenu");

        // La v1 posée existe toujours, octet pour octet, dans l'historique.
        PlacedVersions.Copy copy = placedVersions.locate(pose);
        assertEquals(PlacedVersions.Where.HISTORY, copy.where(), copy.label());
        Path archived = tempDir.resolve(copy.path());
        assertEquals(v1, sha(archived), "même empreinte que le fichier collé");
        assertTrue(copy.label().startsWith("v1 — conservée dans l'historique"), copy.label());

        // Suppression définitive et retrait : refusés tant que la pose existe.
        BuildingFamilyPurge purge = new BuildingFamilyPurge(library, schematics, () -> List.copyOf(placed),
                id -> List.of(), Logger.getLogger("test"), Clock.systemUTC());
        assertFalse(purge.preview("eglise").purgeable(), purge.preview("eglise").blockers().toString());
        assertFalse(purge.purge("eglise", "eglise", "test").ok());
        BuildingRetirement retirement = new BuildingRetirement(library, schematics, () -> List.copyOf(placed),
                Logger.getLogger("test"), Clock.systemUTC());
        assertEquals(BuildingRetirement.Code.IN_USE, retirement.retire("eglise", "test").code());
        assertEquals(v1, sha(archived), "la v1 conservée n'a pas bougé");

        // Restauration : la pose et sa sauvegarde de terrain sont intactes ; aucun bloc touché.
        assertTrue(pose.restorable());
        assertEquals("terrain d'origine de buildsite_0016",
                Files.readString(schematics.resolve(pose.backupSchematic())));
        assertEquals(0, gateway.pastes, "aucun collage, donc aucun bloc du monde modifié");
    }

    @Test
    @DisplayName("Copie du schematic posé non vérifiée après archivage : le fichier courant n'est pas écrasé")
    void unverifiedArchiveRefusesTheOverwrite() throws Exception {
        // Un générateur qui n'archive QUE la définition (pas de dossier de schematics) : la copie
        // de la version posée ne peut pas être prouvée après l'archivage.
        BuildingGenerator withoutSchematicArchive = new BuildingGenerator(library,
                new SchematicWorkshop(gateway, Logger.getLogger("test")), buildings, Logger.getLogger("test"))
                .protectingPlaced(placedVersions);
        assertTrue(withoutSchematicArchive.generate(CHURCH, false).ok());
        String v1 = currentSha();
        placeCurrent("buildsite_0016", v1);

        BuildingGenerator.Result refused = withoutSchematicArchive.generate(CHURCH, true, 1);
        assertEquals(BuildingGenerator.PLACED_VERSION_PROTECTED, refused.code(), refused.message());
        assertEquals(v1, currentSha(), "le fichier posé est intact");
        assertEquals(1, library.find("eglise").orElseThrow().version());
    }

    @Test
    @DisplayName("Copie archivée d'empreinte différente : PLACED_VERSION_PROTECTED, rien n'est écrit")
    void archiveWithAnotherContentIsDetected() throws Exception {
        assertTrue(generator.generate(CHURCH, false).ok());
        // La pose mémorise une empreinte que SEUL le courant porte… puis le courant est réécrit
        // à la main sans passer par l'Atelier : la version posée n'existe plus nulle part.
        String v1 = currentSha();
        placeCurrent("buildsite_0016", v1);
        Files.writeString(schematics.resolve("eglise.schem"), "modifié à la main");

        BuildingGenerator.Result refused = generator.generate(CHURCH, true, 1);
        assertEquals(BuildingGenerator.PLACED_VERSION_PROTECTED, refused.code(), refused.message());
        assertTrue(refused.message().contains("buildsite_0016"), refused.message());
        assertEquals("modifié à la main", Files.readString(schematics.resolve("eglise.schem")));
        assertFalse(Files.exists(buildings.resolve(".history")), "rien n'a même été archivé");
        assertEquals(PlacedVersions.Where.MISSING,
                placedVersions.locate(placed.get(0)).where());
    }

    @Test
    @DisplayName("Pose sans empreinte (antérieure à #234) : republication refusée, référence incertaine")
    void unknownFingerprintIsConservative() throws Exception {
        assertTrue(generator.generate(CHURCH, false).ok());
        String v1 = currentSha();
        placeCurrent("buildsite_0003", "");

        BuildingGenerator.Result refused = generator.generate(CHURCH, true, 1);
        assertEquals(BuildingGenerator.PLACED_VERSION_PROTECTED, refused.code(), refused.message());
        assertEquals(v1, currentSha());
        assertEquals(PlacedVersions.Where.UNKNOWN, placedVersions.locate(placed.get(0)).where());
        assertTrue(placedVersions.locate(placed.get(0)).label().contains("non vérifiable"));
    }

    @Test
    @DisplayName("Bâtiment non posé : la republication reste libre, sous un autre identifiant aussi")
    void unplacedBuildingsAreNotConstrained() throws Exception {
        assertTrue(generator.generate(CHURCH, false).ok());
        assertTrue(generator.generate(CHURCH, true, 1).ok());
        assertEquals(2, library.find("eglise").orElseThrow().version());

        // Et le contournement proposé au propriétaire : enregistrer sous un nouvel identifiant.
        placeCurrent("buildsite_0016", "");
        assertTrue(generator.generate(CHURCH.replace("id: eglise", "id: eglise_v2"), false).ok());
        assertTrue(library.find("eglise_v2").isPresent());
    }

    @Test
    @DisplayName("v1 posée, v2 posée ailleurs, v3 publiée : chaque pose retrouve sa propre version")
    void eachPlacementFindsItsOwnVersion() throws Exception {
        assertTrue(generator.generate(CHURCH, false).ok());
        BuildingPlacement first = placeCurrent("buildsite_0016", currentSha());
        assertTrue(generator.generate(CHURCH, true, 1).ok());
        BuildingPlacement second = placeCurrent("buildsite_0020", currentSha());
        // Second archivage le même instant simulé : horodatage distinct grâce à la version (v2_…).
        BuildingGenerator.Result v3 = generator.generate(CHURCH, true, 2);
        assertTrue(v3.ok(), v3.message());

        PlacedVersions.Copy a = placedVersions.locate(first);
        PlacedVersions.Copy b = placedVersions.locate(second);
        assertEquals(PlacedVersions.Where.HISTORY, a.where());
        assertEquals(PlacedVersions.Where.HISTORY, b.where());
        assertTrue(a.path().contains("/v1_"), a.path());
        assertTrue(b.path().contains("/v2_"), b.path());
        assertEquals(2, placedVersions.locate("eglise").size());
    }

    /** Écrit de vrais fichiers, au contenu différent à chaque écriture ; ne colle jamais rien. */
    static final class DiskGateway implements SchematicGateway {
        private final Path dir;
        private int writes;
        int pastes;

        DiskGateway(Path dir) {
            this.dir = dir;
        }

        @Override public Map<String, String> rejected(java.util.Collection<String> states) {
            return Map.of();
        }

        @Override public boolean available() {
            return true;
        }

        @Override public String unavailableReason() {
            return "";
        }

        @Override public boolean has(String fileName) {
            return Files.isRegularFile(dir.resolve(fileName));
        }

        @Override public Optional<String> fingerprint(String fileName) {
            try {
                return has(fileName) ? Optional.of(sha(dir.resolve(fileName))) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        @Override public Outcome write(Blueprint blueprint, String fileName) {
            try {
                Files.writeString(dir.resolve(fileName), "schem " + fileName + " écriture " + (++writes)
                        + " blocs " + blueprint.blocks().size());
                return Outcome.success();
            } catch (IOException e) {
                return Outcome.failure(e.getMessage());
            }
        }

        @Override public Optional<Dimensions> inspect(String fileName) {
            return Optional.empty();
        }

        @Override public Outcome capture(BuildingFootprint footprint, String fileName) {
            return Outcome.failure("non utilisé");
        }

        @Override public Outcome paste(PasteOrder order) {
            pastes++;
            return Outcome.failure("non utilisé");
        }

        @Override public Outcome restore(String fileName, BuildingFootprint footprint) {
            return Outcome.failure("non utilisé");
        }
    }
}
