package com.lodygames.rpgquest.mob.community;

/**
 * Issue #175 — règles d'échéance du scheduler, pures (aucun Bukkit) pour être testées à la
 * milliseconde près.
 *
 * <p>Principe : une seule échéance à la fois, jamais de rattrapage. Une échéance manquée (serveur
 * arrêté, scheduler en pause) donne <em>au plus une</em> tentative peu après la reprise, puis la
 * cadence normale reprend à partir de cette tentative — jamais une rafale d'apparitions pour
 * « rattraper » les échéances écoulées.</p>
 */
public final class CommunityBossSchedule {

    /** Délai avant la première tentative après un redémarrage dont l'échéance est déjà passée. */
    public static final long STARTUP_GRACE_MILLIS = 2 * 60_000L;

    private CommunityBossSchedule() {
    }

    /** Échéance à armer au démarrage à partir de celle persistée ({@code null} = jamais armé). */
    public static long armOnStart(long now, Long persistedNextDue, long intervalMillis) {
        if (persistedNextDue == null) {
            return now + intervalMillis;
        }
        if (persistedNextDue <= now) {
            return now + Math.min(STARTUP_GRACE_MILLIS, intervalMillis);
        }
        return Math.min(persistedNextDue, now + intervalMillis);
    }

    /**
     * Échéance après un changement de réglages : réactivation = un intervalle complet à partir de
     * maintenant ; intervalle raccourci = jamais plus loin que maintenant + nouvel intervalle.
     */
    public static long rearm(long now, Long currentNextDue, boolean wasEnabled, long intervalMillis) {
        if (!wasEnabled || currentNextDue == null) {
            return now + intervalMillis;
        }
        return Math.min(currentNextDue, now + intervalMillis);
    }

    public static boolean isDue(long now, Long nextDue) {
        return nextDue != null && now >= nextDue;
    }

    /** Prochaine échéance après une tentative (réussie ou non), comptée depuis la tentative elle-même. */
    public static long afterAttempt(long now, long intervalMillis) {
        return now + intervalMillis;
    }
}
