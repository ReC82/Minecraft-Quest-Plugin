package com.lodygames.rpgquest.building.model;

import java.util.Locale;

/**
 * La face d'un bloc sur laquelle l'administrateur a cliqué (issue #213).
 *
 * <p>Projection sans Bukkit de {@code org.bukkit.block.BlockFace}, restreinte aux six faces d'un
 * cube. Elle existe pour une seule raison : rendre la règle d'ancrage
 * ({@link BuildingSiteAnchor}) <strong>testable sans serveur</strong>. Le ticket demande « une règle
 * précise sur bloc cliqué/face et position finale » — une règle qu'on ne peut pas exécuter dans un
 * test n'est pas précise, c'est une intention.</p>
 */
public enum ClickedFace {

    UP(0, 1, 0),
    DOWN(0, -1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0),
    /**
     * Aucune face exploitable : Bukkit renvoie {@code SELF} quand l'interaction ne désigne pas une
     * face (clic dans le vide, interaction d'entité). L'ancre est alors le bloc lui-même.
     */
    SELF(0, 0, 0);

    private final int modX;
    private final int modY;
    private final int modZ;

    ClickedFace(int modX, int modY, int modZ) {
        this.modX = modX;
        this.modY = modY;
        this.modZ = modZ;
    }

    public int modX() {
        return modX;
    }

    public int modY() {
        return modY;
    }

    public int modZ() {
        return modZ;
    }

    /**
     * Lecture du nom d'un {@code BlockFace} de Bukkit. Toute valeur inconnue — y compris les faces
     * diagonales que {@code BlockFace} déclare ({@code NORTH_EAST}…) et qu'une interaction de bloc
     * ne produit jamais — donne {@link #SELF}, donc « l'ancre est le bloc cliqué ». C'est le repli
     * le plus sûr : il ne déplace rien.
     */
    public static ClickedFace of(String bukkitFaceName) {
        if (bukkitFaceName == null || bukkitFaceName.isBlank()) {
            return SELF;
        }
        try {
            return valueOf(bukkitFaceName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SELF;
        }
    }
}
