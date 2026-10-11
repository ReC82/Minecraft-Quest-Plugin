package com.lodygames.rpgquest.building.plan;

import com.lodygames.rpgquest.building.model.Facing;
import java.util.Locale;
import java.util.Optional;

/**
 * Une façade désignée <strong>par son rôle</strong>, pas par sa coordonnée (issue #94, lot C).
 *
 * <h2>Le défaut que cela supprime</h2>
 *
 * <p>Le plan V1 déclarait {@code front: WEST}, et le modèle devait en déduire lui-même que la
 * façade avant était en {@code x = 0}. Il a écrit {@code x = 5}. Son percement de porte a donc vidé
 * de l'air intérieur déjà vide, et le bâtiment est resté hermétiquement fermé — 532 cellules de mur,
 * zéro ouverture.</p>
 *
 * <p>Avec {@code on: FRONT}, le modèle ne calcule plus rien. Le moteur résout la façade depuis
 * {@code front}, de façon déterministe, et c'est une classe entière d'erreurs qui disparaît.</p>
 *
 * <h2>Repère</h2>
 *
 * <p>Les coordonnées locales d'un plan suivent Minecraft : {@code -Z} est le nord, {@code +X} l'est.
 * {@code FRONT} est donc le plan de coordonnée minimale ou maximale selon l'orientation déclarée —
 * et {@code LEFT}/{@code RIGHT} sont vus <strong>depuis l'extérieur, face au bâtiment</strong>,
 * comme le dirait un visiteur et non comme le lirait un plan.</p>
 */
public enum Facade {

    /** La façade d'entrée, celle que {@code front} désigne. */
    FRONT,
    /** La façade opposée. */
    BACK,
    /** À gauche quand on regarde le bâtiment depuis l'extérieur de sa façade avant. */
    LEFT,
    /** À droite, même convention. */
    RIGHT;

    public static Optional<Facade> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /**
     * L'orientation cardinale réelle de cette façade, pour un bâtiment dont l'avant regarde
     * {@code front}.
     *
     * <p>Un visiteur face à la façade avant a le bâtiment devant lui. Sa gauche est donc obtenue en
     * tournant de 90° dans le sens horaire depuis la direction vers laquelle la façade regarde —
     * convention arrêtée ici une fois pour toutes, et vérifiée par un test sur les quatre
     * orientations.</p>
     */
    public BlockStates.Dir direction(Facing front) {
        BlockStates.Dir f = toDir(front);
        return switch (this) {
            case FRONT -> f;
            case BACK -> f.opposite();
            case LEFT -> clockwise(f);
            case RIGHT -> clockwise(clockwise(clockwise(f)));
        };
    }

    /** Converti une orientation du domaine en direction Minecraft. */
    public static BlockStates.Dir toDir(Facing front) {
        return switch (front == null ? Facing.NORTH : front) {
            case NORTH -> BlockStates.Dir.NORTH;
            case SOUTH -> BlockStates.Dir.SOUTH;
            case EAST -> BlockStates.Dir.EAST;
            case WEST -> BlockStates.Dir.WEST;
        };
    }

    private static BlockStates.Dir clockwise(BlockStates.Dir d) {
        return switch (d) {
            case NORTH -> EAST_DIR;
            case EAST -> SOUTH_DIR;
            case SOUTH -> WEST_DIR;
            case WEST -> NORTH_DIR;
        };
    }

    private static final BlockStates.Dir NORTH_DIR = BlockStates.Dir.NORTH;
    private static final BlockStates.Dir SOUTH_DIR = BlockStates.Dir.SOUTH;
    private static final BlockStates.Dir EAST_DIR = BlockStates.Dir.EAST;
    private static final BlockStates.Dir WEST_DIR = BlockStates.Dir.WEST;

    /**
     * Une cellule <strong>sur</strong> cette façade, désignée par son décalage le long de la façade
     * et sa hauteur.
     *
     * <p>Le décalage court dans un sens stable et documenté : selon {@code +X} pour une façade qui
     * regarde le nord ou le sud, selon {@code +Z} pour une façade qui regarde l'est ou l'ouest.
     * Le décalage 0 est donc toujours le coin de coordonnée minimale, ce qui donne au modèle un
     * repère prévisible — « la troisième cellule en partant du coin » veut dire la même chose quelle
     * que soit l'orientation du bâtiment.</p>
     *
     * @param sizeX  dimensions du plan
     * @param offset décalage le long de la façade, borné à la façade
     * @param y      hauteur, en coordonnée locale
     * @return la cellule, ou {@link Optional#empty()} si le décalage sort de la façade
     */
    public Optional<int[]> cell(Facing front, int sizeX, int sizeY, int sizeZ, int offset, int y) {
        if (y < 0 || y >= sizeY) {
            return Optional.empty();
        }
        BlockStates.Dir dir = direction(front);
        boolean alongX = !dir.alongX(); // une façade nord/sud se parcourt selon X
        int span = alongX ? sizeX : sizeZ;
        if (offset < 0 || offset >= span) {
            return Optional.empty();
        }
        int plane = switch (dir) {
            case NORTH -> 0;
            case SOUTH -> sizeZ - 1;
            case WEST -> 0;
            case EAST -> sizeX - 1;
        };
        return Optional.of(alongX
                ? new int[] {offset, y, plane}
                : new int[] {plane, y, offset});
    }

    /** Le pas d'un cran vers l'intérieur depuis cette façade. */
    public int[] inwardStep(Facing front) {
        BlockStates.Dir inward = direction(front).opposite();
        return new int[] {inward.dx(), 0, inward.dz()};
    }

    /** Le nombre de cellules que porte cette façade, en largeur. */
    public int span(Facing front, int sizeX, int sizeZ) {
        return direction(front).alongX() ? sizeZ : sizeX;
    }

    public String label() {
        return switch (this) {
            case FRONT -> "avant";
            case BACK -> "arrière";
            case LEFT -> "gauche";
            case RIGHT -> "droite";
        };
    }
}
