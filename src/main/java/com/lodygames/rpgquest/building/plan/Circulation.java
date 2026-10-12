package com.lodygames.rpgquest.building.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Les niveaux d'un bâtiment et la circulation d'un joueur entre eux (issue #94, refondu par #282).
 *
 * <h2>Un niveau est un PLANCHER, pas un dessus de meuble</h2>
 *
 * <p>La version précédente cherchait les hauteurs où un joueur peut se tenir sur au moins un quart
 * de l'emprise. Sur le 3<sup>e</sup> plan de la Bibliothèque du Hub, une {@code box} de
 * bibliothèques de 15 × 15 couvrait l'étage : le « niveau » détecté était alors le
 * <em>dessus des étagères</em> (y=8), et le vrai plancher (y=7 pour les pieds) n'était plus
 * compté. Pire : une fois l'escalier réparé, ce dessus d'étagères aurait été atteignable en
 * montant d'un bloc depuis l'allée de 1 bloc laissée le long des murs — un étage entièrement
 * inutilisable aurait été déclaré conforme. Faux négatif.</p>
 *
 * <p>Un niveau est donc désormais une <strong>couche de plancher</strong> : des blocs porteurs
 * qui ne sont pas des meubles ({@link AuditGrid#structural}), dont le dessus n'est pas lui-même
 * porteur, sous une couverture, sur au moins un quart de l'emprise <em>intérieure</em> — l'anneau
 * des murs extérieurs n'est jamais un plancher.</p>
 *
 * <h2>Atteint, puis praticable</h2>
 *
 * <p>Un niveau est <em>atteint</em> si un joueur parti du rez-de-chaussée peut se tenir debout sur
 * ce plancher. Il est <em>praticable</em> à proportion des cellules du plancher où il peut
 * réellement se tenir. Le second chiffre est celui qui manquait : un étage atteint par une allée
 * de 1 bloc, le reste muré de meubles, n'est pas un étage.</p>
 */
final class Circulation {

    private Circulation() {
    }

    /**
     * Un plancher.
     *
     * @param slabY le y des blocs du plancher ; on s'y tient en {@code slabY + 1}
     * @param slab  les cellules de plancher, en clés {@link AuditGrid#key} à {@code slabY}
     */
    record Level(int slabY, Set<Long> slab) {

        int standY() {
            return slabY + 1;
        }

        int area() {
            return slab.size();
        }
    }

    /** L'état d'un niveau après la diffusion. */
    record LevelState(Level level, boolean reached, int usable) {

        double freeShare() {
            return level.area() == 0 ? 1.0 : (double) usable / level.area();
        }
    }

    /** L'analyse complète : les niveaux, ce qu'un joueur atteint, et l'état de chaque niveau. */
    record Analysis(List<Level> levels, Set<Long> walkable, List<LevelState> states) {

        int reachedCount() {
            return (int) states.stream().filter(LevelState::reached).count();
        }

        List<Integer> standingLevels() {
            return levels.stream().map(Level::standY).toList();
        }

        String floorLabel() {
            return levels.isEmpty() ? "aucun"
                    : levels.stream().map(l -> String.valueOf(l.standY()))
                            .reduce((a, b) -> a + ", " + b).orElse("");
        }
    }

    static Analysis analyse(AuditGrid grid) {
        List<Level> levels = detect(grid);
        if (levels.isEmpty()) {
            return new Analysis(List.of(), Set.of(), List.of());
        }
        Set<Long> walkable = walkFrom(grid, levels.get(0).standY());
        List<LevelState> states = new ArrayList<>();
        for (Level level : levels) {
            int usable = 0;
            for (long k : level.slab()) {
                int x = AuditGrid.keyX(k);
                int z = AuditGrid.keyZ(k);
                if (walkable.contains(AuditGrid.key(x, level.standY(), z))) {
                    usable++;
                }
            }
            states.add(new LevelState(level, usable > 0, usable));
        }
        return new Analysis(List.copyOf(levels), walkable, List.copyOf(states));
    }

    /**
     * Les planchers, du plus bas au plus haut.
     *
     * <p>Les planchers <em>abrités</em> d'abord (#263 : le dessus d'un toit à deux pentes n'est pas
     * un niveau). Un bâtiment sans aucun plancher abrité — pas de toit du tout — garde la lecture
     * à ciel ouvert : son niveau existe, et c'est le contrôle de couverture qui dira qu'il y pleut.</p>
     */
    static List<Level> detect(AuditGrid grid) {
        boolean interiorOnly = grid.hasInterior();
        int area = 0;
        for (int x = grid.minX(); x <= grid.maxX(); x++) {
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                if (!interiorOnly || grid.interior(x, z)) {
                    area++;
                }
            }
        }
        List<Level> sheltered = new ArrayList<>();
        List<Level> open = new ArrayList<>();
        for (int y = grid.minY(); y < grid.maxY(); y++) {
            Set<Long> top = new LinkedHashSet<>();
            Set<Long> covered = new LinkedHashSet<>();
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                    if (interiorOnly && !grid.interior(x, z)) {
                        continue;
                    }
                    if (grid.structural(x, y, z) && !grid.structural(x, y + 1, z)) {
                        long k = AuditGrid.key(x, y, z);
                        top.add(k);
                        if (coveredFrom(grid, x, y + 3, z)) {
                            covered.add(k);
                        }
                    }
                }
            }
            // Un quart de l'emprise intérieure : assez pour distinguer un plancher d'une marche,
            // d'un comptoir ou d'un palier isolé.
            if (top.size() * 4 >= area) {
                open.add(new Level(y, Set.copyOf(top)));
            }
            if (covered.size() * 4 >= area) {
                sheltered.add(new Level(y, Set.copyOf(covered)));
            }
        }
        return storeys(sheltered.isEmpty() ? open : sheltered);
    }

    /**
     * Ne garde que les vrais étages : un plancher à moins de 3 blocs du précédent est une estrade,
     * un comptoir ou une marche — on ne tient pas debout entre les deux. Sans ce filtre, le podium
     * de 9 × 9 de la V1, un bloc au-dessus du sol, devenait un « étage inaccessible ».
     */
    private static List<Level> storeys(List<Level> candidates) {
        List<Level> kept = new ArrayList<>();
        for (Level level : candidates) {
            if (kept.isEmpty() || level.slabY() - kept.get(kept.size() - 1).slabY() >= 3) {
                kept.add(level);
            }
        }
        return kept;
    }

    /** Un bloc de matière se trouve-t-il dans la colonne, à partir de cette hauteur ? */
    static boolean coveredFrom(AuditGrid grid, int x, int fromY, int z) {
        for (int y = fromY; y <= grid.maxY(); y++) {
            if (!grid.passable(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /** Diffusion depuis le niveau de départ, avec les déplacements d'un joueur. */
    static Set<Long> walkFrom(AuditGrid grid, int startLevel) {
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int x = grid.minX(); x <= grid.maxX(); x++) {
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                if (standable(grid, x, startLevel, z)) {
                    long k = AuditGrid.key(x, startLevel, z);
                    if (seen.add(k)) {
                        queue.add(new int[] {x, startLevel, z});
                    }
                }
            }
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] s : steps) {
                // Plain-pied, montée d'un bloc, descente d'un bloc : les trois pas d'un joueur.
                for (int dy : new int[] {0, 1, -1}) {
                    int nx = c[0] + s[0];
                    int ny = c[1] + dy;
                    int nz = c[2] + s[2];
                    if (!grid.inside(nx, ny, nz) || !standable(grid, nx, ny, nz)) {
                        continue;
                    }
                    // Monter d'un bloc exige aussi de la place au-dessus de SOI : on ne saute pas
                    // la tête dans un plafond.
                    if (dy == 1 && !grid.passable(c[0], c[1] + 2, c[2])
                            && !BlockSemantics.climbable(grid.at(c[0], c[1], c[2]))) {
                        continue;
                    }
                    long k = AuditGrid.key(nx, ny, nz);
                    if (seen.add(k)) {
                        queue.add(new int[] {nx, ny, nz});
                    }
                }
            }
            // Échelle : on monte et on descend dans la même colonne.
            for (int dy : new int[] {1, -1}) {
                int ny = c[1] + dy;
                if (grid.inside(c[0], ny, c[2])
                        && BlockSemantics.climbable(grid.at(c[0], ny, c[2]))) {
                    long k = AuditGrid.key(c[0], ny, c[2]);
                    if (seen.add(k)) {
                        queue.add(new int[] {c[0], ny, c[2]});
                    }
                }
            }
        }
        return seen;
    }

    /** Peut-on se tenir debout ici : un support dessous, deux cellules libres. */
    static boolean standable(AuditGrid grid, int x, int y, int z) {
        if (!grid.inside(x, y, z)) {
            return false;
        }
        if (BlockSemantics.climbable(grid.at(x, y, z))) {
            return true;
        }
        return BlockSemantics.support(grid.at(x, y - 1, z))
                && grid.passable(x, y, z)
                && grid.passable(x, y + 1, z);
    }

    // ---- Diagnostic -----------------------------------------------------------------------------

    /**
     * Pourquoi ce niveau n'est pas atteint, en coordonnées.
     *
     * <p>C'est le texte qui manquait à la boucle de correction : « ajoute un escalier » a produit
     * trois variantes du même bâtiment. Celui-ci dit où sont les trémies, jusqu'où un joueur monte
     * réellement, quelle cellule l'arrête, et quelle instruction l'a posée.</p>
     */
    static List<String> diagnoseUnreached(AuditGrid grid, Analysis analysis, Level target,
                                          ArchitecturalAudit.Provenance provenance) {
        List<String> lines = new ArrayList<>();
        lines.add("Plancher de l'étage en y=" + target.slabY() + " (on s'y tient en y="
                + target.standY() + "), " + target.area() + " cellule(s).");
        lines.add(holes(grid, target));

        // Le point le plus haut atteint sous ce plancher.
        int best = Integer.MIN_VALUE;
        List<int[]> top = new ArrayList<>();
        for (long k : analysis.walkable()) {
            int y = AuditGrid.keyY(k);
            if (y >= target.standY()) {
                continue;
            }
            if (y > best) {
                best = y;
                top.clear();
            }
            if (y == best && top.size() < 3) {
                top.add(new int[] {AuditGrid.keyX(k), y, AuditGrid.keyZ(k)});
            }
        }
        if (!top.isEmpty() && best > analysis.levels().get(0).standY()) {
            StringBuilder sb = new StringBuilder("Point le plus haut réellement atteint : y=" + best
                    + " (");
            for (int i = 0; i < top.size(); i++) {
                int[] c = top.get(i);
                sb.append(i == 0 ? "" : ", ").append(c[0]).append('/').append(c[1]).append('/')
                        .append(c[2]);
            }
            lines.add(sb.append(") — il manque ").append(target.standY() - best)
                    .append(" bloc(s) de montée.").toString());
        }

        lines.addAll(stairsAndLadders(grid, target));
        lines.addAll(blockers(grid, analysis.walkable(), target, provenance));
        return lines;
    }

    private static String holes(AuditGrid grid, Level target) {
        int y = target.slabY();
        Set<Long> seen = new HashSet<>();
        List<int[]> boxes = new ArrayList<>();
        for (int x = grid.minX(); x <= grid.maxX(); x++) {
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                if (!grid.interior(x, z) || !hole(grid, x, y, z) || seen.contains(AuditGrid.key(x, y, z))) {
                    continue;
                }
                int[] b = {x, z, x, z, 0};
                Deque<int[]> queue = new ArrayDeque<>();
                queue.add(new int[] {x, z});
                seen.add(AuditGrid.key(x, y, z));
                while (!queue.isEmpty()) {
                    int[] c = queue.poll();
                    b[0] = Math.min(b[0], c[0]);
                    b[1] = Math.min(b[1], c[1]);
                    b[2] = Math.max(b[2], c[0]);
                    b[3] = Math.max(b[3], c[1]);
                    b[4]++;
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = c[0] + d[0];
                        int nz = c[1] + d[1];
                        if (grid.interior(nx, nz) && hole(grid, nx, y, nz)
                                && seen.add(AuditGrid.key(nx, y, nz))) {
                            queue.add(new int[] {nx, nz});
                        }
                    }
                }
                boxes.add(b);
            }
        }
        if (boxes.isEmpty()) {
            return "Aucune trémie : le plancher y=" + y + " est plein, rien ne permet de le "
                    + "traverser.";
        }
        boxes.sort(Comparator.comparingInt((int[] b) -> -b[4]));
        StringBuilder sb = new StringBuilder("Ouverture(s) dans le plancher y=" + y + " : ");
        for (int i = 0; i < Math.min(4, boxes.size()); i++) {
            int[] b = boxes.get(i);
            sb.append(i == 0 ? "" : " ; ").append("x ").append(b[0]).append('–').append(b[2])
                    .append(", z ").append(b[1]).append('–').append(b[3]).append(" (")
                    .append(b[4]).append(" cellule(s))");
        }
        return sb.append('.').toString();
    }

    private static boolean hole(AuditGrid grid, int x, int y, int z) {
        String state = grid.at(x, y, z);
        return BlockSemantics.passable(state) || BlockSemantics.climbable(state);
    }

    private static boolean stairBlock(String state) {
        return state != null && BlockState.type(state).endsWith("_stairs");
    }

    /** Ce que le plan a posé pour monter : la marche la plus haute, et chaque échelle. */
    private static List<String> stairsAndLadders(AuditGrid grid, Level target) {
        List<String> lines = new ArrayList<>();
        int[] highest = null;
        Map<Long, int[]> ladders = new LinkedHashMap<>();
        for (int x = grid.minX(); x <= grid.maxX(); x++) {
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                if (!grid.interior(x, z)) {
                    continue;
                }
                for (int y = grid.minY(); y <= target.slabY(); y++) {
                    String state = grid.at(x, y, z);
                    // Sous le plancher seulement : une marche posée dans l'étage lui-même (une
                    // volée qui part de la trémie, comme dans le 3e plan) ne mène pas à l'étage.
                    if (stairBlock(state) && y < target.slabY()
                            && (highest == null || y > highest[1])) {
                        highest = new int[] {x, y, z};
                    }
                    if (BlockSemantics.climbable(state)) {
                        ladders.merge(AuditGrid.key(x, 0, z), new int[] {x, y, z, y},
                                (a, b) -> new int[] {a[0], Math.min(a[1], b[1]), a[2],
                                    Math.max(a[3], b[3])});
                    }
                }
            }
        }
        if (highest == null && ladders.isEmpty()) {
            lines.add("Aucun escalier ni aucune échelle sous ce plancher.");
        }
        if (highest != null) {
            lines.add("La marche la plus haute est en " + highest[0] + "/" + highest[1] + "/"
                    + highest[2] + ", sous le plancher (y=" + target.slabY() + ") : la dernière "
                    + "marche d'une volée doit AFFLEURER le plancher, donc être en y="
                    + target.slabY() + ".");
        }
        for (int[] l : ladders.values()) {
            if (l[3] < target.slabY()) {
                lines.add("L'échelle en x=" + l[0] + ", z=" + l[2] + " monte de y=" + l[1]
                        + " à y=" + l[3] + " et s'arrête " + (target.slabY() - l[3])
                        + " bloc(s) sous le plancher : elle doit monter jusqu'à y="
                        + target.slabY() + ", À TRAVERS une trémie.");
            }
        }
        return lines;
    }

    /**
     * Les cellules qui arrêtent un joueur au bord d'une marche : il a le pied, pas la tête.
     *
     * <p>On ne regarde que les marches et les échelles — un mur arrête aussi, mais il n'est pas
     * une tentative de monter et le citer noierait le diagnostic.</p>
     */
    private static List<String> blockers(AuditGrid grid, Set<Long> walkable, Level target,
                                         ArchitecturalAudit.Provenance provenance) {
        Map<Long, String> found = new LinkedHashMap<>();
        List<long[]> ordered = new ArrayList<>();
        for (long k : walkable) {
            ordered.add(new long[] {AuditGrid.keyY(k), k});
        }
        ordered.sort(Comparator.comparingLong((long[] a) -> -a[0]).thenComparingLong(a -> a[1]));
        for (long[] entry : ordered) {
            long k = entry[1];
            int x = AuditGrid.keyX(k);
            int y = AuditGrid.keyY(k);
            int z = AuditGrid.keyZ(k);
            if (y >= target.standY()) {
                continue;
            }
            boolean climbing = BlockSemantics.climbable(grid.at(x, y, z));
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (!stairBlock(grid.at(nx, y, nz))) {
                    continue;
                }
                // Monter sur la marche d'en face : les pieds et la tête au-dessus d'elle, et la
                // place pour la tête au-dessus de soi pendant la demi-marche.
                int[][] needed = {{nx, y + 1, nz}, {nx, y + 2, nz}, {x, y + 2, z}};
                if (report(grid, walkable, provenance, found, "Sur la marche " + nx + "/" + y + "/"
                        + nz, needed)) {
                    return List.copyOf(found.values());
                }
            }
            if (climbing && !BlockSemantics.climbable(grid.at(x, y + 1, z))) {
                int[][] needed = {{x, y + 1, z}, {x, y + 2, z}};
                if (report(grid, walkable, provenance, found, "Au sommet de l'échelle " + x + "/"
                        + y + "/" + z, needed)) {
                    return List.copyOf(found.values());
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** Note les cellules pleines parmi celles qu'il faudrait libres. Vrai quand la liste est pleine. */
    private static boolean report(AuditGrid grid, Set<Long> walkable,
                                  ArchitecturalAudit.Provenance provenance, Map<Long, String> found,
                                  String where, int[][] needed) {
        for (int[] c : needed) {
            if (grid.passable(c[0], c[1], c[2]) || BlockSemantics.climbable(grid.at(c[0], c[1], c[2]))) {
                continue;
            }
            long blocker = AuditGrid.key(c[0], c[1], c[2]);
            if (found.containsKey(blocker)) {
                continue;
            }
            String state = grid.at(c[0], c[1], c[2]);
            int rank = provenance.stepAt(c[0], c[1], c[2]);
            found.put(blocker, where + ", la cellule " + c[0] + "/" + c[1] + "/" + c[2]
                    + " est occupée par " + BlockState.type(state)
                    + (rank > 0 ? " — posée par l'" + provenance.describe(rank) : "")
                    + " : il faut 3 blocs libres au-dessus de chaque marche et 2 au-dessus du "
                    + "palier.");
            if (found.size() >= 4) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ce qui encombre un niveau : les instructions responsables des cellules de plancher où l'on
     * ne peut pas se tenir, de la plus encombrante à la moins encombrante.
     */
    static List<String> clutter(AuditGrid grid, LevelState state,
                                ArchitecturalAudit.Provenance provenance) {
        Map<Integer, Integer> byStep = new LinkedHashMap<>();
        Map<Integer, String> material = new LinkedHashMap<>();
        Level level = state.level();
        for (long k : level.slab()) {
            int x = AuditGrid.keyX(k);
            int z = AuditGrid.keyZ(k);
            for (int h = level.standY(); h <= level.standY() + 1; h++) {
                if (!grid.passable(x, h, z)) {
                    int rank = provenance.stepAt(x, h, z);
                    byStep.merge(rank, 1, Integer::sum);
                    material.putIfAbsent(rank, BlockState.type(grid.at(x, h, z)));
                    break;
                }
            }
        }
        List<Map.Entry<Integer, Integer>> sorted = new ArrayList<>(byStep.entrySet());
        sorted.sort(Map.Entry.<Integer, Integer>comparingByValue().reversed());
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            var e = sorted.get(i);
            lines.add((e.getKey() > 0 ? provenance.describe(e.getKey())
                    : material.get(e.getKey())) + " occupe " + e.getValue() + " cellule(s) de "
                    + "ce plancher");
        }
        return lines;
    }
}
