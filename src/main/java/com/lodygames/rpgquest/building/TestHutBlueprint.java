package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.List;

/**
 * La « Hutte de test » : le seul bâtiment de ce lot, entièrement produit par ce code
 * (issue #213, lot « placement »).
 *
 * <h2>À quoi elle sert, et à quoi elle ne sert pas</h2>
 *
 * <p>Elle n'a pas à être belle. Elle doit rendre <strong>visuellement évidentes</strong> les six
 * choses qu'on ne peut vérifier qu'en jeu : l'orientation, les dimensions, l'ancre, la rotation, la
 * hauteur et la position du collage. D'où une façade dissymétrique — porte au centre, deux fenêtres
 * de part et d'autre — et une toiture à deux pans : une hutte à symétrie parfaite ne dirait rien
 * d'une rotation de 180°.</p>
 *
 * <h2>Repère local, et convention d'ancre</h2>
 *
 * <pre>
 *   x : 0 → 6   (7 blocs de large)
 *   z : 0 → 4   (5 blocs de profondeur)   z = 0 est la FAÇADE, celle qui porte la porte
 *   y : 0 → 5   (6 blocs de haut)         y = 0 est la fondation
 * </pre>
 *
 * <p>La paroi {@code z = 0} regarde les {@code -Z}, donc le <strong>nord</strong> : la définition
 * déclare {@code front = NORTH}, et cette déclaration est géométriquement vraie. Déclarer
 * {@code SOUTH} sur la même géométrie aurait caché un demi-tour permanent dans le code de collage —
 * exactement l'offset implicite que ce lot doit éviter.</p>
 *
 * <p><strong>L'ancre est {@code (3, 1, 0)}</strong> : le bloc bas de la porte, au niveau où l'on
 * marche. C'est la convention demandée (« centre de la porte au niveau du sol »), et elle a une
 * conséquence qu'il faut connaître : la fondation en {@code y = 0} se place <em>un bloc sous</em>
 * l'ancre de l'emplacement. C'est voulu — une fondation s'enfonce dans le sol, et l'ancre d'un
 * emplacement est justement la case libre au-dessus du sol cliqué.</p>
 */
public final class TestHutBlueprint {

    public static final String ID = "test_hut_01";
    public static final String NAME = "Hutte de test";
    public static final String SCHEMATIC = "test_hut_01.schem";

    public static final int SIZE_X = 7;
    public static final int SIZE_Y = 6;
    public static final int SIZE_Z = 5;

    public static final int ANCHOR_X = 3;
    public static final int ANCHOR_Y = 1;
    public static final int ANCHOR_Z = 0;

    /** La façade porte la porte, et elle regarde le nord. Voir le javadoc de la classe. */
    public static final Facing FRONT = Facing.NORTH;

    private static final String COBBLESTONE = "minecraft:cobblestone";
    private static final String LOG = "minecraft:oak_log[axis=y]";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String PANE = "minecraft:glass_pane[east=true,north=false,south=false,"
            + "waterlogged=false,west=true]";
    private static final String SLAB = "minecraft:oak_slab[type=bottom,waterlogged=false]";

    private TestHutBlueprint() {
    }

    /**
     * Construit le plan. <strong>Déterministe</strong> : même ordre, mêmes blocs, à chaque appel —
     * c'est ce qui permet au fichier produit d'être comparable d'une génération à l'autre.
     */
    public static Blueprint blueprint() {
        List<BlueprintBlock> blocks = new ArrayList<>();

        // --- y = 0 : fondation pleine en pierre taillée ------------------------------------------
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                blocks.add(new BlueprintBlock(x, 0, z, COBBLESTONE));
            }
        }

        // --- y = 1 à 3 : murs, porte, fenêtres ---------------------------------------------------
        // Seul le périmètre est bâti : l'intérieur n'est pas listé, donc il est vide.
        for (int y = 1; y <= 3; y++) {
            for (int x = 0; x < SIZE_X; x++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    boolean perimeter = x == 0 || x == SIZE_X - 1 || z == 0 || z == SIZE_Z - 1;
                    if (!perimeter) {
                        continue;
                    }
                    String state = wallState(x, y, z);
                    if (state != null) {
                        blocks.add(new BlueprintBlock(x, y, z, state));
                    }
                }
            }
        }

        // --- y = 4 et 5 : toit à deux pans, pente selon Z ----------------------------------------
        // L'arête court selon X, en z = 2. Les marches regardent vers l'extérieur de chaque pan,
        // ce qui donne une silhouette lisible de loin — donc une rotation lisible de loin.
        for (int x = 0; x < SIZE_X; x++) {
            blocks.add(new BlueprintBlock(x, 4, 0, stairs("north")));
            blocks.add(new BlueprintBlock(x, 4, 1, PLANKS));
            blocks.add(new BlueprintBlock(x, 4, 2, PLANKS));
            blocks.add(new BlueprintBlock(x, 4, 3, PLANKS));
            blocks.add(new BlueprintBlock(x, 4, 4, stairs("south")));

            blocks.add(new BlueprintBlock(x, 5, 1, stairs("north")));
            blocks.add(new BlueprintBlock(x, 5, 2, SLAB));
            blocks.add(new BlueprintBlock(x, 5, 3, stairs("south")));
        }

        return new Blueprint(SIZE_X, SIZE_Y, SIZE_Z, blocks);
    }

    /**
     * L'état du mur à cette position, ou {@code null} pour laisser de l'air (l'ouverture de la
     * porte au-dessus du battant bas, par exemple).
     */
    private static String wallState(int x, int y, int z) {
        boolean facade = z == 0;

        // La porte : deux battants au centre exact de la façade, aux hauteurs 1 et 2.
        if (facade && x == ANCHOR_X && y <= 2) {
            return door(y == 1 ? "lower" : "upper");
        }
        // Les deux fenêtres, à hauteur du regard, de part et d'autre de la porte.
        if (facade && y == 2 && (x == 1 || x == SIZE_X - 2)) {
            return PANE;
        }
        // Les quatre poteaux d'angle, qui donnent à la hutte ses arêtes visibles.
        boolean corner = (x == 0 || x == SIZE_X - 1) && (z == 0 || z == SIZE_Z - 1);
        return corner ? LOG : PLANKS;
    }

    private static String stairs(String facing) {
        return "minecraft:oak_stairs[facing=" + facing
                + ",half=bottom,shape=straight,waterlogged=false]";
    }

    private static String door(String half) {
        return "minecraft:oak_door[facing=north,half=" + half
                + ",hinge=left,open=false,powered=false]";
    }

    /**
     * La définition qui accompagne le plan.
     *
     * <p>Dimensions et ancre sont <strong>lues sur le plan</strong>, pas recopiées à la main : c'est
     * ce qui garantit que la définition affichée dans le panel décrit bien le fichier produit. Une
     * divergence entre les deux serait invisible jusqu'au collage.</p>
     */
    public static BuildingDefinition definition() {
        Blueprint blueprint = blueprint();
        return new BuildingDefinition(ID, NAME,
                "Hutte de validation, produite par le code du plugin. Façade dissymétrique "
                        + "(porte centrée, deux fenêtres) et toit à deux pans, pour que "
                        + "l'orientation et la rotation se constatent d'un coup d'œil.",
                SCHEMATIC,
                blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ(),
                ANCHOR_X, ANCHOR_Y, ANCHOR_Z,
                FRONT, blueprint.materials(), 1);
    }
}
