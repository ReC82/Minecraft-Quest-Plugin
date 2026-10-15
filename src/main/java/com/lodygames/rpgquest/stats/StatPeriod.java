package com.lodygames.rpgquest.stats;

import java.time.Instant;
import java.util.List;

/**
 * Une saison ou un événement de classement (issue #54) : une fenêtre {@code [start, end)} en UTC,
 * et les métriques qu'elle classe. Une période ne possède aucun compteur : ses valeurs se lisent
 * dans le journal source sur sa fenêtre, puis sont figées à sa fin (archive).
 */
public record StatPeriod(String id, StatScope scope, String name, Instant start, Instant end, List<String> metrics) {

    /** Où en est la période à l'instant {@code now}. */
    public enum Status {
        SCHEDULED,
        ACTIVE,
        ENDED
    }

    public StatPeriod {
        if (scope == StatScope.LIFETIME) {
            throw new IllegalArgumentException("une période est une saison ou un événement, pas « lifetime »");
        }
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("début postérieur ou égal à la fin : " + id);
        }
        metrics = List.copyOf(metrics);
    }

    public Status status(Instant now) {
        if (now.isBefore(start)) {
            return Status.SCHEDULED;
        }
        return now.isBefore(end) ? Status.ACTIVE : Status.ENDED;
    }

    /** Vrai si les deux fenêtres partagent au moins un instant (bornes de fin exclues). */
    public boolean overlaps(StatPeriod other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
