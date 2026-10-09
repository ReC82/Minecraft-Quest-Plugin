package com.lodygames.rpgquest.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.Facing;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #213, lot « placement » — la bibliothèque de bâtiments relue depuis des fichiers.
 *
 * <p>Un fichier de contenu peut être faux : écrit à la main, recopié, modifié. Ce qui est vérifié
 * ici, c'est qu'un fichier invalide est <strong>nommé et ignoré</strong> sans empêcher les autres de
 * charger — le plugin doit démarrer même avec un bâtiment mal écrit.</p>
 */
class BuildingLibraryTest {

    @TempDir
    Path tempDir;

    private Path directory;
    private BuildingLibrary library;

    @BeforeEach
    void setUp() throws Exception {
        directory = tempDir.resolve("buildings");
        Files.createDirectories(directory);
        library = new BuildingLibrary(directory, Logger.getLogger("test"));
    }

    private void write(String fileName, String body) throws Exception {
        Files.writeString(directory.resolve(fileName), body);
    }

    private static String hutYaml() {
        return """
                id: test_hut_01
                name: "Hutte de test"
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
                """;
    }

    // ---- Le fichier embarqué -------------------------------------------------------------------

    /**
     * Le fichier embarqué décrit <strong>exactement</strong> le plan que le code produit.
     *
     * <p>C'est le garde-fou le plus utile du lot. La définition et le schematic viennent de deux
     * endroits différents — un YAML relu au démarrage, et {@link TestHutBlueprint} qui écrit le
     * fichier. S'ils divergent, le panel annonce une emprise fausse, la sauvegarde ne couvre pas
     * tout ce qui sera écrasé, et la faute ne se découvre qu'après le collage.</p>
     */
    @Test
    void leFichierEmbarqueDecritExactementLePlan() throws Exception {
        try (InputStream in = BuildingLibrary.class
                .getResourceAsStream("/buildings/test_hut_01.yml")) {
            assertNotNull(in, "la ressource /buildings/test_hut_01.yml doit être embarquée");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> problems = new ArrayList<>();
            Optional<BuildingDefinition> fromFile = BuildingDefinitionYaml.read(yaml, problems);

            assertTrue(fromFile.isPresent(), problems.toString());
            BuildingDefinition fromCode = TestHutBlueprint.definition();
            BuildingDefinition file = fromFile.get();

            assertEquals(fromCode.id(), file.id());
            assertEquals(fromCode.schematic(), file.schematic());
            assertEquals(fromCode.sizeX(), file.sizeX(), "largeur");
            assertEquals(fromCode.sizeY(), file.sizeY(), "hauteur");
            assertEquals(fromCode.sizeZ(), file.sizeZ(), "profondeur");
            assertEquals(fromCode.anchorX(), file.anchorX(), "ancre X");
            assertEquals(fromCode.anchorY(), file.anchorY(), "ancre Y");
            assertEquals(fromCode.anchorZ(), file.anchorZ(), "ancre Z");
            assertEquals(fromCode.front(), file.front(), "façade de référence");
            assertEquals(fromCode.materials(), file.materials(), "palette annoncée");
        }
    }

    /**
     * Le même garde-fou, pour la tour de garde (issue #234).
     *
     * <p>Il compte plus encore que pour la hutte : la tour mesure 14 blocs de haut, donc une
     * divergence d'un seul bloc sur la hauteur annoncée ferait passer les limites verticales du
     * monde pour franchies alors qu'elles ne le sont pas — ou l'inverse.</p>
     */
    @Test
    void leFichierEmbarqueDeLaTourDecritExactementLePlan() throws Exception {
        try (InputStream in = BuildingLibrary.class
                .getResourceAsStream("/buildings/test_watchtower_01.yml")) {
            assertNotNull(in, "la ressource /buildings/test_watchtower_01.yml doit être embarquée");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> problems = new ArrayList<>();
            Optional<BuildingDefinition> fromFile = BuildingDefinitionYaml.read(yaml, problems);

            assertTrue(fromFile.isPresent(), problems.toString());
            BuildingDefinition fromCode = TestWatchtowerBlueprint.definition();
            BuildingDefinition file = fromFile.get();

            assertEquals(fromCode.id(), file.id());
            assertEquals(fromCode.schematic(), file.schematic());
            assertEquals(fromCode.sizeX(), file.sizeX(), "largeur");
            assertEquals(fromCode.sizeY(), file.sizeY(), "hauteur");
            assertEquals(fromCode.sizeZ(), file.sizeZ(), "profondeur");
            assertEquals(fromCode.anchorX(), file.anchorX(), "ancre X");
            assertEquals(fromCode.anchorY(), file.anchorY(), "ancre Y");
            assertEquals(fromCode.anchorZ(), file.anchorZ(), "ancre Z");
            assertEquals(fromCode.front(), file.front(), "façade de référence");
            assertEquals(fromCode.materials(), file.materials(), "palette annoncée");
        }
    }

    // ---- Chargement ----------------------------------------------------------------------------

    @Test
    void aValidFileIsLoaded() throws Exception {
        write("test_hut_01.yml", hutYaml());

        assertEquals(1, library.reload());
        BuildingDefinition definition = library.find("test_hut_01").orElseThrow();
        assertEquals("Hutte de test", definition.name());
        assertEquals(Facing.NORTH, definition.front());
        assertTrue(library.problems().isEmpty(), library.problems().toString());
    }

    @Test
    void anEmptyDirectoryLoadsNothingAndComplainsAboutNothing() {
        assertEquals(0, library.reload());
        assertTrue(library.problems().isEmpty());
        assertEquals(0, library.size());
    }

    @Test
    void aMissingDirectoryIsNotAnError() {
        BuildingLibrary absent = new BuildingLibrary(tempDir.resolve("nulle-part"),
                Logger.getLogger("test"));

        assertEquals(0, absent.reload());
        assertTrue(absent.problems().isEmpty());
    }

    @Test
    void lookupIsCaseInsensitiveAndTolerantOfSpaces() throws Exception {
        write("test_hut_01.yml", hutYaml());
        library.reload();

        assertTrue(library.find("TEST_HUT_01").isPresent());
        assertTrue(library.find("  test_hut_01  ").isPresent());
        assertTrue(library.find("inconnu").isEmpty());
        assertTrue(library.find(null).isEmpty());
    }

    // ---- Fichiers invalides --------------------------------------------------------------------

    /** Un fichier invalide est nommé et ignoré ; les autres chargent quand même. */
    @Test
    void anInvalidFileIsNamedAndDoesNotPreventTheOthersFromLoading() throws Exception {
        write("test_hut_01.yml", hutYaml());
        write("casse.yml", """
                id: casse
                name: "Cassée"
                schematic: casse.schem
                front: NORTH
                """);

        assertEquals(1, library.reload(), "le bâtiment valide doit être chargé");
        assertEquals(1, library.problems().size(), library.problems().toString());
        assertTrue(library.problems().get(0).startsWith("casse.yml : "),
                library.problems().get(0));
        assertTrue(library.problems().get(0).contains("size"), library.problems().get(0));
    }

    @Test
    void aMissingKeyIsReportedByName() throws Exception {
        write("sans_id.yml", "name: \"Sans identifiant\"\n");
        write("sans_nom.yml", "id: sans_nom\nschematic: a.schem\n");
        write("sans_ancre.yml", """
                id: sans_ancre
                name: "Sans ancre"
                schematic: a.schem
                size:
                  x: 7
                  y: 6
                  z: 5
                front: NORTH
                """);

        library.reload();

        assertEquals(0, library.size());
        assertEquals(3, library.problems().size(), library.problems().toString());
        assertTrue(library.problems().stream().anyMatch(p -> p.contains("« id » absente")));
        assertTrue(library.problems().stream().anyMatch(p -> p.contains("« name » absente")));
        assertTrue(library.problems().stream().anyMatch(p -> p.contains("« anchor » absente")));
    }

    @Test
    void anUnknownReferenceOrientationIsReportedWithTheAcceptedValues() throws Exception {
        write("boussole.yml", hutYaml().replace("front: NORTH", "front: NORTHWEST"));

        library.reload();

        assertEquals(0, library.size());
        assertEquals(1, library.problems().size());
        assertTrue(library.problems().get(0).contains("NORTH, EAST, SOUTH, WEST"),
                library.problems().get(0));
    }

    @Test
    void anAnchorOutsideTheBuildingIsReported() throws Exception {
        write("ancre.yml", hutYaml().replace("  x: 3", "  x: 99"));

        library.reload();

        assertEquals(0, library.size());
        assertTrue(library.problems().get(0).contains("Ancre hors du bâtiment"),
                library.problems().get(0));
    }

    /** Deux fichiers pour le même identifiant : le premier gagne, le second est signalé. */
    @Test
    void aDuplicateIdentifierIsReportedAndTheSecondFileIsIgnored() throws Exception {
        write("a_premier.yml", hutYaml());
        write("z_second.yml", hutYaml().replace("Hutte de test", "Doublon"));

        assertEquals(1, library.reload());
        assertEquals("Hutte de test", library.find("test_hut_01").orElseThrow().name(),
                "l'ordre de fichier est déterministe, donc le gagnant aussi");
        assertTrue(library.problems().stream().anyMatch(p -> p.contains("déjà déclaré")),
                library.problems().toString());
    }

    @Test
    void nonYamlFilesAreIgnoredEntirely() throws Exception {
        write("test_hut_01.yml", hutYaml());
        Files.writeString(directory.resolve("notes.txt"), "ceci n'est pas un bâtiment");
        Files.writeString(directory.resolve("test_hut_01.schem"), "binaire");

        assertEquals(1, library.reload());
        assertTrue(library.problems().isEmpty(), library.problems().toString());
    }

    // ---- Rechargement --------------------------------------------------------------------------

    @Test
    void reloadingReplacesTheWholeSet() throws Exception {
        write("test_hut_01.yml", hutYaml());
        library.reload();
        assertEquals(1, library.size());

        Files.delete(directory.resolve("test_hut_01.yml"));

        assertEquals(0, library.reload());
        assertTrue(library.find("test_hut_01").isEmpty(),
                "un bâtiment retiré du disque ne doit pas rester en mémoire");
    }

    @Test
    void theListIsSortedByDisplayName() throws Exception {
        write("a.yml", hutYaml().replace("id: test_hut_01", "id: zebre")
                .replace("Hutte de test", "Zèbre"));
        write("b.yml", hutYaml().replace("id: test_hut_01", "id: abri")
                .replace("Hutte de test", "Abri"));
        library.reload();

        List<BuildingDefinition> all = library.all();

        assertEquals(2, all.size());
        assertEquals("Abri", all.get(0).name());
        assertEquals("Zèbre", all.get(1).name());
    }

    // ---- Dépôt de l'exemple embarqué -----------------------------------------------------------

    @Test
    void startingDepositsTheBundledExample() {
        // Deux bâtiments livrés depuis #234 : la hutte de #213 et la tour de garde.
        assertEquals(2, library.start(), "les exemples embarqués doivent être déposés puis chargés");
        assertTrue(Files.isRegularFile(directory.resolve("test_hut_01.yml")));
        assertTrue(Files.isRegularFile(directory.resolve("test_watchtower_01.yml")));
        assertTrue(library.find("test_hut_01").isPresent());
        assertTrue(library.find("test_watchtower_01").isPresent());
    }

    /**
     * Le dépôt n'écrase <strong>jamais</strong> un fichier existant.
     *
     * <p>Règle du projet, et elle a une raison concrète : un administrateur peut avoir retouché le
     * fichier, et une mise à jour du JAR ne doit pas effacer son travail.</p>
     */
    @Test
    void startingNeverOverwritesAnExistingFile() throws Exception {
        write("test_hut_01.yml", hutYaml().replace("Hutte de test", "Ma version retouchée"));

        library.start();

        assertEquals("Ma version retouchée", library.find("test_hut_01").orElseThrow().name());
        assertFalse(Files.readString(directory.resolve("test_hut_01.yml"))
                .contains("Hutte de test"));
    }
}
