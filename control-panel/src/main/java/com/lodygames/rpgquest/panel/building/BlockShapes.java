package com.lodygames.rpgquest.panel.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * La forme dessinée de chaque bloc de la vue en 3D (issue #786).
 *
 * <p>Un bloc n'est plus forcément un cube : c'est une liste de <strong>pavés</strong> dans sa cellule,
 * tirée de son type et de son état compilé. Une marche a sa demi-dalle et sa contremarche du bon côté
 * (droite, coin intérieur ou extérieur, retournée) ; une chaîne est une tige ; une barrière, un muret ou
 * une vitre se relie à ses voisins comme dans le jeu ; une porte, une trappe ou une échelle est un
 * panneau plaqué du côté où le jeu la pose. Les coordonnées sont celles du <strong>monde</strong> dans la
 * cellule (0 à 1 ; x vers l'est, z vers le sud) : la vue les tourne ensuite avec le bâtiment.</p>
 *
 * <p>Ce que cette classe ne dessine pas : les textures, les sens de lecture des panneaux, les têtes,
 * fleurs et autres décorations, qui restent des cubes ou de petits cubes.</p>
 */
final class BlockShapes {

    private BlockShapes() {
    }

    /** Un pavé dans la cellule ; {@code color} vide = la couleur du matériau. */
    record Box(double x0, double y0, double z0, double x1, double y1, double z1, String color) {

        Box(double x0, double y0, double z0, double x1, double y1, double z1) {
            this(x0, y0, z0, x1, y1, z1, "");
        }

        boolean fullCell() {
            return x0 == 0 && y0 == 0 && z0 == 0 && x1 == 1 && y1 == 1 && z1 == 1;
        }
    }

    /** Les voisins d'une cellule, en coordonnées du monde. */
    interface Neighbours {
        BuildingVoxels.Entry at(int dx, int dy, int dz);
    }

    /** Les quatre directions horizontales, dans le sens des aiguilles d'une montre vu du dessus. */
    enum Dir {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        final int dx;
        final int dz;

        Dir(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        Dir clockwise() {
            return values()[(ordinal() + 1) % 4];
        }

        Dir counterClockwise() {
            return values()[(ordinal() + 3) % 4];
        }

        Dir opposite() {
            return values()[(ordinal() + 2) % 4];
        }

        static Dir of(String value, Dir fallback) {
            for (Dir d : values()) {
                if (d.name().equalsIgnoreCase(value)) {
                    return d;
                }
            }
            return fallback;
        }
    }

    private static final double PX = 1.0 / 16;
    private static final List<Box> CUBE = List.of(new Box(0, 0, 0, 1, 1, 1));
    static final String FLAME = "#ffd75e";

    /** Le nom du bloc, sans namespace, en minuscules. */
    static String name(BuildingVoxels.Entry e) {
        String block = e.block().toLowerCase(Locale.ROOT);
        int colon = block.indexOf(':');
        return colon < 0 ? block : block.substring(colon + 1);
    }

    /** Ce bloc occupe-t-il toute sa cellule ? C'est la condition pour qu'il cache une face voisine. */
    static boolean fullCube(BuildingVoxels.Entry e) {
        List<Box> boxes = boxes(e, (dx, dy, dz) -> null);
        return boxes.size() == 1 && boxes.get(0).fullCell() && !liquid(name(e));
    }

    static boolean liquid(String name) {
        return name.equals("water") || name.equals("lava");
    }

    /** Les pavés du bloc. */
    static List<Box> boxes(BuildingVoxels.Entry e, Neighbours n) {
        String name = name(e);
        if (name.endsWith("_stairs")) {
            return stairs(e);
        }
        if (name.endsWith("_slab")) {
            return switch (e.shape()) {
                case "bottom" -> List.of(new Box(0, 0, 0, 1, .5, 1));
                case "top" -> List.of(new Box(0, .5, 0, 1, 1, 1));
                default -> CUBE;
            };
        }
        if (liquid(name)) {
            BuildingVoxels.Entry above = n.at(0, 1, 0);
            boolean covered = above != null && name(above).equals(name);
            return List.of(new Box(0, 0, 0, 1, covered ? 1 : 14 * PX, 1));
        }
        if (name.endsWith("glass_pane") || name.equals("iron_bars")) {
            return connected(n, 7, 9, 0, 16, BlockShapes::paneLinks);
        }
        if (name.endsWith("_fence_gate")) {
            return gate(e);
        }
        if (name.endsWith("_fence")) {
            List<Box> out = new ArrayList<>(connected(n, 6, 10, 0, 16, null));
            out.addAll(arms(n, 7, 9, 6, 9, BlockShapes::fenceLinks));
            out.addAll(arms(n, 7, 9, 12, 15, BlockShapes::fenceLinks));
            return out;
        }
        if (name.endsWith("_wall")) {
            List<Box> out = new ArrayList<>(connected(n, 4, 12, 0, 16, null));
            out.addAll(arms(n, 5, 11, 0, 14, BlockShapes::wallLinks));
            return out;
        }
        if (name.equals("chain") || name.endsWith("_chain")) {
            return List.of(rod(e.property("axis", "y"), 6.5, 9.5));
        }
        if (name.endsWith("lantern") && !name.equals("jack_o_lantern") && !name.equals("sea_lantern")) {
            double base = "true".equals(e.property("hanging", "false")) ? 1 : 0;
            return List.of(new Box(5 * PX, base * PX, 5 * PX, 11 * PX, (base + 7) * PX, 11 * PX),
                    new Box(6 * PX, (base + 7) * PX, 6 * PX, 10 * PX, (base + 9) * PX, 10 * PX, "#3f4247"));
        }
        if (name.endsWith("torch")) {
            return torch(e, name.contains("wall_torch"));
        }
        if (name.equals("bell")) {
            return List.of(new Box(5 * PX, 6 * PX, 5 * PX, 11 * PX, 13 * PX, 11 * PX),
                    new Box(4 * PX, 4 * PX, 4 * PX, 12 * PX, 6 * PX, 12 * PX));
        }
        if (name.endsWith("_trapdoor")) {
            if ("true".equals(e.property("open", "false"))) {
                return List.of(panel(Dir.of(e.property("facing", "north"), Dir.NORTH).opposite(), 3, 0, 16));
            }
            return "top".equals(e.property("half", "bottom")) ? List.of(new Box(0, 13 * PX, 0, 1, 1, 1))
                    : List.of(new Box(0, 0, 0, 1, 3 * PX, 1));
        }
        if (name.endsWith("_door")) {
            return List.of(door(e));
        }
        if (name.equals("ladder")) {
            return List.of(panel(Dir.of(e.property("facing", "north"), Dir.NORTH).opposite(), 2, 0, 16));
        }
        if (name.endsWith("_wall_sign")) {
            return List.of(panel(Dir.of(e.property("facing", "north"), Dir.NORTH).opposite(), 2, 4.5, 12.5));
        }
        if (name.endsWith("_sign") && !name.endsWith("_hanging_sign")) {
            return List.of(new Box(7.5 * PX, 0, 7.5 * PX, 8.5 * PX, 9 * PX, 8.5 * PX),
                    new Box(0, 9 * PX, 7 * PX, 1, 1, 9 * PX));
        }
        if (name.endsWith("carpet")) {
            return List.of(new Box(0, 0, 0, 1, PX, 1));
        }
        if (name.endsWith("_pressure_plate")) {
            return List.of(new Box(PX, 0, PX, 15 * PX, PX, 15 * PX));
        }
        if (e.role() == '*' && !fullLight(name)) {
            return List.of(new Box(.3, .2, .3, .7, .6, .7));
        }
        return CUBE;
    }

    /** Les lumières qui sont de vrais blocs pleins. */
    private static boolean fullLight(String name) {
        return name.equals("glowstone") || name.equals("sea_lantern") || name.equals("shroomlight")
                || name.equals("jack_o_lantern") || name.endsWith("redstone_lamp") || name.endsWith("froglight")
                || name.endsWith("copper_bulb");
    }

    /**
     * Une marche : la demi-dalle, puis la contremarche du côté {@code facing}. Droite : la moitié de ce
     * côté ; coin extérieur : un seul quart ; coin intérieur : la moitié plus un quart. {@code left} se
     * lit dans le sens du jeu : à gauche de {@code facing}, donc un quart de tour anti-horaire.
     */
    static List<Box> stairs(BuildingVoxels.Entry e) {
        Dir facing = Dir.of(e.property("facing", "north"), Dir.NORTH);
        boolean top = "top".equals(e.property("half", "bottom"));
        String shape = e.property("shape", "straight");
        double slab0 = top ? .5 : 0;
        double step0 = top ? 0 : .5;
        List<Box> out = new ArrayList<>(3);
        out.add(new Box(0, slab0, 0, 1, slab0 + .5, 1));
        Dir side = shape.endsWith("left") ? facing.counterClockwise() : facing.clockwise();
        switch (shape) {
            case "outer_left", "outer_right" -> out.add(quarter(facing, side, step0));
            case "inner_left", "inner_right" -> {
                out.add(half(facing, step0));
                out.add(quarter(facing.opposite(), side, step0));
            }
            default -> out.add(half(facing, step0));
        }
        return out;
    }

    private static Box half(Dir d, double y0) {
        double x0 = d == Dir.EAST ? .5 : 0;
        double x1 = d == Dir.WEST ? .5 : 1;
        double z0 = d == Dir.SOUTH ? .5 : 0;
        double z1 = d == Dir.NORTH ? .5 : 1;
        return new Box(x0, y0, z0, x1, y0 + .5, z1);
    }

    private static Box quarter(Dir a, Dir b, double y0) {
        Box h1 = half(a, y0);
        Box h2 = half(b, y0);
        return new Box(Math.max(h1.x0(), h2.x0()), y0, Math.max(h1.z0(), h2.z0()),
                Math.min(h1.x1(), h2.x1()), y0 + .5, Math.min(h1.z1(), h2.z1()));
    }

    /** Un panneau plaqué contre la face {@code side} de la cellule, épais de {@code px} seizièmes. */
    static Box panel(Dir side, double px, double y0, double y1) {
        double t = px * PX;
        return switch (side) {
            case NORTH -> new Box(0, y0 * PX, 0, 1, y1 * PX, t);
            case SOUTH -> new Box(0, y0 * PX, 1 - t, 1, y1 * PX, 1);
            case WEST -> new Box(0, y0 * PX, 0, t, y1 * PX, 1);
            case EAST -> new Box(1 - t, y0 * PX, 0, 1, y1 * PX, 1);
        };
    }

    /**
     * Une porte : fermée, elle est plaquée du côté opposé à {@code facing} ; ouverte, elle pivote sur
     * sa charnière — à droite, elle se plaque du côté opposé au quart de tour anti-horaire.
     */
    static Box door(BuildingVoxels.Entry e) {
        Dir facing = Dir.of(e.property("facing", "north"), Dir.NORTH);
        if (!"true".equals(e.property("open", "false"))) {
            return panel(facing.opposite(), 3, 0, 16);
        }
        boolean right = "right".equals(e.property("hinge", "left"));
        return panel((right ? facing.counterClockwise() : facing.clockwise()).opposite(), 3, 0, 16);
    }

    private static List<Box> gate(BuildingVoxels.Entry e) {
        Dir facing = Dir.of(e.property("facing", "north"), Dir.NORTH);
        boolean alongX = facing == Dir.NORTH || facing == Dir.SOUTH;
        List<Box> out = new ArrayList<>(3);
        if (alongX) {
            out.add(new Box(0, 5 * PX, 7 * PX, 2 * PX, 1, 9 * PX));
            out.add(new Box(14 * PX, 5 * PX, 7 * PX, 1, 1, 9 * PX));
            if (!"true".equals(e.property("open", "false"))) {
                out.add(new Box(2 * PX, 6 * PX, 7 * PX, 14 * PX, 15 * PX, 9 * PX));
            }
        } else {
            out.add(new Box(7 * PX, 5 * PX, 0, 9 * PX, 1, 2 * PX));
            out.add(new Box(7 * PX, 5 * PX, 14 * PX, 9 * PX, 1, 1));
            if (!"true".equals(e.property("open", "false"))) {
                out.add(new Box(7 * PX, 6 * PX, 2 * PX, 9 * PX, 15 * PX, 14 * PX));
            }
        }
        return out;
    }

    private static List<Box> torch(BuildingVoxels.Entry e, boolean onWall) {
        double cx = .5;
        double cz = .5;
        double y0 = 0;
        if (onWall) {
            Dir wall = Dir.of(e.property("facing", "north"), Dir.NORTH).opposite();
            cx += wall.dx * 5 * PX;
            cz += wall.dz * 5 * PX;
            y0 = 3 * PX;
        }
        return List.of(new Box(cx - PX, y0, cz - PX, cx + PX, y0 + 8 * PX, cz + PX, "#7a5a35"),
                new Box(cx - PX, y0 + 8 * PX, cz - PX, cx + PX, y0 + 10 * PX, cz + PX, FLAME));
    }

    /** Une tige le long de {@code axis}, de {@code from} à {@code to} seizièmes dans les deux autres. */
    static Box rod(String axis, double from, double to) {
        double a = from * PX;
        double b = to * PX;
        return switch (axis) {
            case "x" -> new Box(0, a, a, 1, b, b);
            case "z" -> new Box(a, a, 0, b, b, 1);
            default -> new Box(a, 0, a, b, 1, b);
        };
    }

    /** Un poteau central de {@code from} à {@code to} seizièmes, de {@code y0} à {@code y1}. */
    private static List<Box> connected(Neighbours n, double from, double to, double y0, double y1,
                                       java.util.function.BiPredicate<Neighbours, Dir> links) {
        List<Box> out = new ArrayList<>(5);
        out.add(new Box(from * PX, y0 * PX, from * PX, to * PX, y1 * PX, to * PX));
        if (links != null) {
            out.addAll(arms(n, from, to, y0, y1, links));
        }
        return out;
    }

    /** Les bras qui relient le poteau aux voisins acceptés par {@code links}. */
    private static List<Box> arms(Neighbours n, double from, double to, double y0, double y1,
                                  java.util.function.BiPredicate<Neighbours, Dir> links) {
        List<Box> out = new ArrayList<>(4);
        double a = from * PX;
        double b = to * PX;
        for (Dir d : Dir.values()) {
            if (!links.test(n, d)) {
                continue;
            }
            switch (d) {
                case NORTH -> out.add(new Box(a, y0 * PX, 0, b, y1 * PX, a));
                case SOUTH -> out.add(new Box(a, y0 * PX, b, b, y1 * PX, 1));
                case WEST -> out.add(new Box(0, y0 * PX, a, a, y1 * PX, b));
                case EAST -> out.add(new Box(b, y0 * PX, a, 1, y1 * PX, b));
            }
        }
        return out;
    }

    private static boolean solid(BuildingVoxels.Entry e) {
        return e != null && fullCube(e) && !IsometricView.translucent(e);
    }

    static boolean fenceLinks(Neighbours n, Dir d) {
        BuildingVoxels.Entry e = n.at(d.dx, 0, d.dz);
        if (e == null) {
            return false;
        }
        String name = name(e);
        return name.endsWith("_fence") || name.endsWith("_fence_gate") || solid(e);
    }

    static boolean wallLinks(Neighbours n, Dir d) {
        BuildingVoxels.Entry e = n.at(d.dx, 0, d.dz);
        if (e == null) {
            return false;
        }
        String name = name(e);
        return name.endsWith("_wall") || name.endsWith("_fence_gate") || name.endsWith("glass_pane")
                || name.equals("iron_bars") || solid(e);
    }

    static boolean paneLinks(Neighbours n, Dir d) {
        BuildingVoxels.Entry e = n.at(d.dx, 0, d.dz);
        if (e == null) {
            return false;
        }
        String name = name(e);
        return name.endsWith("glass_pane") || name.equals("iron_bars") || name.endsWith("_wall")
                || (fullCube(e) && (solid(e) || name.endsWith("glass")));
    }
}
