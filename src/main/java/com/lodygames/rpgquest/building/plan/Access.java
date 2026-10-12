package com.lodygames.rpgquest.building.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * L'accès réel à un bâtiment : du terrain extérieur, par une porte, jusqu'à un sol intérieur
 * (#288–#292 ; Maison de Lily, #291).
 *
 * <h2>Ce que l'ancien contrôle laissait passer</h2>
 *
 * <p>L'entrée était cherchée sur la limite de la <em>grille</em>. Sur la Maison de Lily, la porte et
 * les vitres s'étaient posées sur la limite du volume, à trois blocs des murs : la limite de la
 * grille était donc la porte isolée elle-même, l'audit y trouvait un « passage » — air devant, air
 * derrière — et déclarait conforme une maison hermétiquement close. Ici, rien ne se lit sur les
 * bornes : on marche.</p>
 *
 * <h2>Le modèle</h2>
 *
 * <ul>
 *   <li><strong>Le terrain.</strong> L'ancre d'un emplacement est la case libre au-dessus du bloc
 *       cliqué : le plan y pose sa couche {@code anchorY}. Le terrain est donc supposé plat en
 *       {@code anchorY - 1}, et l'on s'y tient en {@code anchorY}. L'adaptation au terrain réel
 *       relève du placement, pas de la conception ;</li>
 *   <li><strong>L'intérieur.</strong> Une cellule franchissable, couverte, fermée dans au moins
 *       trois des quatre directions horizontales par un mur, une porte ou un vitrage. Trois et non
 *       quatre : la cellule derrière une porte ouverte voit le dehors par la porte ;</li>
 *   <li><strong>La marche.</strong> Les pas d'un joueur — à plat, un bloc de montée, un bloc de
 *       descente, les échelles — avec la tête libre. Une montée d'un bloc entier est un
 *       <em>saut</em>, sauf sur une marche d'escalier tournée dans le sens de la marche ou sur une
 *       dalle basse.</li>
 * </ul>
 */
final class Access {

    private Access() {
    }

    /**
     * Le verdict.
     *
     * @param entered    un joueur venu du terrain atteint un sol intérieur
     * @param stepFree   il l'atteint sans sauter
     * @param doors      les portes de la grille (moitié basse)
     * @param detachedDoors portes qui ne bordent aucun intérieur
     * @param detachedGlazing vitrages hors de tout mur : ni intérieur ni maçonnerie autour
     * @param blindGlazing vitrages dans un mur, mais bouchés côté intérieur par un bloc plein
     */
    record Report(boolean entered, boolean stepFree, boolean hasInterior, List<int[]> doors,
                  List<int[]> detachedDoors, List<int[]> detachedGlazing, List<int[]> blindGlazing,
                  List<String> thresholds, int groundY) {
    }

    /** Marge horizontale autour de la grille où l'on part du terrain. */
    private static final int MARGIN = 2;

    static Report analyse(AuditGrid grid, int groundY) {
        List<int[]> doors = new ArrayList<>();
        List<int[]> detachedDoors = new ArrayList<>();
        List<int[]> detachedGlazing = new ArrayList<>();
        List<int[]> blindGlazing = new ArrayList<>();
        boolean hasInterior = false;
        for (int y = grid.minY(); y <= grid.maxY() && !hasInterior; y++) {
            for (int x = grid.minX(); x <= grid.maxX() && !hasInterior; x++) {
                for (int z = grid.minZ(); z <= grid.maxZ() && !hasInterior; z++) {
                    hasInterior = interior(grid, x, y, z);
                }
            }
        }
        for (var e : grid.cells().entrySet()) {
            String state = e.getValue();
            int x = AuditGrid.keyX(e.getKey());
            int y = AuditGrid.keyY(e.getKey());
            int z = AuditGrid.keyZ(e.getKey());
            if (lowerDoor(state)) {
                doors.add(new int[] {x, y, z});
                if (!bordersInterior(grid, x, y, z) && !bordersInterior(grid, x, y + 1, z)
                        && !embedded(grid, x, y, z) && !embedded(grid, x, y + 1, z)) {
                    detachedDoors.add(new int[] {x, y, z});
                }
            } else if (BlockSemantics.glazing(state) && !bordersInterior(grid, x, y, z)) {
                (embedded(grid, x, y, z) ? blindGlazing : detachedGlazing).add(new int[] {x, y, z});
            }
        }
        Set<Long> any = walk(grid, groundY, false);
        Set<Long> free = walk(grid, groundY, true);
        boolean entered = any.stream().anyMatch(k -> standingInside(grid, k));
        boolean stepFree = free.stream().anyMatch(k -> standingInside(grid, k));
        List<String> thresholds = new ArrayList<>();
        for (int[] d : doors) {
            thresholds.add(threshold(grid, d, groundY, any));
        }
        return new Report(entered, stepFree, hasInterior, doors, detachedDoors, detachedGlazing,
                blindGlazing, thresholds, groundY);
    }

    private static boolean standingInside(AuditGrid grid, long k) {
        return interior(grid, AuditGrid.keyX(k), AuditGrid.keyY(k), AuditGrid.keyZ(k));
    }

    static boolean lowerDoor(String state) {
        return state != null && BlockState.type(state).endsWith("_door") && state.contains("half=lower");
    }

    private static boolean door(String state) {
        return state != null && BlockState.type(state).endsWith("_door");
    }

    /** Une cellule fermante : un mur, un vitrage, une porte. */
    private static boolean closing(AuditGrid grid, int x, int y, int z) {
        String state = grid.at(x, y, z);
        return !BlockSemantics.passable(state) || door(state);
    }

    /** Une cellule d'intérieur : franchissable, couverte, close sur trois côtés au moins. */
    static boolean interior(AuditGrid grid, int x, int y, int z) {
        String state = grid.at(x, y, z);
        if (!BlockSemantics.passable(state) || door(state) || !grid.inside(x, y, z)
                || !Circulation.coveredFrom(grid, x, y + 1, z)) {
            return false;
        }
        int hits = 0;
        int[][] rays = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] r : rays) {
            for (int nx = x + r[0], nz = z + r[1]; grid.inside(nx, y, nz); nx += r[0], nz += r[1]) {
                if (closing(grid, nx, y, nz)) {
                    hits++;
                    break;
                }
            }
        }
        return hits >= 3;
    }

    /**
     * Une ouverture borde-t-elle un intérieur ? Directement, ou à travers le mobilier rangé contre
     * le mur (au plus {@value #THROUGH_FURNITURE} cellules) : une fenêtre derrière une rangée de
     * bibliothèques reste une fenêtre de la salle — la Bibliothèque du Hub en est faite.
     */
    private static boolean bordersInterior(AuditGrid grid, int x, int y, int z) {
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        for (int[] d : n) {
            for (int k = 1; k <= THROUGH_FURNITURE + 1; k++) {
                int nx = x + d[0] * k;
                int ny = y + d[1] * k;
                int nz = z + d[2] * k;
                if (interior(grid, nx, ny, nz)) {
                    return true;
                }
                if (!BlockSemantics.furnishing(grid.at(nx, ny, nz))) {
                    break;
                }
            }
        }
        return false;
    }

    private static final int THROUGH_FURNITURE = 2;

    /**
     * Une ouverture tenue par de la maçonnerie : un bloc de structure (ni meuble, ni vitrage, ni
     * porte) parmi ses six voisines — un linteau, un appui, un jambage. Une vitre posée seule dans
     * le jardin, comme celles de la Maison de Lily, n'en a aucune.
     */
    private static boolean embedded(AuditGrid grid, int x, int y, int z) {
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        for (int[] d : n) {
            String state = grid.at(x + d[0], y + d[1], z + d[2]);
            if (BlockSemantics.support(state) && !BlockSemantics.furnishing(state)
                    && !BlockSemantics.glazing(state) && !door(state)) {
                return true;
            }
        }
        return false;
    }

    /** Ce qui porte un joueur : la grille, ou le terrain supposé sous la couche {@code groundY}. */
    private static boolean support(AuditGrid grid, int x, int y, int z, int groundY) {
        String state = grid.at(x, y, z);
        if (state == null) {
            return y < groundY;
        }
        return BlockSemantics.support(state);
    }

    private static boolean standable(AuditGrid grid, int x, int y, int z, int groundY) {
        if (BlockSemantics.climbable(grid.at(x, y, z))) {
            return true;
        }
        return support(grid, x, y - 1, z, groundY)
                && BlockSemantics.passable(grid.at(x, y, z))
                && BlockSemantics.passable(grid.at(x, y + 1, z));
    }

    private static boolean inDomain(AuditGrid grid, int x, int y, int z, int groundY) {
        return x >= grid.minX() - MARGIN && x <= grid.maxX() + MARGIN
                && z >= grid.minZ() - MARGIN && z <= grid.maxZ() + MARGIN
                && y >= Math.min(groundY, grid.minY()) && y <= grid.maxY() + 1;
    }

    /**
     * Monter d'un bloc sans sauter : sur une marche d'escalier (moitié basse) tournée dans le sens
     * de la marche, ou sur une dalle basse.
     */
    private static boolean gentleRise(String support, int dx, int dz) {
        if (support == null) {
            return false;
        }
        String type = BlockState.type(support);
        if (type.endsWith("_slab")) {
            return support.contains("type=bottom") || !support.contains("type=");
        }
        if (type.endsWith("_stairs") && !support.contains("half=top")) {
            String facing = dx > 0 ? "east" : dx < 0 ? "west" : dz > 0 ? "south" : "north";
            return support.contains("facing=" + facing);
        }
        return false;
    }

    /** Diffusion depuis le terrain, tout autour de la grille. */
    static Set<Long> walk(AuditGrid grid, int groundY, boolean noJump) {
        Set<Long> seen = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int x = grid.minX() - MARGIN; x <= grid.maxX() + MARGIN; x++) {
            for (int z = grid.minZ() - MARGIN; z <= grid.maxZ() + MARGIN; z++) {
                boolean outside = x < grid.minX() || x > grid.maxX() || z < grid.minZ() || z > grid.maxZ();
                if (outside && standable(grid, x, groundY, z, groundY)
                        && seen.add(AuditGrid.key(x, groundY, z))) {
                    queue.add(new int[] {x, groundY, z});
                }
            }
        }
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] s : steps) {
                for (int dy : new int[] {0, 1, -1}) {
                    int nx = c[0] + s[0];
                    int ny = c[1] + dy;
                    int nz = c[2] + s[1];
                    if (!inDomain(grid, nx, ny, nz, groundY) || !standable(grid, nx, ny, nz, groundY)) {
                        continue;
                    }
                    if (dy == 1) {
                        if (!BlockSemantics.passable(grid.at(c[0], c[1] + 2, c[2]))
                                && !BlockSemantics.climbable(grid.at(c[0], c[1], c[2]))) {
                            continue;
                        }
                        if (noJump && !gentleRise(grid.at(nx, ny - 1, nz), s[0], s[1])) {
                            continue;
                        }
                    }
                    if (dy == -1 && !BlockSemantics.passable(grid.at(nx, ny + 2, nz))) {
                        continue;
                    }
                    if (seen.add(AuditGrid.key(nx, ny, nz))) {
                        queue.add(new int[] {nx, ny, nz});
                    }
                }
            }
            for (int dy : new int[] {1, -1}) {
                int ny = c[1] + dy;
                if (inDomain(grid, c[0], ny, c[2], groundY)
                        && BlockSemantics.climbable(grid.at(c[0], ny, c[2]))
                        && seen.add(AuditGrid.key(c[0], ny, c[2]))) {
                    queue.add(new int[] {c[0], ny, c[2]});
                }
            }
        }
        return seen;
    }

    /** Ce que voit un joueur devant une porte : seuil, terrain, marche manquante. */
    private static String threshold(AuditGrid grid, int[] d, int groundY, Set<Long> reached) {
        String state = grid.at(d[0], d[1], d[2]);
        String where = d[0] + "/" + d[1] + "/" + d[2];
        String facing = state.replaceAll(".*facing=([a-z]+).*", "$1");
        int ox = switch (facing) {
            case "east" -> 1;
            case "west" -> -1;
            default -> 0;
        };
        int oz = switch (facing) {
            case "south" -> 1;
            case "north" -> -1;
            default -> 0;
        };
        boolean inFront = interior(grid, d[0] + ox, d[1], d[2] + oz);
        boolean behind = interior(grid, d[0] - ox, d[1], d[2] - oz);
        if (!inFront && !behind) {
            return "porte " + where + " : elle ne donne sur aucun intérieur (ni devant ni derrière "
                    + "elle) — elle est posée hors des murs";
        }
        if (reached.contains(AuditGrid.key(d[0], d[1], d[2]))) {
            int rise = d[1] - groundY;
            return "porte " + where + " : atteinte depuis le terrain" + (rise > 0
                    ? ", seuil " + rise + " bloc(s) au-dessus du terrain (y=" + groundY + ")" : "");
        }
        int ex = inFront ? d[0] - ox : d[0] + ox;
        int ez = inFront ? d[2] - oz : d[2] + oz;
        int rise = d[1] - groundY;
        StringBuilder sb = new StringBuilder("porte " + where + " : injoignable depuis le terrain — ");
        if (!BlockSemantics.passable(grid.at(ex, d[1], ez)) || !BlockSemantics.passable(grid.at(ex, d[1] + 1, ez))) {
            sb.append("la cellule devant elle (").append(ex).append('/').append(d[1]).append('/').append(ez)
                    .append(") est occupée par ").append(BlockStates.bareType(grid.at(ex, d[1], ez) == null
                            ? grid.at(ex, d[1] + 1, ez) : grid.at(ex, d[1], ez)));
        } else if (rise > 1) {
            sb.append("son seuil est ").append(rise).append(" blocs au-dessus du terrain (y=")
                    .append(groundY).append(") sans perron ni marches");
        } else if (rise < 0) {
            sb.append("son seuil est sous le niveau du terrain (y=").append(groundY).append(')');
        } else {
            sb.append("aucun chemin praticable n'y mène");
        }
        return sb.toString();
    }
}
