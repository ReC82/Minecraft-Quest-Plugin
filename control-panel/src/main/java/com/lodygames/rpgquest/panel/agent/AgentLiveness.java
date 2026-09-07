package com.lodygames.rpgquest.panel.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Fraîcheur d'un agent, dérivée du dernier heartbeat reçu (issue #51, phase 5). Indépendante de
 * toute session navigateur : le calcul ne dépend que de {@code receivedAt} et de seuils
 * configurables.
 */
public enum AgentLiveness {

    /** Aucun heartbeat jamais reçu. */
    UNKNOWN,
    /** Dernier heartbeat récent (≤ seuil stale). */
    ONLINE,
    /** Heartbeat vieillissant (entre seuil stale et seuil offline). */
    STALE,
    /** Aucun heartbeat récent (> seuil offline). */
    OFFLINE;

    /** Seuils, en secondes : au-delà de {@code stale} → STALE, au-delà de {@code offline} → OFFLINE. */
    public record Thresholds(long staleSeconds, long offlineSeconds) {
        public Thresholds {
            if (staleSeconds <= 0) {
                staleSeconds = 45;
            }
            if (offlineSeconds <= staleSeconds) {
                offlineSeconds = staleSeconds * 3;
            }
        }

        public static Thresholds defaults() {
            return new Thresholds(45, 150);
        }
    }

    public static AgentLiveness of(Optional<HeartbeatRecord> heartbeat, Thresholds thresholds, Instant now) {
        if (heartbeat.isEmpty()) {
            return UNKNOWN;
        }
        long age = Duration.between(heartbeat.get().receivedAt(), now).getSeconds();
        if (age < 0) {
            return ONLINE; // horloge légèrement en avance côté serveur : on reste indulgent
        }
        if (age <= thresholds.staleSeconds()) {
            return ONLINE;
        }
        if (age <= thresholds.offlineSeconds()) {
            return STALE;
        }
        return OFFLINE;
    }

    /** Âge lisible du heartbeat, ex. « il y a 8 s ». */
    public static String ageHuman(Instant receivedAt, Instant now) {
        long s = Math.max(0, Duration.between(receivedAt, now).getSeconds());
        if (s < 90) {
            return "il y a " + s + " s";
        }
        long m = s / 60;
        if (m < 90) {
            return "il y a " + m + " min";
        }
        long h = m / 60;
        if (h < 48) {
            return "il y a " + h + " h";
        }
        return "il y a " + (h / 24) + " j";
    }
}
