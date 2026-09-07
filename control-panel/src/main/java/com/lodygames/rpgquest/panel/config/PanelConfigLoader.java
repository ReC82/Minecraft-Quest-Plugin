package com.lodygames.rpgquest.panel.config;

import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.AgentSettings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Charge la configuration du Control Panel : fichier {@code control-panel.properties} (facultatif,
 * toutes les clés non secrètes ont un défaut) + <strong>variables d'environnement pour les
 * secrets</strong>. Fail-closed : sans {@code RPGQUEST_PANEL_SECRET} et {@code RPGQUEST_PANEL_OWNER_HASH},
 * {@link #load()} lève {@link PanelConfigException}.
 *
 * <p>Précédence : variables d'environnement &gt; fichier &gt; défauts du code.</p>
 */
public final class PanelConfigLoader {

    private final UnaryOperator<String> env;
    private final Path explicitFile;

    public PanelConfigLoader() {
        this(System::getenv, null);
    }

    /** Constructeur pour les tests : source d'environnement et fichier explicites. */
    public PanelConfigLoader(UnaryOperator<String> env, Path explicitFile) {
        this.env = env;
        this.explicitFile = explicitFile;
    }

    public PanelConfig load() {
        Properties props = readProperties();

        String secret = require("RPGQUEST_PANEL_SECRET",
                "secret de signature de session (≥ 32 caractères aléatoires)");
        String ownerHash = require("RPGQUEST_PANEL_OWNER_HASH",
                "hash du mot de passe owner — généré par « java -jar control-panel.jar hash-password »");

        String ownerUsername = firstNonBlank(env.apply("RPGQUEST_PANEL_OWNER_USERNAME"),
                props.getProperty("panel.owner-username"), "owner");
        boolean disabled = booleanOf(firstNonBlank(env.apply("PANEL_DISABLED"),
                props.getProperty("panel.disabled"), "false"));
        int port = intOf(firstNonBlank(env.apply("RPGQUEST_PANEL_PORT"),
                props.getProperty("panel.port"), "8090"), 8090);
        String bind = firstNonBlank(props.getProperty("panel.bind"), "127.0.0.1");
        String baseUrl = firstNonBlank(env.apply("RPGQUEST_PANEL_BASE_URL"),
                props.getProperty("panel.base-url"), "");
        boolean cookieSecure = booleanOf(firstNonBlank(env.apply("RPGQUEST_PANEL_COOKIE_SECURE"),
                props.getProperty("panel.cookie-secure"), "true"));
        int ttl = intOf(firstNonBlank(props.getProperty("panel.session-ttl-minutes"), "120"), 120);
        int idle = intOf(firstNonBlank(props.getProperty("panel.session-idle-minutes"), "30"), 30);
        String dbPath = firstNonBlank(env.apply("RPGQUEST_PANEL_DB"),
                props.getProperty("panel.db"), "control-panel.db");

        List<Target> targets = readTargets(props);
        String defaultTargetId = firstNonBlank(props.getProperty("targets.default"),
                targets.isEmpty() ? null : targets.get(0).id());
        if (targets.stream().noneMatch(t -> t.id().equals(defaultTargetId))) {
            throw new PanelConfigException("targets.default = « " + defaultTargetId
                    + " » ne correspond à aucune cible déclarée (" + targets.stream().map(Target::id).toList() + ").");
        }

        AgentSettings agents = readAgents(props, defaultTargetId);

        return new PanelConfig(port, bind, baseUrl, disabled, cookieSecure, ttl, idle, dbPath,
                ownerUsername, ownerHash, secret, targets, defaultTargetId, agents);
    }

    /**
     * Canal « agent sortant » (issue #51). Clés :
     * <pre>
     *   agents=rpgquest-dev[,rpgquest-staging]
     *   agent.&lt;id&gt;.environment=dev
     *   agent.&lt;id&gt;.token-env=RPGQUEST_AGENT_TOKEN_RPGQUEST_DEV   (défaut dérivé de l'id)
     *   agent.stale-seconds=45
     *   agent.offline-seconds=150
     *   agent.action-expiry-seconds=300
     *   target.&lt;defaultTargetId&gt;.agent=rpgquest-dev   (agent affiché sur le dashboard)
     * </pre>
     * Le jeton vient <strong>uniquement</strong> de l'environnement. Un agent sans jeton est déclaré
     * mais refusé à l'authentification (fail-closed).
     */
    private AgentSettings readAgents(Properties props, String defaultTargetId) {
        String list = firstNonBlank(props.getProperty("agents"), "");
        List<AgentIdentity> agents = new ArrayList<>();
        if (list != null) {
            for (String rawId : list.split(",")) {
                String id = rawId.trim();
                if (id.isEmpty()) {
                    continue;
                }
                String environment = firstNonBlank(props.getProperty("agent." + id + ".environment"), "unknown");
                String tokenEnvKey = firstNonBlank(props.getProperty("agent." + id + ".token-env"),
                        "RPGQUEST_AGENT_TOKEN_" + id.toUpperCase(Locale.ROOT).replace('-', '_'));
                String token = firstNonBlank(env.apply(tokenEnvKey), "");
                agents.add(new AgentIdentity(id, environment, token == null ? "" : token));
            }
        }
        long stale = intOf(firstNonBlank(props.getProperty("agent.stale-seconds"), "45"), 45);
        long offline = intOf(firstNonBlank(props.getProperty("agent.offline-seconds"), "150"), 150);
        long expiry = intOf(firstNonBlank(props.getProperty("agent.action-expiry-seconds"), "300"), 300);
        String defaultAgentId = firstNonBlank(
                props.getProperty("target." + defaultTargetId + ".agent"),
                agents.size() == 1 ? agents.get(0).id() : null);

        return new AgentSettings(agents, new AgentLiveness.Thresholds(stale, offline),
                Duration.ofSeconds(Math.max(30, expiry)), defaultAgentId);
    }

    private List<Target> readTargets(Properties props) {
        String list = firstNonBlank(props.getProperty("targets"), "dev");
        List<Target> targets = new ArrayList<>();
        for (String rawId : list.split(",")) {
            String id = rawId.trim();
            if (id.isEmpty()) {
                continue;
            }
            String label = firstNonBlank(props.getProperty("target." + id + ".label"), "RPGQuest " + id.toUpperCase(Locale.ROOT));
            String modeRaw = firstNonBlank(props.getProperty("target." + id + ".mode"), "bridge");
            Target.Mode mode = "snapshot".equalsIgnoreCase(modeRaw) ? Target.Mode.SNAPSHOT : Target.Mode.BRIDGE;
            String bridgeUrl = firstNonBlank(props.getProperty("target." + id + ".bridge-url"),
                    "http://127.0.0.1:8100/admin/v1");
            String tokenEnvKey = firstNonBlank(props.getProperty("target." + id + ".token-env"),
                    "RPGQUEST_BRIDGE_TOKEN_" + id.toUpperCase(Locale.ROOT));
            String token = firstNonBlank(env.apply(tokenEnvKey), "");
            targets.add(new Target(id, label, mode, bridgeUrl, token));
        }
        if (targets.isEmpty()) {
            throw new PanelConfigException("Aucune cible déclarée (propriété « targets »).");
        }
        return targets;
    }

    private Properties readProperties() {
        Properties props = new Properties();
        Path file = explicitFile != null ? explicitFile : resolveDefaultFile();
        if (file == null || !Files.isReadable(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new PanelConfigException("Impossible de lire " + file + " : " + e.getMessage());
        }
        return props;
    }

    private Path resolveDefaultFile() {
        String explicit = env.apply("RPGQUEST_PANEL_CONFIG");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        Path local = Path.of("control-panel.properties");
        return Files.isReadable(local) ? local : null;
    }

    private String require(String key, String description) {
        String value = env.apply(key);
        if (value == null || value.isBlank()) {
            throw new PanelConfigException("Variable d'environnement obligatoire absente : " + key
                    + " (" + description + "). Le Control Panel ne démarre pas sans elle (fail-closed).");
        }
        return value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static boolean booleanOf(String raw) {
        return raw != null && ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw));
    }

    private static int intOf(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
