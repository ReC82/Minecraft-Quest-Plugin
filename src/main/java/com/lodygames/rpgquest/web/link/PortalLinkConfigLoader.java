package com.lodygames.rpgquest.web.link;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

/**
 * Charge la configuration de {@code /lien} (issue #53), sur le modèle de l'agent PlugAdmin : un
 * fichier local <em>non versionné</em> {@code plugins/RPGQuest/portal-link.properties}, que les
 * variables d'environnement {@code RPGQUEST_PORTAL_LINK_*} surchargent quand l'hébergeur en fournit.
 *
 * <p><strong>Fail-closed</strong> : sans {@code enabled=true}, sans {@code base-url} ou sans
 * {@code token}, ou avec une adresse non HTTPS hors machine locale, la liaison reste désactivée et
 * la raison est journalisée — jamais le jeton.</p>
 *
 * <table><caption>Clés du fichier / variables d'environnement</caption>
 * <tr><th>Fichier</th><th>Environnement</th><th>Défaut</th></tr>
 * <tr><td>{@code enabled}</td><td>{@code RPGQUEST_PORTAL_LINK_ENABLED}</td><td>{@code false}</td></tr>
 * <tr><td>{@code base-url}</td><td>{@code RPGQUEST_PORTAL_LINK_BASE_URL}</td><td>(aucun)</td></tr>
 * <tr><td>{@code token}</td><td>{@code RPGQUEST_PORTAL_LINK_TOKEN}</td><td>(aucun — obligatoire)</td></tr>
 * <tr><td>{@code connect-timeout-ms}</td><td>—</td><td>{@code 5000}</td></tr>
 * <tr><td>{@code request-timeout-ms}</td><td>—</td><td>{@code 10000}</td></tr>
 * </table>
 */
public final class PortalLinkConfigLoader {

    public static final String FILE_NAME = "portal-link.properties";

    private final Path file;
    private final UnaryOperator<String> env;
    private final Logger logger;

    public PortalLinkConfigLoader(Path dataFolder, Logger logger) {
        this(dataFolder.resolve(FILE_NAME), System::getenv, logger);
    }

    /** Constructeur injectable (tests). */
    public PortalLinkConfigLoader(Path file, UnaryOperator<String> env, Logger logger) {
        this.file = file;
        this.env = env;
        this.logger = logger;
    }

    public PortalLinkConfig load() {
        Properties props = readFile();
        boolean enabled = "true".equalsIgnoreCase(resolve(props, "enabled", "RPGQUEST_PORTAL_LINK_ENABLED", "false"));
        String baseUrl = stripTrailingSlash(resolve(props, "base-url", "RPGQUEST_PORTAL_LINK_BASE_URL", ""));
        String token = resolve(props, "token", "RPGQUEST_PORTAL_LINK_TOKEN", "");
        int connectTimeout = clamp(intOf(props.getProperty("connect-timeout-ms"), 5000), 500, 30_000);
        int requestTimeout = clamp(intOf(props.getProperty("request-timeout-ms"), 10_000), 1000, 60_000);

        if (!enabled) {
            logger.info("Liaison portail (/lien) désactivée (enabled != true dans {}).", FILE_NAME);
            return PortalLinkConfig.disabled();
        }
        if (baseUrl.isBlank() || token.isBlank()) {
            logger.warn("Liaison portail (/lien) NON activée : base-url {}, token {}. Compléter {} (hors Git).",
                    baseUrl.isBlank() ? "absent" : "présent", token.isBlank() ? "absent" : "présent", FILE_NAME);
            return PortalLinkConfig.disabled();
        }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://127.0.0.1")
                && !baseUrl.startsWith("http://localhost")) {
            logger.warn("Liaison portail (/lien) NON activée : base-url doit être en HTTPS (ou http en local) — reçu « {} ».",
                    baseUrl);
            return PortalLinkConfig.disabled();
        }
        PortalLinkConfig config = new PortalLinkConfig(true, baseUrl, token, connectTimeout, requestTimeout);
        logger.info("Liaison portail (/lien) configurée : {}", config);
        return config;
    }

    private Properties readFile() {
        Properties props = new Properties();
        if (file == null || !Files.isReadable(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            logger.warn("Liaison portail : lecture de {} impossible ({}) — configuration ignorée.", file, e.getMessage());
        }
        return props;
    }

    private String resolve(Properties props, String fileKey, String envKey, String fallback) {
        String fromEnv = env.apply(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String value = props.getProperty(fileKey);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static int intOf(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
