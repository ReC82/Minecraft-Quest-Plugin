package com.lodygames.rpgquest.building.model;

import com.lodygames.rpgquest.building.plan.StructureKind;
import java.util.Locale;
import java.util.Optional;

/**
 * Type d'un emplacement de construction (issue #568, option A validée le 27/10/2026).
 *
 * <p>Un emplacement {@link #BUILDING} reçoit un bâtiment ou une décoration ; un emplacement
 * {@link #PORTAL} reçoit uniquement une arche de portail ({@link StructureKind#PORTAL}). La règle
 * est vérifiée <strong>côté serveur</strong> à l'aperçu, à la pose et au remplacement : l'écran
 * filtre aussi ses listes, mais ne fait jamais foi.</p>
 *
 * <p><strong>Défaut {@code BUILDING}.</strong> C'est la valeur de la colonne ajoutée par la
 * migration V45 pour tous les emplacements existants, et celle d'un emplacement créé en jeu :
 * avant #568, tout emplacement était un emplacement de bâtiment. Lecture tolérante comme
 * {@link SiteStatus} : une valeur inconnue retombe sur {@code BUILDING} au lieu de faire échouer
 * le chargement de tous les emplacements.</p>
 */
public enum SiteKind {

    /** Emplacement de bâtiment : bâtiment habitable ou décoration. */
    BUILDING,
    /** Emplacement de portail : seulement une arche de portail. */
    PORTAL;

    /** Lecture tolérante : absent, vide ou inconnu donne {@link #BUILDING}. */
    public static SiteKind of(String raw) {
        return parse(raw).orElse(BUILDING);
    }

    /** Même lecture, mais qui dit si la valeur était réellement reconnue. */
    public static Optional<SiteKind> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Ce type d'emplacement accepte-t-il une construction de ce type ? */
    public boolean accepts(StructureKind structure) {
        StructureKind kind = structure == null ? StructureKind.HABITABLE : structure;
        return this == PORTAL ? kind == StructureKind.PORTAL : kind != StructureKind.PORTAL;
    }

    /**
     * Motif de refus en français si la construction ne convient pas à ce type d'emplacement,
     * vide sinon. Un seul texte pour l'aperçu, la pose et le remplacement.
     */
    public Optional<String> refusalFor(StructureKind structure, String buildingName) {
        if (accepts(structure)) {
            return Optional.empty();
        }
        String name = buildingName == null || buildingName.isBlank() ? "Cette construction"
                : "« " + buildingName + " »";
        return Optional.of(this == PORTAL
                ? name + " n'est pas une arche de portail : un emplacement de portail ne reçoit "
                        + "qu'une arche. Choisissez une arche, ou changez le type de l'emplacement."
                : name + " est une arche de portail : elle se pose sur un emplacement de portail, "
                        + "pas sur un emplacement de bâtiment. Changez le type de l'emplacement "
                        + "ou choisissez-en un autre.");
    }

    public String label() {
        return switch (this) {
            case BUILDING -> "bâtiment";
            case PORTAL -> "portail";
        };
    }
}
