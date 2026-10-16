package com.lodygames.rpgquest.web.push;

/**
 * Envoi de l'instantané web au portail hébergé sur AWS (issue #421).
 *
 * <p>{@link #toString()} ne montre jamais le jeton.</p>
 */
public record SnapshotPushConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs) {

    public static SnapshotPushConfig disabled() {
        return new SnapshotPushConfig(false, "", "", 5000, 10_000);
    }

    @Override
    public String toString() {
        return "SnapshotPushConfig[enabled=" + enabled + ", baseUrl=" + baseUrl + ", token=" + (token.isBlank() ? "absent" : "***")
                + ", connectTimeoutMs=" + connectTimeoutMs + ", requestTimeoutMs=" + requestTimeoutMs + "]";
    }
}
