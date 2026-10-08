package com.lodygames.rpgquest.building.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Orientation cardinale d'un emplacement de construction (issue #213).
 *
 * <p><strong>Quatre valeurs, et pas un yaw brut.</strong> Un bâtiment se pose aligné sur la grille
 * du monde : sa façade regarde le nord, l'est, le sud ou l'ouest. Conserver le yaw du joueur
 * (177,43°) donnerait une précision que le placement ne saura jamais utiliser, et obligerait chaque
 * lecteur à refaire la même conversion — donc à la refaire différemment. La conversion a lieu une
 * seule fois, ici, au moment de la création.</p>
 *
 * <p><strong>Convention Minecraft du yaw</strong>, qui n'est pas celle qu'on attend : {@code 0°}
 * regarde le <em>sud</em> ({@code +Z}), puis le yaw croît vers l'ouest. D'où l'ordre de cette
 * énumération, qui est exactement l'ordre des quadrants : SOUTH, WEST, NORTH, EAST.</p>
 */
public enum Facing {

    /** {@code -Z}. Yaw ≈ 180°. */
    NORTH(0, -1),
    /** {@code +X}. Yaw ≈ 270° (ou -90°). */
    EAST(1, 0),
    /** {@code +Z}. Yaw ≈ 0°. */
    SOUTH(0, 1),
    /** {@code -X}. Yaw ≈ 90°. */
    WEST(-1, 0);

    /** Quadrants dans l'ordre du yaw croissant : c'est la table de conversion, pas un détail. */
    private static final Facing[] BY_QUADRANT = {SOUTH, WEST, NORTH, EAST};

    private final int modX;
    private final int modZ;

    Facing(int modX, int modZ) {
        this.modX = modX;
        this.modZ = modZ;
    }

    /** Déplacement en X d'un bloc dans cette direction. */
    public int modX() {
        return modX;
    }

    /** Déplacement en Z d'un bloc dans cette direction. */
    public int modZ() {
        return modZ;
    }

    /**
     * L'orientation cardinale la plus proche du regard horizontal du joueur.
     *
     * <p>Fonction <strong>pure</strong> : aucun type Bukkit, donc testable sans serveur — c'est la
     * seule façon d'avoir une conviction sur une conversion d'angle. Un yaw hors de {@code [0,360)},
     * négatif ou de plusieurs tours, est normalisé : Paper renvoie couramment des valeurs comme
     * {@code -135} ou {@code 412}.</p>
     *
     * <p>Une diagonale exacte (45°, 135°…) tombe sur le quadrant <em>suivant</em> dans l'ordre du
     * yaw, parce que l'arrondi est au demi supérieur. Le cas est arbitraire par nature — il n'y a
     * pas de bonne réponse à « 45° » — mais il est déterministe, et l'administrateur peut de toute
     * façon corriger l'orientation depuis le Control Panel.</p>
     */
    public static Facing fromYaw(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized < 0) {
            normalized += 360.0f;
        }
        int quadrant = Math.round(normalized / 90.0f) % 4;
        return BY_QUADRANT[quadrant];
    }

    /** Lecture tolérante d'un nom stocké ou reçu d'un formulaire. Vide si la valeur est inconnue. */
    public static Optional<Facing> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Libellé français, pour les messages en jeu et les écrans. */
    public String label() {
        return switch (this) {
            case NORTH -> "nord";
            case EAST -> "est";
            case SOUTH -> "sud";
            case WEST -> "ouest";
        };
    }
}
