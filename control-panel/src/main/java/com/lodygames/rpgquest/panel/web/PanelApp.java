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
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.RefData;
import com.lodygames.rpgquest.panel.docs.DocLibrary;
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
import java.nio.file.Path;
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
    private final DocsPages docsPages = new DocsPages(DocLibrary.load());
    private final HomePages homePages = new HomePages(permissions);
    private final NotificationCenter notifications;
    private final ContentWorkspace contentWorkspace;
    private final ContentEditorPages contentEditor;
    private final ContentExportPages contentExportPages;
    private final com.lodygames.rpgquest.panel.diag.DiagnosticsService diagnostics;

    private HttpServer server;

    public PanelApp(PanelConfig config, AuditLog audit, BridgeClient bridge, AgentStore agentStore) {
        this.config = config;
        this.audit = audit;
        this.bridge = bridge;
        this.agentStore = agentStore;
        this.agentRegistry = new AgentRegistry(config.agents().agents());
        this.agentPages = new AgentPages(agentStore, agentRegistry, config.agents().defaultAgentId(), permissions);
        this.notifications = new NotificationCenter(agentStore, agentRegistry);
        this.contentWorkspace = new ContentWorkspace(
                config.contentRepoDir() == null || config.contentRepoDir().isBlank()
                        ? null : Path.of(config.contentRepoDir()));
        this.contentEditor = new ContentEditorPages(contentWorkspace);
        this.contentExportPages = new ContentExportPages(agentStore, config.agents().defaultAgentId());
        this.diagnostics = new com.lodygames.rpgquest.panel.diag.DiagnosticsService(
                agentStore, config.agents().thresholds());
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
        route("/home", this::handleHome);
        route("/dashboard", this::handleDashboard);
        route("/agents", this::handleAgents);
        route("/agents/action", this::handleActionCreate);
        route("/agents/actions.json", this::handleAgentActionsJson);
        route("/actions", this::handleActions);
        route("/assets/", this::handleAsset);
        route("/players", exchange -> handleBusinessPage(exchange, "/players", "Joueurs",
                Permission.PLAYERS_READ, agentPages::players));
        route("/quests", exchange -> handleBusinessPage(exchange, "/quests", "Quêtes",
                Permission.CONTENT_READ, agentPages::quests));
        route("/stories", exchange -> handleBusinessPage(exchange, "/stories", "Stories",
                Permission.CONTENT_READ, agentPages::stories));
        route("/quests/new", exchange -> handleContentEditor(exchange, "quests", "/quests",
                Permission.QUEST_CONTENT_WRITE, "NEW"));
        route("/quests/edit", exchange -> handleContentEditor(exchange, "quests", "/quests",
                Permission.QUEST_CONTENT_WRITE, "EDIT"));
        route("/quests/save", exchange -> handleContentEditor(exchange, "quests", "/quests",
                Permission.QUEST_CONTENT_WRITE, "SAVE"));
        route("/stories/new", exchange -> handleContentEditor(exchange, "stories", "/stories",
                Permission.STORY_CONTENT_WRITE, "NEW"));
        route("/stories/edit", exchange -> handleContentEditor(exchange, "stories", "/stories",
                Permission.STORY_CONTENT_WRITE, "EDIT"));
        route("/stories/save", exchange -> handleContentEditor(exchange, "stories", "/stories",
                Permission.STORY_CONTENT_WRITE, "SAVE"));
        route("/npcs", exchange -> handleBusinessPage(exchange, "/npcs", "PNJ",
                Permission.NPC_READ, agentPages::npcs));
        route("/dialogues", exchange -> handleBusinessPage(exchange, "/dialogues", "Dialogues",
                Permission.DIALOGUE_READ, agentPages::dialogues));
        route("/content/export", this::handleContentExport);
        route("/content/export/download", this::handleContentExportDownload);
        route("/docs", this::handleDocs);
        route("/diagnostics", this::handleDiagnostics);
        route("/diagnostics/refresh", this::handleDiagnosticsRefresh);
        for (String path : new String[] {"/admin", "/dev"}) {
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
        Http.redirect(exchange, "/home");
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
                Http.redirect(exchange, "/home");
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
        Http.redirect(exchange, "/home");
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

    /**
     * Home = launcher à tuiles (issue #92, lot Bootstrap). Page d'arrivée après connexion ;
     * accessible à toute session (chaque tuile est filtrée par sa propre permission).
     */
    private void handleHome(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Target target = config.defaultTarget();
        String agentId = config.agents().defaultAgentId();
        String serverState = agentId == null ? "UNKNOWN"
                : AgentLiveness.of(agentStore.latestHeartbeat(agentId), config.agents().thresholds(), Instant.now()).name();
        AgentPages.HomeSummary summary = agentPages.homeSummary(agentId);
        com.lodygames.rpgquest.panel.diag.DiagnosticsReport diag = diagnostics.collect(agentId);
        String body = homePages.render(session.role(), serverState, summary,
                new HomePages.DiagSummary(diag.errors(), diag.warnings(), diag.anyDataLoaded()));
        Http.html(exchange, 200, renderPage("Accueil", session, "/home", body,
                Layout.Shell.of(target.label(), serverState, session.username())));
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
        body.append(Ui.pageHeader("dashboard", "Dashboard",
                "État réel du serveur RPGQuest — cible « " + target.label() + " ».", ""));

        boolean agentIsPrimary = agentId != null;
        String serverState = "UNKNOWN";
        if (agentIsPrimary) {
            AgentDash d = agentDashboard(agentId, target);
            serverState = d.state();
            body.append(d.html());
        }
        if (permissions.can(session.role(), Permission.DIAGNOSTICS_READ)) {
            body.append(diagnosticsSummarySection(diagnostics.collect(agentId)));
        }
        body.append(localBridgeSection(target, !agentIsPrimary));

        Http.html(exchange, 200, renderPage("Dashboard", session, "/dashboard", body.toString(),
                Layout.Shell.of(target.label(), serverState, session.username())));
    }

    private record AgentDash(String html, String state) {
    }

    /** Synthèse Diagnostics du Dashboard (#38, §15) : compteurs + lien, jamais la liste complète. */
    private static String diagnosticsSummarySection(com.lodygames.rpgquest.panel.diag.DiagnosticsReport r) {
        StringBuilder sb = new StringBuilder("<h2>État du contenu</h2>");
        if (!r.anyDataLoaded()) {
            return sb.append("<p class=\"muted\">Aucun relevé chargé — ouvrir <a href=\"/diagnostics\">Diagnostics</a> "
                    + "puis « Actualiser les diagnostics ».</p>").toString();
        }
        sb.append("<div class=\"cards\">");
        sb.append(Ui.statCard("error", String.valueOf(r.errors()), r.errors() <= 1 ? "Erreur" : "Erreurs",
                r.errors() > 0 ? "err" : "", null));
        sb.append(Ui.statCard("warning", String.valueOf(r.warnings()),
                r.warnings() <= 1 ? "Avertissement" : "Avertissements", r.warnings() > 0 ? "warn" : "", null));
        sb.append("</div>");
        sb.append("<p><a class=\"btn btn-sm btn-outline-primary\" href=\"/diagnostics\">")
                .append(Icons.icon("diagnostics")).append("Voir les diagnostics</a></p>");
        return sb.toString();
    }

    /** Section « agent distant » : l'état pris en compte quand une cible a un agent (issue #51). */
    private AgentDash agentDashboard(String agentId, Target target) {
        Optional<HeartbeatRecord> hb = agentStore.latestHeartbeat(agentId);
        Instant now = Instant.now();
        AgentLiveness live = AgentLiveness.of(hb, config.agents().thresholds(), now);
        StringBuilder sb = new StringBuilder();

        if (hb.isEmpty()) {
            sb.append("<div class=\"hero err\"><div class=\"hero-main\"><span class=\"hero-ic\">")
                    .append(Icons.icon("server")).append("</span><div><div class=\"hero-name\">")
                    .append(Http.esc(target.label())).append("</div><div class=\"hero-state\">")
                    .append("Aucun heartbeat — <span class=\"muted\">via agent distant</span></div></div></div></div>");
            sb.append(Ui.banner("err", "<strong>Aucun heartbeat reçu de l'agent « " + Http.esc(agentId)
                    + " ».</strong><br>Le plugin RPGQuest n'a pas encore contacté PlugAdmin en HTTPS sortant. "
                    + "Vérifier <code>plugadmin-agent.properties</code> côté serveur."));
            return new AgentDash(sb.toString(), "OFFLINE");
        }
        HeartbeatRecord h = hb.get();
        String age = AgentLiveness.ageHuman(h.receivedAt(), now);
        String state = nz(h.serverState()).toUpperCase(java.util.Locale.ROOT);
        String heroKind = "ONLINE".equals(state) ? "ok" : "OFFLINE".equals(state) ? "err" : "";

        sb.append("<div class=\"hero ").append(heroKind).append("\"><div class=\"hero-main\">")
                .append("<span class=\"hero-ic\">").append(Icons.icon("ONLINE".equals(state) ? "online" : "server"))
                .append("</span><div><div class=\"hero-name\">").append(Http.esc(target.label()))
                .append("</div><div class=\"hero-state\">").append(Ui.liveness(live.name()))
                .append(" <span class=\"muted\">").append(Http.esc(state))
                .append(" · via agent distant</span></div></div></div>");
        sb.append("<div class=\"hero-facts\">")
                .append(heroFact("Heartbeat", Http.esc(age)))
                .append(heroFact("Joueurs", h.playersOnline() < 0 ? "—" : h.playersOnline() + " / " + h.maxPlayers()))
                .append(heroFact("Version", Http.esc(nz(h.pluginVersion()))))
                .append(heroFact("Uptime", Http.esc(h.uptimeHuman())))
                .append("</div></div>");

        sb.append("<div class=\"cards\">");
        sb.append(Ui.statCard("players", h.playersOnline() < 0 ? "—" : String.valueOf(h.playersOnline()),
                "Joueurs en ligne", "", "<span class=\"muted\">/ " + h.maxPlayers() + " max</span>"));
        sb.append(Ui.statCard("version", nz(h.pluginVersion()), "Version du plugin", "", null));
        sb.append(Ui.statCard("uptime", h.uptimeHuman(), "Uptime du plugin", "", null));
        sb.append(Ui.statCard("agents", nz(h.protocol()), "Protocole agent", "",
                "<span class=\"muted\">" + Http.esc(nz(h.environment())) + "</span>"));
        sb.append(Ui.statCard(state.equals("ONLINE") ? "online" : "server", state, "État serveur",
                state.equals("ONLINE") ? "ok" : state.equals("OFFLINE") ? "err" : "", null));
        sb.append("</div>");

        appendWorldsTable(sb, h.worldsJson());
        return new AgentDash(sb.toString(), state);
    }

    private static String heroFact(String k, String v) {
        return "<div class=\"hero-fact\"><div class=\"hf-k\">" + Http.esc(k) + "</div><div class=\"hf-v\">" + v + "</div></div>";
    }

    /**
     * Section « bridge local » {@code /admin/v1/health} de #37. Utile pour le dev local ou un
     * serveur co-localisé. {@code primary} = true quand aucune cible n'a d'agent (comportement
     * historique : bannière rouge si injoignable) ; false = affichage secondaire discret.
     */
    private String localBridgeSection(Target target, boolean primary) {
        StringBuilder inner = new StringBuilder();
        boolean bridgeOk = false;
        try {
            BridgeHealth health = bridge.health(target);
            bridgeOk = true;
            inner.append("<div class=\"cards\">");
            card(inner, "RPGQuest", "<span class=\"pill ok\">" + Http.esc(nz(health.status())) + "</span>");
            card(inner, "Version plugin", Http.esc(nz(health.pluginVersion())));
            card(inner, "API bridge", Http.esc(nz(health.bridgeApiVersion())));
            card(inner, "Joueurs", health.playersOnline() < 0 ? "—" : health.playersOnline() + " / " + health.maxPlayers());
            card(inner, "Uptime plugin", Http.esc(health.uptimeHuman()));
            inner.append("</div>");
            appendWorldStatusTable(inner, health);
        } catch (BridgeException e) {
            LOG.log(System.Logger.Level.INFO, "event=bridge_unavailable target=" + target.id() + " reason="
                    + e.getMessage().replace('\n', ' '));
            if (primary) {
                inner.append(Ui.banner("err", "<strong>RPGQuest " + Http.esc(target.label())
                        + " indisponible.</strong> <span class=\"pill err\">OFFLINE</span><br>"
                        + Http.esc(e.getMessage())
                        + "<br><span class=\"muted\">Aucun agent distant n'est configuré pour cette cible "
                        + "(voir docs/control-panel/AGENT.md).</span>"));
            } else {
                inner.append("<p class=\"muted\">Bridge local non joignable — normal si RPGQuest tourne ailleurs "
                        + "(VeryGames). Détail : ").append(Http.esc(e.getMessage())).append("</p>");
            }
        }
        // Détail technique : replié par défaut si tout va bien et qu'un agent est déjà la source.
        if (primary) {
            return "<h2>Bridge local <span class=\"muted\">("
                    + Http.esc(target.bridgeBaseUrl() == null ? "—" : target.bridgeBaseUrl())
                    + ")</span></h2>" + inner;
        }
        String open = bridgeOk ? "" : " open";
        return "<details class=\"tech-detail\"" + open + "><summary>Détails techniques — bridge local <span class=\"muted\">("
                + Http.esc(target.bridgeBaseUrl() == null ? "—" : target.bridgeBaseUrl())
                + ")</span></summary>" + inner + "</details>";
    }

    /** {@code /docs} (accueil + recherche) et {@code /docs/<slug>} (fiche). Slug résolu côté serveur. */
    private void handleDocs(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), Permission.DOCS_READ)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/docs",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission manquante.</p>"));
            return;
        }
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = Http.query(exchange);
        String q = query.get("q");

        if (path.equals("/docs") || path.equals("/docs/")) {
            Http.html(exchange, 200, renderPage("Documentation", session, "/docs", docsPages.home(q)));
            return;
        }
        // /docs/<slug> — jamais un chemin fichier : slug borné puis résolu contre la bibliothèque.
        String slug = path.substring("/docs/".length());
        if (slug.contains("/") || !slug.matches("[a-z0-9-]{1,64}")) {
            Http.html(exchange, 404, renderPage("Introuvable", session, "/docs", docsPages.page("", q)));
            return;
        }
        int status = docsPages.exists(slug) ? 200 : 404;
        Http.html(exchange, status, renderPage("Documentation", session, "/docs", docsPages.page(slug, q)));
    }

    // ---- Export de contenu (issue #108) -------------------------------------------------

    private void handleContentExport(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/content/export") && !path.equals("/content/export/")) {
            Http.redirect(exchange, "/content/export");
            return;
        }
        if (!permissions.can(session.role(), Permission.CONTENT_EXPORT)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/content/export",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission CONTENT_EXPORT requise.</p>"));
            return;
        }
        Map<String, String> query = Http.query(exchange);
        String body = contentExportPages.render(query.get("toast"), query.get("err"));
        Http.html(exchange, 200, renderPage("Export de contenu", session, "/content/export", body,
                Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    private void handleContentExportDownload(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), Permission.CONTENT_EXPORT)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/content/export",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission CONTENT_EXPORT requise.</p>"));
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String actionId = Http.query(exchange).getOrDefault("action", "").trim();
        Optional<AgentActionRow> row = agentStore.action(actionId);
        if (row.isEmpty() || !"content.export".equals(row.get().type())) {
            audit.record(session.username(), "content.export.download", "action=" + actionId, "DENIED",
                    "action inconnue ou mauvais type", rid);
            Http.redirect(exchange, "/content/export?err=" + enc("Export introuvable."));
            return;
        }
        AgentActionRow action = row.get();
        if (!"SUCCESS".equalsIgnoreCase(action.status().name())) {
            Http.redirect(exchange, "/content/export?err=" + enc("Cet export n'est pas encore prêt (" + action.status().name() + ")."));
            return;
        }
        String pack = extractPack(action.resultJson());
        if (pack == null || pack.isBlank()) {
            audit.record(session.username(), "content.export.download", "action=" + actionId, "FAILED",
                    "pack absent du résultat", rid);
            Http.redirect(exchange, "/content/export?err=" + enc("Le résultat de l'export ne contient pas de pack."));
            return;
        }
        String family = action.params().getOrDefault("family", "content");
        String filename = com.lodygames.rpgquest.panel.content.ContentExportName.forFamily(family);
        audit.record(session.username(), "content.export.download",
                "action=" + actionId + " family=" + family + " bytes=" + pack.getBytes(StandardCharsets.UTF_8).length,
                "OK", filename, rid);
        Http.attachment(exchange, filename, "application/yaml", pack.getBytes(StandardCharsets.UTF_8));
    }

    /** Extrait {@code details.pack} du résultat d'action (jamais d'exception vers l'appelant). */
    private static String extractPack(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return null;
        }
        try {
            Object parsed = com.lodygames.rpgquest.panel.json.Json.parse(resultJson);
            if (parsed instanceof Map<?, ?> root && root.get("details") instanceof Map<?, ?> details) {
                Object pack = details.get("pack");
                return pack == null ? null : String.valueOf(pack);
            }
        } catch (RuntimeException ignored) {
            // résultat illisible
        }
        return null;
    }

    // ---- Diagnostics (issue #38) ----------------------------------------------------------

    private void handleDiagnostics(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), Permission.DIAGNOSTICS_READ)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/diagnostics",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission manquante.</p>"));
            return;
        }
        Map<String, String> query = Http.query(exchange);
        String agentId = config.agents().defaultAgentId();
        com.lodygames.rpgquest.panel.diag.DiagnosticsReport report = diagnostics.collect(agentId);
        StringBuilder body = new StringBuilder();
        body.append(actionFeedback(query));
        body.append(DiagnosticsPages.render(session, agentId, report, Instant.now()));
        Http.html(exchange, 200, renderPage("Diagnostics", session, "/diagnostics", body.toString()));
    }

    /**
     * Rafraîchissement <strong>coordonné</strong> (#38, §18) : une seule action opérateur enqueue
     * les quelques relevés {@code *.list} dont dépendent les diagnostics (jamais dix). Feedback via
     * toast (#93). Aucun recalcul serveur ici : l'agent renverra les catalogues à jour.
     */
    private void handleDiagnosticsRefresh(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "POST requis");
            return;
        }
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF", "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        if (!permissions.can(session.role(), Permission.DIAGNOSTICS_READ)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/diagnostics",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission manquante.</p>"));
            return;
        }
        String agentId = config.agents().defaultAgentId();
        Optional<AgentIdentity> agent = agentRegistry.byId(agentId == null ? "" : agentId);
        if (agent.isEmpty()) {
            Http.redirect(exchange, withError("/diagnostics", agentId, null,
                    "Aucun agent configuré : rafraîchissement impossible."));
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String firstId = null;
        int enqueued = 0;
        for (String type : com.lodygames.rpgquest.panel.diag.DiagnosticsService.REFRESH_TYPES) {
            var spec = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.spec(type);
            if (spec.isEmpty() || !permissions.can(session.role(), spec.get().permission())) {
                continue;
            }
            String id = agentStore.createAction(agentId, type, Map.of(), session.username());
            if (firstId == null) {
                firstId = id;
            }
            enqueued++;
        }
        audit.record(session.username(), "diagnostics.refresh", "agent=" + agentId,
                "PENDING", enqueued + " relevé(s)", rid);
        LOG.log(System.Logger.Level.INFO, "event=diagnostics_refresh rid=" + rid + " agent=" + agentId
                + " enqueued=" + enqueued + " by=" + session.username());
        String back = "/diagnostics?agent=" + enc(agentId);
        Http.redirect(exchange, firstId == null ? back + "&err=" + enc("Aucun relevé autorisé pour votre rôle.")
                : back + "&toast=" + enc(firstId));
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
        Instant now = Instant.now();
        List<AgentActionRow> actions = agentStore.recentActions(agentId, 20);
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        int pending = 0;
        for (AgentActionRow a : actions) {
            if (!a.status().terminal()) {
                pending++;
            }
            ActionView.Group group = ActionView.Group.of(a.status());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", shortId(a.id()));
            item.put("idFull", a.id());
            // Relevé de catalogue ré-enfilé automatiquement après une mutation (#112/#115/#116/#119/#120) :
            // panel.js le compte pour savoir quand la file est au repos, mais ne l'affiche pas dans la cloche.
            item.put("auto", "auto".equals(a.createdBy()));
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
            // Enrichissement #93 : toasts + centre de notifications
            item.put("label", ActionView.humanLabel(a.type()));
            item.put("domain", ActionView.Domain.of(a.type()).slug());
            item.put("group", group.slug());
            item.put("target", ActionView.target(a));
            item.put("resultShort", ActionView.shortResult(a));
            item.put("age", ActionView.relativeTime(a.createdAt(), now));
            item.put("notifHtml", ActionView.notifItemHtml(a, now));
            items.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("agent", agentId);
        root.put("pending", pending);
        root.put("badge", notifications.badgeCount(notifications.recent(), now));
        root.put("actions", items);
        Http.json(exchange, 200, com.lodygames.rpgquest.panel.json.Json.write(root));
    }

    /**
     * Historique des actions (issue #93) : {@code /actions} = liste filtrable/recherchable,
     * {@code /actions/<id>} = détail. Source de vérité = table {@code agent_action}
     * ({@link AgentStore}) ; aucune donnée nouvelle. Permission {@code DIAGNOSTICS_READ}.
     */
    private void handleActions(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), Permission.DIAGNOSTICS_READ)) {
            Http.html(exchange, 403, renderPage("Refusé", session, "/actions",
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission " + Permission.DIAGNOSTICS_READ + " requise.</p>"));
            return;
        }
        Instant now = Instant.now();
        String path = exchange.getRequestURI().getPath();
        Optional<String> detailId = ActionsPages.idFromPath(path);
        if (detailId.isPresent()) {
            Optional<AgentActionRow> row = agentStore.action(detailId.get());
            if (row.isEmpty()) {
                Http.html(exchange, 404, renderPage("Introuvable", session, "/actions",
                        Ui.banner("err", "Action <code>" + Http.esc(detailId.get()) + "</code> introuvable.")));
                return;
            }
            Http.html(exchange, 200, renderPage("Action", session, "/actions",
                    ActionsPages.detail(row.get(), now)));
            return;
        }
        if (!path.equals("/actions") && !path.equals("/actions/")) {
            Http.redirect(exchange, "/actions");
            return;
        }
        ActionsPages.Query query = ActionsPages.Query.parse(Http.query(exchange));
        String body = ActionsPages.list(allRecentActions(500), query, now);
        Http.html(exchange, 200, renderPage("Historique des actions", session, "/actions", body));
    }

    /** Les {@code capPerAgent} dernières actions de chaque agent, fusionnées et triées par date desc. */
    private List<AgentActionRow> allRecentActions(int capPerAgent) {
        List<AgentActionRow> all = new java.util.ArrayList<>();
        for (AgentIdentity a : agentRegistry.all()) {
            all.addAll(agentStore.recentActions(a.id(), capPerAgent));
        }
        all.sort(java.util.Comparator.comparing(AgentActionRow::createdAt).reversed());
        return all;
    }

    private static final java.util.regex.Pattern ASSET_PATH =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*");

    /**
     * Sert les fichiers statiques embarqués sous {@code classpath:/assets/} : {@code panel.js},
     * Bootstrap ({@code /assets/bootstrap/…}), Bootstrap Icons ({@code /assets/bootstrap-icons/…}),
     * {@code plugadmin.css}. Tout est <strong>local</strong> — aucun CDN, cohérent avec la CSP
     * {@code default-src 'self'}. Chemin strictement validé (pas de {@code ..}, pas de chemin
     * absolu) ; ETag + {@code 304} ; cache court.
     */
    private void handleAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "GET requis");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String rel = path.startsWith("/assets/") ? path.substring("/assets/".length()) : "";
        if (rel.isEmpty() || rel.contains("..") || !ASSET_PATH.matcher(rel).matches()) {
            Http.text(exchange, 404, "asset introuvable");
            return;
        }
        byte[] bytes;
        try (var in = PanelApp.class.getResourceAsStream("/assets/" + rel)) {
            if (in == null) {
                Http.text(exchange, 404, "asset introuvable");
                return;
            }
            bytes = in.readAllBytes();
        }
        String etag = "\"" + sha256Hex(bytes).substring(0, 16) + "\"";
        var h = exchange.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
        h.set("Cache-Control", "public, max-age=3600");
        h.set("ETag", etag);
        if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }
        h.set("Content-Type", assetContentType(rel));
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String assetContentType(String rel) {
        int dot = rel.lastIndexOf('.');
        String ext = dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "application/javascript; charset=utf-8";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "ttf" -> "font/ttf";
            case "svg" -> "image/svg+xml";
            case "map", "json" -> "application/json; charset=utf-8";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            default -> "application/octet-stream";
        };
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
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
        body.append(actionFeedback(query));
        body.append(renderer.render(session, query));
        Http.html(exchange, 200, renderPage(title, session, path, body.toString()));
    }

    /**
     * Retour d'une mutation, en <strong>toast</strong> (issue #93) : {@code ?toast=<id>} affiche un
     * toast pour l'action créée (mis à jour par {@code panel.js} quand elle se résout) ;
     * {@code ?err=…} un toast d'échec immédiat. Plus de gros bloc « Actions récentes » ici.
     */
    private String actionFeedback(Map<String, String> query) {
        String err = query.get("err");
        if (err != null && !err.isBlank()) {
            return ActionView.errorToastHtml(trimTo(err, 200));
        }
        String toastId = query.get("toast");
        if (toastId != null && !toastId.isBlank()) {
            return agentStore.action(toastId.trim())
                    .map(a -> ActionView.toastHtml(a, Instant.now()))
                    .orElse("");
        }
        return "";
    }

    /**
     * Éditeur guidé de quêtes / stories (issue #46). {@code mode} ∈ {@code NEW} / {@code EDIT} (GET)
     * ou {@code SAVE} (POST, CSRF obligatoire). L'écriture est whitelistée et versionnée par
     * {@link ContentWorkspace} ; aucun déploiement.
     */
    private void handleContentEditor(HttpExchange exchange, String kind, String base,
                                     Permission permission, String mode) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.role(), permission)) {
            Http.html(exchange, 403, renderPage("Refusé", session, base,
                    "<h1>Accès refusé</h1><p class=\"muted\">Permission d'édition de contenu manquante.</p>"));
            return;
        }

        RefData ref = agentPages.referenceData(config.agents().defaultAgentId());
        ContentEditorPages.Result result;

        if ("SAVE".equals(mode)) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Http.text(exchange, 405, "POST requis");
                return;
            }
            Map<String, String> form = Http.formBody(exchange);
            if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
                Http.html(exchange, 403, Layout.bare("CSRF",
                        "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                return;
            }
            result = kind.equals("quests") ? contentEditor.questPost(ref, form) : contentEditor.storyPost(ref, form);
            String actor = session.username();
            String act = form.getOrDefault("_action", "");
            if ("save".equals(act) && result instanceof ContentEditorPages.Result.Redirect rd) {
                audit.record(actor, kind + ".content.write", rd.location(), "OK", null,
                        UUID.randomUUID().toString().substring(0, 8));
            }
        } else {
            String slug = null;
            if ("EDIT".equals(mode)) {
                String prefix = base + "/edit/";
                String path = exchange.getRequestURI().getPath();
                if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
                    Http.redirect(exchange, base);
                    return;
                }
                slug = path.substring(prefix.length());
                if (slug.endsWith("/")) {
                    slug = slug.substring(0, slug.length() - 1);
                }
            }
            boolean saved = "1".equals(Http.query(exchange).get("saved"));
            result = kind.equals("quests")
                    ? contentEditor.questPage(ref, slug, saved)
                    : contentEditor.storyPage(ref, slug, saved);
        }

        if (result instanceof ContentEditorPages.Result.Redirect rd) {
            Http.redirect(exchange, rd.location());
            return;
        }
        String body = ((ContentEditorPages.Result.Html) result).body();
        String title = kind.equals("quests") ? "Éditeur de quête" : "Éditeur de story";
        Http.html(exchange, 200, renderPage(title, session, base, body));
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
        // Garde-fou de contexte (#89) : un formulaire ouvert dans la fiche d'un PNJ précis porte
        // un champ caché « npc_ctx » ; il doit coïncider avec le « npc_id » validé. Empêche une
        // mutation du mauvais PNJ à cause d'un état de formulaire périmé côté navigateur.
        String npcCtx = form.getOrDefault("npc_ctx", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!npcCtx.isEmpty() && !npcCtx.equals(v.params().getOrDefault("npc_id", ""))) {
            audit.record(session.username(), "agent.action.create", "agent=" + agentId + " type=" + type,
                    "DENIED", "contexte PNJ incohérent (ctx=" + npcCtx + " id=" + v.params().get("npc_id") + ")", rid);
            Http.redirect(exchange, withError(returnPath, agentId, null,
                    "Contexte de PNJ incohérent — recharger la page et réessayer."));
            return;
        }
        String id = agentStore.createAction(agentId, type, v.params(), session.username());
        audit.record(session.username(), "agent.action.create",
                "agent=" + agentId + " type=" + type + " action=" + id, "PENDING", safeParams(v.params()), rid);
        LOG.log(System.Logger.Level.INFO, "event=agent_action_created rid=" + rid + " agent=" + agentId
                + " type=" + type + " action=" + id + " by=" + session.username());
        Http.redirect(exchange, appendContext(returnPath, agentId, v.params().get("player")) + "&toast=" + enc(id));
    }

    private static String safeReturnPath(String requested, String fallback) {
        return switch (requested == null ? "" : requested) {
            case "/players", "/quests", "/stories", "/npcs", "/dialogues", "/agents", "/diagnostics",
                 "/content/export" -> requested;
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

            // Synthèse d'activité compacte — l'historique complet est sur /actions (issue #93).
            List<AgentActionRow> actions = agentStore.recentActions(agent.id(), 3);
            sb.append("<h3>Activité récente</h3>");
            if (actions.isEmpty()) {
                sb.append(Ui.empty("Aucune action pour le moment."));
            } else {
                sb.append("<ul class=\"mini-activity\">");
                Instant nowA = Instant.now();
                for (AgentActionRow a : actions) {
                    String tgt = ActionView.target(a);
                    sb.append("<li>").append(ActionView.statusBadge(a.status())).append(" <span class=\"ma-t\">")
                            .append(Http.esc(ActionView.humanLabel(a.type()))).append("</span>");
                    if (!tgt.isEmpty()) {
                        sb.append(" <span class=\"muted\">").append(Http.esc(tgt)).append("</span>");
                    }
                    sb.append(" <span class=\"faint\">· ").append(Http.esc(ActionView.relativeTime(a.createdAt(), nowA)))
                            .append("</span></li>");
                }
                sb.append("</ul>");
            }
            sb.append("<p><a class=\"doc-cm-link\" href=\"/actions\">").append(Icons.icon("history"))
                    .append("Voir l'historique complet des actions</a></p>");
        }
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
                <div style="display:flex;align-items:center;gap:10px;margin-bottom:4px">
                  <span class="brand-mark">PA</span><h1 style="margin:0">PlugAdmin</h1></div>
                <p class="muted">Panneau d'administration RPGQuest</p>
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
        return finishPage(Layout.page(title, session.username(), activeHref, content), session);
    }

    private String renderPage(String title, Session session, String activeHref, String content, Layout.Shell shell) {
        return finishPage(Layout.page(title, activeHref, content, shell), session);
    }

    private String finishPage(String html, Session session) {
        return html
                .replace("%NOTIF%", notifBell(session))
                .replace("%CSRF%", "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">");
    }

    /** Cloche + centre de notifications de la topbar — visible seulement avec {@code DIAGNOSTICS_READ}. */
    private String notifBell(Session session) {
        if (!permissions.can(session.role(), Permission.DIAGNOSTICS_READ)) {
            return "";
        }
        return notifications.bellHtml(config.agents().defaultAgentId(), Instant.now());
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
