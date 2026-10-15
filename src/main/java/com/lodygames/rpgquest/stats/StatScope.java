package com.lodygames.rpgquest.stats;

import java.util.Locale;

/**
 * Portée d'une statistique (issue #54). Une même métrique se lit sur plusieurs portées sans
 * nouveau compteur : {@link #LIFETIME} depuis toujours, {@link #SEASON} et {@link #EVENT} sur la
 * fenêtre {@code [début, fin)} d'une {@link StatPeriod}.
 */
public enum StatScope {
    LIFETIME,
    SEASON,
    EVENT;

    /** Nom écrit dans l'instantané web ({@code lifetime}, {@code season}, {@code event}). */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
