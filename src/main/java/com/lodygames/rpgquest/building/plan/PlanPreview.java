package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Blueprint;
import com.lodygames.rpgquest.building.model.BlueprintBlock;
import com.lodygames.rpgquest.building.model.Facing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Des <strong>coupes 2D</strong> du bâtiment réellement construit (issue #94, lot E).
 *
 * <h2>Pourquoi le plugin les calcule, et pas le panel</h2>
 *
 * <p>Le ticket l'exige explicitement : « aucun second moteur d'expansion dans le panel ». Un
 * aperçu dessiné par le panel depuis le texte du plan montrerait ce que le panel <em>croit</em> que
 * le plan décrit, pas ce que le serveur construira. Ce serait un aperçu qui mentirait exactement
 * là où on a besoin qu'il dise la vérité : la V1 a été scellée parce que l'intention du plan et la
 * grille produite divergeaient de cinq blocs.</p>
 *
 * <p>Ces coupes sont donc prises sur la <strong>même grille que l'audit</strong>, après la même
 * expansion. Si l'aperçu montre un mur sans porte, c'est qu'il n'y a pas de porte.</p>
 *
 * <h2>Pourquoi des caractères et pas une image</h2>
 *
 * <p>Une coupe est une grille de rôles, et le rôle est ce qui intéresse le lecteur : un mur, une
 * vitre, un passage, une lumière. Transmettre des caractères laisse le rendu au panel — qui peut en
 * faire un tableau lisible au doigt sur un téléphone — sans qu'aucune image traverse l'agent, et
 * sans que le plugin ait à connaître une palette ou une taille d'écran. C'est aussi ce qui rend ces
 * coupes testables : une assertion sur une ligne de texte se relit.</p>
 *
 * <p>Le vocabulaire des rôles vient de {@link BlockSemantics}, le même registre déclaré qui sert à
 * l'audit. Deux légendes séparées finiraient par désigner deux choses différentes.</p>
 */
public final class PlanPreview {

    private PlanPreview() {
    }

    /** Le caractère d'une cellule vide. Un espace serait mangé par n'importe quel rendu HTML. */
    public static final char EMPTY = '.';
    /** Matière opaque : le cas normal, briques et planches. */
    public static final char SOLID = '#';
    /** Vitrage. */
    public static final char GLAZING = 'o';
    /** Source de lumière utile. */
    public static final char LIGHT = '*';
    /** Cellule franchissable occupée par un bloc : porte ouverte, trappe, portillon. */
    public static final char PASSABLE = '+';
    /** Grimpable : échelle, vigne. */
    public static final char CLIMBABLE = 'H';
    /** Mobilier et contenu intérieur. */
    public static final char FURNISHING = 'f';
    /** Escalier ou dalle — ce qui fait gagner de la hauteur. */
    public static final char STEP = '/';

    /**
     * Une coupe, avec ses lignes déjà orientées pour la lecture.
     *
     * @param id      identifiant stable, pour que l'écran puisse nommer l'onglet
     * @param label   titre lisible
     * @param axes    ce que parcourent les colonnes et les lignes, pour légender les bords
     * @param rows    la grille, une chaîne par ligne, toutes de même longueur
     */
    public record Slice(String id, String label, String axes, List<String> rows) {

        public Slice {
            rows = List.copyOf(rows == null ? List.of() : rows);
        }

        public int width() {
            return rows.isEmpty() ? 0 : rows.get(0).length();
        }

        public int height() {
            return rows.size();
        }

        /** Cette coupe contient-elle autre chose que du vide ? */
        public boolean hasContent() {
            for (String row : rows) {
                for (int i = 0; i < row.length(); i++) {
                    if (row.charAt(i) != EMPTY) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * L'aperçu complet.
     *
     * @param plans      une coupe horizontale par niveau, du sol vers le haut
     * @param elevations les quatre façades, vues de l'extérieur
     */
    public record Preview(int sizeX, int sizeY, int sizeZ, String front,
                          int anchorX, int anchorY, int anchorZ,
                          List<Slice> plans, List<Slice> elevations) {

        public Preview {
            front = front == null ? "" : front;
            plans = List.copyOf(plans == null ? List.of() : plans);
            elevations = List.copyOf(elevations == null ? List.of() : elevations);
        }
    }

    /**
     * Prend les coupes.
     *
     * @param blueprint la grille expansée — la même que celle auditée
     * @param front     orientation de référence, qui décide quelle façade est l'avant
     */
    public static Preview of(Blueprint blueprint, Facing front, int anchorX, int anchorY,
                             int anchorZ) {
        Map<Long, Character> grid = roles(blueprint);
        int sizeX = blueprint.sizeX();
        int sizeY = blueprint.sizeY();
        int sizeZ = blueprint.sizeZ();

        List<Slice> plans = new ArrayList<>();
        for (int y = 0; y < sizeY; y++) {
            List<String> rows = new ArrayList<>(sizeZ);
            // Une coupe horizontale se lit comme une carte : le nord en haut, donc z croissant vers
            // le bas, et x croissant vers la droite. C'est la convention de n'importe quel plan
            // d'architecte, et s'en écarter obligerait le lecteur à retourner l'écran.
            for (int z = 0; z < sizeZ; z++) {
                StringBuilder row = new StringBuilder(sizeX);
                for (int x = 0; x < sizeX; x++) {
                    row.append(at(grid, x, y, z));
                }
                rows.add(row.toString());
            }
            Slice slice = new Slice("plan-y" + y, "Niveau y = " + y,
                    "x → droite, z → bas (nord en haut)", rows);
            // Un niveau entièrement vide n'apprend rien et allonge l'écran : les combles d'un toit
            // en pente en produisent plusieurs. On les omet, sauf le rez-de-chaussée, dont l'absence
            // se lirait comme une erreur de lecture.
            if (y == 0 || slice.hasContent()) {
                plans.add(slice);
            }
        }

        List<Slice> elevations = new ArrayList<>();
        for (Facade facade : Facade.values()) {
            elevations.add(elevation(grid, facade, front, sizeX, sizeY, sizeZ));
        }
        return new Preview(sizeX, sizeY, sizeZ, front == null ? "" : front.name(),
                anchorX, anchorY, anchorZ, plans, elevations);
    }

    /**
     * Une façade vue de l'extérieur.
     *
     * <p>On projette ce que verrait quelqu'un debout devant : pour chaque cellule de la façade, le
     * <strong>premier</strong> rôle rencontré en avançant vers l'intérieur. Sans cela, une fenêtre
     * serait masquée par le mur du fond et toutes les façades se ressembleraient.</p>
     */
    private static Slice elevation(Map<Long, Character> grid, Facade facade, Facing front,
                                   int sizeX, int sizeY, int sizeZ) {
        int span = facade.span(front, sizeX, sizeZ);
        int depth = facade.direction(front).alongX() ? sizeX : sizeZ;
        int[] inward = facade.inwardStep(front);
        List<String> rows = new ArrayList<>(sizeY);
        // Une élévation se lit avec le ciel en haut : y décroissant vers le bas.
        for (int y = sizeY - 1; y >= 0; y--) {
            StringBuilder row = new StringBuilder(span);
            for (int offset = 0; offset < span; offset++) {
                var cell = facade.cell(front, sizeX, sizeY, sizeZ, offset, y);
                char found = EMPTY;
                if (cell.isPresent()) {
                    int[] c = cell.get();
                    for (int step = 0; step < depth; step++) {
                        char role = at(grid, c[0] + inward[0] * step, c[1],
                                c[2] + inward[2] * step);
                        if (role != EMPTY) {
                            found = role;
                            break;
                        }
                    }
                }
                row.append(found);
            }
            rows.add(row.toString());
        }
        return new Slice("elevation-" + facade.name().toLowerCase(java.util.Locale.ROOT),
                "Façade " + facade.label()
                        + (facade == Facade.FRONT ? " — entrée" : "")
                        + " (" + facade.direction(front) + ")",
                "largeur → droite, hauteur → haut", rows);
    }

    /** Le rôle de chaque cellule occupée, calculé une seule fois. */
    private static Map<Long, Character> roles(Blueprint blueprint) {
        Map<Long, Character> grid = new HashMap<>(blueprint.blocks().size() * 2);
        for (BlueprintBlock block : blueprint.blocks()) {
            grid.put(key(block.x(), block.y(), block.z()), role(block.state()));
        }
        return grid;
    }

    /**
     * Le rôle d'un état de bloc.
     *
     * <p>L'ordre des épreuves est significatif : une lanterne est à la fois une lumière et
     * franchissable, et c'est sa lumière qui intéresse le lecteur d'un plan. Un vitrage avant tout
     * le reste, parce qu'une vitre est ce qui distingue une façade aveugle d'une façade habitée.</p>
     */
    private static char role(String state) {
        if (state == null || state.isBlank()) {
            return EMPTY;
        }
        if (BlockSemantics.glazing(state)) {
            return GLAZING;
        }
        if (BlockSemantics.lightSource(state)) {
            return LIGHT;
        }
        if (BlockSemantics.climbable(state)) {
            return CLIMBABLE;
        }
        String type = BlockState.type(state);
        if (type.endsWith("_stairs") || type.endsWith("_slab")) {
            // Reconnu sur la grammaire des identifiants Minecraft, comme BlockSemantics : ce sont
            // les deux familles qui font gagner de la hauteur, et les voir sur une coupe est ce qui
            // permet de juger qu'un étage est atteignable.
            return STEP;
        }
        if (BlockSemantics.furnishing(state)) {
            return FURNISHING;
        }
        if (BlockSemantics.passable(state)) {
            return PASSABLE;
        }
        return SOLID;
    }

    private static char at(Map<Long, Character> grid, int x, int y, int z) {
        Character role = grid.get(key(x, y, z));
        return role == null ? EMPTY : role;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 42) | ((long) (y + 1024) << 21) | (z + 1024);
    }
}
