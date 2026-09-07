package com.lodygames.rpgquest.web.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

/**
 * Charge la configuration de l'agent sortant PlugAdmin (issue #51).
 *
 * <p><strong>Abstraction de source</strong> : le mécanisme de référence est un fichier local
 * <em>non versionné</em> déployé séparément (FTP/script) —
 * {@code plugins/RPGQuest/plugadmin-agent.properties}. Les variables d'environnement
 * {@code RPGQUEST_PLUGADMIN_*} le <em>surchargent</em> quand VeryGames permet d'en fournir, mais
 * ne sont jamais le seul mécanisme.</p>
 *
 * <p><strong>Fail-closed</strong> : sans {@code enabled=true}, ou si {@code base-url} /
 * {@code agent-id} / {@code token} manque, {@link #load()} renvoie {@link AgentConfig#disabled()}
 * et journalise la raison (jamais le secret).</p>
 *
 * <table><caption>Clés du fichier / variables d'environnement</caption>
 * <tr><th>Fichier</th><th>Environnement</th><th>Défaut</th></tr>
 * <tr><td>{@code enabled}</td><td>{@code RPGQUEST_PLUGADMIN_ENABLED}</td><td>{@code false}</td></tr>
 * <tr><td>{@code base-url}</td><td>{@code RPGQUEST_PLUGADMIN_BASE_URL}</td><td>(aucun)</td></tr>
 * <tr><td>{@code agent-id}</td><td>{@code RPGQUEST_PLUGADMIN_AGENT_ID}</td><td>(aucun)</td></tr>
 * <tr><td>{@code environment}</td><td>{@code RPGQUEST_PLUGADMIN_ENVIRONMENT}</td><td>{@code dev}</td></tr>
 * <tr><td>{@code token}</td><td>{@code RPGQUEST_PLUGADMIN_TOKEN}</td><td>(aucun — obligatoire)</td></tr>
 * <tr><td>{@code heartbeat-seconds}</td><td>{@code RPGQUEST_PLUGADMIN_HEARTBEAT_SECONDS}</td><td>{@code 20}</td></tr>
 * <tr><td>{@code poll-seconds}</td><td>{@code RPGQUEST_PLUGADMIN_POLL_SECONDS}</td><td>{@code 15}</td></tr>
 * <tr><td>{@code actions-enabled}</td><td>{@code RPGQUEST_PLUGADMIN_ACTIONS_ENABLED}</td><td>{@code true}</td></tr>
 * <tr><td>{@code connect-timeout-ms}</td><td>—</td><td>{@code 5000}</td></tr>
 * <tr><td>{@code request-timeout-ms}</td><td>—</td><td>{@code 10000}</td></tr>
 * <tr><td>{@code max-backoff-seconds}</td><td>—</td><td>{@code 300}</td></tr>
 * <tr><td>{@code max-response-kib}</td><td>—</td><td>{@code 256}</td></tr>
 * </table>
 */
public final class AgentConfigLoader {

    /** Nom du fichier local, relatif au dossier de données du plugin. */
    public static final String FILE_NAME = "plugadmin-agent.properties";

    private final Path file;
    private final UnaryOperator<String> env;
    private final Logger logger;

    public AgentConfigLoader(Path dataFolder, Logger logger) {
        this(dataFolder.resolve(FILE_NAME), System::getenv, logger);
    }

    /** Constructeur injectable (tests) : fichier et source d'environnement explicites. */
    public AgentConfigLoader(Path file, UnaryOperator<String> env, Logger logger) {
        this.file = file;
        this.env = env;
        this.logger = logger;
    }

    public AgentConfig load() {
        Properties props = readFile();

        boolean enabledRequested = bool(resolve(props, "enabled", "RPGQUEST_PLUGADMIN_ENABLED", "false"));
        String baseUrl = resolve(props, "base-url", "RPGQUEST_PLUGADMIN_BASE_URL", "");
        String agentId = resolve(props, "agent-id", "RPGQUEST_PLUGADMIN_AGENT_ID", "");
        String environment = resolve(props, "environment", "RPGQUEST_PLUGADMIN_ENVIRONMENT", "dev");
        String token = resolve(props, "token", "RPGQUEST_PLUGADMIN_TOKEN", "");

        int heartbeat = clamp(intOf(resolve(props, "heartbeat-seconds", "RPGQUEST_PLUGADMIN_HEARTBEAT_SECONDS", "20"), 20), 5, 600);
        int poll = clamp(intOf(resolve(props, "poll-seconds", "RPGQUEST_PLUGADMIN_POLL_SECONDS", "15"), 15), 5, 600);
        boolean actionsEnabled = bool(resolve(props, "actions-enabled", "RPGQUEST_PLUGADMIN_ACTIONS_ENABLED", "true"));
        int connectTimeout = clamp(intOf(prop(props, "connect-timeout-ms", "5000"), 5000), 500, 60_000);
        int requestTimeout = clamp(intOf(prop(props, "request-timeout-ms", "10000"), 10_000), 1000, 120_000);
        int maxBackoff = clamp(intOf(prop(props, "max-backoff-seconds", "300"), 300), 5, 3600);
        long maxResponseBytes = clamp(intOf(prop(props, "max-response-kib", "256"), 256), 4, 8192) * 1024L;

        if (!enabledRequested) {
            logger.info("Agent PlugAdmin désactivé (enabled != true dans {} ou RPGQUEST_PLUGADMIN_ENABLED).", FILE_NAME);
            return AgentConfig.disabled();
        }
        if (baseUrl.isBlank() || agentId.isBlank() || token.isBlank()) {
            logger.warn("Agent PlugAdmin NON démarré (fail-closed) : "
                    + "base-url {}, agent-id {}, token {}. Compléter {} (hors Git).",
                    present(baseUrl), present(agentId), present(token), FILE_NAME);
            return AgentConfig.disabled();
        }
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://127.0.0.1")
                && !baseUrl.startsWith("http://localhost")) {
            logger.warn("Agent PlugAdmin NON démarré : base-url doit être en HTTPS (ou http en local) — reçu « {} ».",
                    baseUrl);
            return AgentConfig.disabled();
        }

        AgentConfig config = new AgentConfig(true, baseUrl.trim(), agentId.trim(), environment.trim(), token.trim(),
                heartbeat, poll, connectTimeout, requestTimeout, maxBackoff, actionsEnabled, maxResponseBytes);
        logger.info("Agent PlugAdmin configuré : {}", config);
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
            logger.warn("Agent PlugAdmin : lecture de {} impossible ({}) — configuration ignorée.", file, e.getMessage());
        }
        return props;
    }

    // ---- Résolution env > fichier > défaut ------------------------------------------------

    private String resolve(Properties props, String fileKey, String envKey, String fallback) {
        String fromEnv = env.apply(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return prop(props, fileKey, fallback);
    }

    private static String prop(Properties props, String key, String fallback) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean bool(String raw) {
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }

    private static int intOf(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String present(String value) {
        return value == null || value.isBlank() ? "absent" : "présent";
    }
}
