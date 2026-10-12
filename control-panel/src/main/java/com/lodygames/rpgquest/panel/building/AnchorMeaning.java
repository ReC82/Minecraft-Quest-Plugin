package com.lodygames.rpgquest.panel.building;

import java.util.Locale;

/**
 * Où tombe le repère d'un emplacement sur un bâtiment, en une phrase (issue #269).
 *
 * <p>Il n'y a pas <em>une</em> convention : la hutte de test, faite à la main, a son ancre au milieu
 * de la porte, un bloc au-dessus de sa fondation ; un bâtiment de l'Atelier, sans ancre déclarée, a la
 * sienne au centre de la base, au niveau du bas ({@code BuildingPlanYaml} : {@code sizeX / 2},
 * {@code 0}, {@code sizeZ / 2}). Afficher partout « centre de la porte » aurait été faux pour tout ce
 * que produit l'Atelier. On décrit donc l'ancre réelle de chaque bâtiment.</p>
 */
public final class AnchorMeaning {

    private AnchorMeaning() {
    }

    /** L'ancre par défaut d'un plan sans ancre déclarée — la même règle que le plugin. */
    public static boolean isDefault(int sizeX, int sizeZ, int ax, int ay, int az) {
        return ax == sizeX / 2 && ay == 0 && az == sizeZ / 2;
    }

    public static String describe(int sizeX, int sizeY, int sizeZ, int ax, int ay, int az, String front) {
        if (sizeX <= 0 || sizeZ <= 0) {
            return "";
        }
        String f = front == null ? "" : front.toUpperCase(Locale.ROOT);
        boolean onFront = switch (f) {
            case "NORTH" -> az == 0;
            case "SOUTH" -> az == sizeZ - 1;
            case "WEST" -> ax == 0;
            case "EAST" -> ax == sizeX - 1;
            default -> false;
        };
        boolean centredAlongFront = switch (f) {
            case "NORTH", "SOUTH" -> ax == sizeX / 2 || ax == (sizeX - 1) / 2;
            case "WEST", "EAST" -> az == sizeZ / 2 || az == (sizeZ - 1) / 2;
            default -> false;
        };
        String where;
        if (ax == sizeX / 2 && az == sizeZ / 2) {
            where = "au centre du bâtiment";
        } else if (onFront) {
            where = centredAlongFront ? "au milieu de la façade d'entrée" : "sur la façade d'entrée";
        } else {
            where = "en un point choisi par le plan";
        }
        String height = ay == 0 ? "au niveau du bas du bâtiment"
                : ay + (ay == 1 ? " bloc" : " blocs") + " au-dessus du bas du bâtiment (ce qui est plus bas "
                        + "s'enfonce dans le sol)";
        return "Le repère de l'emplacement tombe " + where + ", " + height + ".";
    }
}
