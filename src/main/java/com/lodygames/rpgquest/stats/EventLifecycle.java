package com.lodygames.rpgquest.stats;

import java.time.Instant;

/**
 * Cycle de vie public d'une saison ou d'un événement (issue #57).
 *
 * <pre>
 * DRAFT ──(state: planned)──▶ SCHEDULED ──start──▶ ACTIVE ──end──▶ ENDED ──archivage──▶ ARCHIVED
 *                                   └──────────── state: cancelled ────────────▶ CANCELLED
 * </pre>
 *
 * <p>{@code DRAFT} et {@code CANCELLED} sont décidés par l'administrateur ({@link StatPeriod.State}) ;
 * les autres découlent de l'horloge et de l'archive : personne ne « démarre » un événement à la main,
 * il commence et finit aux dates écrites.</p>
 */
public enum EventLifecycle {
    /** En préparation : jamais publié, jamais calculé, jamais archivé. */
    DRAFT,
    SCHEDULED,
    ACTIVE,
    /** Terminé, résultats pas encore figés (au plus une minute). */
    ENDED,
    /** Terminé et figé : le classement ne bougera plus. */
    ARCHIVED,
    /** Annulé : publié comme tel, sans classement, jamais archivé ni récompensé. */
    CANCELLED;

    public static EventLifecycle of(StatPeriod period, Instant now, boolean archived) {
        if (period.state() == StatPeriod.State.DRAFT) {
            return DRAFT;
        }
        if (period.state() == StatPeriod.State.CANCELLED) {
            return CANCELLED;
        }
        if (archived) {
            return ARCHIVED;
        }
        return switch (period.status(now)) {
            case SCHEDULED -> SCHEDULED;
            case ACTIVE -> ACTIVE;
            case ENDED -> ENDED;
        };
    }

    /** Vrai si un classement (live ou figé) existe pour cet état. */
    public boolean hasBoard() {
        return this == ACTIVE || this == ENDED || this == ARCHIVED;
    }
}
