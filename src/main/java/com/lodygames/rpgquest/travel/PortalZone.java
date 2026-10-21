package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.building.model.Facing;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition.Zone;

/**
 * Zone d'activation d'un portail dérivée d'un emplacement #213 (issue #565, #228-P2) : fonction
 * pure, sans Bukkit.
 *
 * <p>La <strong>largeur</strong> se mesure perpendiculairement à la face de l'emplacement, la
 * <strong>profondeur</strong> le long de cette face, la <strong>hauteur</strong> part du bloc
 * d'ancrage vers le haut. Largeur et profondeur sont centrées sur l'ancre ; pour une taille paire,
 * le bloc en plus va vers les X/Z croissants. Une arche face au nord (ou au sud) est donc large en X
 * et fine en Z ; face à l'est (ou à l'ouest), large en Z et fine en X.</p>
 */
public final class PortalZone {

    /** Taille maximale d'un côté, en blocs : un portail est un passage, pas une région. */
    public static final int MAX_SIDE = 32;

    private PortalZone() {
    }

    public static Zone around(int x, int y, int z, Facing facing, int width, int height, int depth) {
        checkSide("largeur", width);
        checkSide("hauteur", height);
        checkSide("profondeur", depth);
        boolean alongX = facing == Facing.NORTH || facing == Facing.SOUTH;
        int spanX = alongX ? width : depth;
        int spanZ = alongX ? depth : width;
        int minX = x - (spanX - 1) / 2;
        int minZ = z - (spanZ - 1) / 2;
        return new Zone(minX, y, minZ, minX + spanX - 1, y + height - 1, minZ + spanZ - 1);
    }

    /** {@code null} si la zone saisie en min/max est acceptable, sinon la raison lisible. */
    public static String checkExplicit(Zone zone) {
        if (zone.maxX() - zone.minX() + 1 > MAX_SIDE || zone.maxY() - zone.minY() + 1 > MAX_SIDE
                || zone.maxZ() - zone.minZ() + 1 > MAX_SIDE) {
            return "Zone trop grande : " + MAX_SIDE + " blocs au plus de chaque côté.";
        }
        return null;
    }

    private static void checkSide(String label, int value) {
        if (value < 1 || value > MAX_SIDE) {
            throw new IllegalArgumentException("La " + label + " doit être comprise entre 1 et " + MAX_SIDE + " blocs.");
        }
    }
}
