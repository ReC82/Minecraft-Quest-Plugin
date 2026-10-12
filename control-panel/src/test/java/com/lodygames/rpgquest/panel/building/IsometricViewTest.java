package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #273 — la vue en 3D dessine-t-elle exactement les blocs reçus, au bon endroit ?
 *
 * <p>Ces tests lisent le SVG produit : nombre de faces, position des faces, transparence. Une vue qui
 * dessinerait une face cachée, oublierait un bloc ou refléterait le bâtiment au lieu de le tourner
 * serait prise en défaut ici.</p>
 */
class IsometricViewTest {

    private static final BuildingVoxels.Entry STONE = new BuildingVoxels.Entry('A', "stone_bricks", '#', "full");
    private static final BuildingVoxels.Entry GLASS = new BuildingVoxels.Entry('B', "glass_pane", 'o', "full");
    private static final BuildingVoxels.Entry LAMP = new BuildingVoxels.Entry('C', "lantern", '*', "full");
    private static final BuildingVoxels.Entry SLAB = new BuildingVoxels.Entry('D', "oak_slab", '/', "bottom");

    private static BuildingVoxels grid(List<List<String>> layers, BuildingVoxels.Entry... entries) {
        Map<Character, BuildingVoxels.Entry> palette = new java.util.HashMap<>();
        for (BuildingVoxels.Entry e : entries) {
            palette.put(e.symbol(), e);
        }
        return new BuildingVoxels(layers.get(0).get(0).length(), layers.size(), layers.get(0).size(),
                palette, layers, "");
    }

    private static int faces(String svg) {
        return svg.split("<path ", -1).length - 1;
    }

    @Test
    void aSingleBlockShowsItsThreeVisibleFaces() {
        String svg = IsometricView.svg(grid(List.of(List.of("A")), STONE), 0, 99, "t");
        assertEquals(3, faces(svg), "dessus, droite, gauche");
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""), svg);
        assertTrue(svg.contains("<title>t</title>"), "titre accessible");
    }

    @Test
    void aFaceHiddenByAnOpaqueNeighbourIsNotDrawn() {
        // Deux blocs empilés : le dessus du bloc du bas est caché.
        String stacked = IsometricView.svg(grid(List.of(List.of("A"), List.of("A")), STONE), 0, 99, "t");
        assertEquals(5, faces(stacked));
        // Deux blocs côte à côte en x : la face droite du premier est cachée.
        String side = IsometricView.svg(grid(List.of(List.of("AA")), STONE), 0, 99, "t");
        assertEquals(5, faces(side));
    }

    @Test
    void glassIsTranslucentAndLetsTheFacesBehindItShow() {
        String svg = IsometricView.svg(grid(List.of(List.of("AB")), STONE, GLASS), 0, 99, "t");
        assertEquals(6, faces(svg), "la vitre ne cache pas la face du mur derrière elle");
        assertEquals(3, svg.split("fill-opacity", -1).length - 1, "les trois faces de la vitre sont translucides");
    }

    @Test
    void cuttingAtALevelRemovesEverythingAboveAndShowsTheTopOfTheCut() {
        BuildingVoxels v = grid(List.of(List.of("A"), List.of("A"), List.of("A")), STONE);
        assertEquals(7, faces(IsometricView.svg(v, 0, 99, "t")));
        assertEquals(3, faces(IsometricView.svg(v, 0, 0, "t")), "un seul bloc, dessus compris");
    }

    @Test
    void theViewTurnsAroundTheBuildingWithoutMirroringIt() {
        // Un mur à l'ouest (x = 0), une vitre à l'est (x = 1).
        BuildingVoxels v = grid(List.of(List.of("AB")), STONE, GLASS);
        // Depuis le sud-est, l'est est à droite : la vitre est le cube de droite, dessus en (8, -4).
        assertTrue(firstTranslucentFace(IsometricView.svg(v, 0, 99, "t")).startsWith("M8 -4"));
        // Depuis le nord-ouest, on a fait le demi-tour : la vitre passe au fond, en (0, -8).
        assertTrue(firstTranslucentFace(IsometricView.svg(v, 2, 99, "t")).startsWith("M0 -8"));
        // Les quatre vues dessinent les deux blocs. Quand la vitre est derrière le mur (vues 1 et 2), sa
        // face collée au mur est cachée — et seulement celle-là.
        int[] translucent = {3, 2, 2, 3};
        int[] total = {6, 5, 5, 6};
        for (int view = 0; view < 4; view++) {
            String svg = IsometricView.svg(v, view, 99, "t");
            assertEquals(translucent[view], svg.split("fill-opacity", -1).length - 1, "vue " + view);
            assertEquals(total[view], faces(svg), "vue " + view);
        }
    }

    private static String firstTranslucentFace(String svg) {
        int at = svg.indexOf("fill-opacity");
        int start = svg.lastIndexOf("d=\"", at) + 3;
        return svg.substring(start, svg.indexOf('"', start));
    }

    @Test
    void slabsAndLightsAreDrawnSmallerThanACube() {
        String slab = IsometricView.svg(grid(List.of(List.of("D")), SLAB), 0, 99, "t");
        assertTrue(slab.contains("M0 -4L8 0"), "le dessus d'une dalle basse est à mi-hauteur : " + slab);
        String lamp = IsometricView.svg(grid(List.of(List.of("C")), LAMP), 0, 99, "t");
        assertTrue(lamp.contains("#ffd75e"), "une lumière est jaune");
    }

    @Test
    void theDefaultViewShowsTheEntranceOnTheLeft() {
        assertEquals(0, IsometricView.defaultView("SOUTH"));
        assertEquals(1, IsometricView.defaultView("WEST"));
        assertEquals(2, IsometricView.defaultView("NORTH"));
        assertEquals(3, IsometricView.defaultView("EAST"));
        for (String front : List.of("NORTH", "EAST", "SOUTH", "WEST")) {
            assertTrue(IsometricView.frontHint(front, IsometricView.defaultView(front)).contains("à gauche"), front);
        }
        assertTrue(IsometricView.frontHint("SOUTH", 2).contains("de l'autre côté"));
    }

    @Test
    void coloursFollowTheMaterialNotTheTexture() {
        assertEquals("#b48c56", IsometricView.color(new BuildingVoxels.Entry('A', "oak_planks", '#', "full")));
        assertEquals("#4a3320", IsometricView.color(new BuildingVoxels.Entry('A', "dark_oak_planks", '#', "full")),
                "le chêne noir n'est pas pris pour du chêne");
        assertEquals("#a12722", IsometricView.color(new BuildingVoxels.Entry('A', "red_wool", '#', "full")));
        assertEquals("#7f7f7f", IsometricView.color(new BuildingVoxels.Entry('A', "stone_bricks", '#', "full")),
                "des briques de pierre sont grises, pas rouges");
        assertEquals("#9c4f3c", IsometricView.color(new BuildingVoxels.Entry('A', "bricks", '#', "full")));
        assertEquals("#9a9a9a", IsometricView.color(new BuildingVoxels.Entry('A', "mystery_block", '#', "full")));
    }

    @Test
    void theLegendListsTheMostUsedMaterialsFirst() {
        BuildingVoxels v = grid(List.of(List.of("AAB")), STONE, GLASS);
        List<IsometricView.Swatch> legend = IsometricView.legend(v, 10);
        assertEquals("stone bricks", legend.get(0).label());
        assertEquals("glass pane", legend.get(1).label());
    }

    @Test
    void anAbsentGridDrawsNothing() {
        assertEquals("", IsometricView.svg(BuildingVoxels.none("x"), 0, 0, "t"));
    }

    @Test
    void theTitleIsEscaped() {
        String svg = IsometricView.svg(grid(List.of(List.of("A")), STONE), 0, 99, "<script>\"");
        assertFalse(svg.contains("<script>"));
    }
}
