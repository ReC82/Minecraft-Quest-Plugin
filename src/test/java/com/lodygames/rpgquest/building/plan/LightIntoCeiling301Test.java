package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #301 — Maison de Lily : « light » visait y=5, la couche du plafond. Chaque lanterne
 * remplaçait un bloc du plafond (lanterne encastrée dans un trou), puis la réparation la suspendait
 * par une longue chaîne à travers les combles. Une source de lumière ne perce plus un bloc plein.
 */
class LightIntoCeiling301Test {

    private static final String PLAN = """
            id: lumiere_301
            name: "Lumière sous plafond"
            kind: habitable
            front: SOUTH
            size:
              x: 9
              y: 9
              z: 9
            steps:
              - op: box
                from: [0, 0, 0]
                to: [8, 0, 8]
                block: minecraft:stone_bricks
              - op: walls
                from: [0, 1, 0]
                to: [8, 4, 8]
                block: minecraft:stone_bricks
              - op: box
                from: [0, 5, 0]
                to: [8, 5, 8]
                block: minecraft:spruce_planks
              - op: light
                from: [2, 5, 2]
                to: [6, 5, 6]
                spacing: 4
                block: minecraft:lantern
              - op: door
                facade: FRONT
                offset: 4
                block: minecraft:oak_door
            """;

    private static String at(Blueprint blueprint, int x, int y, int z) {
        for (BlueprintBlock b : blueprint.blocks()) {
            if (b.x() == x && b.y() == y && b.z() == z) {
                return b.state();
            }
        }
        return null;
    }

    private static BuildingPlanExpander.Result expand() {
        List<String> problems = new ArrayList<>();
        BuildingPlan plan = BuildingPlanYaml.readUnvalidated(PLAN, problems)
                .orElseThrow(() -> new AssertionError(problems));
        BuildingPlanExpander.Result r = BuildingPlanExpander.expand(plan);
        assertTrue(r.ok(), r.error());
        return r;
    }

    @Test
    void aLanternAimedIntoTheCeilingHangsJustBelowIt() {
        Blueprint blueprint = expand().blueprint();

        for (int[] c : new int[][] {{2, 2}, {6, 2}, {2, 6}, {6, 6}}) {
            assertEquals("minecraft:spruce_planks", at(blueprint, c[0], 5, c[1]),
                    "le plafond reste fermé en " + c[0] + "/5/" + c[1]);
            String lantern = at(blueprint, c[0], 4, c[1]);
            assertTrue(lantern != null && lantern.startsWith("minecraft:lantern") && lantern.contains("hanging=true"),
                    "lanterne suspendue sous le plafond en " + c[0] + "/4/" + c[1] + " : " + lantern);
        }
    }

    @Test
    void noLanternIsLeftUnsupportedSoNoChainIsNeeded() {
        BuildingPlanExpander.Result r = expand();

        assertTrue(Stability.unsupported(AuditGrid.of(r.blueprint())).isEmpty(),
                "aucune lanterne flottante : la réparation n'a pas à tirer de chaîne vers le toit");
        assertFalse(r.blueprint().blocks().stream().anyMatch(b -> b.state().contains("chain")));
    }
}
