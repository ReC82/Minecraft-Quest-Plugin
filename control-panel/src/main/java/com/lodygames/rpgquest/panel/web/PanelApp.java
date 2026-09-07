package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.audit.AuditLog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.PermissionService;
import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.bridge.BridgeClient;
import com.lodygames.rpgquest.panel.bridge.BridgeException;
import com.lodygames.rpgquest.panel.bridge.BridgeHealth;
import com.lodygames.rpgquest.panel.config.PanelConfig;
import com.lodygames.rpgquest.panel.config.Target;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.security.AuthService;
import com.lodygames.rpgquest.panel.security.PasswordHasher;
import com.lodygames.rpgquest.panel.security.Session;
import com.lodygames.rpgquest.panel.security.SessionStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Application Control Panel : assemble auth / sessions / CSRF / audit / bridge et sert les pages.
 * Rendu HTML côté serveur (pas de SPA en V1). {@link #start()} renvoie le port réellement lié
 * (utiliser {@code httpPort = 0} en test).
 */
public final class PanelApp {

    private static final System.Logger LOG = System.getLogger("rpgquest.panel");
    private static final String SESSION_COOKIE = "panel_session";
    private static final String LOGIN_CSRF_COOKIE = "panel_login_csrf";

    private final PanelConfig config;
    private final AuditLog audit;
    private final BridgeClient bridge;
    private final AuthService authService;
    private final SessionStore sessions;
    private final PermissionService permissions = new PermissionService();

    private HttpServer server;

    public PanelApp(PanelConfig config, AuditLog audit, BridgeClient bridge) {
        this.config = config;
        this.audit = audit;
        this.bridge = bridge;
        this.authService = new AuthService(config.ownerUsername(), config.ownerPasswordHash(), new PasswordHasher());
        this.sessions = new SessionStore(config.sessionSecret(),
                Duration.ofMinutes(config.sessionTtlMinutes()), Duration.ofMinutes(config.sessionIdleMinutes()));
    }

    public int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bind(), config.httpPort()), 0);
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "rpgquest-panel");
            t.setDaemon(true);
            return t;
        }));
        route("/", this::handleRoot);
        route("/health", this::handleLiveness);
        route("/login", this::handleLogin);
        route("/logout", this::handleLogout);
        route("/dashboard", this::handleDashboard);
        for (String path : new String[] {"/players", "/npc", "/quests", "/stories", "/diagnostics", "/admin", "/dev"}) {
            route(path, exchange -> handlePlaceholder(exchange, path));
        }
        server.start();
        int port = server.getAddress().getPort();
        LOG.log(System.Logger.Level.INFO, "event=panel_started port=" + port + " target=" + config.defaultTargetId()
                + " disabled=" + config.disabled());
        return port;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ---- Routage --------------------------------------------------------------------------

    private void route(String path, Route route) {
        server.createContext(path, wrap(path, route));
    }

    private interface Route {
        void handle(HttpExchange exchange) throws IOException;
    }

    private HttpHandler wrap(String path, Route route) {
        return exchange -> {
            String rid = UUID.randomUUID().toString().substring(0, 8);
            long t0 = System.nanoTime();
            int status = 0;
            try (exchange) {
                // Contexte "/" attrape tout : ne router que la racine exacte ici.
                if (path.equals("/") && !exchange.getRequestURI().getPath().equals("/")) {
                    status = 404;
                    Http.html(exchange, 404, Layout.bare("Introuvable", "<h1>404</h1><p class=\"muted\">Page inconnue.</p>"));
                    return;
                }
                if (config.disabled() && !path.equals("/health")) {
                    status = 503;
                    Http.html(exchange, 503, Layout.bare("Indisponible",
                            "<h1>Panel désactivé</h1><p class=\"muted\">Le Control Panel est temporairement hors service (kill-switch).</p>"));
                    return;
                }
                route.handle(exchange);
                status = exchange.getResponseCode();
            } catch (RuntimeException | IOException e) {
                status = 500;
                LOG.log(System.Logger.Level.WARNING, "event=handler_error rid=" + rid + " path=" + path, e);
                safeError(exchange);
            } finally {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                LOG.log(System.Logger.Level.INFO, "event=request rid=" + rid + " method=" + exchange.getRequestMethod()
                        + " path=" + exchange.getRequestURI().getPath() + " status=" + status + " ms=" + ms);
            }
        };
    }

    private static void safeError(HttpExchange exchange) {
        try {
            if (exchange.getResponseCode() == -1) {
                Http.html(exchange, 500, Layout.bare("Erreur", "<h1>Erreur interne</h1><p class=\"muted\">Réessayer plus tard.</p>"));
            }
        } catch (IOException ignored) {
            // réponse déjà partiellement envoyée : rien de plus à faire.
        }
    }

    // ---- Handlers -----------------------------------------------------------------------

    private void handleRoot(HttpExchange exchange) throws IOException {
        Http.redirect(exchange, "/dashboard");
    }

    /** Liveness du panel lui-même — jamais authentifié, jamais bloqué par le kill-switch. */
    private void handleLiveness(HttpExchange exchange) throws IOException {
        String body = "{\"panel\":\"ONLINE\",\"disabled\":" + config.disabled()
                + ",\"time\":\"" + Instant.now() + "\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (currentSession(exchange).isPresent()) {
                Http.redirect(exchange, "/dashboard");
                return;
            }
            String csrf = UUID.randomUUID().toString();
            Http.setCookie(exchange, LOGIN_CSRF_COOKIE, csrf, config.cookieSecure(), "Lax", 600);
            Http.html(exchange, 200, loginPage(csrf, null));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "POST requis");
            return;
        }
        Map<String, String> form = Http.formBody(exchange);
        String cookieCsrf = Http.cookies(exchange).get(LOGIN_CSRF_COOKIE);
        if (!constantTimeEquals(cookieCsrf, form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("Session expirée",
                    "<h1>Formulaire expiré</h1><p class=\"muted\">Recharge la page de connexion et réessaie.</p>"));
            return;
        }
        String username = form.getOrDefault("username", "");
        Optional<Role> role = authService.authenticate(username, form.getOrDefault("password", ""));
        String rid = UUID.randomUUID().toString().substring(0, 8);
        if (role.isEmpty()) {
            audit.record(safeActor(username), "login.failure", "env=" + config.defaultTargetId(),
                    "DENIED", "identifiants invalides", rid);
            String csrf = UUID.randomUUID().toString();
            Http.setCookie(exchange, LOGIN_CSRF_COOKIE, csrf, config.cookieSecure(), "Lax", 600);
            Http.html(exchange, 401, loginPage(csrf, "Identifiants invalides."));
            return;
        }
        Session session = sessions.create(config.ownerUsername(), role.get().name());
        Http.setCookie(exchange, SESSION_COOKIE, sessions.signedCookieValue(session),
                config.cookieSecure(), "Lax", config.sessionTtlMinutes() * 60);
        Http.clearCookie(exchange, LOGIN_CSRF_COOKIE, config.cookieSecure());
        audit.record(session.username(), "login.success", "env=" + config.defaultTargetId(), "OK", null, rid);
        Http.redirect(exchange, "/dashboard");
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "POST requis");
            return;
        }
        Optional<Session> session = currentSession(exchange);
        if (session.isEmpty()) {
            Http.redirect(exchange, "/login");
            return;
        }
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(session.get().csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF", "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        sessions.invalidate(session.get().id());
        Http.clearCookie(exchange, SESSION_COOKIE, config.cookieSecure());
        audit.record(session.get().username(), "logout", "env=" + config.defaultTargetId(), "OK", null, rid);
        Http.redirect(exchange, "/login");
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), Permission.DASHBOARD_VIEW)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/dashboard",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission manquante.</p>"));
            return;
        }
        Target target = config.defaultTarget();
        String content;
        try {
            BridgeHealth health = bridge.health(target);
            content = dashboardContent(target, health, null);
        } catch (BridgeException e) {
            LOG.log(System.Logger.Level.INFO, "event=bridge_unavailable target=" + target.id() + " reason="
                    + e.getMessage().replace('\n', ' '));
            content = dashboardContent(target, null, e.getMessage());
        }
        Http.html(exchange, 200, renderPage("Dashboard", session, "/dashboard", content));
    }

    private void handlePlaceholder(HttpExchange exchange, String path) throws IOException {
        Optional<Session> session = requireSession(exchange);
        if (session.isEmpty()) {
            return;
        }
        String name = path.substring(1);
        Http.html(exchange, 200, renderPage(name, session.get(), path,
                "<h1>" + Http.esc(capitalize(name)) + "</h1>"
                        + "<p class=\"sub\">Module à venir.</p>"
                        + "<p class=\"muted\">Ce module fait partie de la roadmap Control Panel "
                        + "(voir <code>docs/control-panel/ROADMAP.md</code>). Le socle #37 ne fournit que le "
                        + "dashboard et le health check réel.</p>"));
    }

    // ---- Rendu ------------------------------------------------------------------------

    private String dashboardContent(Target target, BridgeHealth health, String bridgeError) {
        boolean online = health != null;
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Dashboard</h1><p class=\"sub\">État réel du serveur RPGQuest — cible « ")
                .append(Http.esc(target.label())).append(" ».</p>");

        if (bridgeError != null) {
            sb.append("<div class=\"banner err\"><strong>RPGQuest ").append(Http.esc(target.label()))
                    .append(" indisponible.</strong><br>").append(Http.esc(bridgeError)).append("</div>");
        }

        sb.append("<div class=\"cards\">");
        card(sb, "Control Panel", "<span class=\"pill ok\">ONLINE</span>");
        card(sb, "Environnement cible", Http.esc(target.label()) + " <span class=\"muted\">(" + Http.esc(target.id()) + ")</span>");
        card(sb, "RPGQuest", online
                ? "<span class=\"pill ok\">ONLINE</span>"
                : "<span class=\"pill err\">OFFLINE</span>");
        card(sb, "Version plugin", online ? Http.esc(nz(health.pluginVersion())) : "—");
        card(sb, "API bridge", online ? Http.esc(nz(health.bridgeApiVersion())) : "—");
        card(sb, "Joueurs en ligne", online
                ? (health.playersOnline() < 0 ? "—" : health.playersOnline() + " / " + health.maxPlayers())
                : "—");
        card(sb, "Uptime plugin", online ? Http.esc(health.uptimeHuman()) : "—");
        card(sb, "Dernier check", Http.esc(Instant.now().toString()));
        sb.append("</div>");

        if (online && !health.worlds().isEmpty()) {
            sb.append("<h2>Mondes RPGQuest essentiels</h2><table><tr><th>Rôle</th><th>Monde</th><th>Chargé</th></tr>");
            for (BridgeHealth.WorldStatus w : health.worlds()) {
                sb.append("<tr><td>").append(Http.esc(w.role())).append("</td><td>").append(Http.esc(nz(w.name())))
                        .append("</td><td>").append(w.loaded()
                                ? "<span class=\"pill ok\">oui</span>" : "<span class=\"pill warn\">non</span>")
                        .append("</td></tr>");
            }
            sb.append("</table>");
            if (!health.allEssentialWorldsLoaded()) {
                sb.append("<div class=\"banner err\">Un ou plusieurs mondes essentiels ne sont pas chargés — "
                        + "certains parcours (Claims, Wild) seront cassés.</div>");
            }
        }
        return sb.toString();
    }

    private static void card(StringBuilder sb, String key, String value) {
        sb.append("<div class=\"card\"><div class=\"k\">").append(Http.esc(key)).append("</div><div class=\"v\">")
                .append(value).append("</div></div>");
    }

    private String loginPage(String csrf, String error) {
        return Layout.bare("Connexion", """
                <h1>Connexion</h1><p class="muted">RPGQuest Control Panel</p>
                <form method="post" action="/login">
                  <input type="hidden" name="_csrf" value="%CSRF%">
                  <label>Identifiant</label><input type="text" name="username" autocomplete="username" autofocus>
                  <label>Mot de passe</label><input type="password" name="password" autocomplete="current-password">
                  <button class="btn" type="submit">Se connecter</button>
                  %ERR%
                </form>
                """
                .replace("%CSRF%", Http.esc(csrf))
                .replace("%ERR%", error == null ? "" : "<div class=\"formerr\">" + Http.esc(error) + "</div>"));
    }

    private String renderPage(String title, Session session, String activeHref, String content) {
        return Layout.page(title, session.username(), activeHref, content)
                .replace("%CSRF%", "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">");
    }

    // ---- Sessions ----------------------------------------------------------------------

    private Optional<Session> currentSession(HttpExchange exchange) {
        return sessions.resolve(Http.cookies(exchange).get(SESSION_COOKIE));
    }

    private Optional<Session> requireSession(HttpExchange exchange) throws IOException {
        Optional<Session> session = currentSession(exchange);
        if (session.isEmpty()) {
            Http.redirect(exchange, "/login");
        }
        return session;
    }

    // ---- Utils ----------------------------------------------------------------------

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeActor(String username) {
        if (username == null || username.isBlank()) {
            return "anonymous";
        }
        String trimmed = username.strip();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
    }

    private static String nz(String value) {
        return value == null ? "—" : value;
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
