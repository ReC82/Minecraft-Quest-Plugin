package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Issue #273 — la grille reçue du plugin est lue telle quelle, ou refusée entière avec son motif. */
class BuildingVoxelsTest {

    private static Map<String, Object> details(Object palette, Object layers, String reason) {
        return Map.of("voxels", Map.of("palette", palette, "layers", layers, "omittedReason", reason));
    }

    private static final List<Map<String, String>> PALETTE = List.of(
            Map.of("symbol", "A", "block", "stone_bricks", "role", "#", "shape", "full"));

    @Test
    void aWellFormedGridIsReadCellByCell() {
        BuildingVoxels v = BuildingVoxels.from(details(PALETTE,
                List.of(List.of("AA", ".."), List.of("A.", "..")), ""));
        assertTrue(v.present());
        assertEquals(2, v.sizeX());
        assertEquals(2, v.sizeY());
        assertEquals(2, v.sizeZ());
        assertEquals("stone_bricks", v.at(1, 0, 0).block());
        assertNull(v.at(1, 1, 0), "l'air n'est pas un bloc");
        assertNull(v.at(5, 0, 0), "hors de la grille non plus");
    }

    @Test
    void anOlderPluginSaysAnUpdateIsNeeded() {
        BuildingVoxels v = BuildingVoxels.from(Map.of("plans", List.of()));
        assertFalse(v.present());
        assertTrue(v.omittedReason().contains("version plus récente du plugin"), v.omittedReason());
    }

    @Test
    void theServerReasonIsKeptWhenTheGridIsOmitted() {
        BuildingVoxels v = BuildingVoxels.from(details(List.of(), List.of(), "Bâtiment trop grand."));
        assertFalse(v.present());
        assertEquals("Bâtiment trop grand.", v.omittedReason());
    }

    @Test
    void aMalformedGridIsRefusedWholeRatherThanDrawnInPart() {
        assertFalse(BuildingVoxels.from(details(PALETTE, List.of(List.of("AA"), List.of("A")), "")).present(),
                "rangées de longueurs différentes");
        assertFalse(BuildingVoxels.from(details(PALETTE, List.of(List.of("AZ")), "")).present(),
                "symbole absent de la palette");
        assertFalse(BuildingVoxels.from(details(PALETTE, List.of(List.of("A"), List.of("A", "A")), "")).present(),
                "couches de tailles différentes");
    }
}
