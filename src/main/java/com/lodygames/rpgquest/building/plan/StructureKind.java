package com.lodygames.rpgquest.building.plan;

import java.util.Locale;
import java.util.Optional;

/**
 * Ce qu'une structure <strong>prétend être</strong> (issue #94, lot A).
 *
 * <p>Le type décide des règles qu'on a le droit de lui appliquer. Exiger une porte, des fenêtres et
 * un éclairage d'une fontaine ou d'une arche de portail n'aurait aucun sens — et ce serait la
 * meilleure façon de rendre l'audit inutilisable dès qu'il servira au décor (#244) ou à
 * l'architecture de portail (#228).</p>
 *
 * <p>Le type est déclaré par le plan ({@code kind:}), et vaut {@link #HABITABLE} par défaut : c'est
 * le cas le plus exigeant, donc le défaut le plus sûr. Un plan qui ne dit rien est jugé comme un
 * bâtiment, et devra donc être ouvert, éclairé et praticable.</p>
 */
public enum StructureKind {

    /**
     * Un bâtiment où un joueur entre et circule : maison, bibliothèque, caserne. Tous les contrôles
     * s'appliquent.
     */
    HABITABLE,

    /**
     * Un élément de décor : fontaine, statue, muret, lampadaire. Aucune exigence d'entrée, de
     * fenêtre, d'éclairage intérieur ni de niveaux — il n'y a pas d'intérieur. Restent les contrôles
     * qui ont du sens partout : la structure pose réellement des blocs, et ses états de bloc sont
     * reconnus.
     */
    DECORATION,

    /**
     * L'architecture autour d'un portail (#228) : une arche, un cadre. Traversable par
     * construction, mais sans intérieur habitable. Mêmes contrôles qu'une décoration, plus
     * l'exigence d'un passage franchissable — c'est tout l'objet d'un portail.
     */
    PORTAL;

    public static Optional<StructureKind> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Libellé français, pour les messages d'audit. */
    public String label() {
        return switch (this) {
            case HABITABLE -> "bâtiment habitable";
            case DECORATION -> "décoration";
            case PORTAL -> "architecture de portail";
        };
    }
}
