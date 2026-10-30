package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.BuildingLibrary;
import com.lodygames.rpgquest.building.SchematicWorkshop;
import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.Facing;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * #747 (retour du 29/10/2026, maison du botaniste #658) — « gable minecraft:glass » recevait des états
 * d'escalier ({@code facing/half/shape/waterlogged}) que le verre n'a pas : trois refus PLAN_INVALID de suite.
 *
 * <p>Le toit reçoit désormais l'état que le registre 1.21.11 admet pour son matériau : marches orientées pour
 * un escalier, dalle basse pour une dalle, état simple pour un bloc plein sans escalier. Les plans réels du
 * projet « Maison du Botaniste » sont rejoués sans appel payant.</p>
 */
class GableMaterial747Test {

    @TempDir
    Path tempDir;

    private static BuildingPlan plan(String material) {
        return new BuildingPlan("test_gable", "Toit", 7, 6, 5, Facing.WEST, 3, 0, 2, List.of(
                PlanStep.of(PlanStep.Kind.GABLE, PlanBox.of(0, 0, 0, 6, 2, 4), material, "x")));
    }

    private static List<String> states(Blueprint blueprint) {
        return blueprint.blocks().stream().map(b -> b.state()).distinct().toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"minecraft:glass", "minecraft:white_stained_glass", "minecraft:tinted_glass"})
    void aFullBlockWithoutStairsKeepsItsSimpleStateAndTheSlope(String material) {
        var result = BuildingPlanExpander.expand(plan(material));

        assertTrue(result.ok(), result.error());
        assertEquals(List.of(material), states(result.blueprint()));
        // La pente est conservée : un rang plus haut par pas vers le faîte, en gradins pleins.
        assertTrue(result.blueprint().at(0, 0, 0).isPresent());
        assertTrue(result.blueprint().at(0, 1, 1).isPresent());
        assertTrue(result.blueprint().at(0, 2, 2).isPresent(), "le faîte");
        assertTrue(result.blueprint().at(0, 1, 3).isPresent());
        assertTrue(result.blueprint().at(0, 0, 4).isPresent());
    }

    @Test
    void aSlabRoofGetsValidSlabStates() {
        var result = BuildingPlanExpander.expand(plan("minecraft:spruce_slab"));

        assertTrue(result.ok(), result.error());
        // Rangs de toit en dalle basse ; sans « fill », les pignons gardent l'état par défaut de la dalle.
        assertEquals("minecraft:spruce_slab[type=bottom,waterlogged=false]",
                result.blueprint().at(0, 2, 2).orElseThrow().state());
        for (String state : states(result.blueprint())) {
            assertTrue(BlockCatalog.vanilla().refusal(state).isEmpty(), state);
        }
    }

    @Test
    void aStairRoofStillGetsOrientedSteps() {
        var result = BuildingPlanExpander.expand(plan("minecraft:spruce_stairs"));

        assertTrue(result.ok(), result.error());
        String low = result.blueprint().at(0, 0, 0).orElseThrow().state();
        String high = result.blueprint().at(0, 0, 4).orElseThrow().state();
        assertEquals("minecraft:spruce_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]", low);
        assertEquals("minecraft:spruce_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", high);
    }

    @Test
    void anUnknownBlockIsStillRefusedByTheRegistry() {
        var result = BuildingPlanExpander.expand(plan("minecraft:glass_stairs"));

        assertFalse(result.ok());
        assertTrue(result.error().contains("minecraft:glass_stairs"), result.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"96224470", "10e84a38"})
    void theBotanistPlansAreNoLongerRefusedForTheirGlassRoof(String version) {
        BuildingLibrary library = new BuildingLibrary(tempDir.resolve("buildings"), Logger.getLogger("test"));
        library.reload();
        BuildingGenerator generator = new BuildingGenerator(library,
                new SchematicWorkshop(new BuildingGeneratorTest.FakeGateway(), Logger.getLogger("test")),
                tempDir.resolve("buildings"), Logger.getLogger("test"));

        BuildingGenerator.Verdict v = generator.inspect(
                VerticalCirculation282Test.fixture("botaniste_747_" + version + ".yml"),
                new PlanRepair.Limits(17, 21, 11));

        assertFalse(v.codes().contains("PLAN_INVALID"), v.code() + " " + v.message());
        assertFalse(v.message().contains("minecraft:glass["), v.message());
    }
}
