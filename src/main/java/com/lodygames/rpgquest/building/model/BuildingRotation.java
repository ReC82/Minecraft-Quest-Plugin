package com.lodygames.rpgquest.building.model;

/**
 * La rotation à appliquer pour qu'un bâtiment regarde dans la direction d'un emplacement
 * (issue #213, lot « placement »).
 *
 * <h2>Pourquoi ce calcul vit ici, et pas dans l'adaptateur WorldEdit</h2>
 *
 * <p>C'est la règle la plus facile à casser de tout le chantier, et la plus coûteuse à constater :
 * une erreur de signe ne se voit pas dans un test d'intégration, elle se voit en jeu, une fois la
 * hutte posée à l'envers. Le calcul est donc une <strong>fonction pure</strong>, sans aucun type
 * WorldEdit ni Bukkit, et il est exécuté pour de vrai par les tests. WorldEdit n'est qu'un moteur
 * de collage : il ne décide rien ici.</p>
 *
 * <h2>Convention des axes, énoncée une seule fois</h2>
 *
 * <p>Repère du monde Minecraft : {@code +X} = est, {@code +Z} = sud, {@code -Z} = nord. L'azimut
 * croît donc dans le sens <strong>horaire vu de dessus</strong> : nord → est → sud → ouest.</p>
 *
 * <p>La rotation appliquée est l'écart d'azimut entre la façade de référence du bâtiment
 * ({@link BuildingDefinition#front()}) et l'orientation voulue de l'emplacement. Elle est toujours
 * un multiple de 90°, parce qu'un bâtiment se pose aligné sur la grille du monde.</p>
 */
public final class BuildingRotation {

    private BuildingRotation() {
    }

    /**
     * Azimut d'une orientation, en degrés horaires depuis le nord.
     *
     * <p>Rien à voir avec le yaw de Minecraft (où {@code 0} regarde le <em>sud</em>) : on veut ici
     * un azimut de boussole ordinaire, parce que c'est lui qui se soustrait proprement.</p>
     */
    public static int azimuthOf(Facing facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
        };
    }

    /**
     * La rotation à appliquer pour amener {@code reference} sur {@code target}, dans
     * {@code {0, 90, 180, 270}}.
     */
    public static int degreesBetween(Facing reference, Facing target) {
        return Math.floorMod(azimuthOf(target) - azimuthOf(reference), 360);
    }

    /**
     * Normalise un angle quelconque sur un multiple de 90° dans {@code [0, 360)}.
     *
     * <p>Utile pour relire une rotation stockée : une valeur aberrante doit se refermer sur une
     * valeur licite plutôt que de propager un angle impossible jusqu'au collage.</p>
     */
    public static int normalize(int degrees) {
        int wrapped = Math.floorMod(degrees, 360);
        return (wrapped / 90) * 90;
    }

    /** Vrai si l'angle échange les axes X et Z — donc si les dimensions se croisent. */
    public static boolean swapsAxes(int degrees) {
        int normalized = normalize(degrees);
        return normalized == 90 || normalized == 270;
    }

    /**
     * Fait tourner un décalage horizontal autour de l'axe Y, dans le sens <strong>horaire vu de
     * dessus</strong> (nord → est → sud → ouest).
     *
     * <p>Le sens n'est pas un détail de goût : il est fixé par la convention d'azimut ci-dessus.
     * Vérification de bon sens, celle que les tests rejouent : à 90°, la direction « nord »
     * {@code (0, -1)} doit devenir « est » {@code (1, 0)}.</p>
     *
     * @return le décalage tourné, {@code [dx, dz]}
     */
    public static int[] rotateOffset(int dx, int dz, int degrees) {
        return switch (normalize(degrees)) {
            case 90 -> new int[] {-dz, dx};
            case 180 -> new int[] {-dx, -dz};
            case 270 -> new int[] {dz, -dx};
            default -> new int[] {dx, dz};
        };
    }

    /** L'orientation obtenue en faisant tourner {@code facing} de {@code degrees}. */
    public static Facing rotate(Facing facing, int degrees) {
        int azimuth = Math.floorMod(azimuthOf(facing) + normalize(degrees), 360);
        return switch (azimuth) {
            case 90 -> Facing.EAST;
            case 180 -> Facing.SOUTH;
            case 270 -> Facing.WEST;
            default -> Facing.NORTH;
        };
    }
}
