package com.lodygames.rpgquest.panel.ai;

import com.lodygames.rpgquest.panel.building.BuildingVoxels;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Ce que l'on peut MESURER sur le résultat compilé, au-delà de la présence d'un bloc (#747).
 *
 * <h2>Pourquoi</h2>
 *
 * <p>Église du hub, 23/10/2026 : « cloche présente » était coché alors que le plan posait trois cloches
 * au sommet d'une colonne d'un bloc, au-dessus du toit. Une cloche ne fait pas un clocher. Ces mesures
 * lisent la grille que le plugin a réellement calculée pour l'aperçu ({@link BuildingVoxels}) — jamais le
 * texte du plan — et ne prétendent rien de plus que ce qu'elles mesurent :</p>
 *
 * <ul>
 *   <li><strong>partie haute distincte</strong> : un groupe de colonnes contiguës, d'au moins 3 × 3 d'emprise
 *       et 8 colonnes (un pourtour de murs), dont le
 *       sommet dépasse d'au moins {@value #TOWER_RISE} blocs la hauteur médiane du bâtiment. Une
 *       colonne isolée n'en est pas une ;</li>
 *   <li><strong>cloche dans la partie haute</strong> : une cloche dans l'emprise de ce groupe, au-dessus
 *       de la hauteur médiane ;</li>
 *   <li><strong>allée</strong> : la plus longue file droite et continue de tapis, sur un même niveau ;</li>
 *   <li><strong>volume de la conception réalisé</strong> : la part des colonnes du pourtour de son
 *       emprise qui montent jusqu'au haut de ses murs.</li>
 * </ul>
 *
 * <p>Rien n'est mesuré sans grille : l'appelant le dit (« non mesurée »), il ne conclut pas.</p>
 */
public final class CompiledMeasures {

    /** Hauteur minimale d'une partie haute au-dessus de la médiane des sommets. */
    public static final int TOWER_RISE = 4;

    private final BuildingVoxels voxels;
    private final int[][] top;

    private CompiledMeasures(BuildingVoxels voxels) {
        this.voxels = voxels;
        this.top = new int[voxels.sizeX()][voxels.sizeZ()];
        for (int x = 0; x < voxels.sizeX(); x++) {
            for (int z = 0; z < voxels.sizeZ(); z++) {
                top[x][z] = -1;
                for (int y = voxels.sizeY() - 1; y >= 0; y--) {
                    if (voxels.at(x, y, z) != null) {
                        top[x][z] = y;
                        break;
                    }
                }
            }
        }
    }

    /** Les mesures d'une grille, ou rien si elle est absente. */
    public static Optional<CompiledMeasures> of(BuildingVoxels voxels) {
        return voxels == null || !voxels.present() ? Optional.empty() : Optional.of(new CompiledMeasures(voxels));
    }

    /** Une partie haute mesurée : emprise, sommet, et hauteur au-dessus du reste. */
    public record HighPart(int minX, int maxX, int minZ, int maxZ, int columns, int summit, int median) {

        public String describe() {
            return "partie haute de " + (maxX - minX + 1) + " × " + (maxZ - minZ + 1) + " colonnes (x " + minX + "–"
                    + maxX + ", z " + minZ + "–" + maxZ + "), sommet y=" + summit + ", " + (summit - median)
                    + " blocs au-dessus de la hauteur médiane du bâtiment (y=" + median + ")";
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** La hauteur médiane des sommets des colonnes bâties. */
    public int medianTop() {
        List<Integer> tops = new ArrayList<>();
        for (int[] row : top) {
            for (int t : row) {
                if (t >= 0) {
                    tops.add(t);
                }
            }
        }
        if (tops.isEmpty()) {
            return -1;
        }
        tops.sort(Integer::compare);
        return tops.get(tops.size() / 2);
    }

    /** La plus grande partie haute distincte, si elle existe. */
    public Optional<HighPart> highPart() {
        int median = medianTop();
        if (median < 0) {
            return Optional.empty();
        }
        int sx = voxels.sizeX();
        int sz = voxels.sizeZ();
        boolean[][] seen = new boolean[sx][sz];
        HighPart best = null;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                if (seen[x][z] || top[x][z] < median + TOWER_RISE) {
                    continue;
                }
                int[] box = {x, x, z, z};
                int count = 0;
                int summit = 0;
                Deque<int[]> queue = new ArrayDeque<>();
                queue.add(new int[] {x, z});
                seen[x][z] = true;
                while (!queue.isEmpty()) {
                    int[] c = queue.poll();
                    count++;
                    summit = Math.max(summit, top[c[0]][c[1]]);
                    box[0] = Math.min(box[0], c[0]);
                    box[1] = Math.max(box[1], c[0]);
                    box[2] = Math.min(box[2], c[1]);
                    box[3] = Math.max(box[3], c[1]);
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = c[0] + d[0];
                        int nz = c[1] + d[1];
                        if (nx >= 0 && nz >= 0 && nx < sx && nz < sz && !seen[nx][nz]
                                && top[nx][nz] >= median + TOWER_RISE) {
                            seen[nx][nz] = true;
                            queue.add(new int[] {nx, nz});
                        }
                    }
                }
                // Une tour a des murs : au moins 3 × 3 colonnes d'emprise, et au moins un pourtour de 3 × 3
                // (8 colonnes) qui monte. Des cloches alignées au sommet d'une colonne (V-2caffe63) n'en sont pas.
                boolean wide = box[1] - box[0] >= 2 && box[3] - box[2] >= 2 && count >= 8;
                if (wide && (best == null || count > best.columns())) {
                    best = new HighPart(box[0], box[1], box[2], box[3], count, summit, median);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Les cellules d'un bloc donné ({@code minecraft:bell}…), comme {@code {x, y, z}}. */
    public List<int[]> cellsOf(String block) {
        String bare = block.startsWith("minecraft:") ? block.substring("minecraft:".length()) : block;
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < voxels.sizeY(); y++) {
            for (int z = 0; z < voxels.sizeZ(); z++) {
                for (int x = 0; x < voxels.sizeX(); x++) {
                    BuildingVoxels.Entry e = voxels.at(x, y, z);
                    if (e != null && bareOf(e.block()).equals(bare)) {
                        out.add(new int[] {x, y, z});
                    }
                }
            }
        }
        return out;
    }

    /** Une cloche dans la partie haute, au-dessus de la hauteur médiane. */
    public Optional<int[]> bellInHighPart() {
        Optional<HighPart> part = highPart();
        if (part.isEmpty()) {
            return Optional.empty();
        }
        return cellsOf("bell").stream().filter(c -> part.get().contains(c[0], c[2]) && c[1] > part.get().median())
                .findFirst();
    }

    /** La plus longue file droite et continue de tapis sur un même niveau (longueur, axe). */
    public record Run(int length, String axis, int x, int y, int z) {
    }

    /**
     * La plus longue file de tapis dans l'axe donné ({@code "x"} ou {@code "z"}) ; dans les deux axes si
     * l'axe est inconnu.
     */
    public Run longestCarpetRun(String axis) {
        Run best = new Run(0, "", 0, 0, 0);
        boolean alongX = !"z".equals(axis);
        boolean alongZ = !"x".equals(axis);
        for (int y = 0; y < voxels.sizeY(); y++) {
            for (int z = 0; alongX && z < voxels.sizeZ(); z++) {
                int run = 0;
                for (int x = 0; x < voxels.sizeX(); x++) {
                    run = carpet(x, y, z) ? run + 1 : 0;
                    if (run > best.length()) {
                        best = new Run(run, "x", x - run + 1, y, z);
                    }
                }
            }
            for (int x = 0; alongZ && x < voxels.sizeX(); x++) {
                int run = 0;
                for (int z = 0; z < voxels.sizeZ(); z++) {
                    run = carpet(x, y, z) ? run + 1 : 0;
                    if (run > best.length()) {
                        best = new Run(run, "z", x, y, z - run + 1);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Une allée : une file d'au moins 5 tapis dans l'axe de l'entrée (z pour une façade nord ou sud, x
     * pour est ou ouest), sur au moins 40 % de la longueur du bâtiment dans cet axe. Une nappe de tapis
     * posée en travers n'est pas une allée.
     */
    public boolean hasAisle(String axis) {
        Run r = longestCarpetRun(axis);
        int span = "x".equals(r.axis()) ? voxels.sizeX() : voxels.sizeZ();
        return r.length() >= 5 && r.length() * 10 >= span * 4;
    }

    /** L'axe de l'entrée d'un bâtiment orienté {@code front} : z pour nord/sud, x pour est/ouest, vide sinon. */
    public static String entranceAxis(String front) {
        String f = front == null ? "" : front.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (f) {
            case "NORTH", "SOUTH" -> "z";
            case "EAST", "WEST" -> "x";
            default -> "";
        };
    }

    /**
     * La réalisation d'un volume de la conception : colonnes de son pourtour qui montent jusqu'au haut
     * de ses murs (à un bloc près), sur le nombre de colonnes du pourtour qui tiennent dans la grille.
     */
    public int[] perimeterReaching(int minX, int maxX, int minZ, int maxZ, int wallTop) {
        int reached = 0;
        int total = 0;
        for (int x = Math.max(0, minX); x <= Math.min(voxels.sizeX() - 1, maxX); x++) {
            for (int z = Math.max(0, minZ); z <= Math.min(voxels.sizeZ() - 1, maxZ); z++) {
                boolean border = x == minX || x == maxX || z == minZ || z == maxZ;
                if (!border) {
                    continue;
                }
                total++;
                if (top[x][z] >= wallTop - 1) {
                    reached++;
                }
            }
        }
        return new int[] {reached, total};
    }

    private boolean carpet(int x, int y, int z) {
        BuildingVoxels.Entry e = voxels.at(x, y, z);
        return e != null && bareOf(e.block()).endsWith("_carpet");
    }

    private static String bareOf(String block) {
        String b = block == null ? "" : block;
        int bracket = b.indexOf('[');
        b = bracket < 0 ? b : b.substring(0, bracket);
        return b.startsWith("minecraft:") ? b.substring("minecraft:".length()) : b;
    }
}
