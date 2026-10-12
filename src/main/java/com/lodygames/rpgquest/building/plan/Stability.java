package com.lodygames.rpgquest.building.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ce qui tient après la pose, une fois la physique de Minecraft passée (#288).
 *
 * <h2>Les défauts constatés</h2>
 *
 * <ul>
 *   <li>un <strong>lit absent après pose</strong> : une {@code box} de {@code white_bed} posait deux
 *       pieds côte à côte, sans tête — la première mise à jour de voisinage les détruit ;</li>
 *   <li>des <strong>lanternes flottantes</strong>, ni suspendues ni posées, qui se détachent et se
 *       ramassent.</li>
 * </ul>
 *
 * <p>La physique n'est pas désactivée à la pose pour masquer ces défauts : la grille doit être
 * <em>stable</em>. Ce contrôle lit la grille finale et nomme chaque bloc qui tomberait, avec son
 * appui manquant et l'instruction qui l'a posé.</p>
 *
 * <h2>Le registre des dépendances</h2>
 *
 * <p>Il couvre les familles qu'un plan d'Atelier emploie réellement — luminaires, portes, lits,
 * tapis, fleurs, panneaux, bannières, boutons, rails — et ne prétend pas modéliser toute la
 * physique du jeu. Un bloc absent de ces familles est supposé tenir seul, comme un mur.</p>
 */
final class Stability {

    private Stability() {
    }

    /** Un bloc qui ne tiendrait pas, et pourquoi. */
    record Problem(int x, int y, int z, String state, String why) {
    }

    private static final Set<String> SMALL_PLANTS = Set.of("poppy", "dandelion", "allium",
            "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "blue_orchid", "wither_rose", "torchflower",
            "short_grass", "fern", "dead_bush", "brown_mushroom", "red_mushroom",
            "sweet_berry_bush", "pink_petals", "wildflowers", "leaf_litter", "closed_eyeblossom",
            "open_eyeblossom", "snow", "sea_pickle", "redstone_wire", "rail", "lily_pad");

    private static final Set<String> TALL_PLANTS = Set.of("sunflower", "lilac", "rose_bush",
            "peony", "tall_grass", "large_fern", "pitcher_plant");

    /** Un bloc qui porte ce qui est posé dessus. */
    static boolean holdsFromBelow(String below) {
        if (below == null || dependent(below)) {
            return false;
        }
        String type = BlockState.type(below);
        return !BlockSemantics.passable(below) || type.endsWith("_fence") || type.endsWith("_wall");
    }

    /** Un bloc auquel on peut suspendre une lanterne ou une enseigne. */
    static boolean holdsFromAbove(String above) {
        if (above == null) {
            return false;
        }
        String type = BlockState.type(above);
        if (type.equals("chain") || type.endsWith("_chain") || type.endsWith("_fence")
                || type.endsWith("_wall") || type.endsWith("_bars")) {
            return true;
        }
        return !dependent(above) && !BlockSemantics.passable(above);
    }

    /** Ce bloc a-t-il besoin d'un appui ? */
    private static boolean dependent(String state) {
        String type = BlockState.type(state);
        return lantern(state) || type.endsWith("torch") || type.endsWith("_carpet")
                || type.endsWith("_pressure_plate") || type.endsWith("_rail")
                || type.endsWith("candle") || type.endsWith("_sapling") || type.endsWith("_banner")
                || type.endsWith("_sign") || type.endsWith("_button") || type.equals("lever")
                || type.equals("ladder") || type.endsWith("_door") || type.endsWith("_bed")
                || SMALL_PLANTS.contains(type) || TALL_PLANTS.contains(type);
    }

    private static boolean lantern(String state) {
        return BlockState.type(state).endsWith("lantern")
                && BlockCatalog.vanilla().hasProperty(state, "hanging");
    }

    private static String prop(String state, String key) {
        int at = state.indexOf(key + "=");
        if (at < 0) {
            return "";
        }
        int end = at + key.length() + 1;
        int stop = end;
        while (stop < state.length() && state.charAt(stop) != ',' && state.charAt(stop) != ']') {
            stop++;
        }
        return state.substring(end, stop);
    }

    private static int[] step(String facing) {
        return switch (facing) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "east" -> new int[] {1, 0};
            case "west" -> new int[] {-1, 0};
            default -> new int[] {0, 0};
        };
    }

    /** Les blocs qui tomberaient faute d'appui. */
    static List<Problem> unsupported(AuditGrid grid) {
        List<Problem> out = new ArrayList<>();
        for (var e : grid.cells().entrySet()) {
            String state = e.getValue();
            String type = BlockState.type(state);
            int x = AuditGrid.keyX(e.getKey());
            int y = AuditGrid.keyY(e.getKey());
            int z = AuditGrid.keyZ(e.getKey());
            String below = grid.at(x, y - 1, z);
            String above = grid.at(x, y + 1, z);
            String why = null;
            if (lantern(state)) {
                if ("true".equals(prop(state, "hanging"))) {
                    if (!holdsFromAbove(above)) {
                        why = "lanterne suspendue sans rien au-dessus pour la tenir";
                    }
                } else if (!holdsFromBelow(below)) {
                    why = "lanterne posée sur le vide, et rien au-dessus pour la suspendre";
                }
            } else if (type.endsWith("wall_torch") || type.equals("ladder")
                    || type.endsWith("_wall_sign") || type.endsWith("_wall_banner")
                    || type.endsWith("_wall_hanging_sign")) {
                int[] d = step(prop(state, "facing"));
                if (!holdsFromBelow(grid.at(x - d[0], y, z - d[1]))) {
                    why = "aucun mur derrière lui (en " + (x - d[0]) + "/" + y + "/" + (z - d[1]) + ")";
                }
            } else if (type.endsWith("_hanging_sign")) {
                if (!holdsFromAbove(above)) {
                    why = "enseigne suspendue sans rien au-dessus";
                }
            } else if (type.endsWith("_button") || type.equals("lever")) {
                String face = prop(state, "face");
                int[] d = step(prop(state, "facing"));
                boolean ok = switch (face) {
                    case "floor" -> holdsFromBelow(below);
                    case "ceiling" -> holdsFromAbove(above);
                    default -> holdsFromBelow(grid.at(x - d[0], y, z - d[1]));
                };
                if (!ok) {
                    why = "rien pour le porter (face " + face + ")";
                }
            } else if (type.endsWith("_door")) {
                if ("lower".equals(prop(state, "half")) && !holdsFromBelow(below)) {
                    why = "porte sans seuil plein sous elle";
                }
            } else if (TALL_PLANTS.contains(type)) {
                if ("lower".equals(prop(state, "half")) && !holdsFromBelow(below)) {
                    why = "plante sans sol sous elle";
                }
            } else if ((type.endsWith("torch") || type.endsWith("_carpet")
                    || type.endsWith("_pressure_plate") || type.endsWith("_rail")
                    || type.endsWith("candle") || type.endsWith("_sapling")
                    || type.endsWith("_banner") || type.endsWith("_sign")
                    || SMALL_PLANTS.contains(type)) && below == null) {
                why = "rien sous lui";
            } else if ((type.endsWith("torch") || type.endsWith("_pressure_plate")
                    || type.endsWith("_sapling") || SMALL_PLANTS.contains(type))
                    && !holdsFromBelow(below) && !type.endsWith("_carpet")) {
                why = "posé sur « " + BlockState.type(below) + " », qui ne le porte pas";
            }
            if (why != null) {
                out.add(new Problem(x, y, z, state, why));
            }
        }
        out.sort(java.util.Comparator.comparingInt(Problem::y).thenComparingInt(Problem::z)
                .thenComparingInt(Problem::x));
        return out;
    }

    /** Les assemblages de plusieurs blocs dont une moitié manque ou ne correspond pas. */
    static List<Problem> brokenAssemblies(AuditGrid grid) {
        List<Problem> out = new ArrayList<>();
        for (var e : grid.cells().entrySet()) {
            String state = e.getValue();
            String type = BlockState.type(state);
            int x = AuditGrid.keyX(e.getKey());
            int y = AuditGrid.keyY(e.getKey());
            int z = AuditGrid.keyZ(e.getKey());
            String why = null;
            if (type.endsWith("_door") || TALL_PLANTS.contains(type)) {
                boolean lower = !"upper".equals(prop(state, "half"));
                String other = grid.at(x, lower ? y + 1 : y - 1, z);
                if (other == null || !BlockState.type(other).equals(type)
                        || prop(other, "half").equals(prop(state, "half"))
                        || !prop(other, "facing").equals(prop(state, "facing"))
                        || !prop(other, "hinge").equals(prop(state, "hinge"))) {
                    why = (lower ? "moitié basse sans moitié haute assortie au-dessus"
                            : "moitié haute sans moitié basse assortie au-dessous");
                }
            } else if (type.endsWith("_bed")) {
                String part = prop(state, "part");
                int[] d = step(prop(state, "facing"));
                int sign = "head".equals(part) ? -1 : 1;
                String other = grid.at(x + sign * d[0], y, z + sign * d[1]);
                if (part.isEmpty() || other == null || !BlockState.type(other).equals(type)
                        || prop(other, "part").equals(part)
                        || !prop(other, "facing").equals(prop(state, "facing"))) {
                    why = ("head".equals(part) ? "tête de lit sans son pied" : "pied de lit sans sa tête")
                            + " (attendu en " + (x + sign * d[0]) + "/" + y + "/" + (z + sign * d[1])
                            + ", même couleur, même orientation)";
                }
            }
            if (why != null) {
                out.add(new Problem(x, y, z, state, why));
            }
        }
        out.sort(java.util.Comparator.comparingInt(Problem::y).thenComparingInt(Problem::z)
                .thenComparingInt(Problem::x));
        return out;
    }
}
