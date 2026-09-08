package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentEndpoints;
import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.AgentRegistry;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
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
import java.util.List;
import java.util.LinkedHashMap;
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
    private final AgentStore agentStore;
    private final AgentRegistry agentRegistry;
    private final AgentEndpoints agentEndpoints;
    private final AgentPages agentPages;

    private HttpServer server;

    public PanelApp(PanelConfig config, AuditLog audit, BridgeClient bridge, AgentStore agentStore) {
        this.config = config;
        this.audit = audit;
        this.bridge = bridge;
        this.agentStore = agentStore;
        this.agentRegistry = new AgentRegistry(config.agents().agents());
        this.agentPages = new AgentPages(agentStore, agentRegistry, config.agents().defaultAgentId(), permissions);
        this.agentEndpoints = new AgentEndpoints(agentRegistry, agentStore, audit,
                config.agents().actionExpiry(), config::disabled);
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
        route("/agents", this::handleAgents);
        route("/agents/action", this::handleActionCreate);
        route("/agents/actions.json", this::handleAgentActionsJson);
        route("/assets/panel.js", this::handleAssetPanelJs);
        route("/players", exchange -> handleBusinessPage(exchange, "/players", "Joueurs",
                Permission.PLAYERS_READ, agentPages::players));
        route("/quests", exchange -> handleBusinessPage(exchange, "/quests", "Quêtes",
                Permission.CONTENT_READ, agentPages::quests));
        route("/stories", exchange -> handleBusinessPage(exchange, "/stories", "Stories",
                Permission.CONTENT_READ, agentPages::stories));
        route("/npcs", exchange -> handleBusinessPage(exchange, "/npcs", "PNJ",
                Permission.NPC_READ, agentPages::npcs));
        route("/dialogues", exchange -> handleBusinessPage(exchange, "/dialogues", "Dialogues",
                Permission.DIALOGUE_READ, agentPages::dialogues));
        for (String path : new String[] {"/diagnostics", "/admin", "/dev"}) {
            route(path, exchange -> handlePlaceholder(exchange, path));
        }
        agentEndpoints.register(server);
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
        String agentId = config.agents().defaultAgentId();

        StringBuilder body = new StringBuilder();
        body.append("<h1>Dashboard</h1><p class=\"sub\">État réel du serveur RPGQuest — cible « ")
                .append(Http.esc(target.label())).append(" ».</p>");

        boolean agentIsPrimary = agentId != null;
        if (agentIsPrimary) {
            body.append(agentDashboardSection(agentId, target));
        }
        body.append(localBridgeSection(target, !agentIsPrimary));

        Http.html(exchange, 200, renderPage("Dashboard", session, "/dashboard", body.toString()));
    }

    /** Section « agent distant » : l'état pris en compte quand une cible a un agent (issue #51). */
    private String agentDashboardSection(String agentId, Target target) {
        Optional<HeartbeatRecord> hb = agentStore.latestHeartbeat(agentId);
        Instant now = Instant.now();
        AgentLiveness live = AgentLiveness.of(hb, config.agents().thresholds(), now);
        StringBuilder sb = new StringBuilder();

        sb.append("<h2>").append(Http.esc(target.label()))
                .append(" ").append(Ui.liveness(live.name()))
                .append(" <span class=\"muted\">via agent distant</span></h2>");

        if (hb.isEmpty()) {
            sb.append("<div class=\"banner err\"><strong>Aucun heartbeat reçu de l'agent « ")
                    .append(Http.esc(agentId)).append(" ».</strong><br>Le plugin RPGQuest n'a pas encore contacté "
                    + "PlugAdmin en HTTPS sortant. Vérifier <code>plugadmin-agent.properties</code> côté serveur.</div>");
            return sb.toString();
        }
        HeartbeatRecord h = hb.get();
        String age = AgentLiveness.ageHuman(h.receivedAt(), now);

        sb.append("<div class=\"cards\">");
        card(sb, "Statut", Ui.liveness(live.name()));
        card(sb, "Dernier heartbeat", Http.esc(age) + " <span class=\"muted\">(" + Http.esc(h.receivedAt().toString()) + ")</span>");
        card(sb, "Version plugin", Http.esc(nz(h.pluginVersion())));
        card(sb, "Protocole agent", Http.esc(nz(h.protocol())));
        card(sb, "Joueurs", h.playersOnline() < 0 ? "—" : h.playersOnline() + " / " + h.maxPlayers());
        card(sb, "Uptime plugin", Http.esc(h.uptimeHuman()));
        card(sb, "État serveur", Http.esc(nz(h.serverState())));
        card(sb, "Environnement", Http.esc(nz(h.environment())));
        sb.append("</div>");

        appendWorldsTable(sb, h.worldsJson());
        return sb.toString();
    }

    /**
     * Section « bridge local » {@code /admin/v1/health} de #37. Utile pour le dev local ou un
     * serveur co-localisé. {@code primary} = true quand aucune cible n'a d'agent (comportement
     * historique : bannière rouge si injoignable) ; false = affichage secondaire discret.
     */
    private String localBridgeSection(Target target, boolean primary) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Bridge local <span class=\"muted\">(").append(Http.esc(target.bridgeBaseUrl() == null ? "—" : target.bridgeBaseUrl()))
                .append(")</span></h2>");
        try {
            BridgeHealth health = bridge.health(target);
            sb.append("<div class=\"cards\">");
            card(sb, "RPGQuest", "<span class=\"pill ok\">" + Http.esc(nz(health.status())) + "</span>");
            card(sb, "Version plugin", Http.esc(nz(health.pluginVersion())));
            card(sb, "API bridge", Http.esc(nz(health.bridgeApiVersion())));
            card(sb, "Joueurs", health.playersOnline() < 0 ? "—" : health.playersOnline() + " / " + health.maxPlayers());
            card(sb, "Uptime plugin", Http.esc(health.uptimeHuman()));
            sb.append("</div>");
            appendWorldStatusTable(sb, health);
        } catch (BridgeException e) {
            LOG.log(System.Logger.Level.INFO, "event=bridge_unavailable target=" + target.id() + " reason="
                    + e.getMessage().replace('\n', ' '));
            if (primary) {
                sb.append("<div class=\"banner err\"><strong>RPGQuest ").append(Http.esc(target.label()))
                        .append(" indisponible.</strong> <span class=\"pill err\">OFFLINE</span><br>")
                        .append(Http.esc(e.getMessage()))
                        .append("<br><span class=\"muted\">Aucun agent distant n'est configuré pour cette cible "
                                + "(voir docs/control-panel/AGENT.md).</span></div>");
            } else {
                sb.append("<p class=\"muted\">Bridge local non joignable — normal si RPGQuest tourne ailleurs "
                        + "(VeryGames). Détail : ").append(Http.esc(e.getMessage())).append("</p>");
            }
        }
        return sb.toString();
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

    // ---- Agents (issue #51) -----------------------------------------------------------

    private void handleAgents(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), Permission.DIAGNOSTICS_READ)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/agents",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission manquante.</p>"));
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            handleAgentActionCreate(exchange, session);
            return;
        }
        Http.html(exchange, 200, renderPage("Agents", session, "/agents", agentsContent(session)));
    }

    private void handleAgentActionCreate(HttpExchange exchange, Session session) throws IOException {
        // Formulaire de preuve de /agents (type par défaut player.variable.get) et point d'entrée
        // générique partagent la même validation whitelistée.
        createAgentAction(exchange, session, "/agents");
    }

    /**
     * État JSON compact des actions récentes d'un agent — consommé par {@code /assets/panel.js}
     * pour le rafraîchissement automatique (issue #65). Même modèle que le tableau rendu côté
     * serveur ; {@code pending} = nombre d'actions non terminales (le polling s'arrête à 0).
     */
    private void handleAgentActionsJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Optional<Session> maybe = currentSession(exchange);
        if (maybe.isEmpty()) {
            Http.json(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        if (!permissions.can(maybe.get().role(), Permission.DIAGNOSTICS_READ)) {
            Http.json(exchange, 403, "{\"error\":\"forbidden\"}");
            return;
        }
        String agentId = Http.query(exchange).getOrDefault("agent", "").trim();
        if (agentRegistry.byId(agentId).isEmpty()) {
            Http.json(exchange, 404, "{\"error\":\"unknown_agent\"}");
            return;
        }
        List<AgentActionRow> actions = agentStore.recentActions(agentId, 20);
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        int pending = 0;
        for (AgentActionRow a : actions) {
            if (!a.status().terminal()) {
                pending++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", shortId(a.id()));
            item.put("idFull", a.id());
            item.put("type", a.type());
            item.put("typeHtml", Ui.actionType(a.type())); // libellé humain + fil technique copiable
            item.put("params", renderParams(a.params()));
            item.put("status", a.status().name());
            item.put("pill", actionPill(a.status()));
            item.put("statusHtml", Ui.actionStatus(a.status())); // pastille normalisée (glyphe + texte)
            item.put("terminal", a.status().terminal());
            item.put("deliverCount", a.deliverCount());
            item.put("result", renderResult(a));
            item.put("createdAt", a.createdAt().toString());
            items.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("agent", agentId);
        root.put("pending", pending);
        root.put("actions", items);
        Http.json(exchange, 200, com.lodygames.rpgquest.panel.json.Json.write(root));
    }

    /** Sert le script de rafraîchissement automatique (même origine, conforme CSP). */
    private void handleAssetPanelJs(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "GET requis");
            return;
        }
        byte[] js;
        try (var in = PanelApp.class.getResourceAsStream("/assets/panel.js")) {
            if (in == null) {
                Http.text(exchange, 404, "asset introuvable");
                return;
            }
            js = in.readAllBytes();
        }
        Http.securityHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
        exchange.sendResponseHeaders(200, js.length);
        try (var out = exchange.getResponseBody()) {
            out.write(js);
        }
    }

    // ---- Pages métier (Joueurs / Quêtes / Stories) ----------------------------------

    private interface PageRenderer {
        String render(Session session, Map<String, String> query);
    }

    private void handleBusinessPage(HttpExchange exchange, String path, String title,
                                    Permission permission, PageRenderer renderer) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), permission)) {
            Http.html(exchange, 403, renderPage("Refusé", session, path,
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission manquante.</p>"));
            return;
        }
        Map<String, String> query = Http.query(exchange);
        StringBuilder body = new StringBuilder();
        String err = query.get("err");
        if (err != null && !err.isBlank()) {
            body.append("<div class=\"banner err\">").append(Http.esc(trimTo(err, 200))).append("</div>");
        } else if ("1".equals(query.get("ok"))) {
            body.append("<div class=\"banner ok\">Action envoyée à l'agent — son statut apparaît "
                    + "ci-dessous dans « Actions récentes » et se rafraîchit tout seul.</div>");
        }
        body.append(renderer.render(session, query));
        Http.html(exchange, 200, renderPage(title, session, path, body.toString()));
    }

    /** {@code POST /agents/action} : création générique d'une action whitelistée depuis n'importe quelle page. */
    private void handleActionCreate(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "POST requis");
            return;
        }
        createAgentAction(exchange, maybe.get(), "/agents");
    }

    /**
     * Valide (permission + whitelist + bornes des paramètres) puis crée une action agent, journalise
     * et redirige vers la page d'origine. Défense en profondeur : l'agent puis le service métier
     * re-valident tout.
     */
    private void createAgentAction(HttpExchange exchange, Session session, String defaultReturn) throws IOException {
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF", "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String rawType = form.getOrDefault("type", "").trim();
        String type = rawType.isEmpty() ? "player.variable.get" : rawType;
        String agentId = form.getOrDefault("agent", "").trim();
        String returnPath = safeReturnPath(form.getOrDefault("return", defaultReturn), defaultReturn);

        Optional<com.lodygames.rpgquest.panel.agent.AgentActionCatalog.Spec> spec =
                com.lodygames.rpgquest.panel.agent.AgentActionCatalog.spec(type);
        if (spec.isEmpty()) {
            audit.record(session.username(), "agent.action.create", "type=" + type, "DENIED", "type non whitelisté", rid);
            Http.redirect(exchange, withError(returnPath, agentId, null, "Type d'action non autorisé."));
            return;
        }
        if (!permissions.can(session.role(), spec.get().permission())) {
            audit.record(session.username(), "agent.action.create", "type=" + type, "DENIED", "permission manquante", rid);
            Http.html(exchange, 403, renderPage("Refusé", session, returnPath,
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission " + spec.get().permission() + " requise.</p>"));
            return;
        }
        Optional<AgentIdentity> agent = agentRegistry.byId(agentId);
        if (agent.isEmpty()) {
            audit.record(session.username(), "agent.action.create", "type=" + type, "DENIED", "agent inconnu : " + agentId, rid);
            Http.redirect(exchange, withError(returnPath, agentId, null, "Agent inconnu."));
            return;
        }
        com.lodygames.rpgquest.panel.agent.AgentActionCatalog.Validation v =
                com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate(type, form);
        if (!v.valid()) {
            audit.record(session.username(), "agent.action.create", "agent=" + agentId + " type=" + type,
                    "DENIED", v.error(), rid);
            Http.redirect(exchange, withError(returnPath, agentId, form.get("player"), v.error()));
            return;
        }
        String id = agentStore.createAction(agentId, type, v.params(), session.username());
        audit.record(session.username(), "agent.action.create",
                "agent=" + agentId + " type=" + type + " action=" + id, "PENDING", safeParams(v.params()), rid);
        LOG.log(System.Logger.Level.INFO, "event=agent_action_created rid=" + rid + " agent=" + agentId
                + " type=" + type + " action=" + id + " by=" + session.username());
        Http.redirect(exchange, appendContext(returnPath, agentId, v.params().get("player")) + "&ok=1");
    }

    private static String safeReturnPath(String requested, String fallback) {
        return switch (requested == null ? "" : requested) {
            case "/players", "/quests", "/stories", "/npcs", "/dialogues", "/agents" -> requested;
            default -> fallback;
        };
    }

    private static String appendContext(String path, String agentId, String player) {
        StringBuilder sb = new StringBuilder(path).append("?agent=").append(enc(agentId));
        if (player != null && !player.isBlank()) {
            sb.append("&player=").append(enc(player));
        }
        return sb.toString();
    }

    private static String withError(String path, String agentId, String player, String error) {
        return appendContext(path, agentId, player) + "&err=" + enc(error == null ? "Requête invalide." : error);
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String safeParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, val) -> {
            if (!"value".equals(k)) { // ne jamais recopier une valeur libre dans l'audit
                sb.append(sb.isEmpty() ? "" : " ").append(k).append('=').append(val);
            } else {
                sb.append(sb.isEmpty() ? "" : " ").append("value=<défini>");
            }
        });
        return sb.toString();
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String agentsContent(Session session) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Agents RPGQuest</h1>")
                .append("<p class=\"sub\">Canal <strong>sortant</strong> : RPGQuest / VeryGames contacte PlugAdmin en "
                        + "HTTPS. PlugAdmin n'ouvre jamais de connexion vers VeryGames.</p>");
        if (agentRegistry.isEmpty()) {
            sb.append("<p class=\"muted\">Aucun agent configuré (propriété <code>agents</code> de "
                    + "<code>control-panel.properties</code>). Voir <code>docs/control-panel/AGENT.md</code>.</p>");
            return sb.toString();
        }
        boolean canSend = permissions.can(session.role(), Permission.ACTION_VARIABLE_GET);
        Instant now = Instant.now();
        for (AgentIdentity agent : agentRegistry.all()) {
            Optional<HeartbeatRecord> hb = agentStore.latestHeartbeat(agent.id());
            AgentLiveness live = AgentLiveness.of(hb, config.agents().thresholds(), now);
            sb.append("<h2>Agent ").append(Ui.liveness(live.name()))
                    .append(" <span class=\"muted\">").append(Http.esc(agent.id()))
                    .append(" · env ").append(Http.esc(agent.environment()))
                    .append(agent.usable() ? "" : " — jeton absent").append("</span></h2>");

            sb.append("<div class=\"cards\">");
            card(sb, "Dernier heartbeat", hb.map(h -> Http.esc(AgentLiveness.ageHuman(h.receivedAt(), now))
                    + " <span class=\"muted\">(" + Http.esc(h.receivedAt().toString()) + ")</span>").orElse("—"));
            card(sb, "Version plugin", hb.map(h -> Http.esc(nz(h.pluginVersion()))).orElse("—"));
            card(sb, "Joueurs", hb.map(h -> h.playersOnline() < 0 ? "—" : h.playersOnline() + " / " + h.maxPlayers()).orElse("—"));
            card(sb, "Uptime", hb.map(HeartbeatRecord::uptimeHuman).map(Http::esc).orElse("—"));
            sb.append("</div>");

            if (canSend) {
                sb.append("<h3>Action de preuve <span class=\"faint\">·</span> ").append(Ui.id("player.variable.get")).append("</h3>")
                        .append("<form method=\"post\" action=\"/agents\" class=\"actform read\">")
                        .append("<input type=\"hidden\" name=\"_csrf\" value=\"").append(Http.esc(session.csrfToken())).append("\">")
                        .append("<input type=\"hidden\" name=\"agent\" value=\"").append(Http.esc(agent.id())).append("\">")
                        .append("<label>Joueur (nom ou UUID)</label><input type=\"text\" name=\"player\" autocomplete=\"off\">")
                        .append("<label>Clé de variable</label><input type=\"text\" name=\"key\" value=\"CLAIM_TIER_1\">")
                        .append("<button class=\"btn\" type=\"submit\">Envoyer l'action</button>")
                        .append("</form>");
            } else {
                sb.append(Ui.empty("Envoi d'action non autorisé pour ce rôle."));
            }

            List<AgentActionRow> actions = agentStore.recentActions(agent.id(), 20);
            long pending = actions.stream().filter(a -> !a.status().terminal()).count();
            sb.append("<div class=\"actions-panel\" data-actions-agent=\"").append(Http.esc(agent.id()))
                    .append("\" data-actions-pending=\"").append(pending).append("\">");
            sb.append("<h3>Actions récentes</h3>");
            sb.append(Ui.tableOpen("Id", "Type", "Params", "Statut", "Livr.", "Résultat", "Créée"));
            if (actions.isEmpty()) {
                sb.append("<tr><td colspan=\"7\" class=\"muted\">Aucune action pour le moment.</td></tr>");
            } else {
                for (AgentActionRow a : actions) {
                    sb.append("<tr><td>").append(Ui.id(shortId(a.id()), a.id())).append("</td>")
                            .append("<td>").append(Ui.actionType(a.type())).append("</td>")
                            .append("<td class=\"muted\">").append(Http.esc(renderParams(a.params()))).append("</td>")
                            .append("<td>").append(Ui.actionStatus(a.status())).append("</td>")
                            .append("<td>").append(a.deliverCount()).append("</td>")
                            .append("<td>").append(Http.esc(renderResult(a))).append("</td>")
                            .append("<td class=\"muted\">").append(Http.esc(a.createdAt().toString())).append("</td></tr>");
                }
            }
            sb.append(Ui.tableClose());
            sb.append("<p class=\"muted poll-status\" hidden></p>");
            sb.append("</div>");
        }
        sb.append("<script src=\"/assets/panel.js\" defer></script>");
        return sb.toString();
    }

    private static String actionPill(AgentActionStatus status) {
        return switch (status) {
            case SUCCESS -> "ok";
            case PENDING, DELIVERED -> "warn";
            case FAILED, REJECTED, EXPIRED -> "err";
        };
    }

    private static String shortId(String id) {
        return id == null ? "" : (id.length() > 8 ? id.substring(0, 8) : id);
    }

    private static String renderParams(Map<String, String> params) {
        if (params.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(k).append('=').append(v));
        return sb.toString();
    }

    private static String renderResult(AgentActionRow a) {
        if (!a.status().terminal()) {
            return "—";
        }
        // Le statut est déjà porté par la pastille de la colonne « Statut » : ici, seulement
        // la valeur et le message lisibles.
        String value = a.resultValue() == null || a.resultValue().isBlank() ? "" : a.resultValue();
        String message = a.resultMessage() == null || a.resultMessage().isBlank() ? "" : a.resultMessage();
        String out = (value + (value.isEmpty() || message.isEmpty() ? "" : " · ") + message).trim();
        return out.isEmpty() ? "—" : MiniText.prettifyTokens(out);
    }

    // ---- Rendu ------------------------------------------------------------------------

    /** Table des mondes essentiels à partir du JSON {@code {role:{name,loaded}}} d'un heartbeat. */
    private void appendWorldsTable(StringBuilder sb, String worldsJson) {
        Map<String, Object> worlds;
        try {
            worlds = worldsJson == null || worldsJson.isBlank()
                    ? Map.of() : com.lodygames.rpgquest.panel.json.Json.parseObject(worldsJson);
        } catch (RuntimeException e) {
            return;
        }
        if (worlds.isEmpty()) {
            return;
        }
        sb.append("<h2>Mondes RPGQuest essentiels</h2>").append(Ui.tableOpen("Rôle", "Monde", "Chargé"));
        boolean allLoaded = true;
        for (Map.Entry<String, Object> e : worlds.entrySet()) {
            Map<String, Object> w = e.getValue() instanceof Map<?, ?> m
                    ? castMap(m) : Map.of();
            boolean loaded = Boolean.TRUE.equals(w.get("loaded"));
            allLoaded &= loaded;
            sb.append("<tr><td>").append(Http.esc(e.getKey())).append("</td><td>")
                    .append(Http.esc(nz(w.get("name") == null ? null : String.valueOf(w.get("name")))))
                    .append("</td><td>").append(loaded
                            ? Ui.pill("oui", "success", "✓") : Ui.pill("non", "pending", "○"))
                    .append("</td></tr>");
        }
        sb.append(Ui.tableClose());
        if (!allLoaded) {
            sb.append("<div class=\"banner err\">Un ou plusieurs mondes essentiels ne sont pas chargés — "
                    + "certains parcours (Claims, Wild) seront cassés.</div>");
        }
    }

    private void appendWorldStatusTable(StringBuilder sb, BridgeHealth health) {
        if (health.worlds().isEmpty()) {
            return;
        }
        sb.append("<h2>Mondes RPGQuest essentiels <span class=\"muted\">(bridge local)</span></h2>")
                .append(Ui.tableOpen("Rôle", "Monde", "Chargé"));
        for (BridgeHealth.WorldStatus w : health.worlds()) {
            sb.append("<tr><td>").append(Http.esc(w.role())).append("</td><td>").append(Http.esc(nz(w.name())))
                    .append("</td><td>").append(w.loaded()
                            ? Ui.pill("oui", "success", "✓") : Ui.pill("non", "pending", "○"))
                    .append("</td></tr>");
        }
        sb.append(Ui.tableClose());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
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
