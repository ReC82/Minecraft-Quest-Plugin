package com.lodygames.rpgquest.quest.progress;

import java.time.Duration;

/** Délai d'attente lisible par un joueur (« 3 h 20 min », « 1 j 4 h ») — issue #13. */
public final class CooldownText {

    private CooldownText() {
    }

    public static String format(Duration remaining) {
        long totalMinutes = Math.max(1, (remaining == null ? 0 : remaining.toSeconds() + 59) / 60);
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return hours > 0 ? days + " j " + hours + " h" : days + " j";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + " h " + minutes + " min" : hours + " h";
        }
        return minutes + " min";
    }
}
