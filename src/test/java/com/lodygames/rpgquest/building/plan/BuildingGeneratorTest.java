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

    /** Arche de portail (issue #568) : socle en y=0, passage x 2..4, y 1..3, sur toute la profondeur. */
    private static final String ARCH_PLAN = """
            id: arche_portail
            name: "Arche du portail"
            kind: portal
            front: NORTH
            size:
              x: 7
              y: 6
              z: 3
            steps:
              - op: box
                from: [0, 0, 0]
                to: [6, 4, 2]
                block: minecraft:stone_bricks
              - op: clear
                from: [2, 1, 0]
                to: [4, 3, 2]
              - op: box
                from: [0, 0, 0]
                to: [6, 0, 2]
                block: minecraft:polished_andesite
            """;

    @Test
    @DisplayName("#568 : une arche générée garde son type et sa zone de passage dans la bibliothèque")
    void aGeneratedPortalArchKeepsItsKindAndPassage() throws Exception {
        BuildingGenerator.Result result = generator.generate(ARCH_PLAN, false);

        assertTrue(result.ok(), result.message());
        var definition = library.find("arche_portail").orElseThrow();
        assertEquals(StructureKind.PORTAL, definition.kind());
        // Ancre par défaut (3, 0, 1) dans la dalle : le passage commence juste au-dessus.
        assertEquals(new com.lodygames.rpgquest.building.model.PortalPassage(2, 1, 0, 4, 3, 2),
                definition.passage());
        String yaml = Files.readString(buildings.resolve("arche_portail.yml"));
        assertTrue(yaml.contains("kind: portal"), yaml);
        assertTrue(yaml.contains("passage:"), yaml);
        assertTrue(generator.preview(ARCH_PLAN).verdict().message().contains("Passage du portail : 3 × 3 × 3"));
    }

    @Test
    @DisplayName("#568 : une arche sans passage à l'ancre est refusée avant toute écriture")
    void aPortalArchWithoutPassageAtTheAnchorIsRefusedBeforeWriting() {
        String offCentre = ARCH_PLAN.replace("id: arche_portail", "id: arche_decalee")
                + "anchor:\n  x: 0\n  y: 0\n  z: 1\n";

        BuildingGenerator.Result result = generator.generate(offCentre, false);

        assertFalse(result.ok());
        assertEquals(BuildingGenerator.PORTAL_PASSAGE_MISSING, result.code());
        assertFalse(gateway.files.contains("arche_decalee.schem"), "rien n'a été écrit");
        assertTrue(library.find("arche_decalee").isEmpty());
        BuildingGenerator.Verdict v = generator.preview(offCentre).verdict();
        assertFalse(v.ok());
        assertTrue(v.codes().contains(BuildingGenerator.PORTAL_PASSAGE_MISSING), v.codes().toString());
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
        for (String expected : new String[] {"Vitrages insuffisants", "Aucune source de lumière"}) {
            assertTrue(message.contains(expected), expected + " absent de : " + message);
        }
        assertFalse(message.contains("Aucune entrée"),
                "l'audit ne doit pas reprocher une entrée qui existe : " + message);
    }

    // ---- Inspection à blanc (issue #94, lot F) ----------------------------------------------

    /**
     * L'inspection est la pièce qui rend la boucle de correction tenable : avant elle, le seul
     * moyen de connaître le verdict du serveur était de <em>générer</em> — donc d'écrire un
     * bâtiment pour apprendre qu'il était mauvais.
     */
    @Test
    @DisplayName("Inspecter un plan refusé rend le même verdict que générer, sans écrire un octet")
    void inspectingARefusedPlanGivesTheSameVerdictAsGeneratingWithoutWritingAByte() {
        String habitable = LIBRARY_PLAN.replace("kind: decoration\n", "");

        BuildingGenerator.Verdict verdict = generator.inspect(habitable);

        assertFalse(verdict.ok());
        assertEquals("ARCHITECTURE_REFUSED", verdict.code());
        assertEquals(generator.generate(habitable, false).message(), verdict.message(),
                "le même refus, rédigé une seule fois : deux textes apprendraient deux choses");
        assertTrue(verdict.codes().contains("GLAZING_MISSING"), verdict.codes().toString());

        assertTrue(gateway.files.isEmpty(), "aucun schematic");
        assertFalse(Files.exists(buildings.resolve("hub_library.yml")), "aucune définition");
        assertTrue(library.find("hub_library").isEmpty(), "rien dans la bibliothèque");
    }

    /**
     * Le cas le plus important à ne pas confondre : un plan <strong>conforme</strong> ressort de
     * l'inspection en succès, et pourtant aucun bâtiment n'existe. Un écran qui prendrait cet
     * {@code ok} pour une génération annoncerait un artefact absent.
     */
    @Test
    @DisplayName("Un plan conforme passe l'inspection sans qu'aucun bâtiment existe pour autant")
    void aconformingPlanPassesInspectionWithoutAnyBuildingComingIntoExistence() {
        BuildingGenerator.Verdict verdict = generator.inspect(LIBRARY_PLAN);

        assertTrue(verdict.ok(), verdict.message());
        assertEquals("OK", verdict.code());
        assertEquals("hub_library", verdict.buildingId());
        assertTrue(verdict.blocks() > 0, "les blocs sont comptés : " + verdict.blocks());
        assertFalse(verdict.exists(), "la bibliothèque est vide : le plan n'a jamais été généré");

        assertTrue(gateway.files.isEmpty(), "l'inspection n'écrit pas de schematic");
        assertFalse(Files.exists(buildings.resolve("hub_library.yml")));
        assertTrue(library.find("hub_library").isEmpty());
    }

    /** #305 : le plan fait 9 × 7 × 8 (X × Z × Y) ; chaque limite du projet est jugée à part. */
    @Test
    @DisplayName("Un plan qui dépasse la longueur Z du projet est refusé même si X tient, axe nommé")
    void aPlanBeyondTheProjectDepthIsRefusedEvenIfItsWidthFits() {
        BuildingGenerator.Verdict fits = generator.inspect(LIBRARY_PLAN, new PlanRepair.Limits(13, 7, 8));
        assertTrue(fits.ok(), fits.message());

        BuildingGenerator.Verdict deep = generator.inspect(LIBRARY_PLAN, new PlanRepair.Limits(13, 6, 8));
        assertFalse(deep.ok());
        assertEquals(BuildingGenerator.PROJECT_LIMITS_EXCEEDED, deep.code());
        assertTrue(deep.message().contains("size.z = 7 pour une longueur maximale (Z) de 6"), deep.message());
        assertFalse(deep.message().contains("size.x"), "X tient : il n'est pas cité");
        assertEquals(null, deep.repair(), "agrandir ne rapetisse rien : aucune réparation proposée");

        BuildingGenerator.Verdict wide = generator.inspect(LIBRARY_PLAN, new PlanRepair.Limits(8, 13, 8));
        assertTrue(wide.message().contains("size.x = 9 pour une largeur maximale (X) de 8"), wide.message());
        assertTrue(generator.preview(LIBRARY_PLAN, new PlanRepair.Limits(13, 6, 8)).hasPreview(),
                "l'aperçu reste dessiné pour montrer ce qui déborde");
        assertTrue(generator.inspect(LIBRARY_PLAN, PlanRepair.Limits.none()).ok(), "sans limite connue, rien ne change");
        assertTrue(gateway.files.isEmpty());
    }

    @Test
    @DisplayName("L'inspection porte les mesures prises sur la grille, pour la boucle de correction")
    void inspectionCarriesTheMeasurementsTakenOnTheGrid() {
        BuildingGenerator.Verdict verdict = generator.inspect(LIBRARY_PLAN);

        assertEquals(verdict.blocks(), verdict.metrics().solidCells(),
                "les blocs annoncés sont ceux de la grille, pas une estimation");
        assertEquals(0, verdict.metrics().glazedCells(), "ce plan n'a aucun vitrage, et le dit");
        assertEquals("décoration", verdict.kind(),
                "le type gouverne les contrôles : il doit remonter");
    }

    @Test
    @DisplayName("L'inspection signale qu'un identifiant est déjà pris, sans rien remplacer")
    void inspectionReportsAnAlreadyTakenIdentifierWithoutReplacingAnything() {
        generator.generate(LIBRARY_PLAN, false);
        int filesBefore = gateway.files.size();

        BuildingGenerator.Verdict verdict = generator.inspect(LIBRARY_PLAN);

        assertTrue(verdict.exists(), "l'écran doit pouvoir prévenir avant de proposer d'écraser");
        assertTrue(verdict.ok(), "un identifiant pris n'est pas un défaut architectural");
        assertEquals(filesBefore, gateway.files.size(), "aucune réécriture");
    }

    @Test
    @DisplayName("Un plan illisible est refusé par l'inspection, sans mesures inventées")
    void anunreadablePlanIsRefusedByInspectionWithoutInventedMeasurements() {
        BuildingGenerator.Verdict verdict = generator.inspect("ceci n'est pas un plan");

        assertFalse(verdict.ok());
        assertEquals("PLAN_INVALID", verdict.code());
        assertEquals(null, verdict.metrics(),
                "aucune mesure n'est possible sans grille : ne pas en inventer");
    }

    // ---- Aperçu 2D (issue #94, lot E) -------------------------------------------------------

    @Test
    @DisplayName("L'aperçu partage le verdict de l'inspection, et n'écrit rien non plus")
    void thepreviewSharesTheInspectionVerdictAndWritesNothingEither() {
        String habitable = LIBRARY_PLAN.replace("kind: decoration\n", "");

        BuildingGenerator.Examination exam = generator.preview(habitable);

        assertEquals(generator.inspect(habitable), exam.verdict(),
                "un seul chemin d'examen : les deux doivent rendre le MÊME verdict");
        assertTrue(exam.hasPreview(), "les coupes expliquent le refus");
        assertTrue(gateway.files.isEmpty(), "aucun schematic");
        assertFalse(Files.exists(buildings.resolve("hub_library.yml")), "aucune définition");
    }

    /** Un plan refusé est exactement le cas où l'aperçu sert : il montre pourquoi. */
    @Test
    @DisplayName("Un plan refusé a quand même ses coupes, et elles montrent le défaut")
    void arefusedPlanStillHasSlicesAndTheyShowTheDefect() {
        String habitable = LIBRARY_PLAN.replace("kind: decoration\n", "");

        BuildingGenerator.Examination exam = generator.preview(habitable);

        assertFalse(exam.verdict().ok());
        assertEquals(4, exam.preview().elevations().size());
        assertFalse(exam.preview().plans().isEmpty());
        // Ce plan n'a aucun vitrage : aucune coupe ne doit en montrer.
        for (var slice : exam.preview().elevations()) {
            assertFalse(slice.text().indexOf(PlanPreview.GLAZING) >= 0,
                    "aucun vitrage dans ce plan : " + slice.id());
        }
    }

    @Test
    @DisplayName("Un plan illisible n'a pas de coupes, et n'en invente pas")
    void anunreadablePlanHasNoSlices() {
        BuildingGenerator.Examination exam = generator.preview("ceci n'est pas un plan");

        assertFalse(exam.verdict().ok());
        assertFalse(exam.hasPreview(), "aucune grille, donc aucune coupe");
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
    // ---- #288–#292 : contenu refusé avant le verdict, écriture distinguée ---------------------

    @Test
    @DisplayName("Un état refusé par le vrai parseur du moteur rend le plan invalide AVANT tout verdict conforme")
    void anEngineRefusalMakesThePlanInvalidBeforeAnyVerdict() {
        gateway.refuse.put("minecraft:stone_bricks", "NoMatchException — simulé");

        BuildingGenerator.Verdict verdict = generator.inspect(LIBRARY_PLAN);
        assertFalse(verdict.ok());
        assertEquals("PLAN_INVALID", verdict.code());
        assertTrue(verdict.codes().contains("BLOCK_REJECTED"), verdict.codes().toString());
        assertTrue(verdict.message().contains("« minecraft:stone_bricks » en 0/0/0"), verdict.message());
        assertTrue(verdict.message().contains("instruction 1"), verdict.message());

        BuildingGenerator.Result result = generator.generate(LIBRARY_PLAN, false);
        assertEquals("PLAN_INVALID", result.code());
        assertTrue(gateway.files.isEmpty(), "rien n'est écrit");
    }

    @Test
    @DisplayName("Un échec d'écriture est annoncé comme tel, pas comme une faute du plan")
    void aWriteFailureIsReportedAsInfrastructure() {
        gateway.writeFails = true;
        BuildingGenerator.Result result = generator.generate(LIBRARY_PLAN, false);
        assertEquals("SCHEMATIC_FAILED", result.code());
        assertTrue(result.message().contains("Échec d'écriture"), result.message());
        assertTrue(result.message().contains("passé la validation de ses blocs"), result.message());
    }

    // ---- #290 : republier une nouvelle version du même bâtiment ------------------------------

    @Test
    @DisplayName("Republier : la version vue est contrôlée, la précédente est archivée, la nouvelle prend sa place")
    void republishingChecksTheVersionAndArchivesThePreviousOne() throws Exception {
        Path schematics = tempDir.resolve("schematics");
        Files.createDirectories(schematics);
        generator.archivingTo(schematics, java.time.Clock.fixed(java.time.Instant.parse("2026-10-12T20:00:00Z"),
                java.time.ZoneOffset.UTC));
        assertTrue(generator.generate(LIBRARY_PLAN, false).ok());
        Files.writeString(schematics.resolve("hub_library.schem"), "v1");

        // Quelqu'un a vu la version 2 : il n'y en a qu'une. Rien n'est écrit.
        BuildingGenerator.Result conflict = generator.generate(LIBRARY_PLAN, true, 2);
        assertEquals("VERSION_CONFLICT", conflict.code(), conflict.message());
        assertEquals(1, library.find("hub_library").orElseThrow().version());

        BuildingGenerator.Result republished = generator.generate(LIBRARY_PLAN, true, 1);
        assertTrue(republished.ok(), republished.message());
        assertEquals(2, republished.version());
        assertTrue(republished.replaced());
        Path archive = buildings.resolve(".history").resolve("hub_library").resolve("v1_20261012-200000");
        assertTrue(Files.exists(archive.resolve("hub_library.yml")), "la définition v1 est archivée");
        assertEquals("v1", Files.readString(archive.resolve("hub_library.schem")), "le schematic v1 aussi");
        assertTrue(republished.effects().stream().anyMatch(e -> e.contains("version 1 archivée")),
                republished.effects().toString());
        assertEquals(2, library.find("hub_library").orElseThrow().version());
    }

    /**
     * #288–#292 : le parcours complet sur le plan RÉEL de la Maison de Lily, jusqu'au schematic —
     * matériaux corrigés comme l'IA les corrigerait, réparation du moteur, audit, enregistrement.
     * L'écriture du .schem par WorldEdit lui-même se vérifie en jeu (TC-295) : sans serveur, son
     * registre n'existe pas ; ici le moteur simulé reçoit la grille finale.
     */
    @Test
    @DisplayName("Maison de Lily réelle : refusée pour ses blocs, puis réparée, auditée et enregistrée jusqu'au schematic")
    void lilysRealPlanGoesAllTheWayToASchematic() {
        String real = VerticalCirculation282Test.fixture("maison_de_lily_aa8ff980.yml");
        BuildingGenerator.Verdict first = generator.inspect(real, new PlanRepair.Limits(19, 10));
        assertEquals("PLAN_INVALID", first.code());
        assertTrue(first.message().contains("minecraft:bed") && first.message().contains("stained_glass_pane"),
                first.message());
        assertTrue(gateway.files.isEmpty(), "rien n'est écrit pour un plan refusé");

        String corrected = real.replace("""
                  - op: box
                    from: [9, 1, 13]
                    to: [10, 1, 14]
                    block: minecraft:bed
                """, """
                  - op: bed
                    from: [9, 1, 13]
                    direction: south
                    block: minecraft:pink_bed
                """).replace("minecraft:stained_glass_pane", "minecraft:pink_stained_glass_pane");
        BuildingGenerator.Verdict second = generator.inspect(corrected, new PlanRepair.Limits(19, 10));
        assertTrue(second.repair() != null, "le moteur propose une réparation : " + second.message());
        // Ses lanternes de y=2, en plein milieu de la pièce, sont trop loin du toit pour une chaîne :
        // le moteur ne les invente pas ailleurs, le refus reste — c'est la correction de l'IA.
        assertTrue(second.repair().codes().contains("UNSUPPORTED_BLOCK"), second.repair().codes().toString());

        String lanternsOnTheFloor = corrected
                .replace("from: [8, 2, 8]\n    to: [10, 3, 10]", "from: [8, 1, 8]\n    to: [10, 1, 10]")
                .replace("from: [12, 2, 9]\n    to: [13, 2, 10]", "from: [12, 1, 9]\n    to: [13, 1, 10]");
        assertTrue(!lanternsOnTheFloor.equals(corrected), "les deux lumières ont été descendues");
        // Son second toit (clear puis gable de y=8) recouvre toute la largeur au-dessus de la pente du
        // premier : 56 cellules ouvertes sous débord (ENVELOPE_OPEN), que les vitres posées sur la
        // limite du volume masquaient. Aucune réparation sûre : l'IA le retire.
        String secondRoof = """
                  - op: clear
                    from: [3, 8, 3]
                    to: [15, 9, 15]
                  - op: gable
                    from: [3, 8, 3]
                    to: [15, 9, 15]
                    axis: x
                    fill: minecraft:smooth_sandstone
                    block: minecraft:dark_oak_stairs
                """;
        assertTrue(lanternsOnTheFloor.contains(secondRoof), "le plan réel a bien ce second toit");
        lanternsOnTheFloor = lanternsOnTheFloor.replace(secondRoof, "");
        BuildingGenerator.Verdict fourth = generator.inspect(lanternsOnTheFloor, new PlanRepair.Limits(19, 10));
        assertTrue(fourth.repair() != null && fourth.repair().accepted(), String.valueOf(fourth.repair()));
        String repaired = fourth.repair().plan();
        BuildingGenerator.Verdict third = generator.inspect(repaired, new PlanRepair.Limits(19, 10));
        assertTrue(third.ok(), "la version réparée est conforme : " + third.message());

        BuildingGenerator.Result saved = generator.generate(repaired, false);
        assertTrue(saved.ok(), saved.message());
        assertTrue(gateway.files.contains("maison_de_lily.schem"), "le schematic est écrit");
        assertTrue(library.find("maison_de_lily").isPresent(), "et le bâtiment est dans la bibliothèque");
    }

    static final class FakeGateway implements SchematicGateway {
        final Set<String> files = new LinkedHashSet<>();
        final java.util.Map<String, String> refuse = new java.util.HashMap<>();
        boolean available = true;
        boolean writeFails;

        @Override public java.util.Map<String, String> rejected(java.util.Collection<String> states) {
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            for (String state : states) {
                if (refuse.containsKey(state)) {
                    out.put(state, refuse.get(state));
                }
            }
            return out;
        }

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
