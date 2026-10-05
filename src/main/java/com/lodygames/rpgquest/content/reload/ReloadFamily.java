package com.lodygames.rpgquest.content.reload;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Familles de contenu rechargeables à chaud (issue #131).
 *
 * <p><strong>Pourquoi une énumération distincte de {@code content.pack.ContentFamily}.</strong>
 * Celle-ci répond à « que peut-on <em>recharger</em> dans le runtime », l'autre à « que peut-on
 * <em>exporter</em> ». Les deux ensembles diffèrent réellement : les objets et les profils de mobs
 * sont rechargeables mais ne font pas partie d'un content pack, et {@code all} n'a pas de sens ici
 * (un rechargement se demande famille par famille, pour pouvoir n'en risquer qu'une). Les fusionner
 * forcerait l'une des deux à mentir sur ses membres.</p>
 *
 * <p>L'ordre de déclaration est l'<strong>ordre d'application</strong> : les familles dont d'autres
 * dépendent passent d'abord, pour qu'une référence croisée ne pointe jamais vers un ensemble
 * intermédiaire. Objets et PNJ avant les quêtes (une quête cite un donneur et des objets), quêtes
 * avant les stories et les dialogues (qui citent des quêtes).</p>
 */
public enum ReloadFamily {

    /** {@code items/*.yml} — aucun dépendant, mais cité par les récompenses de quêtes. */
    ITEMS("items", "Objets"),
    /** {@code npcs/*.yml} — cité par le champ {@code giver:} des quêtes et par les dialogues. */
    NPCS("npcs", "PNJ"),
    /** {@code quests/*.yml} — cité par les stories et par les actions de dialogue. */
    QUESTS("quests", "Quêtes"),
    /** {@code stories/*.yml} — cite des quêtes. */
    STORIES("stories", "Stories"),
    /** {@code dialogues/*.yml} — cite des quêtes et des PNJ. */
    DIALOGUES("dialogues", "Dialogues"),
    /** {@code mobs/*.yml} — profils de mobs spéciaux et boss (issue #169). */
    MOBS("mobs", "Mobs spéciaux & boss");

    private final String wire;
    private final String label;

    ReloadFamily(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /** Jeton transporté par l'action agent. */
    public String wire() {
        return wire;
    }

    /** Libellé humain (français) pour l'interface. */
    public String label() {
        return label;
    }

    public static Optional<ReloadFamily> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ReloadFamily family : values()) {
            if (family.wire.equals(normalized)) {
                return Optional.of(family);
            }
        }
        return Optional.empty();
    }

    /**
     * Analyse une liste CSV de jetons. Un jeton inconnu fait échouer l'ensemble (jamais une
     * interprétation partielle silencieuse d'une demande d'administration).
     *
     * @return l'ensemble demandé, dans l'ordre d'application ; {@link Optional#empty()} si un jeton
     *     est inconnu ou si la liste est vide
     */
    public static Optional<Set<ReloadFamily>> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Optional.empty();
        }
        Set<ReloadFamily> requested = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Optional<ReloadFamily> family = fromWire(trimmed);
            if (family.isEmpty()) {
                return Optional.empty();
            }
            requested.add(family.get());
        }
        return requested.isEmpty() ? Optional.empty() : Optional.of(ordered(requested));
    }

    /** Toutes les familles, dans l'ordre d'application. */
    public static Set<ReloadFamily> all() {
        return ordered(Set.of(values()));
    }

    /** Remet un ensemble quelconque dans l'ordre de déclaration (= ordre de dépendance). */
    public static Set<ReloadFamily> ordered(Set<ReloadFamily> families) {
        Set<ReloadFamily> out = new LinkedHashSet<>();
        for (ReloadFamily family : values()) {
            if (families.contains(family)) {
                out.add(family);
            }
        }
        return out;
    }
}
