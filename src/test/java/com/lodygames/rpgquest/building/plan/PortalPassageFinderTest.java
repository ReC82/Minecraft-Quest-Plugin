package com.lodygames.rpgquest.building.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.building.model.PortalPassage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #568 : la zone de passage d'une arche est relevée sur la grille, à partir de l'ancre. */
class PortalPassageFinderTest {

    private static final String STONE = "minecraft:stone_bricks";

    /**
     * Arche sur un socle : sol en y=0, piliers en x=0 et x=4, linteau en y=5, sur {@code depth} blocs de
     * profondeur en Z. Passage attendu : x 1..3, y 1..4, toute la profondeur.
     */
    private static Blueprint arch(int depth) {
        List<BlueprintBlock> blocks = new ArrayList<>();
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < 5; x++) {
                blocks.add(new BlueprintBlock(x, 0, z, STONE));
                blocks.add(new BlueprintBlock(x, 5, z, STONE));
            }
            for (int y = 1; y < 5; y++) {
                blocks.add(new BlueprintBlock(0, y, z, STONE));
                blocks.add(new BlueprintBlock(4, y, z, STONE));
            }
        }
        return new Blueprint(5, 6, depth, blocks);
    }

    @Test
    void thePassageIsTheThroughOpeningAroundTheAnchor() {
        assertEquals(new PortalPassage(1, 1, 0, 3, 4, 1),
                PortalPassageFinder.find(arch(2), 2, 1, 0, Facing.NORTH).orElseThrow());
    }

    @Test
    void anArchFacingEastCrossesAlongX() {
        // La même arche lue face à l'est : on la traverse en X ; la largeur est donc en Z (0..1, profondeur 2).
        List<BlueprintBlock> blocks = new ArrayList<>();
        for (BlueprintBlock b : arch(2).blocks()) {
            blocks.add(new BlueprintBlock(b.z(), b.y(), b.x(), b.state()));
        }
        Blueprint turned = new Blueprint(2, 6, 5, blocks);
        assertEquals(new PortalPassage(0, 1, 1, 1, 4, 3),
                PortalPassageFinder.find(turned, 0, 1, 2, Facing.EAST).orElseThrow());
    }

    @Test
    void anAnchorInTheFloorSlabStartsThePassageJustAbove() {
        assertEquals(new PortalPassage(1, 1, 0, 3, 4, 1),
                PortalPassageFinder.find(arch(2), 2, 0, 0, Facing.NORTH).orElseThrow());
    }

    @Test
    void noPassageWhenTheAnchorIsInAPillarOrTheOpeningIsBlocked() {
        assertTrue(PortalPassageFinder.find(arch(2), 0, 1, 0, Facing.NORTH).isEmpty(), "ancre dans un pilier");
        List<BlueprintBlock> blocks = new ArrayList<>(arch(2).blocks());
        blocks.add(new BlueprintBlock(2, 2, 1, STONE)); // bouche le fond du passage au niveau de l'ancre
        blocks.add(new BlueprintBlock(1, 2, 1, STONE));
        blocks.add(new BlueprintBlock(3, 2, 1, STONE));
        assertTrue(PortalPassageFinder.find(new Blueprint(5, 6, 2, blocks), 2, 1, 0, Facing.NORTH).isEmpty());
    }
}
