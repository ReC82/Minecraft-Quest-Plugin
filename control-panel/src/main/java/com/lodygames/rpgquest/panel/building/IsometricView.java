package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * La vue en 3D d'un bâtiment, dessinée en SVG côté serveur (issue #273).
 *
 * <h2>Ce qu'elle montre, et ce qu'elle ne prétend pas montrer</h2>
 *
 * <p>Chaque cube dessiné est un bloc de la grille que le serveur construira ({@link BuildingVoxels}) :
 * un mur sans porte apparaît sans porte. Les couleurs viennent du <strong>matériau</strong> (pierre,
 * bois, brique…) et du <strong>rôle</strong> (vitre translucide, lumière jaune), pas des textures du
 * jeu. Portes, escaliers, vitres et mobilier sont des cubes ; les dalles des demi-cubes ; les lumières
 * de petits cubes. Ce sont les limites assumées de cette première version, dites à l'écran.</p>
 *
 * <h2>Comment</h2>
 *
 * <p>Projection isométrique exacte (direction de vue {@code (1, 1, 1)}) : les cubes se dessinent du
 * plus lointain au plus proche en triant sur {@code u + y + v}, ce qui est exact pour une grille de
 * cubes vue sous cet angle. Une face cachée par un bloc opaque voisin n'est pas émise, ce qui garde le
 * SVG léger. Aucun script : la CSP du panel interdit le JavaScript en ligne, et un SVG statique suffit.
 * Quatre orientations (on tourne autour du bâtiment) et une coupe par niveau (on retire tout ce qui est
 * au-dessus pour voir l'intérieur).</p>
 */
public final class IsometricView {

    private IsometricView() {
    }

    /** Demi-largeur d'un cube à l'écran ; la hauteur d'un cube vaut la même chose. */
    private static final int A = 8;

    /** Les quatre points de vue, dans l'ordre où l'on fait le tour du bâtiment. */
    private static final String[] VIEW_LABELS = {"depuis le sud-est", "depuis le sud-ouest",
            "depuis le nord-ouest", "depuis le nord-est"};
    /** Pour chaque vue, la direction de la face de droite puis de celle de gauche. */
    private static final String[][] VISIBLE = {{"EAST", "SOUTH"}, {"SOUTH", "WEST"}, {"WEST", "NORTH"},
            {"NORTH", "EAST"}};

    public static int views() {
        return VIEW_LABELS.length;
    }

    public static String viewLabel(int view) {
        return VIEW_LABELS[Math.floorMod(view, VIEW_LABELS.length)];
    }

    /** La vue qui montre l'entrée, sur la face de gauche. */
    public static int defaultView(String front) {
        return switch (front == null ? "" : front.toUpperCase(Locale.ROOT)) {
            case "WEST" -> 1;
            case "NORTH" -> 2;
            case "EAST" -> 3;
            default -> 0;
        };
    }

    /** Où se trouve l'entrée dans cette vue, en une phrase. */
    public static String frontHint(String front, int view) {
        String f = front == null ? "" : front.toUpperCase(Locale.ROOT);
        String[] visible = VISIBLE[Math.floorMod(view, VISIBLE.length)];
        String side = f.equals(visible[0]) ? "à droite" : f.equals(visible[1]) ? "à gauche" : "";
        String name = switch (f) {
            case "NORTH" -> "nord";
            case "EAST" -> "est";
            case "WEST" -> "ouest";
            case "SOUTH" -> "sud";
            default -> "";
        };
        if (name.isEmpty()) {
            return "";
        }
        return side.isEmpty() ? "L'entrée (côté " + name + ") est de l'autre côté : tournez autour du bâtiment."
                : "L'entrée (côté " + name + ") est la face " + side + ".";
    }

    /** Une couleur de la légende. */
    public record Swatch(String color, String label) {
    }

    /**
     * Le SVG du bâtiment.
     *
     * @param view     0 à 3, voir {@link #viewLabel}
     * @param maxLevel dernier niveau dessiné (inclus) ; au-delà de la hauteur, tout est dessiné
     * @return le SVG, ou une chaîne vide si la grille est absente
     */
    public static String svg(BuildingVoxels voxels, int view, int maxLevel, String title) {
        if (voxels == null || !voxels.present()) {
            return "";
        }
        Rotated g = new Rotated(voxels, Math.floorMod(view, VIEW_LABELS.length));
        int top = Math.min(Math.max(maxLevel, 0), voxels.sizeY() - 1);
        List<int[]> cells = new ArrayList<>();
        for (int y = 0; y <= top; y++) {
            for (int v = 0; v < g.depth; v++) {
                for (int u = 0; u < g.width; u++) {
                    if (g.at(u, y, v) != null) {
                        cells.add(new int[] {u, y, v});
                    }
                }
            }
        }
        cells.sort(Comparator.<int[]>comparingInt(c -> c[0] + c[1] + c[2])
                .thenComparingInt(c -> c[1]).thenComparingInt(c -> c[0]));

        StringBuilder paths = new StringBuilder(cells.size() * 120);
        for (int[] c : cells) {
            drawCell(paths, g, c[0], c[1], c[2], top);
        }
        // Cadre : de la pointe gauche (u = 0, v = D) à la pointe droite (u = W, v = 0), du sommet
        // (y = H) à la base avant (u = W, v = D).
        int minX = -g.depth * A - A;
        int maxX = g.width * A + A;
        int minY = -(top + 1) * A - A;
        int maxY = (g.width + g.depth) * A / 2 + A;
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"iso-view\" viewBox=\"" + minX + " " + minY
                + " " + (maxX - minX) + " " + (maxY - minY) + "\" role=\"img\" aria-label=\""
                + escape(title) + "\"><title>" + escape(title) + "</title>"
                // Un liseré léger, posé une fois pour toutes les faces : il dessine les blocs (on les
                // compte), et masque les fins interstices que l'anticrénelage laisse entre deux faces
                // voisines — d'un navigateur à l'autre, sinon, le bâtiment paraîtrait « fissuré ».
                + "<g stroke=\"#000\" stroke-opacity=\".14\" stroke-width=\".35\" stroke-linejoin=\"round\">"
                + paths + "</g></svg>";
    }

    /** Les matériaux présents, du plus employé au moins employé, avec leur couleur. */
    public static List<Swatch> legend(BuildingVoxels voxels, int max) {
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (List<String> layer : voxels.layers()) {
            for (String row : layer) {
                for (int i = 0; i < row.length(); i++) {
                    char c = row.charAt(i);
                    if (c != BuildingVoxels.EMPTY) {
                        counts.merge(c, 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Swatch> out = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<Character, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    BuildingVoxels.Entry entry = voxels.palette().get(e.getKey());
                    String label = entry.block().replace('_', ' ');
                    out.putIfAbsent(label, new Swatch(color(entry), label));
                });
        return out.values().stream().limit(max).toList();
    }

    // ---- Dessin ------------------------------------------------------------------------------

    private static void drawCell(StringBuilder sb, Rotated g, int u, int y, int v, int top) {
        BuildingVoxels.Entry e = g.at(u, y, v);
        String base = color(e);
        String opacity = e.role() == 'o' ? " fill-opacity=\".45\"" : "";
        double y0 = y;
        double y1 = y + 1;
        double inset = 0;
        if ("bottom".equals(e.shape())) {
            y1 = y + 0.5;
        } else if ("top".equals(e.shape())) {
            y0 = y + 0.5;
        }
        if (e.role() == '*') {
            inset = 0.3;
            y0 = y + 0.2;
            y1 = y + 0.6;
        }
        boolean full = inset == 0 && y0 == y && y1 == y + 1;
        double u0 = u + inset;
        double u1 = u + 1 - inset;
        double v0 = v + inset;
        double v1 = v + 1 - inset;
        // Face du dessus : visible sauf sous un bloc opaque — et toujours au niveau de coupe.
        if (!full || y == top || !hides(e, g.at(u, y + 1, v))) {
            face(sb, shade(base, 1.0), opacity, p(u0, y1, v0), p(u1, y1, v0), p(u1, y1, v1), p(u0, y1, v1));
        }
        if (!full || !hides(e, g.at(u + 1, y, v))) {
            face(sb, shade(base, 0.8), opacity, p(u1, y0, v0), p(u1, y1, v0), p(u1, y1, v1), p(u1, y0, v1));
        }
        if (!full || !hides(e, g.at(u, y, v + 1))) {
            face(sb, shade(base, 0.64), opacity, p(u0, y0, v1), p(u1, y0, v1), p(u1, y1, v1), p(u0, y1, v1));
        }
    }

    /** Le voisin cache-t-il la face qui le touche ? Un bloc opaque plein, ou une vitre contre une vitre. */
    private static boolean hides(BuildingVoxels.Entry self, BuildingVoxels.Entry neighbour) {
        if (neighbour == null || !"full".equals(neighbour.shape())) {
            return false;
        }
        if (neighbour.symbol() == self.symbol()) {
            return true;
        }
        return neighbour.role() == '#' || neighbour.role() == '/';
    }

    private static double[] p(double u, double y, double v) {
        return new double[] {(u - v) * A, (u + v) * A / 2.0 - y * A};
    }

    private static void face(StringBuilder sb, String fill, String opacity, double[]... points) {
        sb.append("<path d=\"M");
        for (int i = 0; i < points.length; i++) {
            if (i > 0) {
                sb.append('L');
            }
            sb.append(num(points[i][0])).append(' ').append(num(points[i][1]));
        }
        sb.append("Z\" fill=\"").append(fill).append('"').append(opacity).append("/>");
    }

    private static String num(double value) {
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < 1e-9) {
            return Long.toString(rounded);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // ---- Couleurs ----------------------------------------------------------------------------

    /** Familles de matériaux, dans l'ordre où on les essaie : la plus précise d'abord. */
    private static final String[][] FAMILIES = {
            {"dark_oak", "#4a3320"}, {"spruce", "#7a5a35"}, {"birch", "#d6c48a"}, {"jungle", "#a8754c"},
            {"acacia", "#b5643a"}, {"mangrove", "#773933"}, {"cherry", "#e2b4a8"}, {"bamboo", "#cdb85a"},
            {"crimson", "#7e3a56"}, {"warped", "#3a8a80"}, {"oak", "#b48c56"},
            {"nether_brick", "#3c1f24"}, {"mud_brick", "#8c6e55"}, {"deepslate", "#4d4d52"},
            {"end_stone", "#dcdea0"}, {"stone_brick", "#7f7f7f"}, {"brick", "#9c4f3c"},
            {"blackstone", "#2f2a30"}, {"cobble", "#7d7d7d"},
            {"andesite", "#8a8a8a"}, {"diorite", "#c9c9c4"}, {"granite", "#9a6b58"}, {"tuff", "#6d6f66"},
            {"sandstone", "#dccf9a"}, {"quartz", "#ece7df"}, {"calcite", "#e6e4dd"}, {"snow", "#f4f8fa"},
            {"terracotta", "#a0603f"}, {"prismarine", "#5ea597"}, {"copper", "#c06a4a"},
            {"leaves", "#4f8a3a"}, {"grass", "#6aa04a"}, {"dirt", "#7a5a3a"}, {"mud", "#4e443a"},
            {"stone", "#8f8f8f"}, {"log", "#6b5233"}, {"wood", "#6b5233"}, {"planks", "#b48c56"},
            {"bookshelf", "#8a6a3e"}, {"wool", "#e8e8e8"}, {"concrete", "#cfcfcf"}, {"iron", "#d8d8d8"},
            {"gold", "#e8c14a"}, {"hay", "#c9a93a"}};

    private static final String[][] DYES = {{"white", "#e9ecec"}, {"light_gray", "#9d9d97"},
            {"gray", "#474f52"}, {"black", "#1d1d21"}, {"brown", "#835432"}, {"red", "#a12722"},
            {"orange", "#f07613"}, {"yellow", "#f8c627"}, {"lime", "#70b919"}, {"green", "#546d1b"},
            {"cyan", "#158991"}, {"light_blue", "#3aafd9"}, {"blue", "#35399d"}, {"purple", "#792aac"},
            {"magenta", "#bd44b3"}, {"pink", "#ed8dac"}};

    /** La couleur de base d'un bloc : son rôle d'abord, puis sa teinture, puis sa famille. */
    static String color(BuildingVoxels.Entry e) {
        String block = e.block().toLowerCase(Locale.ROOT);
        if (e.role() == '*') {
            return "#ffd75e";
        }
        if (e.role() == 'H') {
            return "#9a7440";
        }
        if (e.role() == 'o' && !block.contains("stained")) {
            return "#a8d8f0";
        }
        if (block.contains("wool") || block.contains("concrete") || block.contains("stained")
                || block.contains("carpet") || (block.contains("terracotta") && !block.equals("terracotta"))) {
            for (String[] dye : DYES) {
                if (block.startsWith(dye[0] + "_")) {
                    return dye[1];
                }
            }
        }
        for (String[] family : FAMILIES) {
            if (block.contains(family[0])) {
                return family[1];
            }
        }
        return "#9a9a9a";
    }

    /** Assombrit une couleur : les trois faces visibles d'un cube ne reçoivent pas la même lumière. */
    static String shade(String hex, double factor) {
        if (factor >= 1.0) {
            return hex;
        }
        int r = (int) Math.round(Integer.parseInt(hex.substring(1, 3), 16) * factor);
        int gr = (int) Math.round(Integer.parseInt(hex.substring(3, 5), 16) * factor);
        int b = (int) Math.round(Integer.parseInt(hex.substring(5, 7), 16) * factor);
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, gr, b);
    }

    private static String escape(String text) {
        return (text == null ? "" : text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** La grille vue sous l'une des quatre orientations : on tourne autour, on ne la reflète jamais. */
    private static final class Rotated {
        private final BuildingVoxels voxels;
        private final int view;
        private final int width;
        private final int depth;

        Rotated(BuildingVoxels voxels, int view) {
            this.voxels = voxels;
            this.view = view;
            boolean swapped = view % 2 == 1;
            this.width = swapped ? voxels.sizeZ() : voxels.sizeX();
            this.depth = swapped ? voxels.sizeX() : voxels.sizeZ();
        }

        BuildingVoxels.Entry at(int u, int y, int v) {
            if (u < 0 || v < 0 || u >= width || v >= depth) {
                return null;
            }
            int sx = voxels.sizeX();
            int sz = voxels.sizeZ();
            return switch (view) {
                case 1 -> voxels.at(sx - 1 - v, y, u);
                case 2 -> voxels.at(sx - 1 - u, y, sz - 1 - v);
                case 3 -> voxels.at(v, y, sz - 1 - u);
                default -> voxels.at(u, y, v);
            };
        }
    }
}
