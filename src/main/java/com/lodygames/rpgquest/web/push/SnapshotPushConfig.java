package com.lodygames.rpgquest.web.push;

import com.lodygames.rpgquest.config.WebExportConfig;
import com.lodygames.rpgquest.player.PublicProfilePolicy;

/**
 * Envoi de l'instantané web et du relevé d'état agrégé au portail hébergé sur AWS (issue #421).
 *
 * <p>{@code snapshotEnabled} (bêta du 24/10) : l'instantané complet (pseudos, XP, classements) part
 * vers le portail toutes les {@code snapshotIntervalSeconds}, même si {@code web-export.enabled}
 * reste {@code false} dans {@code config.yml} — le choix est porté par ce fichier propre au serveur,
 * hors Git, jamais par la configuration livrée. {@code profileVisibility}, s'il est renseigné,
 * remplace {@code web-export.profile-visibility-default} pour cet envoi ; {@code /profile web prive}
 * reste toujours respecté.</p>
 *
 * <p>{@link #toString()} ne montre jamais le jeton.</p>
 */
public record SnapshotPushConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs,
                                 int statusIntervalSeconds, boolean snapshotEnabled, int snapshotIntervalSeconds,
                                 PublicProfilePolicy.Visibility profileVisibility) {

    /** Période par défaut du relevé d'état agrégé, bien sous le seuil de péremption du portail (120 s). */
    public static final int DEFAULT_STATUS_INTERVAL_SECONDS = 30;
    /** Période par défaut de l'instantané complet (décision du propriétaire : 15 min, 10 min au plus court). */
    public static final int DEFAULT_SNAPSHOT_INTERVAL_SECONDS = 900;
    public static final int MIN_SNAPSHOT_INTERVAL_SECONDS = 600;
    public static final int MAX_SNAPSHOT_INTERVAL_SECONDS = 3600;

    public SnapshotPushConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs) {
        this(enabled, baseUrl, token, connectTimeoutMs, requestTimeoutMs, DEFAULT_STATUS_INTERVAL_SECONDS);
    }

    public SnapshotPushConfig(boolean enabled, String baseUrl, String token, int connectTimeoutMs, int requestTimeoutMs,
                              int statusIntervalSeconds) {
        this(enabled, baseUrl, token, connectTimeoutMs, requestTimeoutMs, statusIntervalSeconds, false,
                DEFAULT_SNAPSHOT_INTERVAL_SECONDS, null);
    }

    public static SnapshotPushConfig disabled() {
        return new SnapshotPushConfig(false, "", "", 5000, 10_000);
    }

    /**
     * Paramètres d'export réellement appliqués : ceux de {@code config.yml}, sauf quand ce fichier
     * active l'instantané complet vers le portail — l'export tourne alors à sa propre période, avec
     * la visibilité par défaut éventuellement choisie ici.
     */
    public WebExportConfig applyTo(WebExportConfig base) {
        if (!enabled || !snapshotEnabled) {
            return base;
        }
        return new WebExportConfig(true, base.outputDirectory(), snapshotIntervalSeconds,
                base.includeConnectedPlayers(), base.leaderboardSize(), base.leaderboardSkills(), base.announcements(),
                profileVisibility == null ? base.profileVisibilityDefault() : profileVisibility);
    }

    @Override
    public String toString() {
        return "SnapshotPushConfig[enabled=" + enabled + ", baseUrl=" + baseUrl + ", token=" + (token.isBlank() ? "absent" : "***")
                + ", connectTimeoutMs=" + connectTimeoutMs + ", requestTimeoutMs=" + requestTimeoutMs
                + ", statusIntervalSeconds=" + statusIntervalSeconds + ", snapshotEnabled=" + snapshotEnabled
                + ", snapshotIntervalSeconds=" + snapshotIntervalSeconds
                + ", profileVisibility=" + (profileVisibility == null ? "config.yml" : profileVisibility) + "]";
    }
}
