package com.lodygames.rpgquest.building.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * La géométrie d'un escalier <strong>entre deux niveaux</strong>, calculée par le moteur
 * (issue #282).
 *
 * <h2>Pourquoi une primitive de plus, alors que {@code stair_run} existe</h2>
 *
 * <p>Le 3<sup>e</sup> plan IA de la Bibliothèque du Hub (12/10/2026) a été refusé pour
 * {@code LEVEL_UNREACHABLE} alors qu'il contenait un escalier, une échelle et une trémie. Mesuré
 * sur sa grille, chacun était faux d'une façon différente :</p>
 *
 * <ul>
 *   <li>la volée comptait <strong>5 marches</strong> du sol y=1 vers un plancher en y=6 : il en
 *       fallait 6. La dernière marche arrivait <em>sous</em> le plancher, en dehors de la trémie,
 *       et le « palier » de {@code stair_run} perçait alors le plancher au lieu de s'y poser ;</li>
 *   <li>une {@code box} de bibliothèques posée <em>après</em> l'escalier recouvrait 15 × 15
 *       cellules de l'étage, et rebouchait la hauteur libre de la dernière marche ;</li>
 *   <li>l'échelle s'arrêtait en y=4, deux blocs sous le plancher, sans trémie au-dessus.</li>
 * </ul>
 *
 * <p>Trois calculs que le modèle doit faire juste ensemble — nombre de marches, position de la
 * trémie, ordre des instructions — et qu'il rate séparément d'une génération à l'autre. Cette
 * primitive les lui retire : le modèle donne le <strong>pied</strong> de l'escalier, la
 * <strong>direction</strong> de montée et le <strong>plancher</strong> à atteindre ; le moteur en
 * déduit tout le reste.</p>
 *
 * <h2>Le contrat géométrique</h2>
 *
 * <ul>
 *   <li>{@code from} est la première marche, posée sur le sol du niveau de départ ;</li>
 *   <li>{@code floor} est le y du <strong>plancher</strong> traversé (le bloc sur lequel on
 *       marche à l'étage). Le nombre de marches vaut {@code floor - from.y + 1} : la dernière
 *       marche <em>affleure</em> le plancher, elle en prend la place ;</li>
 *   <li>au-dessus de chaque marche, trois cellules sont dégagées (jamais au-delà de
 *       {@code floor + 2}) : c'est la hauteur libre, et c'est aussi, là où elle croise le
 *       plancher, <strong>la trémie</strong> — calculée, jamais devinée. Trois et non deux :
 *       franchir une demi-marche porte la tête d'un joueur à 3,3 blocs au-dessus de la marche
 *       qu'il quitte ;</li>
 *   <li>devant la première marche, un départ de trois blocs de haut ; après la dernière, un
 *       palier posé <em>sur</em> le plancher, de deux blocs de haut ;</li>
 *   <li>{@code width} élargit la volée vers {@code +z} (montée est/ouest) ou {@code +x} (montée
 *       nord/sud), de 1 à 3 voies.</li>
 * </ul>
 *
 * <h2>Le volume est réservé</h2>
 *
 * <p>{@link BuildingPlanExpander} applique les escaliers <strong>après toutes les autres
 * instructions</strong>. Un meuble, une lanterne ou une bibliothèque posés plus loin dans le plan
 * ne peuvent donc plus reboucher la volée — c'était la seconde cause du refus. En contrepartie,
 * l'escalier ne touche jamais l'enveloppe : s'il croise un mur extérieur, une porte ou un vitrage,
 * le plan est refusé avec la cellule en cause, plutôt que de percer la façade en silence.</p>
 *
 * @param x        pied de la volée — la première marche
 * @param y        y de la première marche, c'est-à-dire le niveau où l'on se tient au départ
 * @param z        pied de la volée
 * @param dir      direction de montée
 * @param floor    y du plancher d'arrivée
 * @param width    nombre de voies, 1 à {@value #MAX_WIDTH}
 * @param material bloc d'escalier, sans propriétés
 */
public record Staircase(int x, int y, int z, BlockStates.Dir dir, int floor, int width,
                        String material) {

    /** Largeur maximale : au-delà, ce n'est plus un escalier mais une estrade. */
    public static final int MAX_WIDTH = 3;

    /** Hauteur maximale d'une volée : au-delà, deux niveaux de plus de 32 blocs n'existent pas. */
    public static final int MAX_STEPS = 32;

    public static final String DEFAULT_MATERIAL = "minecraft:oak_stairs";

    public Staircase {
        width = Math.max(1, Math.min(MAX_WIDTH, width));
        material = BlockStates.bareTypeOr(material, DEFAULT_MATERIAL);
    }

    /** Une cellule à écrire : un état de bloc, ou {@code null} pour la vider. */
    public record Placement(int x, int y, int z, String state) {

        public boolean clears() {
            return state == null;
        }
    }

    /** Lit l'instruction {@code staircase} d'un plan. Vide si sa direction est inconnue. */
    public static Optional<Staircase> of(PlanStep step) {
        BlockStates.Dir dir = direction(step.axis());
        if (dir == null) {
            return Optional.empty();
        }
        PlanBox box = step.box();
        return Optional.of(new Staircase(box.minX(), box.minY(), box.minZ(), dir,
                step.number("floor", box.minY()), step.number("width", 1), step.state()));
    }

    /** Une direction cardinale écrite en minuscules, ou {@code null}. */
    static BlockStates.Dir direction(String raw) {
        for (BlockStates.Dir candidate : BlockStates.Dir.values()) {
            if (candidate.value().equals(raw)) {
                return candidate;
            }
        }
        return null;
    }

    /** Nombre de marches : la dernière affleure le plancher. */
    public int steps() {
        return floor - y + 1;
    }

    /** Le décalage d'une voie à l'autre : perpendiculaire à la montée. */
    private int laneX() {
        return dir.alongX() ? 0 : 1;
    }

    private int laneZ() {
        return dir.alongX() ? 1 : 0;
    }

    /**
     * Le motif qui rend cet escalier inconstructible, sans grille — bornes et cohérence seules.
     *
     * @return vide si l'escalier tient dans le volume {@code sx × sy × sz}
     */
    public Optional<String> refusal(int sx, int sy, int sz) {
        if (floor <= y) {
            return Optional.of("le plancher d'arrivée « floor: " + floor + " » doit être AU-DESSUS "
                    + "de la première marche (y=" + y + "). « floor » est le y du plancher de "
                    + "l'étage, le bloc sur lequel on marche en haut.");
        }
        if (steps() > MAX_STEPS) {
            return Optional.of("volée de " + steps() + " marches, " + MAX_STEPS + " au plus.");
        }
        if (y < 1) {
            return Optional.of("la première marche est en y=" + y + " : elle doit être posée SUR "
                    + "un sol, donc au moins en y=1.");
        }
        for (Placement p : placements()) {
            if (p.x() < 0 || p.y() < 0 || p.z() < 0 || p.x() >= sx || p.y() >= sy || p.z() >= sz) {
                return Optional.of("la volée, son départ ou son palier sortent du volume en "
                        + p.x() + "/" + p.y() + "/" + p.z() + " (volume " + sx + " × " + sy + " × "
                        + sz + "). Rapproche le pied de l'intérieur ou change de direction.");
            }
        }
        return Optional.empty();
    }

    /**
     * Toutes les cellules que cet escalier écrit, dans un ordre fixe : les vides d'abord, les
     * marches ensuite. Aucune marche ne se trouve dans un vide — elles sont dans des colonnes ou
     * des hauteurs distinctes — mais l'ordre fixe garantit le même résultat quoi qu'il arrive.
     */
    public List<Placement> placements() {
        List<Placement> out = new ArrayList<>();
        int n = steps();
        for (int lane = 0; lane < width; lane++) {
            int ox = x + laneX() * lane;
            int oz = z + laneZ() * lane;
            // Le départ : trois blocs libres devant la première marche — on y monte.
            for (int h = 0; h <= 2; h++) {
                out.add(new Placement(ox - dir.dx(), y + h, oz - dir.dz(), null));
            }
            for (int i = 0; i < n; i++) {
                int sx = ox + dir.dx() * i;
                int sz = oz + dir.dz() * i;
                int sy = y + i;
                // La hauteur libre au-dessus de chaque marche — et donc la trémie, là où elle
                // croise le plancher. TROIS blocs, pas deux : en franchissant la demi-marche
                // suivante, les pieds passent à sy+1,5 et la tête à sy+3,3, encore au-dessus de
                // CETTE colonne. Avec deux blocs, le joueur se cogne au bord de la trémie et ne
                // monte pas. Jamais au-delà de floor+2 : l'escalier ne touche pas le plafond de
                // l'étage.
                for (int h = sy + 1; h <= Math.min(sy + 3, floor + 2); h++) {
                    out.add(new Placement(sx, h, sz, null));
                }
            }
            // Le palier : on se tient en floor+1, SUR le plancher. Il n'est jamais percé.
            int lx = ox + dir.dx() * n;
            int lz = oz + dir.dz() * n;
            out.add(new Placement(lx, floor + 1, lz, null));
            out.add(new Placement(lx, floor + 2, lz, null));
        }
        String state = BlockStates.stairs(material, dir, false);
        for (int lane = 0; lane < width; lane++) {
            int ox = x + laneX() * lane;
            int oz = z + laneZ() * lane;
            for (int i = 0; i < n; i++) {
                out.add(new Placement(ox + dir.dx() * i, y + i, oz + dir.dz() * i, state));
            }
        }
        return out;
    }

    /** Les cellules qui doivent porter : sous le départ, et le plancher sous le palier. */
    public List<int[]> supports() {
        List<int[]> out = new ArrayList<>();
        int n = steps();
        for (int lane = 0; lane < width; lane++) {
            int ox = x + laneX() * lane;
            int oz = z + laneZ() * lane;
            out.add(new int[] {ox - dir.dx(), y - 1, oz - dir.dz()});
            out.add(new int[] {ox + dir.dx() * n, floor, oz + dir.dz() * n});
        }
        return out;
    }

    /** L'instruction YAML équivalente, prête à être insérée dans la liste {@code steps}. */
    public String yaml(String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("- op: staircase\n");
        sb.append(indent).append("  from: [").append(x).append(", ").append(y).append(", ")
                .append(z).append("]\n");
        sb.append(indent).append("  direction: ").append(dir.value()).append('\n');
        sb.append(indent).append("  floor: ").append(floor).append('\n');
        if (width > 1) {
            sb.append(indent).append("  width: ").append(width).append('\n');
        }
        sb.append(indent).append("  block: ").append(material).append('\n');
        return sb.toString();
    }

    /** Description française, pour l'écran et le modèle. */
    public String describe() {
        int n = steps();
        int lx = x + dir.dx() * n;
        int lz = z + dir.dz() * n;
        return "escalier de " + n + " marche(s)" + (width > 1 ? " sur " + width + " voies" : "")
                + ", pied en " + x + "/" + y + "/" + z + ", montée vers " + dir.value()
                + ", palier en " + lx + "/" + (floor + 1) + "/" + lz + " sur le plancher y="
                + floor;
    }
}
