package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Issue #568 — le volume de passage d'une arche, lu depuis l'aperçu et dessiné dans la vue 3D. */
class PassageVolumeTest {

    private static final BuildingVoxels.Entry STONE = new BuildingVoxels.Entry('A', "stone_bricks", '#', "full");

    /** Une arche de 3 × 3, 1 de profondeur : passage d'un bloc au milieu, deux de haut. */
    private static final BuildingVoxels ARCH = new BuildingVoxels(3, 3, 1, Map.of('A', STONE),
            List.of(List.of("A.A"), List.of("A.A"), List.of("AAA")), "");

    private static Map<String, Object> details(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return Map.of("passage", Map.of("minX", minX, "minY", minY, "minZ", minZ, "maxX", maxX, "maxY", maxY,
                "maxZ", maxZ));
    }

    @Test
    @DisplayName("Le passage transmis est relu ; absent, mal formé ou hors de la grille, il est ignoré")
    void thePassageIsReadOnlyWhenItFitsTheGrid() {
        assertEquals(Optional.of(new PassageVolume(1, 0, 0, 1, 1, 0)),
                PassageVolume.from(details(1, 0, 0, 1, 1, 0), ARCH));
        assertEquals(Optional.empty(), PassageVolume.from(Map.of(), ARCH));
        assertEquals(Optional.empty(), PassageVolume.from(Map.of("passage", Map.of("minX", 1)), ARCH));
        assertEquals(Optional.empty(), PassageVolume.from(details(1, 0, 0, 3, 1, 0), ARCH), "hors de la grille");
        assertEquals(Optional.empty(), PassageVolume.from(details(1, 1, 0, 1, 0, 0), ARCH), "bornes inversées");
    }

    @Test
    @DisplayName("Largeur et sens de traversée suivent la façade ; l'écart avec le projet est signalé")
    void widthAndCrossingFollowTheFront() {
        PassageVolume p = new PassageVolume(2, 1, 0, 4, 3, 2);
        assertEquals(3, p.width("NORTH"));
        assertTrue(p.describe("SOUTH").contains("3 blocs de large, 3 de haut, 3 de profondeur"), p.describe("SOUTH"));
        assertTrue(p.describe("NORTH").contains("du nord au sud"));
        PassageVolume side = new PassageVolume(0, 1, 2, 0, 2, 3);
        assertEquals(2, side.width("EAST"));
        assertTrue(side.describe("WEST").contains("d'est en ouest"));
        assertEquals("", p.mismatch("NORTH", 3, 3));
        assertEquals("", p.mismatch("NORTH", 0, 0), "rien demandé, rien à signaler");
        assertTrue(p.mismatch("NORTH", 3, 4).contains("3 × 3 (largeur × hauteur), le projet en demande 3 × 4"));
    }

    @Test
    @DisplayName("La vue 3D dessine le volume, au bon endroit dans chaque orientation, tronqué par la coupe")
    void theIsometricViewDrawsTheVolume() {
        PassageVolume corner = new PassageVolume(0, 0, 0, 0, 0, 0);
        String plain = IsometricView.svg(ARCH, 0, 99, "t");
        assertTrue(!plain.contains("iso-passage"), "sans passage, rien de plus");

        String south = IsometricView.svg(ARCH, 0, 99, "t", corner);
        String overlay = south.substring(south.indexOf("<g class=\"iso-passage\""));
        assertEquals(6, overlay.split("<path ", -1).length - 1, "trois arêtes cachées, trois faces");
        assertTrue(overlay.contains("M0 -8L8 -4"), "dessus de la cellule x = 0 vue du sud-est : " + overlay);
        // Vu du nord-ouest, la même colonne passe à l'autre bout : on tourne, on ne reflète pas.
        String north = IsometricView.svg(ARCH, 2, 99, "t", corner);
        assertTrue(north.substring(north.indexOf("iso-passage")).contains("M16 0L24 4"), north);

        // Coupé sous le passage : il disparaît avec ce qui est retiré.
        assertTrue(!IsometricView.svg(ARCH, 0, 0, "t", new PassageVolume(1, 1, 0, 1, 1, 0)).contains("iso-passage"));
    }
}
