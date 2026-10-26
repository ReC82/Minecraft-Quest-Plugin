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
 * bois, brique…) et du <strong>rôle</strong>, pas des textures du jeu ; l'eau, le verre et la glace
 * sont translucides. Depuis #786, la <strong>forme</strong> vient du bloc et de son état compilé
 * ({@link BlockShapes}) : marches orientées, droites ou en coin, retournées ; dalles ; chaînes, barrières,
 * murets et vitres reliés à leurs voisins ; portes, trappes, échelles, lanternes, torches, cloches. Le
 * mobilier et les décorations restent des cubes : c'est la limite assumée, dite à l'écran.</p>
 *
 * <h2>Comment</h2>
 *
 * <p>Projection isométrique exacte (direction de vue {@code (1, 1, 1)}) : les cubes se dessinent du
 * plus lointain au plus proche en triant sur {@code u + y + v}, ce qui est exact pour une grille de
 * cubes vue sous cet angle ; les pavés d'une même cellule suivent le même ordre. Une face cachée par un bloc opaque voisin n'est pas émise, ce qui garde le
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

    /** Une couleur de la légende, et le nombre de blocs de ce matériau dans la grille. */
    public record Swatch(String color, String label, int count) {

        public Swatch(String color, String label) {
            this(color, label, 0);
        }
    }

    /**
     * Le SVG du bâtiment.
     *
     * @param view     0 à 3, voir {@link #viewLabel}
     * @param maxLevel dernier niveau dessiné (inclus) ; au-delà de la hauteur, tout est dessiné
     * @return le SVG, ou une chaîne vide si la grille est absente
     */
    public static String svg(BuildingVoxels voxels, int view, int maxLevel, String title) {
        return svg(voxels, view, maxLevel, title, null);
    }

    /**
     * Le SVG du bâtiment, avec le volume de passage d'une arche de portail (issue #568) dessiné
     * par-dessus : contour pointillé et faces translucides violettes, comme un portail du Nether.
     * Le volume est dessiné <em>devant</em> les blocs, volontairement : il est fait d'air, et c'est
     * lui qu'on veut voir. Une coupe le tronque au même niveau que le bâtiment.
     *
     * @param passage le volume à montrer, ou {@code null}
     */
    public static String svg(BuildingVoxels voxels, int view, int maxLevel, String title, PassageVolume passage) {
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
                + paths + "</g>" + passageOverlay(g, passage, top) + "</svg>";
    }

    /** Couleur du volume de passage : le violet d'un portail du Nether. */
    public static final String PASSAGE_COLOR = "#a64dff";

    private static String passageOverlay(Rotated g, PassageVolume passage, int top) {
        if (passage == null || passage.minY() > top) {
            return "";
        }
        int[] a = g.view(passage.minX(), passage.minZ());
        int[] b = g.view(passage.maxX(), passage.maxZ());
        double u0 = Math.min(a[0], b[0]);
        double u1 = Math.max(a[0], b[0]) + 1;
        double v0 = Math.min(a[1], b[1]);
        double v1 = Math.max(a[1], b[1]) + 1;
        double y0 = passage.minY();
        double y1 = Math.min(passage.maxY(), top) + 1;
        StringBuilder sb = new StringBuilder("<g class=\"iso-passage\" stroke=\"").append(PASSAGE_COLOR)
                .append("\" stroke-width=\"1.2\" stroke-dasharray=\"3 2\" stroke-linejoin=\"round\">");
        // Arêtes cachées d'abord (derrière, en pointillé léger), puis les trois faces visibles.
        String hidden = " fill=\"none\" stroke-opacity=\".55\"";
        line(sb, hidden, p(u0, y0, v0), p(u1, y0, v0));
        line(sb, hidden, p(u0, y0, v0), p(u0, y0, v1));
        line(sb, hidden, p(u0, y0, v0), p(u0, y1, v0));
        String visible = " fill-opacity=\".28\"";
        face(sb, PASSAGE_COLOR, visible, p(u0, y1, v0), p(u1, y1, v0), p(u1, y1, v1), p(u0, y1, v1));
        face(sb, PASSAGE_COLOR, visible, p(u1, y0, v0), p(u1, y1, v0), p(u1, y1, v1), p(u1, y0, v1));
        face(sb, PASSAGE_COLOR, visible, p(u0, y0, v1), p(u1, y0, v1), p(u1, y1, v1), p(u0, y1, v1));
        return sb.append("</g>").toString();
    }

    private static void line(StringBuilder sb, String attrs, double[] from, double[] to) {
        sb.append("<path d=\"M").append(num(from[0])).append(' ').append(num(from[1])).append('L')
                .append(num(to[0])).append(' ').append(num(to[1])).append('"').append(attrs).append("/>");
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
        // Plusieurs symboles peuvent porter le même matériau (états différents : marches tournées,
        // lanternes suspendues ou posées) : on les additionne.
        Map<String, Integer> byLabel = new LinkedHashMap<>();
        Map<String, String> colors = new LinkedHashMap<>();
        for (var e : counts.entrySet()) {
            BuildingVoxels.Entry entry = voxels.palette().get(e.getKey());
            String label = entry.block().replace('_', ' ');
            byLabel.merge(label, e.getValue(), Integer::sum);
            colors.putIfAbsent(label, color(entry));
        }
        return byLabel.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(max).map(e -> new Swatch(colors.get(e.getKey()), e.getKey(), e.getValue())).toList();
    }

    // ---- Dessin ------------------------------------------------------------------------------

    private static void drawCell(StringBuilder sb, Rotated g, int u, int y, int v, int top) {
        BuildingVoxels.Entry e = g.at(u, y, v);
        String base = color(e);
        String opacity = translucent(e) ? " fill-opacity=\"" + opacity(e) + "\"" : "";
        int[] w = g.world(u, v);
        List<BlockShapes.Box> boxes = new ArrayList<>();
        for (BlockShapes.Box box : BlockShapes.boxes(e, (dx, dy, dz) -> g.voxels.at(w[0] + dx, y + dy, w[1] + dz))) {
            boxes.add(g.toView(box));
        }
        // Dans la cellule, du plus lointain au plus proche, comme les cellules entre elles.
        boxes.sort(Comparator.comparingDouble(b -> b.x0() + b.x1() + b.y0() + b.y1() + b.z0() + b.z1()));
        for (BlockShapes.Box b : boxes) {
            // En repère de vue : x = u, z = v. Une face sur le bord de la cellule est cachée par un
            // voisin plein et opaque — ou par le même bloc (de l'eau contre de l'eau, une vitre contre
            // une vitre). Le dessus reste toujours visible au niveau de coupe.
            String fill = b.color().isEmpty() ? base : b.color();
            double u0 = u + b.x0();
            double u1 = u + b.x1();
            double y0 = y + b.y0();
            double y1 = y + b.y1();
            double v0 = v + b.z0();
            double v1 = v + b.z1();
            if (b.y1() < 1 || y == top || !hides(e, g.at(u, y + 1, v))) {
                face(sb, shade(fill, 1.0), opacity, p(u0, y1, v0), p(u1, y1, v0), p(u1, y1, v1), p(u0, y1, v1));
            }
            if (b.x1() < 1 || !hides(e, g.at(u + 1, y, v))) {
                face(sb, shade(fill, 0.8), opacity, p(u1, y0, v0), p(u1, y1, v0), p(u1, y1, v1), p(u1, y0, v1));
            }
            if (b.z1() < 1 || !hides(e, g.at(u, y, v + 1))) {
                face(sb, shade(fill, 0.64), opacity, p(u0, y0, v1), p(u1, y0, v1), p(u1, y1, v1), p(u0, y1, v1));
            }
        }
    }

    /** Le voisin cache-t-il la face qui le touche ? Un bloc plein et opaque, ou le même bloc plein. */
    private static boolean hides(BuildingVoxels.Entry self, BuildingVoxels.Entry neighbour) {
        if (neighbour == null) {
            return false;
        }
        if (BlockShapes.name(neighbour).equals(BlockShapes.name(self))
                && (BlockShapes.fullCube(neighbour) || BlockShapes.liquid(BlockShapes.name(self)))) {
            return true;
        }
        return BlockShapes.fullCube(neighbour) && !translucent(neighbour);
    }

    /** Ce qu'on voit à travers : l'eau, le verre, la glace. */
    static boolean translucent(BuildingVoxels.Entry e) {
        String name = BlockShapes.name(e);
        return name.equals("water") || name.contains("glass") || name.equals("ice") || name.equals("frosted_ice")
                || name.equals("slime_block") || name.equals("honey_block")
                || (e.role() == 'o' && !name.equals("iron_bars"));
    }

    private static String opacity(BuildingVoxels.Entry e) {
        String name = BlockShapes.name(e);
        if (name.equals("water")) {
            return ".62";
        }
        return name.contains("stained") || name.contains("tinted") ? ".55" : ".35";
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

    /**
     * Blocs reconnaissables à leur nom exact ou à un fragment précis, essayés avant les familles :
     * l'eau ne doit jamais prendre la couleur d'une pierre, ni une chaîne celle du fer poli.
     */
    private static final String[][] SPECIFIC = {
            {"water", "#2f6fd6"}, {"lava", "#e8641c"}, {"tinted_glass", "#3d3446"}, {"glass", "#bfe9f5"},
            {"packed_ice", "#8db4ea"}, {"blue_ice", "#74a8f3"}, {"ice", "#a5c8f7"},
            {"chain", "#3c4047"}, {"iron_bars", "#5b6068"}, {"lantern", "#ffcf4d"}, {"bell", "#e0b53a"},
            {"ladder", "#9a7440"}, {"mossy", "#6f7d52"}, {"cracked_stone_brick", "#8a8880"},
            {"chiseled_stone_brick", "#9c9a92"}, {"smooth_stone", "#b3b3b0"}, {"cobblestone", "#686868"},
            {"end_stone", "#dcdea0"}, {"blackstone", "#2f2a30"}, {"deepslate", "#4d4d52"},
            {"stone_brick", "#a6a49b"}};

    /** L'écorce : un tronc ne doit pas se confondre avec les planches de la même essence. */
    private static final String[][] BARK = {
            {"dark_oak", "#3c2c18"}, {"spruce", "#3b2a17"}, {"birch", "#e4e1d7"}, {"jungle", "#56461d"},
            {"acacia", "#676157"}, {"mangrove", "#544231"}, {"cherry", "#3a2230"}, {"crimson", "#5c1d32"},
            {"warped", "#3a2c4a"}, {"pale_oak", "#5e5a55"}, {"oak", "#5b4528"}};

    /** Familles de matériaux, dans l'ordre où on les essaie : la plus précise d'abord. */
    private static final String[][] FAMILIES = {
            {"dark_oak", "#4a3320"}, {"pale_oak", "#e3d9d3"}, {"spruce", "#7a5a35"}, {"birch", "#d6c48a"},
            {"jungle", "#a8754c"}, {"acacia", "#c26d3f"}, {"mangrove", "#773933"}, {"cherry", "#e2b4a8"},
            {"bamboo", "#cdb85a"}, {"crimson", "#7e3a56"}, {"warped", "#3a8a80"}, {"oak", "#b8905a"},
            {"nether_brick", "#3c1f24"}, {"mud_brick", "#8c6e55"}, {"deepslate", "#4d4d52"},
            {"end_stone", "#dcdea0"}, {"brick", "#9c4f3c"},
            {"blackstone", "#2f2a30"}, {"cobble", "#686868"},
            {"andesite", "#888a8c"}, {"diorite", "#c9c9c4"}, {"granite", "#9a6b58"}, {"tuff", "#6d6f66"},
            {"sandstone", "#dccf9a"}, {"quartz", "#ece7df"}, {"calcite", "#e6e4dd"}, {"snow", "#f4f8fa"},
            {"terracotta", "#a0603f"}, {"prismarine", "#5ea597"}, {"copper", "#c06a4a"},
            {"leaves", "#4f8a3a"}, {"grass", "#6aa04a"}, {"dirt", "#7a5a3a"}, {"mud", "#4e443a"},
            {"gravel", "#857b78"}, {"sand", "#dbcf9f"}, {"clay", "#a0a6b3"},
            {"stone", "#8f8f8f"}, {"log", "#5b4528"}, {"wood", "#5b4528"}, {"planks", "#b8905a"},
            {"bookshelf", "#8a6a3e"}, {"wool", "#e8e8e8"}, {"concrete", "#cfcfcf"}, {"iron", "#d8d8d8"},
            {"gold", "#e8c14a"}, {"hay", "#c9a93a"}};

    private static final String[][] DYES = {{"white", "#e9ecec"}, {"light_gray", "#9d9d97"},
            {"gray", "#474f52"}, {"black", "#1d1d21"}, {"brown", "#835432"}, {"red", "#a12722"},
            {"orange", "#f07613"}, {"yellow", "#f8c627"}, {"lime", "#70b919"}, {"green", "#546d1b"},
            {"cyan", "#158991"}, {"light_blue", "#3aafd9"}, {"blue", "#35399d"}, {"purple", "#792aac"},
            {"magenta", "#bd44b3"}, {"pink", "#ed8dac"}};

    /** La couleur de base d'un bloc : sa teinture, puis un nom précis, son rôle, son écorce, sa famille. */
    static String color(BuildingVoxels.Entry e) {
        String block = BlockShapes.name(e);
        if (block.contains("wool") || block.contains("concrete") || block.contains("stained")
                || block.contains("carpet") || block.contains("candle") || block.contains("bed")
                || (block.contains("terracotta") && !block.equals("terracotta"))) {
            for (String[] dye : DYES) {
                if (block.startsWith(dye[0] + "_")) {
                    return dye[1];
                }
            }
        }
        for (String[] specific : SPECIFIC) {
            if (block.contains(specific[0])) {
                return specific[1];
            }
        }
        if (e.role() == '*') {
            return BlockShapes.FLAME;
        }
        if (e.role() == 'H') {
            return "#9a7440";
        }
        if (e.role() == 'o') {
            return "#bfe9f5";
        }
        boolean trunk = block.endsWith("_log") || block.endsWith("_wood") || block.endsWith("_stem")
                || block.endsWith("_hyphae");
        if (trunk && !block.startsWith("stripped_")) {
            for (String[] bark : BARK) {
                if (block.contains(bark[0])) {
                    return bark[1];
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
        final BuildingVoxels voxels;
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

        /** Les coordonnées {@code x, z} du monde de la cellule {@code u, v}. */
        int[] world(int u, int v) {
            int sx = voxels.sizeX();
            int sz = voxels.sizeZ();
            return switch (view) {
                case 1 -> new int[] {sx - 1 - v, u};
                case 2 -> new int[] {sx - 1 - u, sz - 1 - v};
                case 3 -> new int[] {v, sz - 1 - u};
                default -> new int[] {u, v};
            };
        }

        /** La cellule {@code u, v} de la vue qui montre la colonne {@code x, z} du monde : l'inverse de {@link #world}. */
        int[] view(int x, int z) {
            int sx = voxels.sizeX();
            int sz = voxels.sizeZ();
            return switch (view) {
                case 1 -> new int[] {z, sx - 1 - x};
                case 2 -> new int[] {sx - 1 - x, sz - 1 - z};
                case 3 -> new int[] {sz - 1 - z, x};
                default -> new int[] {x, z};
            };
        }

        /** Un pavé du monde, dans le repère de la vue : {@code x} devient {@code u}, {@code z} devient {@code v}. */
        BlockShapes.Box toView(BlockShapes.Box b) {
            return switch (view) {
                case 1 -> new BlockShapes.Box(b.z0(), b.y0(), 1 - b.x1(), b.z1(), b.y1(), 1 - b.x0(), b.color());
                case 2 -> new BlockShapes.Box(1 - b.x1(), b.y0(), 1 - b.z1(), 1 - b.x0(), b.y1(), 1 - b.z0(), b.color());
                case 3 -> new BlockShapes.Box(1 - b.z1(), b.y0(), b.x0(), 1 - b.z0(), b.y1(), b.x1(), b.color());
                default -> b;
            };
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
