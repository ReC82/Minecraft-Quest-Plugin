package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * #288–#292, premier lot : les deux blocages démontrés sur la Maison de Lily (projet
 * bef26b6d, version aa8ff980) qui rendaient le verdict « conforme » trompeur — des ouvertures
 * posées sur la limite du volume, hors des murs, et des blocs inexistants découverts seulement à
 * l'écriture du schematic. Tout est déterministe, sans serveur et sans appel payant.
 */
class Facades288Test {

    private static final String LILY = "maison_de_lily_aa8ff980.yml";

    private static BuildingPlan loose(String yaml) {
        List<String> problems = new ArrayList<>();
        return BuildingPlanYaml.readUnvalidated(yaml, problems)
                .orElseThrow(() -> new AssertionError(problems.toString()));
    }

    private static BuildingPlan strict(String yaml) {
        List<String> problems = new ArrayList<>();
        return BuildingPlanYaml.read(yaml, problems)
                .orElseThrow(() -> new AssertionError(problems.toString()));
    }

    private static ArchitecturalAudit.Report audit(BuildingPlan plan) {
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertTrue(r.ok(), r.error());
        return ArchitecturalAudit.audit(r.blueprint(), plan.front(), plan.rules(), r.provenance(),
                plan.anchorY());
    }

    private static String at(Blueprint b, int x, int y, int z) {
        return b.at(x, y, z).map(c -> c.state()).orElse(null);
    }

    /**
     * La Maison de Lily, matériaux corrigés comme l'IA les corrigerait après le refus (vitres roses,
     * lit retiré — le lit a son propre lot, #288). Ce qui reste est exactement le défaut de façade.
     */
    private static String lilyWithRealMaterials() {
        String yaml = VerticalCirculation282Test.fixture(LILY);
        String bed = """
                  - op: box
                    from: [9, 1, 13]
                    to: [10, 1, 14]
                    block: minecraft:bed
                """;
        assertTrue(yaml.contains(bed), "le plan réel contient le lit fautif");
        return yaml.replace(bed, "").replace("minecraft:stained_glass_pane",
                "minecraft:pink_stained_glass_pane");
    }

    // ---- Blocs : le registre de la version servie ----------------------------------------------

    @Test
    @DisplayName("Lily : les DEUX blocs inexistants sont refusés d'un coup, avec instructions et suggestions")
    void lilysTwoUnknownBlocksAreRefusedTogetherBeforeAnyVerdict() {
        BuildingPlan raw = loose(VerticalCirculation282Test.fixture(LILY));
        String refusal = raw.validate().orElseThrow();

        assertTrue(refusal.contains("« minecraft:bed » n'existe pas en Minecraft 1.21.11"), refusal);
        assertTrue(refusal.contains("instruction 17 (box minecraft:bed de [9, 1, 13] à [10, 1, 14])"),
                refusal);
        assertTrue(refusal.contains("« minecraft:stained_glass_pane » n'existe pas"), refusal);
        assertTrue(refusal.contains("instruction 5 (window"), refusal);
        assertTrue(refusal.contains("minecraft:pink_stained_glass_pane"), "suggestion réelle : " + refusal);
        assertFalse(BuildingPlanExpander.expand(raw).ok(), "l'expansion ne contourne pas le refus");
        assertFalse(PlanRepair.repair(raw, new PlanRepair.Limits(19, 10)).repaired(),
                "aucune couleur n'est devinée à la place du brief");
    }

    @Test
    @DisplayName("Le registre embarqué est celui de 1.21.11 et connaît les blocs colorés réels")
    void theCatalogIsTheRealRegistry() {
        BlockCatalog catalog = BlockCatalog.vanilla();
        assertEquals(1166, catalog.size());
        assertTrue(catalog.known("minecraft:pink_bed"));
        assertTrue(catalog.known("minecraft:pink_stained_glass_pane"));
        assertTrue(catalog.known("minecraft:potted_poppy"));
        assertFalse(catalog.known("minecraft:bed"));
        assertFalse(catalog.known("minecraft:stained_glass_pane"));
        assertTrue(catalog.refusal("minecraft:oak_stairs[facing=up]").orElseThrow()
                .contains("valeurs : north, south, west, east"));
        assertTrue(catalog.refusal("minecraft:stone[axis=y]").orElseThrow()
                .contains("ce bloc n'a aucune propriété"));
        assertTrue(catalog.refusal("minecraft:pink_bed[facing=north,part=head]").isEmpty());
    }

    @Test
    @DisplayName("Un état calculé invalide est refusé à l'expansion, avec la cellule et l'instruction")
    void aComputedStateIsCheckedWithItsCell() {
        // Une porte posée avec un bloc qui n'est pas une porte : les propriétés calculées
        // (facing, half, hinge…) n'existent pas pour des planches. Avant : NoMatchException à
        // l'écriture, après un audit « conforme ».
        BuildingPlan plan = strict(house(Facing.NORTH, 1, """
                  - op: door
                    facade: FRONT
                    offset: 6
                    block: minecraft:oak_planks
                """));
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertFalse(r.ok());
        assertTrue(r.error().contains("minecraft:oak_planks[facing=north,half=lower"), r.error());
        assertTrue(r.error().contains("en 6/1/3"), r.error());
        assertTrue(r.error().contains("la propriété « facing » n'existe pas"), r.error());
    }

    @Test
    @DisplayName("Lanterne des mers et verre plein : le moteur ne leur invente aucune propriété")
    void noPropertyIsInventedForBlocksThatHaveNone() {
        BuildingPlan plan = strict(house(Facing.NORTH, 1, """
                  - op: door
                    facade: FRONT
                    offset: 6
                  - op: light
                    from: [5, 4, 5]
                    to: [5, 4, 5]
                    block: minecraft:sea_lantern
                  - op: window
                    facade: BACK
                    offset: 6
                    block: minecraft:glass
                """));
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertTrue(r.ok(), r.error());
        assertEquals("minecraft:sea_lantern", at(r.blueprint(), 5, 4, 5));
        assertEquals("minecraft:glass", at(r.blueprint(), 6, 2, 9));
    }

    // ---- Façades : le mur construit, pas la limite du volume -----------------------------------

    @Test
    @DisplayName("Lily : la porte et les fenêtres se posent dans les murs [3..15], plus sur la limite 0/18")
    void lilysOpeningsLandInTheBuiltWalls() {
        BuildingPlan plan = loose(lilyWithRealMaterials());
        assertTrue(plan.validate().isEmpty(), plan.validate().orElse(""));

        // La fenêtre avant vise x=3..4 : x=3 est l'ANGLE du mur — refus nommé, plus de vitre isolée.
        BuildingPlanExpander.Result raw = BuildingPlanExpander.expand(plan);
        assertFalse(raw.ok());
        assertTrue(raw.error().startsWith("Instruction 5 (WINDOW)"), raw.error());
        assertTrue(raw.error().contains("z=15, x de 4 à 14 (angles exclus)"), raw.error());

        PlanRepair.Outcome outcome = PlanRepair.repair(plan, new PlanRepair.Limits(19, 10));
        assertTrue(outcome.repaired(), outcome.toString());
        String changes = String.join("\n", outcome.changes());
        assertTrue(changes.contains("offset 3 → 4"), changes);

        Blueprint b = BuildingPlanExpander.expand(outcome.plan()).blueprint();
        // La porte, dans le mur avant z=15, regarde le sud.
        assertTrue(at(b, 6, 1, 15).startsWith("minecraft:dark_oak_door[facing=south,half=lower"),
                at(b, 6, 1, 15));
        assertTrue(at(b, 6, 2, 15).contains("half=upper"));
        // Plus rien sur la limite du volume : ni porte, ni vitrage.
        for (var block : b.blocks()) {
            boolean edge = block.x() == 0 || block.x() == 18 || block.z() == 0 || block.z() == 18;
            assertFalse(edge && (block.state().contains("door") || block.state().contains("glass")),
                    "ouverture sur la limite du volume : " + block);
        }
        // Les vitrages sont dans les murs gauche (x=3), droit (x=15) et arrière (z=3).
        assertTrue(at(b, 3, 2, 5).startsWith("minecraft:pink_stained_glass_pane"), at(b, 3, 2, 5));
        assertTrue(at(b, 15, 2, 5).startsWith("minecraft:pink_stained_glass_pane"), at(b, 15, 2, 5));
        assertTrue(at(b, 7, 2, 3).startsWith("minecraft:pink_stained_glass_pane"), at(b, 7, 2, 3));

        List<String> after = outcome.after();
        assertFalse(after.contains("NO_ENTRANCE"), after.toString());
        assertFalse(after.contains("OPENING_DETACHED"), after.toString());
        assertFalse(after.contains("ENTRANCE_STEP"), after.toString());
        // #291 : le plancher dépasse le terrain d'un bloc ; la marche est posée DANS le volume.
        assertTrue(changes.contains("marche(s) de dark_oak_stairs ajoutée(s)"), changes);
        assertTrue(at(b, 6, 0, 16).startsWith("minecraft:dark_oak_stairs[facing=north"), at(b, 6, 0, 16));
    }

    @Test
    @DisplayName("Une porte et une vitre isolées hors des murs ne valent jamais une entrée ni une fenêtre")
    void isolatedOpeningsNeverCount() {
        // L'ancienne erreur reproduite à la main : la maison est close, une porte et une vitre
        // sont posées trois cellules devant elle.
        BuildingPlan plan = strict(house(Facing.SOUTH, 1, """
                  - op: box
                    from: [6, 1, 12]
                    to: [6, 1, 12]
                    block: minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]
                  - op: box
                    from: [6, 2, 12]
                    to: [6, 2, 12]
                    block: minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]
                  - op: box
                    from: [0, 2, 6]
                    to: [0, 2, 6]
                    block: minecraft:glass_pane
                """));
        ArchitecturalAudit.Report report = audit(plan);
        assertTrue(report.blockingCodes().contains("NO_ENTRANCE"), report.blockingCodes().toString());
        assertTrue(report.blockingCodes().contains("OPENING_DETACHED"), report.blockingCodes().toString());
        String message = report.refusalMessage();
        assertTrue(message.contains("6/1/12"), message);
        assertTrue(message.contains("0/2/6"), message);
        assertTrue(message.contains("ne donne sur aucun intérieur"), message);
    }

    @ParameterizedTest
    @EnumSource(Facing.class)
    @DisplayName("Sur les quatre orientations, la porte se pose dans le mur avant du corps en retrait")
    void theDoorTargetsTheInsetBodyOnEveryOrientation(Facing front) {
        BuildingPlan plan = strict(house(front, 1, OPENINGS));
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertTrue(r.ok(), r.error());
        int[] door = switch (front) {
            case NORTH -> new int[] {6, 1, 3};
            case SOUTH -> new int[] {6, 1, 9};
            case WEST -> new int[] {3, 1, 6};
            case EAST -> new int[] {9, 1, 6};
        };
        String state = at(r.blueprint(), door[0], door[1], door[2]);
        assertNotNull(state, "porte attendue en " + door[0] + "/" + door[1] + "/" + door[2]);
        assertTrue(state.contains("facing=" + front.name().toLowerCase()) && state.contains("half=lower"),
                state);
        ArchitecturalAudit.Report report = audit(plan);
        assertFalse(report.refused(), front + " : " + report.refusalMessage());
        assertEquals(4, report.metrics().glazedFacades(), "les fenêtres sont attribuées à leur vraie façade");
    }

    @ParameterizedTest
    @EnumSource(Facing.class)
    @DisplayName("Seuil à un bloc du terrain : la marche est posée dans le volume, tournée vers la porte")
    void aRaisedThresholdGetsAStepInsideTheVolume(Facing front) {
        BuildingPlan plan = strict(house(front, 0, OPENINGS));
        ArchitecturalAudit.Report report = audit(plan);
        assertEquals(List.of("ENTRANCE_STEP"), report.blockingCodes(), report.refusalMessage());

        PlanRepair.Outcome outcome = PlanRepair.repair(plan, PlanRepair.Limits.none());
        assertTrue(outcome.accepted(), outcome.toString());
        Blueprint b = BuildingPlanExpander.expand(outcome.plan()).blueprint();
        int[] step = switch (front) {
            case NORTH -> new int[] {6, 0, 2};
            case SOUTH -> new int[] {6, 0, 10};
            case WEST -> new int[] {2, 0, 6};
            case EAST -> new int[] {10, 0, 6};
        };
        String toward = switch (front) {
            case NORTH -> "south";
            case SOUTH -> "north";
            case WEST -> "east";
            case EAST -> "west";
        };
        String state = at(b, step[0], step[1], step[2]);
        assertNotNull(state, front + " : marche attendue en " + step[0] + "/0/" + step[2]);
        assertTrue(state.contains("_stairs[facing=" + toward), state);
    }

    @Test
    @DisplayName("Bâtiment sur toute l'emprise : porte sur la limite comme avant, fondation enterrée faute de place")
    void aFullFootprintBuildingKeepsItsFacadeAndSinksItsFoundation() {
        BuildingPlan plan = strict("""
                id: pleine_emprise
                name: "Pleine emprise"
                front: WEST
                size: {x: 9, y: 8, z: 9}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [8, 0, 8]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [0, 1, 0]
                    to: [8, 4, 8]
                    block: minecraft:spruce_planks
                  - op: box
                    from: [0, 5, 0]
                    to: [8, 5, 8]
                    block: minecraft:spruce_planks
                  - op: door
                    facade: FRONT
                    offset: 4
                  - op: window
                    facade: LEFT
                    offset: 4
                  - op: window
                    facade: RIGHT
                    offset: 4
                  - op: light
                    from: [4, 4, 4]
                    to: [4, 4, 4]
                """);
        Blueprint b = BuildingPlanExpander.expand(plan).blueprint();
        assertTrue(at(b, 0, 1, 4).startsWith("minecraft:oak_door[facing=west,half=lower"), at(b, 0, 1, 4));
        assertEquals(List.of("ENTRANCE_STEP"), audit(plan).blockingCodes());

        PlanRepair.Outcome outcome = PlanRepair.repair(plan, PlanRepair.Limits.none());
        assertTrue(outcome.accepted(), outcome.toString());
        assertEquals(1, outcome.plan().anchorY(), "l'ancre monte au seuil : le bâtiment ne déborde pas");
        assertEquals(9, outcome.plan().sizeX());
        assertTrue(String.join("\n", outcome.changes()).contains("fondation enterrée"));
    }

    @Test
    @DisplayName("Deux corps : la façade est le plus extérieur ; « wall » désigne l'autre ; un mur absent est refusé")
    void twoBodiesAreResolvedExplicitly() {
        String base = """
                id: tour_devant
                name: "Tour devant"
                front: SOUTH
                anchor: {x: 6, y: 1, z: 6}
                size: {x: 13, y: 8, z: 15}
                steps:
                  - op: walls
                    from: [2, 1, 2]
                    to: [10, 4, 10]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [4, 1, 10]
                    to: [8, 4, 13]
                    block: minecraft:stone_bricks
                  - op: door
                    facade: FRONT
                    offset: 6
                """;
        Blueprint outer = BuildingPlanExpander.expand(strict(base)).blueprint();
        assertTrue(at(outer, 6, 1, 13).contains("_door["), "la tour, plus extérieure : " + at(outer, 6, 1, 13));

        Blueprint inner = BuildingPlanExpander.expand(strict(base + "    wall: 10\n")).blueprint();
        assertTrue(at(inner, 6, 1, 10).contains("_door["), "le mur désigné : " + at(inner, 6, 1, 10));

        BuildingPlanExpander.Result refused = BuildingPlanExpander.expand(strict(base + "    wall: 7\n"));
        assertFalse(refused.ok());
        assertTrue(refused.error().contains("vise « wall: 7 »"), refused.error());
        assertTrue(refused.error().contains("z=10") && refused.error().contains("z=13"), refused.error());
    }

    // ---- Gabarit --------------------------------------------------------------------------------

    private static final String OPENINGS = """
              - op: door
                facade: FRONT
                offset: 6
              - op: window
                facade: FRONT
                offset: 4
              - op: window
                facade: LEFT
                offset: 6
              - op: window
                facade: RIGHT
                offset: 6
              - op: window
                facade: BACK
                offset: 6
              - op: light
                from: [6, 4, 6]
                to: [6, 4, 6]
            """;

    /**
     * Une maison de 7 × 7 en retrait de 3 dans un volume de 13 × 13 — exactement la forme de Lily.
     * {@code anchorY} 1 : la fondation affleure le terrain ; 0 : elle le dépasse d'un bloc.
     */
    private static String house(Facing front, int anchorY, String openings) {
        return """
                id: maison_retrait
                name: "Maison en retrait"
                front: %s
                anchor: {x: 6, y: %d, z: 6}
                size: {x: 13, y: 8, z: 13}
                steps:
                  - op: box
                    from: [3, 0, 3]
                    to: [9, 0, 9]
                    block: minecraft:stone_bricks
                  - op: walls
                    from: [3, 1, 3]
                    to: [9, 4, 9]
                    block: minecraft:spruce_planks
                  - op: box
                    from: [3, 5, 3]
                    to: [9, 5, 9]
                    block: minecraft:spruce_planks
                """.formatted(front.name(), anchorY) + openings;
    }
}
