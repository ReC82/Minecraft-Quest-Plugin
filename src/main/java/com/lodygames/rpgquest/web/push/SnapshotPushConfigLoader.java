package com.lodygames.rpgquest.web.push;

import com.lodygames.rpgquest.player.PublicProfilePolicy;
import com.lodygames.rpgquest.web.LocalSettingsFile;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

/**
 * Charge la configuration de l'envoi d'instantané au portail (issue #421), sur le modèle de
 * {@code /lien} : un fichier local <em>non versionné</em>
 * {@code plugins/RPGQuest/portal-snapshot.properties}, que les variables d'environnement
 * {@code RPGQUEST_PORTAL_SNAPSHOT_*} surchargent quand l'hébergeur en fournit.
 *
 * <p><strong>Fail-closed</strong> : sans {@code enabled=true}, sans {@code base-url} ou sans
 * {@code token}, ou avec une adresse non HTTPS hors machine locale, rien n'est envoyé et la raison
 * est journalisée — jamais le jeton. Le jeton est <em>dédié</em> : ni celui de l'agent PlugAdmin,
 * ni celui de {@code /lien}.</p>
 *
 * <table><caption>Clés du fichier / variables d'environnement</caption>
 * <tr><th>Fichier</th><th>Environnement</th><th>Défaut</th></tr>
 * <tr><td>{@code enabled}</td><td>{@code RPGQUEST_PORTAL_SNAPSHOT_ENABLED}</td><td>{@code false}</td></tr>
 * <tr><td>{@code base-url}</td><td>{@code RPGQUEST_PORTAL_SNAPSHOT_BASE_URL}</td><td>(aucun)</td></tr>
 * <tr><td>{@code token}</td><td>{@code RPGQUEST_PORTAL_SNAPSHOT_TOKEN}</td><td>(aucun — obligatoire)</td></tr>
 * <tr><td>{@code connect-timeout-ms}</td><td>—</td><td>{@code 5000}</td></tr>
 * <tr><td>{@code request-timeout-ms}</td><td>—</td><td>{@code 10000}</td></tr>
 * <tr><td>{@code status-interval-seconds}</td><td>—</td><td>{@code 30} (10–300)</td></tr>
 * <tr><td>{@code snapshot-enabled}</td><td>—</td><td>{@code false} : instantané complet seulement si
 *     {@code web-export.enabled}</td></tr>
 * <tr><td>{@code snapshot-interval-seconds}</td><td>—</td><td>{@code 900} (600–3600)</td></tr>
 * <tr><td>{@code snapshot-profile-visibility}</td><td>—</td><td>(vide : celle de {@code config.yml}) ;
 *     {@code public} ou {@code private}</td></tr>
 * </table>
 */
public final class SnapshotPushConfigLoader {

    public static final String FILE_NAME = "portal-snapshot.properties";

    private final Path file;
    private final UnaryOperator<String> env;
    private final Logger logger;

    public SnapshotPushConfigLoader(Path dataFolder, Logger logger) {
        this(dataFolder.resolve(FILE_NAME), System::getenv, logger);
    }

    /** Constructeur injectable (tests). */
    public SnapshotPushConfigLoader(Path file, UnaryOperator<String> env, Logger logger) {
        this.file = file;
        this.env = env;
        this.logger = logger;
    }

    public SnapshotPushConfig load() {
        LocalSettingsFile settings = LocalSettingsFile.read(file, env, logger, "Envoi d'instantané au portail");
        boolean enabled = settings.flag("enabled", "RPGQUEST_PORTAL_SNAPSHOT_ENABLED");
        String baseUrl = settings.baseUrl("base-url", "RPGQUEST_PORTAL_SNAPSHOT_BASE_URL");
        String token = settings.text("token", "RPGQUEST_PORTAL_SNAPSHOT_TOKEN", "");
        int connectTimeout = settings.boundedInt("connect-timeout-ms", 5000, 500, 30_000);
        int requestTimeout = settings.boundedInt("request-timeout-ms", 10_000, 1000, 60_000);
        int statusInterval = settings.boundedInt("status-interval-seconds",
                SnapshotPushConfig.DEFAULT_STATUS_INTERVAL_SECONDS, 10, 300);
        boolean snapshotEnabled = settings.flag("snapshot-enabled", null);
        int snapshotInterval = settings.boundedInt("snapshot-interval-seconds",
                SnapshotPushConfig.DEFAULT_SNAPSHOT_INTERVAL_SECONDS, SnapshotPushConfig.MIN_SNAPSHOT_INTERVAL_SECONDS,
                SnapshotPushConfig.MAX_SNAPSHOT_INTERVAL_SECONDS);
        PublicProfilePolicy.Visibility visibility = visibility(settings.text("snapshot-profile-visibility", null, ""));

        if (!enabled) {
            logger.info("Envoi d'instantané au portail désactivé (enabled != true dans {}).", FILE_NAME);
            return SnapshotPushConfig.disabled();
        }
        if (baseUrl.isBlank() || token.isBlank()) {
            logger.warn("Envoi d'instantané au portail NON activé : base-url {}, token {}. Compléter {} (hors Git).",
                    baseUrl.isBlank() ? "absent" : "présent", token.isBlank() ? "absent" : "présent", FILE_NAME);
            return SnapshotPushConfig.disabled();
        }
        if (!LocalSettingsFile.isAcceptableBaseUrl(baseUrl)) {
            logger.warn("Envoi d'instantané au portail NON activé : base-url doit être en HTTPS (ou http en local) — reçu « {} ».",
                    baseUrl);
            return SnapshotPushConfig.disabled();
        }
        SnapshotPushConfig config = new SnapshotPushConfig(true, baseUrl, token, connectTimeout, requestTimeout, statusInterval,
                snapshotEnabled, snapshotInterval, visibility);
        logger.info("Envoi d'instantané au portail configuré : {}", config);
        return config;
    }

    /** {@code public} / {@code private} (casse indifférente) ; toute autre valeur garde celle de {@code config.yml}. */
    private PublicProfilePolicy.Visibility visibility(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return PublicProfilePolicy.Visibility.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("snapshot-profile-visibility « {} » inconnue dans {} (attendu public ou private) : "
                    + "visibilité de config.yml conservée.", raw, FILE_NAME);
            return null;
        }
    }
}
