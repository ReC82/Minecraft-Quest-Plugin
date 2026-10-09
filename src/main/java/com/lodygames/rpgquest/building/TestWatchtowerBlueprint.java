package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.BuildingDefinition;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.List;

/**
 * La « Tour de garde de test » : la deuxième structure du moteur de construction (issue #234).
 *
 * <h2>Pourquoi une tour, et pourquoi celle-ci</h2>
 *
 * <p>La hutte de #213 a prouvé que la chaîne fonctionne. Elle ne prouve pas qu'elle fonctionne sur
 * une structure <strong>exigeante</strong> : un seul niveau, pas d'escalier, cinq blocs de haut, et
 * une symétrie gauche/droite qui laisse passer une erreur de 90°. Cette tour est conçue pour mettre
 * en difficulté exactement ce que la hutte ne testait pas :</p>
 *
 * <ul>
 *   <li><strong>la hauteur</strong> — 14 blocs, donc les limites verticales du monde deviennent une
 *       contrainte réelle et non théorique ;</li>
 *   <li><strong>plusieurs niveaux</strong> avec planchers et trémies, donc des blocs omis
 *       volontairement : un collage qui « remplirait les trous » se verrait immédiatement ;</li>
 *   <li><strong>un escalier intérieur en spirale</strong>, dont chaque volée regarde une direction
 *       différente : si le moteur de rotation se trompait de sens, l'escalier ne monterait plus et
 *       la tour deviendrait invisitable — c'est un test de rotation qu'on constate avec ses
 *       jambes ;</li>
 *   <li><strong>des blocs orientés</strong> de trois familles (marches, porte, torches murales),
 *       qui doivent tourner <em>avec</em> la structure ;</li>
 *   <li><strong>les quatre faces distinctes</strong> — voir ci-dessous.</li>
 * </ul>
 *
 * <h2>Les quatre faces sont reconnaissables, et c'est le point</h2>
 *
 * <p>Le ticket demande qu'on voie immédiatement NORTH de SOUTH de EAST de WEST. Une tour carrée à
 * quatre faces identiques ne dirait rien d'une rotation de 90°, et une tour symétrique rien d'une
 * rotation de 180°. Chaque face porte donc une signature différente :</p>
 *
 * <pre>
 *   z = 0  (NORD, la FAÇADE) : la porte, deux fenêtres, deux torches murales de part et d'autre
 *   x = 8  (EST)             : une colonne de meurtrières, une par niveau
 *   z = 8  (SUD)             : une large ouverture de guet au dernier niveau, sans rien plus bas
 *   x = 0  (OUEST)           : pleine et aveugle, bâtie en moellon brut sur toute sa hauteur
 *                              quand les autres sont en pierre taillée — la face la plus massive,
 *                              reconnaissable de loin sans distinguer aucune ouverture
 * </pre>
 *
 * <h2>Repère local et convention d'ancre</h2>
 *
 * <pre>
 *   x : 0 → 8   (9 blocs de large)
 *   z : 0 → 8   (9 blocs de profondeur)   z = 0 est la FAÇADE
 *   y : 0 → 13  (14 blocs de haut)        y = 0 est la fondation
 * </pre>
 *
 * <p>Même convention que la hutte, et pour la même raison : la paroi {@code z = 0} regarde les
 * {@code -Z}, donc le nord, donc {@code front = NORTH} est <strong>géométriquement vrai</strong>.
 * L'ancre {@code (4, 1, 0)} est le bloc bas de la porte, au niveau où l'on marche ; la fondation en
 * {@code y = 0} se place donc un bloc sous l'ancre de l'emplacement.</p>
 *
 * <h2>Ce que cette tour n'est pas</h2>
 *
 * <p>Ce n'est pas une œuvre : aucune décoration extérieure, aucun mobilier, aucun matériau rare.
 * Elle doit être <strong>visitable</strong> — on entre, on monte, on arrive en haut sans tomber — et
 * c'est tout ce qu'on lui demande. Tout ornement ajouté ici serait du bruit entre le moteur et ce
 * qu'on cherche à vérifier.</p>
 */
public final class TestWatchtowerBlueprint {

    public static final String ID = "test_watchtower_01";
    public static final String NAME = "Tour de garde de test";
    public static final String SCHEMATIC = "test_watchtower_01.schem";

    public static final int SIZE_X = 9;
    public static final int SIZE_Y = 14;
    public static final int SIZE_Z = 9;

    public static final int ANCHOR_X = 4;
    public static final int ANCHOR_Y = 1;
    public static final int ANCHOR_Z = 0;

    /** La façade porte la porte, et elle regarde le nord. Même convention que la hutte. */
    public static final Facing FRONT = Facing.NORTH;

    /**
     * Les trois niveaux habitables, et la hauteur de leur plancher.
     *
     * <p>Chaque niveau occupe trois blocs de hauteur libre, surmontés d'un plancher. Le pas de 4
     * entre deux planchers est ce qui rend l'escalier possible : trois marches et on débouche sur le
     * plancher suivant.</p>
     */
    private static final int[] FLOOR_Y = {1, 5, 9};
    /** Le plancher de la terrasse, au-dessus du dernier niveau. */
    private static final int ROOF_Y = 13;

    private static final String FOUNDATION = "minecraft:cobblestone";
    private static final String WALL = "minecraft:stone_bricks";
    private static final String BUTTRESS = "minecraft:cobblestone";
    /** Les arêtes : un troisième matériau, pour qu'elles restent lisibles sur les quatre faces. */
    private static final String CORNER = "minecraft:chiseled_stone_bricks";
    private static final String FLOOR = "minecraft:oak_planks";
    private static final String PANE = "minecraft:glass_pane[east=true,north=false,south=false,"
            + "waterlogged=false,west=true]";

    private TestWatchtowerBlueprint() {
    }

    /**
     * Construit le plan. <strong>Déterministe</strong> : même ordre, mêmes blocs, à chaque appel —
     * c'est ce qui rend le fichier produit comparable d'une génération à l'autre, donc son empreinte
     * utilisable comme version (issue #234).
     */
    public static Blueprint blueprint() {
        List<BlueprintBlock> blocks = new ArrayList<>();

        foundation(blocks);
        for (int level = 0; level < FLOOR_Y.length; level++) {
            walls(blocks, level);
            stairFlight(blocks, level);
        }
        floorsAndHatches(blocks);
        roofAndBattlements(blocks);

        return new Blueprint(SIZE_X, SIZE_Y, SIZE_Z, blocks);
    }

    // ---- Les morceaux, dans l'ordre où on les bâtirait ---------------------------------------

    /** Fondation pleine : elle s'enfonce d'un bloc sous l'ancre de l'emplacement. */
    private static void foundation(List<BlueprintBlock> blocks) {
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                blocks.add(new BlueprintBlock(x, 0, z, FOUNDATION));
            }
        }
    }

    /**
     * Le périmètre d'un niveau : murs, et les ouvertures propres à chaque face.
     *
     * <p>Seul le périmètre est listé — l'intérieur n'étant pas listé, il reste <strong>vide</strong>,
     * et c'est ce qui rend la tour réellement visitable.</p>
     */
    private static void walls(List<BlueprintBlock> blocks, int level) {
        int base = FLOOR_Y[level];
        for (int y = base; y < base + 3; y++) {
            for (int x = 0; x < SIZE_X; x++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    boolean perimeter = x == 0 || x == SIZE_X - 1 || z == 0 || z == SIZE_Z - 1;
                    if (!perimeter) {
                        continue;
                    }
                    String state = wallState(x, y, z, level, base);
                    if (state != null) {
                        blocks.add(new BlueprintBlock(x, y, z, state));
                    }
                }
            }
        }
        // Torches murales, à l'intérieur, contre la façade : elles éclairent l'entrée et donnent
        // une troisième famille de blocs orientés à faire tourner.
        //
        // Au REZ-DE-CHAUSSÉE seulement, et ce n'est pas un choix esthétique : aux niveaux
        // supérieurs, la case (6, base+1, 1) est occupée par une marche de la volée en spirale.
        // Blueprint#validate refuse deux blocs à la même position — à juste titre, le fichier ne
        // serait plus déterministe — mais mieux vaut ne pas écrire la faute que la faire rattraper.
        if (level == 0) {
            blocks.add(new BlueprintBlock(2, base + 1, 1, wallTorch("south")));
            blocks.add(new BlueprintBlock(SIZE_X - 3, base + 1, 1, wallTorch("south")));
        }
    }

    /**
     * L'état du mur à cette position, ou {@code null} pour laisser de l'air.
     *
     * <p>C'est ici que vivent les quatre signatures de face. Chaque condition est écrite pour une
     * seule face, afin qu'aucune ne puisse se confondre avec une autre après rotation.</p>
     */
    private static String wallState(int x, int y, int z, int level, int base) {
        boolean facade = z == 0;
        boolean east = x == SIZE_X - 1;
        boolean south = z == SIZE_Z - 1;
        boolean topLevel = level == FLOOR_Y.length - 1;

        // NORD — la porte, au rez-de-chaussée seulement, au centre exact de la façade.
        if (facade && level == 0 && x == ANCHOR_X && y <= base + 1) {
            return door(y == base ? "lower" : "upper");
        }
        // NORD — deux fenêtres à hauteur de regard, de part et d'autre de la porte.
        if (facade && y == base + 1 && (x == 2 || x == SIZE_X - 3)) {
            return PANE;
        }
        // EST — une meurtrière par niveau, décalée vers l'avant pour ne pas être au centre.
        if (east && y == base + 1 && z == 3) {
            return null;
        }
        // SUD — large ouverture de guet, au dernier niveau uniquement.
        if (south && topLevel && y <= base + 1 && x >= 3 && x <= SIZE_X - 4) {
            return null;
        }
        // Les quatre poteaux d'angle, qui donnent à la tour ses arêtes visibles.
        boolean corner = (x == 0 || x == SIZE_X - 1) && (z == 0 || z == SIZE_Z - 1);
        if (corner) {
            return CORNER;
        }
        // OUEST — face aveugle, en moellon brut sur toute sa hauteur. C'est un changement de
        // MATÉRIAU et non un contrefort ajouté : un contrefort posé en x = 0 doublerait le mur
        // déjà écrit à cette position, et Blueprint#validate le refuserait (deux blocs à la même
        // place). Le résultat visuel est le même — une face franchement plus massive que les
        // trois autres — sans introduire de bloc en conflit.
        if (x == 0) {
            return BUTTRESS;
        }
        return WALL;
    }

    /**
     * Une volée d'escalier par niveau, chacune le long d'une paroi <strong>différente</strong>.
     *
     * <p>C'est délibérément une spirale et non trois volées parallèles : chaque volée regarde une
     * direction distincte, donc les quatre valeurs de {@code facing} des marches sont exercées. Si
     * le moteur de rotation inversait un sens, une volée cesserait de monter — et on le constaterait
     * en essayant de monter, pas en relisant du code.</p>
     *
     * <p><strong>Quatre marches</strong>, et le compte est contraint par la géométrie du jeu, pas
     * par le goût : deux planchers consécutifs sont séparés de quatre blocs, et une marche ne fait
     * franchir qu'un demi-bloc de plus que le bloc sur lequel elle repose. Avec trois marches, la
     * dernière culminerait à 3,5 alors que le plancher d'arrivée se marche à 5,0 — infranchissable,
     * et la tour serait invisitable. La quatrième marche occupe une case du plancher lui-même, d'où
     * la trémie de quatre cases de {@link #floorsAndHatches}, et l'on enjambe ensuite sur la case
     * de plancher suivante dans le sens de la montée.</p>
     */
    private static void stairFlight(List<BlueprintBlock> blocks, int level) {
        int base = FLOOR_Y[level];
        for (int step = 0; step < 4; step++) {
            int y = base + step;
            switch (level) {
                // Niveau 0 : le long du mur SUD, on monte vers l'EST.
                case 0 -> blocks.add(new BlueprintBlock(1 + step, y, SIZE_Z - 2, stairs("east")));
                // Niveau 1 : le long du mur EST, on monte vers le NORD.
                case 1 -> blocks.add(new BlueprintBlock(SIZE_X - 2, y, SIZE_Z - 2 - step,
                        stairs("north")));
                // Niveau 2 : le long du mur NORD, on monte vers l'OUEST.
                default -> blocks.add(new BlueprintBlock(SIZE_X - 2 - step, y, 1, stairs("west")));
            }
        }
    }

    /**
     * Les planchers intermédiaires, percés au-dessus de chaque volée.
     *
     * <p>La trémie n'est pas un trou décoratif : sans elle, la tête du joueur heurterait le plancher
     * au milieu de l'escalier et la tour serait invisitable. Les cases omises sont exactement les
     * <strong>quatre</strong> de la volée — dont la dernière marche, qui occupe une case du plancher.
     * La case suivante dans le sens de la montée reste pleine : c'est le palier d'arrivée.</p>
     */
    private static void floorsAndHatches(List<BlueprintBlock> blocks) {
        for (int level = 1; level < FLOOR_Y.length; level++) {
            int y = FLOOR_Y[level] - 1;
            for (int x = 1; x < SIZE_X - 1; x++) {
                for (int z = 1; z < SIZE_Z - 1; z++) {
                    if (isHatch(x, z, level - 1)) {
                        continue;
                    }
                    blocks.add(new BlueprintBlock(x, y, z, FLOOR));
                }
            }
        }
    }

    /**
     * Vrai si cette case du plancher doit rester ouverte pour laisser passer la volée du niveau
     * {@code fromLevel}.
     *
     * <p>Quatre cases, exactement celles des quatre marches. La case d'arrivée, elle, n'est
     * <strong>pas</strong> percée : c'est sur elle qu'on pose le pied en sortant de l'escalier.</p>
     */
    private static boolean isHatch(int x, int z, int fromLevel) {
        return switch (fromLevel) {
            case 0 -> z == SIZE_Z - 2 && x >= 1 && x <= 4;
            case 1 -> x == SIZE_X - 2 && z >= SIZE_Z - 5 && z <= SIZE_Z - 2;
            default -> z == 1 && x >= SIZE_X - 5 && x <= SIZE_X - 2;
        };
    }

    /**
     * La terrasse et ses créneaux.
     *
     * <p>Les créneaux ne sont pas un ornement : ils sont la <strong>protection contre la chute</strong>
     * que le ticket demande, et un merlon sur deux suffit à la fournir tout en laissant la silhouette
     * reconnaissable de loin.</p>
     */
    private static void roofAndBattlements(List<BlueprintBlock> blocks) {
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                if (isHatch(x, z, FLOOR_Y.length - 1)) {
                    continue;
                }
                blocks.add(new BlueprintBlock(x, ROOF_Y - 1, z, FLOOR));
            }
        }
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                boolean perimeter = x == 0 || x == SIZE_X - 1 || z == 0 || z == SIZE_Z - 1;
                // Un merlon sur deux : créneaux francs, et aucune case d'où l'on puisse tomber en
                // marchant droit.
                if (perimeter && ((x + z) % 2 == 0)) {
                    blocks.add(new BlueprintBlock(x, ROOF_Y, z, WALL));
                }
            }
        }
    }


    private static String stairs(String facing) {
        return "minecraft:oak_stairs[facing=" + facing
                + ",half=bottom,shape=straight,waterlogged=false]";
    }

    private static String door(String half) {
        return "minecraft:oak_door[facing=north,half=" + half
                + ",hinge=left,open=false,powered=false]";
    }

    private static String wallTorch(String facing) {
        return "minecraft:wall_torch[facing=" + facing + "]";
    }

    /**
     * La définition qui accompagne le plan.
     *
     * <p>Dimensions et ancre sont <strong>lues sur le plan</strong>, pas recopiées : une définition
     * qui mentirait sur ses dimensions ferait annoncer une emprise fausse, et la faute ne se verrait
     * qu'une fois la tour posée.</p>
     */
    public static BuildingDefinition definition() {
        Blueprint blueprint = blueprint();
        return new BuildingDefinition(ID, NAME,
                "Tour de garde de validation, produite par le code du plugin. Trois niveaux, "
                        + "escalier intérieur en spirale, terrasse à créneaux, et quatre faces "
                        + "franchement différentes (porte au nord, meurtrières à l'est, ouverture "
                        + "de guet au sud, contrefort aveugle à l'ouest) pour que l'orientation se "
                        + "constate d'un coup d'œil.",
                SCHEMATIC,
                blueprint.sizeX(), blueprint.sizeY(), blueprint.sizeZ(),
                ANCHOR_X, ANCHOR_Y, ANCHOR_Z,
                FRONT, blueprint.materials(), 1);
    }
}
