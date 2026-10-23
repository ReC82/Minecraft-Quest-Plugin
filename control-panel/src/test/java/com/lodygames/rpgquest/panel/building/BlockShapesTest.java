package com.lodygames.rpgquest.panel.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.building.BlockShapes.Box;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #786 — chaque type de bloc a-t-il la forme et l'orientation que le jeu lui donne ?
 *
 * <p>Les pavés sont lus en coordonnées du monde (x vers l'est, z vers le sud, 0 à 1 dans la cellule).
 * Les références sont celles des formes du jeu : une marche tournée vers le nord a sa contremarche au
 * nord ; une porte fermée tournée vers le sud est plaquée au nord de sa cellule.</p>
 */
class BlockShapesTest {

    private static final double EPS = 1e-9;

    private static BuildingVoxels.Entry entry(String block, String state) {
        return new BuildingVoxels.Entry('A', block, '#', "full", state);
    }

    private static List<Box> alone(String block, String state) {
        return BlockShapes.boxes(entry(block, state), (dx, dy, dz) -> null);
    }

    /** Le volume du bloc, en cellules : une marche en fait trois quarts. */
    private static double volume(List<Box> boxes) {
        return boxes.stream().mapToDouble(b -> (b.x1() - b.x0()) * (b.y1() - b.y0()) * (b.z1() - b.z0())).sum();
    }

    /** Le bloc couvre-t-il le point (x, y, z) de sa cellule ? */
    private static boolean covers(List<Box> boxes, double x, double y, double z) {
        return boxes.stream().anyMatch(b -> b.x0() <= x && x <= b.x1() && b.y0() <= y && y <= b.y1()
                && b.z0() <= z && z <= b.z1());
    }

    @Test
    @DisplayName("Marche droite : demi-dalle en bas, contremarche du côté où elle regarde")
    void straightStairsRiseOnTheirFacingSide() {
        List<Box> north = alone("oak_stairs", "facing=north,half=bottom,shape=straight");
        assertEquals(.75, volume(north), EPS);
        assertTrue(covers(north, .5, .75, .25), "haut au nord");
        assertFalse(covers(north, .5, .75, .75), "rien en haut au sud");
        assertTrue(covers(north, .5, .25, .75), "demi-dalle en bas");

        List<Box> east = alone("oak_stairs", "facing=east,half=bottom,shape=straight");
        assertTrue(covers(east, .75, .75, .5));
        assertFalse(covers(east, .25, .75, .5));
    }

    @Test
    @DisplayName("Marche retournée : la demi-dalle en haut, la contremarche en bas")
    void upsideDownStairsAreFlipped() {
        List<Box> top = alone("oak_stairs", "facing=south,half=top,shape=straight");
        assertEquals(.75, volume(top), EPS);
        assertTrue(covers(top, .5, .75, .25), "dalle en haut, partout");
        assertTrue(covers(top, .5, .25, .75), "contremarche en bas, au sud");
        assertFalse(covers(top, .5, .25, .25), "rien en bas au nord");
    }

    @Test
    @DisplayName("Coins : extérieur = un quart en haut, intérieur = trois quarts, à gauche ou à droite")
    void cornerStairsFollowTheGame() {
        List<Box> outerLeft = alone("oak_stairs", "facing=north,half=bottom,shape=outer_left");
        assertEquals(.625, volume(outerLeft), EPS);
        assertTrue(covers(outerLeft, .25, .75, .25), "nord-ouest : à gauche quand on regarde le nord");
        assertFalse(covers(outerLeft, .75, .75, .25));

        List<Box> outerRight = alone("oak_stairs", "facing=north,half=bottom,shape=outer_right");
        assertTrue(covers(outerRight, .75, .75, .25), "nord-est");
        assertFalse(covers(outerRight, .25, .75, .25));

        List<Box> innerLeft = alone("oak_stairs", "facing=north,half=bottom,shape=inner_left");
        assertEquals(.875, volume(innerLeft), EPS);
        assertTrue(covers(innerLeft, .25, .75, .75), "sud-ouest en plus de la moitié nord");
        assertFalse(covers(innerLeft, .75, .75, .75), "le sud-est reste la marche");
    }

    @Test
    @DisplayName("Sans état (plugin antérieur à #786) : une marche droite tournée vers le nord, pas un cube")
    void stairsWithoutStateStillLookLikeStairs() {
        assertEquals(.75, volume(alone("oak_stairs", "")), EPS);
    }

    @Test
    @DisplayName("Dalles : basse, haute, double")
    void slabsFollowTheirType() {
        assertEquals(.5, volume(BlockShapes.boxes(new BuildingVoxels.Entry('A', "oak_slab", '/', "bottom"),
                (dx, dy, dz) -> null)), EPS);
        List<Box> top = BlockShapes.boxes(new BuildingVoxels.Entry('A', "oak_slab", '/', "top"), (dx, dy, dz) -> null);
        assertEquals(.5, top.get(0).y0(), EPS);
        assertTrue(BlockShapes.fullCube(new BuildingVoxels.Entry('A', "oak_slab", '/', "full")), "double dalle");
    }

    @Test
    @DisplayName("Chaîne : une tige fine dans l'axe, pas un cube")
    void chainsAreThin() {
        List<Box> vertical = alone("iron_chain", "axis=y");
        assertEquals(1, vertical.size());
        assertEquals(1, vertical.get(0).y1() - vertical.get(0).y0(), EPS);
        assertTrue(vertical.get(0).x1() - vertical.get(0).x0() < .25, "fine");
        List<Box> alongX = alone("chain", "axis=x");
        assertEquals(1, alongX.get(0).x1() - alongX.get(0).x0(), EPS);
        assertFalse(BlockShapes.fullCube(entry("iron_chain", "")));
    }

    @Test
    @DisplayName("Barrière : un poteau seul, des bras vers les barrières et les murs voisins")
    void fencesConnectToTheirNeighbours() {
        assertEquals(1, alone("oak_fence", "").size(), "isolée : le poteau");
        Map<String, BuildingVoxels.Entry> around = new HashMap<>();
        around.put("1,0", entry("oak_fence", ""));
        around.put("0,-1", entry("stone_bricks", ""));
        around.put("-1,0", entry("lantern", ""));
        List<Box> boxes = BlockShapes.boxes(entry("oak_fence", ""),
                (dx, dy, dz) -> dy == 0 ? around.get(dx + "," + dz) : null);
        assertEquals(1 + 2 * 2, boxes.size(), "deux bras (deux traverses chacun) : est et nord, pas l'ouest");
        assertTrue(covers(boxes, .95, .45, .5), "bras vers l'est");
        assertTrue(covers(boxes, .5, .45, .05), "bras vers le nord");
        assertFalse(covers(boxes, .05, .45, .5), "pas de bras vers une lanterne");
    }

    @Test
    @DisplayName("Vitre et barreaux : un panneau fin qui rejoint les murs et les autres vitres")
    void panesConnect() {
        Map<String, BuildingVoxels.Entry> around = new HashMap<>();
        around.put("-1,0", entry("stone_bricks", ""));
        around.put("1,0", entry("glass_pane", ""));
        List<Box> pane = BlockShapes.boxes(entry("glass_pane", ""),
                (dx, dy, dz) -> dy == 0 ? around.get(dx + "," + dz) : null);
        assertTrue(covers(pane, .02, .5, .5) && covers(pane, .98, .5, .5), "d'un mur à l'autre, en x");
        assertFalse(covers(pane, .5, .5, .1), "fine en z");
        List<Box> bars = BlockShapes.boxes(entry("iron_bars", ""), (dx, dy, dz) -> dy == 0 ? around.get(dx + "," + dz) : null);
        assertEquals(volume(pane), volume(bars), EPS);
    }

    @Test
    @DisplayName("Muret : poteau large, bras vers les murets voisins")
    void wallsConnect() {
        List<Box> wall = BlockShapes.boxes(entry("cobblestone_wall", ""),
                (dx, dy, dz) -> dy == 0 && dz == 1 && dx == 0 ? entry("cobblestone_wall", "") : null);
        assertEquals(2, wall.size());
        assertTrue(covers(wall, .5, .5, .98));
    }

    @Test
    @DisplayName("Porte : fermée, plaquée du côté opposé à son orientation ; ouverte, sur sa charnière")
    void doorsFollowFacingAndHinge() {
        Box closed = alone("oak_door", "facing=south,half=lower,hinge=left,open=false").get(0);
        assertEquals(0, closed.z0(), EPS);
        assertEquals(3.0 / 16, closed.z1(), EPS);
        Box openRight = alone("oak_door", "facing=south,half=lower,hinge=right,open=true").get(0);
        assertEquals(0, openRight.x0(), EPS, "charnière à droite : plaquée à l'ouest");
        assertEquals(3.0 / 16, openRight.x1(), EPS);
        Box openLeft = alone("oak_door", "facing=south,half=lower,hinge=left,open=true").get(0);
        assertEquals(1, openLeft.x1(), EPS, "charnière à gauche : plaquée à l'est");
    }

    @Test
    @DisplayName("Trappe : basse ou haute fermée, verticale ouverte ; échelle plaquée au mur")
    void trapdoorsAndLadders() {
        assertEquals(3.0 / 16, alone("oak_trapdoor", "facing=north,half=bottom,open=false").get(0).y1(), EPS);
        assertEquals(13.0 / 16, alone("oak_trapdoor", "facing=north,half=top,open=false").get(0).y0(), EPS);
        Box open = alone("oak_trapdoor", "facing=north,half=bottom,open=true").get(0);
        assertEquals(13.0 / 16, open.z0(), EPS, "ouverte vers le nord : plaquée au sud");
        Box ladder = alone("ladder", "facing=east").get(0);
        assertEquals(0, ladder.x0(), EPS, "tournée vers l'est : contre le mur à l'ouest");
        assertTrue(ladder.x1() < .2);
    }

    @Test
    @DisplayName("Lanterne posée ou suspendue, torche murale décalée vers son mur, cloche")
    void lightsAndBells() {
        double standing = alone("lantern", "hanging=false").get(0).y0();
        double hanging = alone("lantern", "hanging=true").get(0).y0();
        assertTrue(hanging > standing, "suspendue : un peu plus haut");
        assertTrue(volume(alone("lantern", "")) < .1, "petite");
        Box wallTorch = alone("wall_torch", "facing=north").get(0);
        assertTrue(wallTorch.z0() > .5, "tournée vers le nord : contre le mur au sud");
        assertEquals("#7a5a35", wallTorch.color(), "le manche en bois, la flamme jaune au-dessus");
        assertTrue(volume(alone("bell", "attachment=floor,facing=north")) < .5);
        assertTrue(BlockShapes.fullCube(new BuildingVoxels.Entry('A', "glowstone", '*', "full")),
                "une lumière pleine reste un cube");
    }

    @Test
    @DisplayName("L'eau est un liquide translucide, abaissée en surface ; jamais un cube qui cache")
    void waterIsLowerAtTheSurfaceAndTranslucent() {
        BuildingVoxels.Entry water = new BuildingVoxels.Entry('B', "water", '+', "full");
        assertTrue(BlockShapes.boxes(water, (dx, dy, dz) -> null).get(0).y1() < 1, "surface abaissée");
        assertEquals(1, BlockShapes.boxes(water, (dx, dy, dz) -> dy == 1 ? water : null).get(0).y1(), EPS,
                "sous de l'eau : pleine");
        assertTrue(IsometricView.translucent(water));
        assertFalse(BlockShapes.fullCube(water), "l'eau ne cache pas la paroi du puits derrière elle");
        assertTrue(IsometricView.translucent(entry("glass", "")));
        assertTrue(IsometricView.translucent(entry("red_stained_glass_pane", "")));
        assertFalse(IsometricView.translucent(entry("iron_bars", "")));
    }

    @Test
    @DisplayName("Couleurs distinctes : eau ≠ pierre, verre ≠ eau, tronc ≠ planches, pavé ≠ briques de pierre")
    void materialsThatLookAlikeAreTold() {
        String water = IsometricView.color(new BuildingVoxels.Entry('B', "water", '+', "full"));
        String glass = IsometricView.color(new BuildingVoxels.Entry('B', "glass", 'o', "full"));
        String cobble = IsometricView.color(entry("cobblestone", ""));
        String bricks = IsometricView.color(entry("stone_bricks", ""));
        String log = IsometricView.color(entry("oak_log", ""));
        String wood = IsometricView.color(entry("oak_wood", ""));
        String planks = IsometricView.color(entry("oak_planks", ""));
        String stairs = IsometricView.color(entry("oak_stairs", ""));
        String chain = IsometricView.color(entry("iron_chain", ""));
        assertNotEquals(water, cobble);
        assertNotEquals(water, glass);
        assertNotEquals(cobble, bricks);
        assertNotEquals(log, planks);
        assertEquals(log, wood, "bois écorcé = même écorce que le tronc");
        assertEquals(planks, stairs, "les marches de chêne sont des planches de chêne");
        assertNotEquals(chain, IsometricView.color(entry("iron_block", "")));
        assertTrue(distance(water, cobble) > 100, "l'eau est bleue, pas grise");
        assertTrue(distance(cobble, bricks) > 40, "assez écartées pour se lire côte à côte");
        assertTrue(distance(log, planks) > 80);
        assertEquals(IsometricView.color(entry("stripped_oak_log", "")), planks, "tronc écorcé : la couleur du bois");
    }

    private static double distance(String a, String b) {
        int[] x = rgb(a);
        int[] y = rgb(b);
        return Math.sqrt(Math.pow(x[0] - y[0], 2) + Math.pow(x[1] - y[1], 2) + Math.pow(x[2] - y[2], 2));
    }

    private static int[] rgb(String hex) {
        return new int[] {Integer.parseInt(hex.substring(1, 3), 16), Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }
}
