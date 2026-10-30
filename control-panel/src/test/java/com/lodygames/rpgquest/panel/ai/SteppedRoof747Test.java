package com.lodygames.rpgquest.panel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.building.BuildingVoxels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #747 (retour du 29/10/2026, maison du botaniste #658) — un « gable minecraft:glass » est désormais compilé
 * en gradins de verre plein, faute d'escalier de verre. Ce toit doit compter comme un toit en pente ; un toit
 * plat, même bordé d'un parapet, non.
 */
class SteppedRoof747Test {

    /** Une grille de 5 × 5 colonnes dont les sommets sont donnés par rangée z, en {@code block}. */
    private static BuildingVoxels grid(String block, int[] topByZ, boolean parapet) {
        int height = 8;
        List<List<String>> layers = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            List<String> rows = new ArrayList<>();
            for (int z = 0; z < topByZ.length; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < 5; x++) {
                    boolean edge = parapet && (x == 0 || x == 4 || z == 0 || z == topByZ.length - 1);
                    int t = topByZ[z] + (edge ? 1 : 0);
                    row.append(y <= t ? 'g' : '.');
                }
                rows.add(row.toString());
            }
            layers.add(rows);
        }
        return BuildingVoxels.from(Map.of("voxels", Map.of(
                "palette", List.of(Map.of("symbol", "g", "block", block, "role", "S", "shape", "", "state", block)),
                "layers", layers)));
    }

    @Test
    void aSteppedGlassRoofIsAPitchedRoof() {
        BuildingVoxels v = grid("minecraft:glass", new int[] {3, 4, 5, 4, 3}, false);
        assertTrue(v.present(), v.toString());
        int[] c = CompiledMeasures.of(v).orElseThrow().pitchedColumns();
        assertArrayEquals(new int[] {25, 25}, c, "égouts, versants et faîte : toutes les colonnes");
    }

    @Test
    void aSteppedRoofWithAFlatRidgeIsStillPitched() {
        int[] c = CompiledMeasures.of(grid("minecraft:glass", new int[] {3, 4, 4, 3}, false)).orElseThrow()
                .pitchedColumns();
        assertEquals(20, c[0]);
    }

    @Test
    void aFlatRoofIsNotPitchedEvenWithAParapet() {
        assertEquals(0, CompiledMeasures.of(grid("minecraft:glass", new int[] {4, 4, 4, 4, 4}, false)).orElseThrow()
                .pitchedColumns()[0]);
        int[] parapet = CompiledMeasures.of(grid("minecraft:stone_bricks", new int[] {4, 4, 4, 4, 4}, true))
                .orElseThrow().pitchedColumns();
        assertFalse(parapet[0] * 100 >= parapet[1] * CompiledMeasures.PITCHED_PERCENT, parapet[0] + "/" + parapet[1]);
    }
}
