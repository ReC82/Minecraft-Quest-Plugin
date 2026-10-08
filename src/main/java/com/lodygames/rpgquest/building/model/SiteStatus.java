package com.lodygames.rpgquest.building.model;

import java.util.Locale;
import java.util.Optional;

/**
 * État d'un emplacement de construction (issue #213).
 *
 * <p><strong>Une seule valeur, volontairement.</strong> Tant qu'aucun bâtiment ne peut être affecté
 * ni posé, un emplacement est vide — et il n'existe aucun geste capable de le rendre autre chose.
 * Déclarer {@code RESERVED} ou {@code OCCUPIED} aujourd'hui reviendrait à inventer un cycle de vie
 * que rien ne fait avancer : des états morts, que chaque écran devrait afficher sans jamais pouvoir
 * les produire, et dont la sémantique serait figée avant d'avoir servi.</p>
 *
 * <p><strong>Ce qui rend l'ajout futur additif.</strong> La colonne est un {@code TEXT} et la
 * lecture passe par {@link #of(String)}, qui retombe sur {@link #EMPTY} devant une valeur inconnue.
 * Ajouter un état plus tard ne demande donc <strong>aucune migration</strong>, et une base écrite
 * par une version plus récente reste lisible par une plus ancienne au lieu de la faire échouer.</p>
 */
public enum SiteStatus {

    /** Aucun bâtiment affecté ni posé. Seul état que ce lot sait produire. */
    EMPTY;

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
        };
    }
}
