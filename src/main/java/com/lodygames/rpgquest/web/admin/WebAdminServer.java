package com.lodygames.rpgquest.web.admin;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.web.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

/**
 * Bridge d'administration HTTP du plugin (issue #37) — la <strong>seule</strong> voie d'intégration
 * entre le RPGQuest Control Panel et le plugin. API <strong>versionnée</strong> ({@code
 * /admin/v1/*}), <strong>authentifiée</strong> par jeton porteur, qui répond avec l'état
 * <strong>réel</strong> du serveur ({@link HealthSource}) — jamais une lecture de {@code data.db}.
 *
 * <p><strong>Fail-closed &amp; configuration hors dépôt</strong> : rien n'écoute tant que
 * {@code RPGQUEST_WEB_ADMIN_ENABLED=true} <em>et</em> {@code RPGQUEST_WEB_ADMIN_TOKEN} (jeton fort)
 * ne sont pas fournis par l'environnement — jamais par {@code config.yml}, jamais versionnés.
 * Écoute {@code 127.0.0.1} par défaut : le bridge n'est pas destiné à Internet en direct (reverse
 * proxy / réseau interne — voir {@code docs/control-panel/}).</p>
 *
 * <table><caption>Variables d'environnement</caption>
 * <tr><td>{@code RPGQUEST_WEB_ADMIN_ENABLED}</td><td>{@code false}</td></tr>
 * <tr><td>{@code RPGQUEST_WEB_ADMIN_TOKEN}</td><td>(aucun — sans lui le bridge ne démarre pas)</td></tr>
 * <tr><td>{@code RPGQUEST_WEB_ADMIN_BIND}</td><td>{@code 127.0.0.1}</td></tr>
 * <tr><td>{@code RPGQUEST_WEB_ADMIN_PORT}</td><td>{@code 8100}</td></tr>
 * <tr><td>{@code RPGQUEST_WEB_ADMIN_ENV}</td><td>{@code unknown}</td></tr></table>
 */
public final class WebAdminServer implements PluginService {

    /** Version du contrat exposé. Incrémentée uniquement sur changement incompatible. */
    public static final String API_VERSION = "v1";

    private static final String BEARER_PREFIX = "Bearer ";

    private final HealthSource healthSource;
    private final Logger logger;
    private final UnaryOperator<String> env;

    private HttpServer httpServer;
    private ExecutorService executor;

    public WebAdminServer(HealthSource healthSource, Logger logger) {
        this(healthSource, logger, System::getenv);
    }

    /** Constructeur injectable (tests) : {@code env} remplace {@link System#getenv(String)}. */
    public WebAdminServer(HealthSource healthSource, Logger logger, UnaryOperator<String> env) {
        this.healthSource = healthSource;
        this.logger = logger;
        this.env = env;
    }

    @Override
    public void start() {
        if (!"true".equalsIgnoreCase(env("RPGQUEST_WEB_ADMIN_ENABLED", ""))) {
            logger.info("Bridge d'administration web désactivé (RPGQUEST_WEB_ADMIN_ENABLED != true).");
            return;
        }
        String token = env("RPGQUEST_WEB_ADMIN_TOKEN", "");
        if (token.isBlank()) {
            logger.warn("Bridge d'administration web NON démarré : RPGQUEST_WEB_ADMIN_ENABLED=true mais "
                    + "RPGQUEST_WEB_ADMIN_TOKEN est absent (fail-closed).");
            return;
        }
        String bind = env("RPGQUEST_WEB_ADMIN_BIND", "127.0.0.1");
        int port = intEnv("RPGQUEST_WEB_ADMIN_PORT", 8100);
        try {
            httpServer = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException e) {
            logger.error("Bridge d'administration web : impossible d'ouvrir {}:{}.", bind, port, e);
            httpServer = null;
            return;
        }
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "RPGQuest-WebAdmin");
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(executor);
        httpServer.createContext("/admin/" + API_VERSION + "/health", exchange -> route(exchange, token));
        httpServer.start();
        logger.info("Bridge d'administration web à l'écoute sur {}:{} (routes /admin/{}/*).",
                bind, httpServer.getAddress().getPort(), API_VERSION);
    }

    @Override
    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Port réellement lié (utile quand {@code RPGQUEST_WEB_ADMIN_PORT=0}, en test), ou -1 si non démarré. */
    public int boundPort() {
        return httpServer == null ? -1 : httpServer.getAddress().getPort();
    }

    // ---- Routage / sécurité -------------------------------------------------------------------

    private void route(HttpExchange exchange, String expectedToken) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, error("method_not_allowed", "GET requis."));
                return;
            }
            if (!authorized(exchange, expectedToken)) {
                sendJson(exchange, 401, error("unauthorized", "Jeton porteur manquant ou invalide."));
                return;
            }
            try {
                sendJson(exchange, 200, healthSource.healthPayload());
            } catch (RuntimeException e) {
                logger.warn("Bridge d'administration web : échec du traitement de {}", exchange.getRequestURI(), e);
                sendJson(exchange, 500, error("internal_error", "Erreur interne du bridge."));
            }
        } catch (Exception e) {
            logger.warn("Bridge d'administration web : erreur de transport", e);
        }
    }

    /** Comparaison en temps constant, fail-closed (jeton attendu vide =&gt; refus). */
    private static boolean authorized(HttpExchange exchange, String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String provided = header.substring(BEARER_PREFIX.length()).trim();
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Utilitaires -----------------------------------------------------------------------

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", err);
        return root;
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String env(String key, String fallback) {
        String value = env.apply(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int intEnv(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
