package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.SchematicGateway;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BuildingFootprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #94 — la génération produit-elle un <strong>artefact réel</strong> ?
 *
 * <p><strong>Pourquoi le plan de référence est déclaré « décoration ».</strong> Ces tests portent
 * sur le <em>pipeline</em> : les trois artefacts, le versionnage, le refus d'écraser. Le contrat
 * architectural du lot A a ses propres tests ({@code ArchitecturalAuditTest},
 * {@code ArchitecturalAuditV1RegressionTest}), et imposer ici une porte, des fenêtres et un
 * éclairage à un plan dont ce n'est pas le sujet mélangerait deux préoccupations. Le refus
 * architectural est vérifié explicitement plus bas.</p>
 *
 * <p>C'est l'exigence explicite du ticket : « aucun faux succès : un résultat affiché comme généré
 * doit correspondre à un artefact réel ». Ces tests vérifient donc les trois choses qu'un succès
 * promet — le schematic écrit, la définition sur le disque, l'entrée dans la bibliothèque — et
 * surtout qu'un échec à n'importe quelle étape n'en laisse aucune à moitié.</p>
 */
class BuildingGeneratorTest {

    @TempDir
    Path tempDir;

    private FakeGateway gateway;
    private BuildingLibrary library;
    private BuildingGenerator generator;
    private Path buildings;

    /** Le plan de la Bibliothèque du Hub, réduit mais réaliste : sol, murs, porte, toit. */
    private static final String LIBRARY_PLAN = """
            id: hub_library
            name: "Bibliothèque du Hub"
            kind: decoration
            front: WEST
            size:
              x: 9
              y: 8
              z: 7
            anchor:
              x: 4
              y: 0
              z: 3
            steps:
              - op: box
                from: [0, 0, 0]
                to: [8, 0, 6]
                block: minecraft:stone_bricks
              - op: walls
                from: [0, 1, 0]
                to: [8, 4, 6]
                block: minecraft:stone_bricks
              - op: clear
                from: [0, 1, 3]
                to: [0, 2, 3]
              - op: gable
                from: [0, 5, 0]
                to: [8, 7, 6]
                block: minecraft:dark_oak_stairs
                axis: x
            """;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        buildings = tempDir.resolve("buildings");
        library = new BuildingLibrary(buildings, Logger.getLogger("test"));
        library.reload();
        generator = new BuildingGenerator(library,
                new SchematicWorkshop(gateway, Logger.getLogger("test")),
                buildings, Logger.getLogger("test"));
    }

    // ---- Le chemin qui compte ----------------------------------------------------------------

    @Test
    @DisplayName("Un succès signifie : schematic écrit, définition sur le disque, bâtiment dans la bibliothèque")
    void asuccessMeansTheThreeArtefactsReallyExist() throws Exception {
        BuildingGenerator.Result result = generator.generate(LIBRARY_PLAN, false);

        assertTrue(result.ok(), result.message());
        assertEquals("GENERATED", result.code());
        assertEquals("hub_library", result.buildingId());

        // 1. le schematic
        assertTrue(gateway.files.contains("hub_library.schem"), "le .schem doit avoir été écrit");
        // 2. la définition sur le disque
        Path file = buildings.resolve("hub_library.yml");
        assertTrue(Files.exists(file), "la définition doit être écrite");
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("hub_library"), yaml);
        // 3. la bibliothèque la porte réellement
        assertTrue(library.find("hub_library").isPresent(),
                "le bâtiment doit être posable, donc présent dans la bibliothèque");

        // Et les dimensions annoncées sont celles du plan, pas une estimation.
        var definition = library.find("hub_library").orElseThrow();
        assertEquals(9, definition.sizeX());
        assertEquals(8, definition.sizeY());
        assertEquals(7, definition.sizeZ());
        assertEquals("hub_library.schem", definition.schematic());
        assertEquals(1, definition.version());
        assertTrue(result.blocks() > 0, "des blocs ont été posés");
    }

    @Test
    @DisplayName("Le plan source est conservé dans la définition : un bâtiment généré n'est pas un cul-de-sac")
    void thesourcePlanIsKeptInsideTheDefinition() throws Exception {
        generator.generate(LIBRARY_PLAN, false);

        String yaml = Files.readString(buildings.resolve("hub_library.yml"));
        assertTrue(yaml.contains("plan:"), "le plan doit être conservé");
        assertTrue(yaml.contains("gable"), "y compris ses instructions : " + yaml);
    }

    @Test
    @DisplayName("Régénérer incrémente la version, pour que le bâtiment posé sache qu'il a vieilli")
    void regeneratingBumpsTheVersion() {
        assertTrue(generator.generate(LIBRARY_PLAN, false).ok());

        BuildingGenerator.Result again = generator.generate(LIBRARY_PLAN, true);

        assertTrue(again.ok(), again.message());
        assertEquals("REGENERATED", again.code());
        assertTrue(again.replaced());
        assertEquals(2, again.version());
        assertEquals(2, library.find("hub_library").orElseThrow().version());
    }

    @Test
    @DisplayName("La même génération produit le même nombre de blocs : l'empreinte reste stable")
    void thesamePlanAlwaysProducesTheSameBlockCount() {
        int first = generator.generate(LIBRARY_PLAN, false).blocks();
        int second = generator.generate(LIBRARY_PLAN, true).blocks();

        assertEquals(first, second,
                "sinon chaque régénération ferait croire à un bâtiment périmé (#234)");
    }

    // ---- Les refus : aucun artefact à moitié -------------------------------------------------

    @Test
    @DisplayName("Un identifiant déjà pris n'est jamais écrasé sans l'avoir demandé")
    void anexistingBuildingIsNeverOverwrittenWithoutAsking() throws Exception {
        assertTrue(generator.generate(LIBRARY_PLAN, false).ok());
        String before = Files.readString(buildings.resolve("hub_library.yml"));

        BuildingGenerator.Result refused = generator.generate(LIBRARY_PLAN, false);

        assertFalse(refused.ok());
        assertEquals("ALREADY_EXISTS", refused.code());
        assertTrue(refused.message().contains("existe déjà"), refused.message());
        assertEquals(before, Files.readString(buildings.resolve("hub_library.yml")),
                "le fichier existant doit être intact");
    }

    @Test
    @DisplayName("Un plan illisible ou invalide n'écrit RIEN du tout")
    void aninvalidPlanWritesNothingAtAll() {
        for (String bad : new String[] {
            "",
            "ceci n'est pas un plan",
            // identifiant interdit
            "id: 'Hub Library'\nname: x\nfront: WEST\nsize:\n  x: 2\n  y: 2\n  z: 2\n"
                    + "steps:\n  - op: box\n    from: [0,0,0]\n    block: minecraft:stone\n",
            // instruction hors vocabulaire : refusée, pas ignorée
            "id: hub_library\nname: x\nfront: WEST\nsize:\n  x: 2\n  y: 2\n  z: 2\n"
                    + "steps:\n  - op: summon_dragon\n    from: [0,0,0]\n    block: minecraft:stone\n",
            // zone hors volume
            "id: hub_library\nname: x\nfront: WEST\nsize:\n  x: 2\n  y: 2\n  z: 2\n"
                    + "steps:\n  - op: box\n    from: [0,0,0]\n    to: [99,0,0]\n"
                    + "    block: minecraft:stone\n"}) {
            BuildingGenerator.Result result = generator.generate(bad, false);

            assertFalse(result.ok(), "doit être refusé : " + bad);
            assertTrue(gateway.files.isEmpty(), "aucun schematic ne doit avoir été écrit");
            assertFalse(Files.exists(buildings.resolve("hub_library.yml")),
                    "aucune définition ne doit avoir été écrite");
            assertTrue(library.find("hub_library").isEmpty());
        }
    }

    /**
     * Le cas qui compte le plus : le moteur refuse d'écrire le fichier. Il ne doit alors
     * <strong>pas</strong> exister de définition qui le désigne, sinon la bibliothèque afficherait
     * un bâtiment « schematic absent » né de nulle part.
     */
    @Test
    @DisplayName("Si le schematic échoue, aucune définition n'est écrite")
    void whenTheSchematicFailsNoDefinitionIsWritten() {
        gateway.available = false;
        gateway.writeFails = true;

        BuildingGenerator.Result result = generator.generate(LIBRARY_PLAN, false);

        assertFalse(result.ok());
        assertEquals("SCHEMATIC_FAILED", result.code());
        assertTrue(result.message().contains("Aucune définition"), result.message());
        assertFalse(Files.exists(buildings.resolve("hub_library.yml")));
        assertTrue(library.find("hub_library").isEmpty());
    }

    @Test
    @DisplayName("Un état de bloc mal formé est refusé avant toute écriture")
    void amalformedBlockStateIsRefusedBeforeAnyWrite() {
        // Une tentative d'y glisser autre chose qu'un bloc : refusée par la forme, pas par une
        // liste noire — c'est ce qui la rend robuste.
        String plan = "id: hub_library\nname: x\nfront: WEST\nsize:\n  x: 2\n  y: 2\n  z: 2\n"
                + "steps:\n  - op: box\n    from: [0,0,0]\n"
                + "    block: \"minecraft:stone; /op Pirate\"\n";

        BuildingGenerator.Result result = generator.generate(plan, false);

        assertFalse(result.ok());
        assertTrue(result.message().contains("état de bloc refusé"), result.message());
        assertTrue(gateway.files.isEmpty());
    }


    // ---- Issue #94, lot A : le contrat architectural refuse AVANT toute écriture --------------

    /**
     * Un bâtiment habitable scellé est refusé, et <strong>rien n'est écrit</strong>.
     *
     * <p>C'est le défaut exact de la V1 : elle était passée en succès avec « prêt à être posé ».</p>
     */
    @Test
    @DisplayName("Un bâtiment habitable sans porte ni fenêtre ni lumière est refusé, sans rien écrire")
    void asealedHabitableBuildingIsRefusedWithoutWritingAnything() {
        // Le même volume, mais déclaré habitable : le contrat s'applique.
        String habitable = LIBRARY_PLAN.replace("kind: decoration\n", "");

        BuildingGenerator.Result result = generator.generate(habitable, false);

        assertFalse(result.ok());
        assertEquals("ARCHITECTURE_REFUSED", result.code());
        assertTrue(result.message().contains("Corrige PRÉCISÉMENT"),
                "le refus doit être réutilisable par la boucle de correction : " + result.message());
        assertTrue(result.message().contains("Rien n'a été écrit"), result.message());

        // Et vérifié, pas supposé : aucun des trois artefacts n'existe.
        assertTrue(gateway.files.isEmpty(), "aucun schematic");
        assertFalse(Files.exists(buildings.resolve("hub_library.yml")), "aucune définition");
        assertTrue(library.find("hub_library").isEmpty(), "rien dans la bibliothèque");
    }

    @Test
    @DisplayName("Le refus nomme chacun des défauts, numérotés, pour que l'IA puisse corriger")
    void therefusalNamesEachDefectForTheCorrectionLoop() {
        String habitable = LIBRARY_PLAN.replace("kind: decoration\n", "");

        String message = generator.generate(habitable, false).message();

        // Ce plan a bien une porte (clear en x=0, dans le plan de la façade ouest) : l'entrée
        // n'est donc PAS en cause, et l'audit ne doit pas l'inventer. Ce qui manque réellement :
        for (String expected : new String[] {"Vitrages insuffisants", "Aucune source de lumière",
            "PIGNONS"}) {
            assertTrue(message.contains(expected), expected + " absent de : " + message);
        }
        assertFalse(message.contains("Aucune entrée"),
                "l'audit ne doit pas reprocher une entrée qui existe : " + message);
    }

    @Test
    @DisplayName("Les avertissements non bloquants remontent avec le succès, sans le compromettre")
    void nonBlockingWarningsAreReportedAlongsideSuccess() {
        // Une décoration strictement monomatière : elle passe (aucun contrôle d'habitabilité) mais
        // doit être SIGNALÉE. Taire ce qu'on sait reviendrait à livrer sans le dire.
        String monotonous = """
                id: mur_simple
                name: "Mur simple"
                kind: decoration
                front: NORTH
                size:
                  x: 5
                  y: 3
                  z: 2
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [4, 2, 1]
                    block: minecraft:stone_bricks
                """;

        BuildingGenerator.Result result = generator.generate(monotonous, false);

        assertTrue(result.ok(), result.message());
        assertTrue(result.effects().stream().anyMatch(e -> e.startsWith("⚠")),
                "un avertissement d'audit doit remonter dans les effets : " + result.effects());
        assertTrue(result.effects().stream().anyMatch(e -> e.contains("matériau")),
                "et dire lequel : " + result.effects());
    }

    // ---- Moteur simulé -----------------------------------------------------------------------

    /** Moteur minimal : il retient les fichiers écrits, et sait échouer sur commande. */
    private static final class FakeGateway implements SchematicGateway {
        final Set<String> files = new LinkedHashSet<>();
        boolean available = true;
        boolean writeFails;

        @Override public boolean available() {
            return available;
        }

        @Override public String unavailableReason() {
            return "moteur simulé indisponible";
        }

        @Override public boolean has(String fileName) {
            return files.contains(fileName);
        }

        @Override public Optional<String> fingerprint(String fileName) {
            return files.contains(fileName) ? Optional.of("sha-" + fileName) : Optional.empty();
        }

        @Override public Outcome write(Blueprint blueprint, String fileName) {
            if (writeFails) {
                return Outcome.failure("moteur simulé : écriture refusée");
            }
            files.add(fileName);
            return Outcome.success();
        }

        @Override public Optional<Dimensions> inspect(String fileName) {
            return Optional.empty();
        }

        @Override public Outcome capture(BuildingFootprint footprint, String fileName) {
            return Outcome.failure("non utilisé par ce test");
        }

        @Override public Outcome paste(PasteOrder order) {
            return Outcome.failure("non utilisé par ce test");
        }

        @Override public Outcome restore(String fileName, BuildingFootprint footprint) {
            return Outcome.failure("non utilisé par ce test");
        }
    }
}
