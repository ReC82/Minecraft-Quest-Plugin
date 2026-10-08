package com.lodygames.rpgquest.building.model;

import java.util.Locale;
import java.util.Optional;

/**
 * État d'un emplacement de construction (issue #213).
 *
 * <p><strong>Deux valeurs, et la seconde est arrivée sans migration.</strong> Le socle de #213 n'en
 * déclarait qu'une : tant qu'aucun bâtiment ne pouvait être posé, un emplacement était vide, et
 * déclarer {@code OCCUPIED} aurait inventé un cycle de vie que rien ne faisait avancer. Le lot
 * « placement » a produit le geste manquant, donc l'état existe maintenant pour de bon.</p>
 *
 * <p><strong>Ce qui a rendu l'ajout additif.</strong> La colonne est un {@code TEXT} et la lecture
 * passe par {@link #of(String)}, qui retombe sur {@link #EMPTY} devant une valeur inconnue. Ajouter
 * {@code OCCUPIED} n'a donc demandé <strong>aucune migration</strong>, et une base écrite par une
 * version plus récente reste lisible par une plus ancienne au lieu de la faire échouer — une base
 * de #213 relue ici voit simplement tous ses emplacements vides, ce qui est exact.</p>
 */
public enum SiteStatus {

    /** Aucun bâtiment posé : l'emplacement n'est qu'un repère. */
    EMPTY,
    /** Un bâtiment est posé ici — il existe un {@code BuildingPlacement} pour cet emplacement. */
    OCCUPIED;

    /**
     * Lecture tolérante. Une valeur absente, vide ou inconnue donne {@link #EMPTY} : c'est la
     * réponse honnête pour un emplacement dont on ne sait rien, et elle évite qu'une base écrite
     * par une version future ne casse la lecture.
     */
    public static SiteStatus of(String raw) {
        if (raw == null || raw.isBlank()) {
            return EMPTY;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EMPTY;
        }
    }

    /** Même lecture, mais qui dit si la valeur était réellement reconnue. */
    public static Optional<SiteStatus> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String label() {
        return switch (this) {
            case EMPTY -> "vide";
            case OCCUPIED -> "occupé";
        };
    }
}
