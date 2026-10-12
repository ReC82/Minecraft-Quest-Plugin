package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #288 — lits, éclairages et physique, sur le plan réel qui a montré les défauts (Maison du
 * bibliothécaire, version adc9189d : « box white_bed [6,1,8]→[7,1,8] », lit absent après pose ;
 * lanternes détachables). Déterministe, sans serveur ; la stabilité en jeu reste à constater par le
 * propriétaire (TC-294).
 */
class Stability288Test {

    private static final String REAL = "maison_bibliothecaire_adc9189d.yml";

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

    private static String at(Blueprint b, int x, int y, int z) {
        return b.at(x, y, z).map(BlueprintBlock::state).orElse(null);
    }

    private static ArchitecturalAudit.Report audit(Blueprint b) {
        return ArchitecturalAudit.audit(b, Facing.NORTH, ArchitecturalRules.forKind(StructureKind.DECORATION));
    }

    // ---- Le lit ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Plan réel adc9189d : la « box » de lit est refusée, avec ce qu'il faut écrire à la place")
    void theRealBoxOfBedIsRefused() {
        String refusal = loose(VerticalCirculation282Test.fixture(REAL)).validate().orElseThrow();
        assertTrue(refusal.contains("un lit occupe DEUX cellules"), refusal);
        assertTrue(refusal.contains("minecraft:white_bed"), refusal);
        assertTrue(refusal.contains("op: bed"), refusal);
    }

    @Test
    @DisplayName("op: bed pose un vrai lit : pied, tête, couleur et orientation cohérents, sans assemblage brisé")
    void opBedPlacesARealBed() {
        String yaml = VerticalCirculation282Test.fixture(REAL).replace("""
                  - op: box
                    from: [6, 1, 8]
                    to: [7, 1, 8]
                    block: minecraft:white_bed
                """, """
                  - op: bed
                    from: [6, 1, 8]
                    direction: east
                    block: minecraft:white_bed
                """);
        BuildingPlan plan = loose(yaml);
        assertTrue(plan.validate().isEmpty(), plan.validate().orElse(""));
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertTrue(r.ok(), r.error());
        assertEquals("minecraft:white_bed[facing=east,occupied=false,part=foot]", at(r.blueprint(), 6, 1, 8));
        assertEquals("minecraft:white_bed[facing=east,occupied=false,part=head]", at(r.blueprint(), 7, 1, 8));
        var report = ArchitecturalAudit.audit(r.blueprint(), plan.front(), plan.rules(), r.provenance(),
                plan.anchorY());
        assertFalse(report.blockingCodes().contains("BROKEN_ASSEMBLY"), report.refusalMessage());

        // Sérialisation : relu par le vrai parseur, le plan écrit est identique.
        List<String> problems = new ArrayList<>();
        String written = BuildingPlanYaml.write(plan);
        assertTrue(written.contains("op: bed") && written.contains("direction: east"), written);
        assertEquals(plan, BuildingPlanYaml.read(written, problems).orElseThrow(() -> new AssertionError(problems)));
    }

    @Test
    @DisplayName("Plan réel adc9189d, lit corrigé : les 9 lanternes flottantes sont nommées puis suspendues, plan conforme")
    void theRealPlansFloatingLanternsAreNamedThenHung() {
        String yaml = VerticalCirculation282Test.fixture(REAL).replace("""
                  - op: box
                    from: [6, 1, 8]
                    to: [7, 1, 8]
                    block: minecraft:white_bed
                """, """
                  - op: bed
                    from: [6, 1, 8]
                    direction: east
                    block: minecraft:white_bed
                """);
        BuildingPlan plan = loose(yaml);
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        var report = ArchitecturalAudit.audit(r.blueprint(), plan.front(), plan.rules(), r.provenance(),
                plan.anchorY());
        assertTrue(report.blockingCodes().contains("UNSUPPORTED_BLOCK"), report.blockingCodes().toString());
        assertTrue(report.refusalMessage().contains("9 bloc(s)"), report.refusalMessage());
        assertTrue(report.refusalMessage().contains("lantern en 1/4/1"), report.refusalMessage());

        PlanRepair.Outcome outcome = PlanRepair.repair(plan, new PlanRepair.Limits(11, 8));
        assertTrue(outcome.accepted(), outcome.toString());
        Blueprint fixed = BuildingPlanExpander.expand(outcome.plan()).blueprint();
        long lanterns = fixed.blocks().stream().filter(b -> b.state().startsWith("minecraft:lantern")).count();
        long hanging = fixed.blocks().stream()
                .filter(b -> b.state().equals("minecraft:lantern[hanging=true,waterlogged=false]")).count();
        assertEquals(9, lanterns);
        assertEquals(9, hanging, "toutes suspendues, aucune retirée");
    }

    @ParameterizedTest
    @ValueSource(strings = {"north", "south", "east", "west"})
    @DisplayName("Sur les quatre directions, la tête est dans la direction du « facing » des deux moitiés")
    void theHeadFollowsTheFacingOnEveryDirection(String direction) {
        BuildingPlan plan = strict("""
                id: lit_seul
                name: "Lit"
                kind: decoration
                front: NORTH
                size: {x: 5, y: 3, z: 5}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [4, 0, 4]
                    block: minecraft:oak_planks
                  - op: bed
                    from: [2, 1, 2]
                    direction: %s
                    block: minecraft:pink_bed
                """.formatted(direction));
        Blueprint b = BuildingPlanExpander.expand(plan).blueprint();
        int[] head = switch (direction) {
            case "north" -> new int[] {2, 1};
            case "south" -> new int[] {2, 3};
            case "east" -> new int[] {3, 2};
            default -> new int[] {1, 2};
        };
        assertEquals("minecraft:pink_bed[facing=" + direction + ",occupied=false,part=head]",
                at(b, head[0], 1, head[1]));
        assertTrue(audit(b).blocking().isEmpty(), audit(b).refusalMessage());
    }

    @Test
    @DisplayName("Un lit dont la tête sortirait du volume est refusé avant toute grille")
    void aBedLeavingTheVolumeIsRefused() {
        String refusal = loose("""
                id: lit_bord
                name: "Lit"
                kind: decoration
                front: NORTH
                size: {x: 3, y: 3, z: 3}
                steps:
                  - op: bed
                    from: [2, 1, 1]
                    direction: east
                    block: minecraft:pink_bed
                """).validate().orElseThrow();
        assertTrue(refusal.contains("la tête du lit (3/1/1) sort du volume"), refusal);
    }

    @Test
    @DisplayName("Un demi-lit ou une demi-porte dans la grille est un assemblage brisé, nommé")
    void halfAssembliesAreNamed() {
        Blueprint b = new Blueprint(5, 4, 5, List.of(
                new BlueprintBlock(0, 0, 0, "minecraft:stone"),
                new BlueprintBlock(1, 0, 0, "minecraft:stone"),
                new BlueprintBlock(0, 1, 0, "minecraft:white_bed[facing=north,occupied=false,part=foot]"),
                new BlueprintBlock(1, 1, 0, "minecraft:white_bed[facing=north,occupied=false,part=foot]"),
                new BlueprintBlock(3, 0, 3, "minecraft:stone"),
                new BlueprintBlock(3, 2, 3, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]")));
        var report = audit(b);
        assertTrue(report.blockingCodes().contains("BROKEN_ASSEMBLY"), report.blockingCodes().toString());
        String message = report.refusalMessage();
        assertTrue(message.contains("pied de lit sans sa tête"), message);
        assertTrue(message.contains("moitié haute sans moitié basse"), message);
    }

    // ---- Les lanternes --------------------------------------------------------------------------

    @Test
    @DisplayName("Une lanterne posée avant le toit est suspendue au toit une fois tout posé")
    void aLanternWrittenBeforeTheRoofHangsFromIt() {
        BuildingPlan plan = strict("""
                id: lanterne_toit
                name: "Lanterne"
                kind: decoration
                front: NORTH
                size: {x: 5, y: 5, z: 5}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [4, 0, 4]
                    block: minecraft:stone
                  - op: light
                    from: [2, 3, 2]
                    to: [2, 3, 2]
                    block: minecraft:lantern
                  - op: box
                    from: [0, 4, 0]
                    to: [4, 4, 4]
                    block: minecraft:spruce_planks
                """);
        Blueprint b = BuildingPlanExpander.expand(plan).blueprint();
        assertEquals("minecraft:lantern[hanging=true,waterlogged=false]", at(b, 2, 3, 2));
        assertFalse(audit(b).blockingCodes().contains("UNSUPPORTED_BLOCK"));
    }

    @Test
    @DisplayName("Une lanterne qui flotte est refusée en la nommant ; le moteur la suspend à une chaîne s'il y a un plafond")
    void aFloatingLanternIsRefusedThenHungOnAChain() {
        BuildingPlan plan = strict("""
                id: lanterne_vide
                name: "Lanterne"
                kind: decoration
                front: NORTH
                size: {x: 5, y: 6, z: 5}
                steps:
                  - op: box
                    from: [0, 0, 0]
                    to: [4, 0, 4]
                    block: minecraft:stone
                  - op: light
                    from: [2, 2, 2]
                    to: [2, 2, 2]
                    block: minecraft:lantern
                  - op: box
                    from: [0, 5, 0]
                    to: [4, 5, 4]
                    block: minecraft:spruce_planks
                """);
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        var report = ArchitecturalAudit.audit(r.blueprint(), plan.front(), plan.rules(), r.provenance());
        assertEquals(List.of("UNSUPPORTED_BLOCK"), report.blockingCodes());
        assertTrue(report.refusalMessage().contains("lantern en 2/2/2"), report.refusalMessage());
        assertTrue(report.refusalMessage().contains("instruction 2 (light"), report.refusalMessage());

        PlanRepair.Outcome outcome = PlanRepair.repair(plan, PlanRepair.Limits.none());
        assertTrue(outcome.accepted(), outcome.toString());
        Blueprint fixed = BuildingPlanExpander.expand(outcome.plan()).blueprint();
        assertEquals("minecraft:iron_chain[axis=y,waterlogged=false]", at(fixed, 2, 3, 2));
        assertEquals("minecraft:iron_chain[axis=y,waterlogged=false]", at(fixed, 2, 4, 2));
        assertEquals("minecraft:lantern[hanging=true,waterlogged=false]", at(fixed, 2, 2, 2));
    }

    @Test
    @DisplayName("Lanterne sur lanterne, torche murale et échelle sans mur : refusées")
    void dependentBlocksWithoutTheirSupportAreRefused() {
        Blueprint b = new Blueprint(6, 5, 6, List.of(
                new BlueprintBlock(0, 0, 0, "minecraft:stone"),
                new BlueprintBlock(0, 1, 0, "minecraft:lantern[hanging=false,waterlogged=false]"),
                new BlueprintBlock(0, 2, 0, "minecraft:lantern[hanging=false,waterlogged=false]"),
                new BlueprintBlock(3, 2, 3, "minecraft:wall_torch[facing=north]"),
                new BlueprintBlock(5, 1, 5, "minecraft:ladder[facing=south,waterlogged=false]")));
        var report = audit(b);
        assertEquals(List.of("UNSUPPORTED_BLOCK"), report.blockingCodes());
        String message = report.refusalMessage();
        assertTrue(message.contains("3 bloc(s)"), message);
        assertTrue(message.contains("lantern en 0/2/0"), message);
        assertTrue(message.contains("wall_torch en 3/2/3 : aucun mur derrière lui (en 3/2/4)"), message);
        assertTrue(message.contains("ladder en 5/1/5"), message);
    }
}
