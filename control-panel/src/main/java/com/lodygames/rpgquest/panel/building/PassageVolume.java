package com.lodygames.rpgquest.panel.building;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Le volume de passage d'une arche de portail (issue #568), tel que le serveur l'a relevé sur la grille :
 * coordonnées <strong>locales</strong> du plan, bornes incluses. C'est la future zone de téléportation,
 * reprise telle quelle à la liaison du portail — l'aperçu 3D la dessine pour qu'on la voie avant la pose.
 */
public record PassageVolume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** Le passage transmis par {@code building.plan.preview}, s'il tient dans la grille ; vide sinon. */
    public static Optional<PassageVolume> from(Map<String, Object> details, BuildingVoxels voxels) {
        if (details == null || !(details.get("passage") instanceof Map<?, ?> raw) || voxels == null
                || !voxels.present()) {
            return Optional.empty();
        }
        Integer[] v = new Integer[6];
        String[] keys = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        for (int i = 0; i < keys.length; i++) {
            if (!(raw.get(keys[i]) instanceof Number n)) {
                return Optional.empty();
            }
            v[i] = n.intValue();
        }
        PassageVolume p = new PassageVolume(v[0], v[1], v[2], v[3], v[4], v[5]);
        boolean inside = p.minX >= 0 && p.minY >= 0 && p.minZ >= 0 && p.minX <= p.maxX && p.minY <= p.maxY
                && p.minZ <= p.maxZ && p.maxX < voxels.sizeX() && p.maxY < voxels.sizeY()
                && p.maxZ < voxels.sizeZ();
        return inside ? Optional.of(p) : Optional.empty();
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    /** La largeur du passage, mesurée le long de la façade (nord ou sud : selon X ; est ou ouest : selon Z). */
    public int width(String front) {
        return crossAlongX(front) ? sizeZ() : sizeX();
    }

    /** L'épaisseur traversée, perpendiculaire à la façade. */
    public int depth(String front) {
        return crossAlongX(front) ? sizeX() : sizeZ();
    }

    /** Le passage en une phrase : largeur, hauteur, profondeur et sens de la traversée. */
    public String describe(String front) {
        int width = width(front);
        return "Zone de téléportation : " + width + " bloc" + (width > 1 ? "s" : "") + " de large, " + sizeY()
                + " de haut, " + depth(front) + " de profondeur ; on la traverse " + (crossAlongX(front)
                ? "d'est en ouest" : "du nord au sud") + ". Après la pose, le portail reprend exactement ce "
                + "volume, tourné avec l'arche : aucune coordonnée à saisir.";
    }

    /**
     * L'écart entre le passage relevé et celui demandé au projet, en une phrase ; vide s'ils coïncident
     * ou si rien n'a été demandé ({@code 0}).
     */
    public String mismatch(String front, int wantedWidth, int wantedHeight) {
        boolean widthOff = wantedWidth > 0 && width(front) != wantedWidth;
        boolean heightOff = wantedHeight > 0 && sizeY() != wantedHeight;
        if (!widthOff && !heightOff) {
            return "";
        }
        return "Le passage relevé mesure " + width(front) + " × " + sizeY() + " (largeur × hauteur), le projet en "
                + "demande " + wantedWidth + " × " + wantedHeight + ". Le portail reprendra le passage relevé : "
                + "corrigez le plan si ce n'est pas l'arche voulue.";
    }

    private static boolean crossAlongX(String front) {
        return switch (front == null ? "" : front.toUpperCase(Locale.ROOT)) {
            case "EAST", "WEST" -> true;
            default -> false;
        };
    }
}
