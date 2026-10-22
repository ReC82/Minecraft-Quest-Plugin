package com.lodygames.rpgquest.web.push;

/**
 * Envoi de l'instantané web et du relevé d'état agrégé au portail hébergé sur AWS (issue #421).
 *
 * <p>{@link #toString()} ne montre jamais le jeton.</p>
 */
public record SnapshotPushConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs,
                                 int statusIntervalSeconds) {

    /** Période par défaut du relevé d'état agrégé, bien sous le seuil de péremption du portail (120 s). */
    public static final int DEFAULT_STATUS_INTERVAL_SECONDS = 30;

    public SnapshotPushConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs) {
        this(enabled, baseUrl, token, connectTimeoutMs, requestTimeoutMs, DEFAULT_STATUS_INTERVAL_SECONDS);
    }

    public static SnapshotPushConfig disabled() {
        return new SnapshotPushConfig(false, "", "", 5000, 10_000);
    }

    @Override
    public String toString() {
        return "SnapshotPushConfig[enabled=" + enabled + ", baseUrl=" + baseUrl + ", token=" + (token.isBlank() ? "absent" : "***")
                + ", connectTimeoutMs=" + connectTimeoutMs + ", requestTimeoutMs=" + requestTimeoutMs
                + ", statusIntervalSeconds=" + statusIntervalSeconds + "]";
    }
}
