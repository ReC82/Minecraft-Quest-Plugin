package com.lodygames.rpgquest.web.link;

/**
 * Configuration de la liaison compte du portail ↔ compte Minecraft (issue #53).
 *
 * <p>{@link #toString()} ne montre jamais le jeton.</p>
 */
public record PortalLinkConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs,
                               int progressIntervalMinutes) {

    /** Envoi périodique de la progression des joueurs reliés connectés (#53), en minutes. */
    public static final int DEFAULT_PROGRESS_INTERVAL_MINUTES = 15;

    public PortalLinkConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs) {
        this(enabled, baseUrl, token, connectTimeoutMs, requestTimeoutMs, DEFAULT_PROGRESS_INTERVAL_MINUTES);
    }

    public static PortalLinkConfig disabled() {
        return new PortalLinkConfig(false, "", "", 5000, 10_000);
    }

    @Override
    public String toString() {
        return "PortalLinkConfig[enabled=" + enabled + ", baseUrl=" + baseUrl + ", token=" + (token.isBlank() ? "absent" : "***")
                + ", connectTimeoutMs=" + connectTimeoutMs + ", requestTimeoutMs=" + requestTimeoutMs
                + ", progressIntervalMinutes=" + progressIntervalMinutes + "]";
    }
}
