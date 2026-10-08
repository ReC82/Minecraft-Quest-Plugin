package com.lodygames.rpgquest.panel.web;

import com.lodygames.rpgquest.panel.agent.AgentActionRow;
import com.lodygames.rpgquest.panel.agent.AgentActionStatus;
import com.lodygames.rpgquest.panel.agent.AgentEndpoints;
import com.lodygames.rpgquest.panel.agent.AgentIdentity;
import com.lodygames.rpgquest.panel.agent.AgentLiveness;
import com.lodygames.rpgquest.panel.agent.AgentRegistry;
import com.lodygames.rpgquest.panel.agent.AgentStore;
import com.lodygames.rpgquest.panel.agent.HeartbeatRecord;
import com.lodygames.rpgquest.panel.ops.RestartService;
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
import com.lodygames.rpgquest.panel.content.ContentPackSchema;
import com.lodygames.rpgquest.panel.content.ContentPackTemplates;
import com.lodygames.rpgquest.panel.content.ContentWorkspace;
import com.lodygames.rpgquest.panel.content.RefData;
import com.lodygames.rpgquest.panel.docs.DocLibrary;
import com.lodygames.rpgquest.panel.http.Http;
import com.lodygames.rpgquest.panel.security.AuthService;
import com.lodygames.rpgquest.panel.security.PasswordHasher;
import com.lodygames.rpgquest.panel.security.Session;
import com.lodygames.rpgquest.panel.security.SessionStore;
import com.lodygames.rpgquest.panel.users.PanelUser;
import com.lodygames.rpgquest.panel.users.SqliteUserRepository;
import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.McRight;
import com.lodygames.rpgquest.panel.users.GroupDirectory;
import com.lodygames.rpgquest.panel.users.McBridgeDirectory;
import com.lodygames.rpgquest.panel.users.McBridgeRepository;
import com.lodygames.rpgquest.panel.users.GroupRepository;
import com.lodygames.rpgquest.panel.users.InMemoryGroupRepository;
import com.lodygames.rpgquest.panel.users.SqliteGroupRepository;
import com.lodygames.rpgquest.panel.users.UserDirectory;
import com.lodygames.rpgquest.panel.users.UserRepository;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final UserDirectory users;
    private final GroupDirectory groups;
    private final McBridgeDirectory mcBridge;
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
    private final ContentDeletionPages contentDeletion;
    private final ContentExportPages contentExportPages;
    private final com.lodygames.rpgquest.panel.diag.DiagnosticsService diagnostics;
    /** Issue #95 — exploitation serveur : redémarrage piloté et page dédiée. */
    private final RestartService restartService;
    private final OpsPages opsPages;
    /** Dernière annonce acceptée, pour l'intervalle minimal anti-matraquage (#95). */
    private final java.util.concurrent.atomic.AtomicReference<Instant> lastAnnounceAt =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);

    private HttpServer server;

    public PanelApp(PanelConfig config, AuditLog audit, BridgeClient bridge, AgentStore agentStore) {
        this(config, audit, bridge, agentStore, new SqliteUserRepository(config.panelDbPath()),
                new SqliteGroupRepository(config.panelDbPath()));
    }

    /**
     * Variante avec un {@link UserRepository} injecté (tests, ou stockage alternatif). Le compte
     * OWNER d'environnement est toujours réamorcé — c'est le chemin de récupération anti-verrouillage
     * (issue #50).
     */
    public PanelApp(PanelConfig config, AuditLog audit, BridgeClient bridge, AgentStore agentStore,
                    UserRepository userRepository) {
        this(config, audit, bridge, agentStore, userRepository, new InMemoryGroupRepository());
    }

    /** Variante avec comptes <strong>et</strong> groupes injectés (tests, issue #199). */
    public PanelApp(PanelConfig config, AuditLog audit, BridgeClient bridge, AgentStore agentStore,
                    UserRepository userRepository, GroupRepository groupRepository) {
        this.config = config;
        this.audit = audit;
        this.bridge = bridge;
        this.agentStore = agentStore;
        this.agentRegistry = new AgentRegistry(config.agents().agents());
        this.contentWorkspace = new ContentWorkspace(
                config.contentRepoDir() == null || config.contentRepoDir().isBlank()
                        ? null : Path.of(config.contentRepoDir()),
                // Sauvegardes de suppression (#194) à côté de la base du panel, donc HORS du dépôt
                // Git et hors du JAR : les mettre sous src/main/resources/ les embarquerait dans
                // le plugin construit et salirait le working tree, ce qui bloque les déploiements.
                config.panelDbPath() == null || config.panelDbPath().isBlank() ? null
                        : Path.of(config.panelDbPath()).toAbsolutePath().getParent()
                                .resolve("content-backups"));
        this.agentPages = new AgentPages(agentStore, agentRegistry, config.agents().defaultAgentId(),
                permissions, new com.lodygames.rpgquest.panel.content.SourceCatalog(contentWorkspace));
        this.notifications = new NotificationCenter(agentStore, agentRegistry);
        this.contentEditor = new ContentEditorPages(contentWorkspace);
        // Issue #194 : suppression de contenu, aperçu + confirmation. Partage l'espace de travail
        // et le catalogue source, donc voit exactement ce que l'éditeur voit.
        this.contentDeletion = new ContentDeletionPages(contentWorkspace,
                new com.lodygames.rpgquest.panel.content.ContentDeletionAnalyzer(contentWorkspace,
                        new com.lodygames.rpgquest.panel.content.SourceCatalog(contentWorkspace)));
        this.contentExportPages = new ContentExportPages(agentStore, config.agents().defaultAgentId());
        this.diagnostics = new com.lodygames.rpgquest.panel.diag.DiagnosticsService(
                agentStore, config.agents().thresholds());
        // Issue #95 — redémarrage par RCON depuis AWS. C'est le mécanisme déjà éprouvé par
        // scripts/verygames-restart.sh : un plugin ne peut pas garantir son propre retour, puisque
        // l'agent s'arrête avec le serveur. Les annonces du compte à rebours réutilisent l'action
        // agent whitelistée « server.announce » — aucun second canal d'annonce.
        //
        // Le témoin de redémarrage lit l'uptime annoncé par le heartbeat : si l'uptime DIMINUE, le
        // processus a bien redémarré, même si aucune sonde RCON n'est tombée pendant l'arrêt.
        String opsAgentId = config.agents().defaultAgentId();
        this.restartService = new RestartService(
                new RestartService.ConfiguredRconGateway(
                        config.ops().rconFor(config.defaultTargetId()),
                        config.ops().rconTimeout(),
                        "Aucun accès RCON configuré pour la cible « " + config.defaultTargetId()
                                + " ». Renseigner ops.rcon." + config.defaultTargetId()
                                + ".host et la variable d'environnement du mot de passe."),
                message -> enqueueAnnounce(opsAgentId, message),
                () -> opsAgentId == null ? java.util.Optional.empty()
                        : agentStore.latestHeartbeat(opsAgentId)
                                .map(HeartbeatRecord::uptimeSeconds)
                                .filter(seconds -> seconds >= 0),
                config.ops().restartReturnTimeout(), config.ops().restartPollInterval(),
                config.ops().restartStopGrace());
        this.opsPages = new OpsPages(agentStore, permissions, restartService,
                config.agents().thresholds(), opsAgentId, null);
        this.agentEndpoints = new AgentEndpoints(agentRegistry, agentStore, audit,
                config.agents().actionExpiry(), config::disabled);
        PasswordHasher hasher = new PasswordHasher();
        this.users = new UserDirectory(userRepository, hasher);
        this.groups = new GroupDirectory(groupRepository);
        this.mcBridge = new McBridgeDirectory(new McBridgeRepository(config.panelDbPath()));
        this.users.ensureBootstrapOwner(config.ownerUsername(), config.ownerPasswordHash());
        this.authService = new AuthService(userRepository, hasher);
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
        // Issue #194 : GET = aperçu des conséquences, POST = application après confirmation tapée.
        route("/quests/delete", exchange -> handleContentDelete(exchange, "quests"));
        route("/stories/delete", exchange -> handleContentDelete(exchange, "stories"));
        route("/npcs", exchange -> handleBusinessPage(exchange, "/npcs", "PNJ",
                Permission.NPC_READ, agentPages::npcs));
        route("/travel", exchange -> handleBusinessPage(exchange, "/travel", "Réseau de voyage",
                Permission.TRAVEL_READ, agentPages::travel));
        route("/mobs", exchange -> handleBusinessPage(exchange, "/mobs", "Mobs spéciaux & boss",
                Permission.MOB_READ, agentPages::mobs));
        route("/dialogues", exchange -> handleBusinessPage(exchange, "/dialogues", "Dialogues",
                Permission.DIALOGUE_READ, agentPages::dialogues));
        route("/dialogues/new", exchange -> handleContentEditor(exchange, "dialogues", "/dialogues",
                Permission.DIALOGUE_WRITE, "NEW"));
        route("/dialogues/edit", exchange -> handleContentEditor(exchange, "dialogues", "/dialogues",
                Permission.DIALOGUE_WRITE, "EDIT"));
        route("/dialogues/save", exchange -> handleContentEditor(exchange, "dialogues", "/dialogues",
                Permission.DIALOGUE_WRITE, "SAVE"));
        route("/content/export", this::handleContentExport);
        route("/content/export/download", this::handleContentExportDownload);
        // Issue #110 — contrat de contenu machine-readable. Trois téléchargements en lecture pure,
        // tous GÉNÉRÉS depuis les descripteurs du moteur : aucun fichier maintenu à la main, donc
        // rien qui puisse décrire un type inexistant ou oublier un type existant.
        route("/content/schema.json", this::handleContentSchema);
        route("/content/template", this::handleContentTemplate);
        route("/content/contract.md", this::handleContentContractDoc);
        route("/docs", this::handleDocs);
        // Issue #95 — exploitation serveur. /ops rend la page ; les deux endpoints JSON
        // alimentent la console et le suivi d'opération sans rechargement complet.
        route("/ops", this::handleOps);
        route("/ops/restart", this::handleOpsRestart);
        route("/ops/restart/cancel", this::handleOpsRestartCancel);
        route("/ops/logs.json", this::handleOpsLogsJson);
        route("/ops/state.json", this::handleOpsStateJson);
        route("/diagnostics", this::handleDiagnostics);
        route("/diagnostics/refresh", this::handleDiagnosticsRefresh);
        route("/users", this::handleUsers);
        route("/users/create", this::handleUserCreate);
        route("/users/groups", this::handleUserGroups);
        route("/groups", this::handleGroups);
        route("/groups/create", this::handleGroupCreate);
        route("/groups/rename", this::handleGroupRename);
        route("/groups/permissions", this::handleGroupPermissions);
        route("/groups/delete", this::handleGroupDelete);
        route("/groups/mc", this::handleGroupMcNodes);
        route("/users/mc/link", this::handleMcLink);
        route("/users/mc/unlink", this::handleMcUnlink);
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
        restartService.shutdown();
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
        AuthService.Result auth = authService.authenticate(username, form.getOrDefault("password", ""));
        String rid = UUID.randomUUID().toString().substring(0, 8);
        if (!auth.ok()) {
            boolean disabled = auth.outcome() == AuthService.Outcome.DISABLED;
            audit.record(safeActor(username), "login.failure", "env=" + config.defaultTargetId(),
                    "DENIED", disabled ? "compte désactivé" : "identifiants invalides", rid);
            String csrf = UUID.randomUUID().toString();
            Http.setCookie(exchange, LOGIN_CSRF_COOKIE, csrf, config.cookieSecure(), "Lax", 600);
            Http.html(exchange, 401, loginPage(csrf,
                    disabled ? "Ce compte est désactivé — contactez un propriétaire du Control Panel."
                            : "Identifiants invalides."));
            return;
        }
        Session session = sessions.create(auth.userId(), auth.username(), auth.role().name());
        Http.setCookie(exchange, SESSION_COOKIE, sessions.signedCookieValue(session),
                config.cookieSecure(), "Lax", config.sessionTtlMinutes() * 60);
        Http.clearCookie(exchange, LOGIN_CSRF_COOKIE, config.cookieSecure());
        audit.record(session.username(), "login.success", "env=" + config.defaultTargetId(), "OK",
                "role=" + auth.role().name(), rid);
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
        String body = homePages.render(session.effective(), serverState, summary,
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
        if (!permissions.can(session.effective(), Permission.DASHBOARD_VIEW)) {
            forbidden(exchange, session, "/dashboard");
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
        if (permissions.can(session.effective(), Permission.DIAGNOSTICS_READ)) {
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
        if (!permissions.can(session.effective(), Permission.DOCS_READ)) {
            forbidden(exchange, session, "/docs");
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
        if (!permissions.can(session.effective(), Permission.CONTENT_EXPORT)) {
            forbidden(exchange, session, "/content/export");
            return;
        }
        Map<String, String> query = Http.query(exchange);
        String body = contentExportPages.render(query.get("toast"), query.get("err"));
        Http.html(exchange, 200, renderPage("Export de contenu", session, "/content/export", body,
                Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    // ---- Contrat de contenu machine-readable (issue #110) -------------------------------

    private void handleContentSchema(HttpExchange exchange) throws IOException {
        serveGeneratedContract(exchange, "content.schema.download",
                "lodyquests-content-pack-v" + ContentPackSchema.SCHEMA_VERSION + ".schema.json",
                "application/schema+json", ContentPackSchema.json());
    }

    private void handleContentTemplate(HttpExchange exchange) throws IOException {
        String family = Http.query(exchange).getOrDefault("family", "").trim().toLowerCase(Locale.ROOT);
        // Une famille inconnue n'est pas une erreur : elle donne le gabarit du pack complet, qui est
        // un sur-ensemble valide. Le nom du fichier dit toujours ce qui a réellement été servi.
        String label = ContentPackSchema.FAMILIES.contains(family) ? family : "content";
        serveGeneratedContract(exchange, "content.template.download",
                "lodyquests-template-" + label + ".yml", "application/yaml",
                ContentPackTemplates.template(family));
    }

    private void handleContentContractDoc(HttpExchange exchange) throws IOException {
        serveGeneratedContract(exchange, "content.contract.download",
                "lodyquests-content-contract.md", "text/markdown",
                ContentPackTemplates.aiDocumentation());
    }

    /**
     * Sert un document généré. Lecture pure : aucun état serveur n'est touché, aucun agent n'est
     * sollicité (le contrat ne dépend pas du serveur Minecraft), et la même permission que l'export
     * s'applique — c'est la même nature de donnée, du contenu déclaratif sans secret.
     */
    private void serveGeneratedContract(HttpExchange exchange, String auditAction, String filename,
                                        String contentType, String body) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.CONTENT_EXPORT)) {
            forbidden(exchange, session, "/content/export");
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        audit.record(session.username(), auditAction, "bytes=" + bytes.length, "OK", filename,
                UUID.randomUUID().toString().substring(0, 8));
        Http.attachment(exchange, filename, contentType, bytes);
    }

    private void handleContentExportDownload(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.CONTENT_EXPORT)) {
            forbidden(exchange, session, "/content/export");
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
        if (!permissions.can(session.effective(), Permission.DIAGNOSTICS_READ)) {
            forbidden(exchange, session, "/diagnostics");
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
    /**
     * Toutes les permissions d'une action sont-elles détenues ?
     *
     * <p>Une action qui orchestre plusieurs effets (créer une définition, faire apparaître un PNJ,
     * poser un skin) en exige <strong>l'ensemble</strong> : les regrouper ne doit jamais accorder
     * implicitement un droit que l'opérateur n'a pas. Voir
     * {@code AgentActionCatalog.Spec#requiredPermissions()}.</p>
     */
    private boolean hasAllPermissions(Session session, com.lodygames.rpgquest.panel.agent.AgentActionCatalog.Spec spec) {
        for (com.lodygames.rpgquest.panel.authz.Permission required : spec.requiredPermissions()) {
            if (!permissions.can(session.effective(), required)) {
                return false;
            }
        }
        return true;
    }

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
        if (!permissions.can(session.effective(), Permission.DIAGNOSTICS_READ)) {
            forbidden(exchange, session, "/diagnostics");
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
            if (spec.isEmpty() || !hasAllPermissions(session, spec.get())) {
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

    // ---- Suppression de contenu (issue #194) ----------------------------------------------

    /**
     * Suppression d'une quête ou d'une story.
     *
     * <p>{@code GET} affiche l'aperçu des conséquences ; {@code POST} applique, et seulement si
     * l'identifiant a été <strong>retapé</strong>. Après une suppression source réussie, la
     * suppression de la copie serveur est enfilée comme action agent — sans quoi le contenu
     * resterait chargé et donnerait l'impression de réapparaître.</p>
     */
    private void handleContentDelete(HttpExchange exchange, String kind) throws IOException {
        String base = "quests".equals(kind) ? "/quests" : "/stories";
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.CONTENT_DELETE)) {
            forbidden(exchange, session, base);
            return;
        }

        String agentId = config.agents().defaultAgentId();
        boolean post = "POST".equalsIgnoreCase(exchange.getRequestMethod());
        String slug;
        String typed = null;
        if (post) {
            Map<String, String> form = Http.formBody(exchange);
            if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
                Http.html(exchange, 403, Layout.bare("CSRF",
                        "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                return;
            }
            slug = form.getOrDefault("slug", "").trim();
            typed = form.getOrDefault(ContentDeletionPages.CONFIRM_FIELD, "");
        } else {
            slug = Http.query(exchange).getOrDefault("slug", "").trim();
        }

        boolean runtimePresent = agentPages.runtimeKnowsContent(agentId, kind, slug);
        // #194 : sans relevé, « le serveur ne connaît pas ce contenu » serait une affirmation
        // fausse — on ne lui a jamais demandé. L'aperçu doit dire la différence.
        boolean listingAvailable = agentPages.runtimeListingAvailable(agentId, kind);
        String title = "quests".equals(kind) ? "Supprimer une quête" : "Supprimer une story";

        if (!post) {
            Http.html(exchange, 200, renderPage(title, session, base,
                    contentDeletion.preview(kind, slug, runtimePresent, listingAvailable,
                            session.csrfToken(), null)));
            return;
        }

        var plan = contentDeletion.plan(kind, slug, runtimePresent);
        if (!ContentDeletionPages.confirmationMatches(plan, typed)) {
            // Confirmation absente ou erronée : on réaffiche l'aperçu, sans rien toucher.
            Http.html(exchange, 200, renderPage(title, session, base,
                    contentDeletion.preview(kind, slug, runtimePresent, listingAvailable,
                            session.csrfToken(),
                            "Confirmation incorrecte : il faut retaper exactement l'identifiant. "
                                    + "Aucune modification n'a été faite.")));
            return;
        }

        String rid = UUID.randomUUID().toString().substring(0, 8);
        var result = contentDeletion.apply(kind, slug, runtimePresent, typed);
        boolean queued = false;
        if (result != null && result.ok() && runtimePresent) {
            agentStore.createAction(agentId, "content.definition.delete",
                    Map.of("kind", kind, "id", plan.plainId()), session.username());
            queued = true;
        }
        audit.record(session.username(), kind + ".content.delete", base + "/" + slug,
                result != null && result.ok() ? "OK" : "REFUSED",
                result == null ? "confirmation invalide" : result.code(), rid);
        LOG.log(System.Logger.Level.INFO, "event=content_delete rid=" + rid + " kind=" + kind
                + " id=" + plan.plainId() + " ok=" + (result != null && result.ok())
                + " runtime_queued=" + queued + " by=" + session.username());

        Http.html(exchange, 200, renderPage(title, session, base,
                contentDeletion.result(kind, plan.plainId(), result, queued)));
    }

    // ---- Gestion des utilisateurs (issue #50) --------------------------------------------

    private static final java.util.regex.Pattern USER_ID = java.util.regex.Pattern.compile("[0-9a-fA-F-]{8,36}");

    /**
     * {@code /users} (liste + création), {@code /users/<id>} (détail), {@code /users/<id>/role} et
     * {@code /users/<id>/active} (mutations POST). Tout est gardé par {@link Permission#USER_MANAGE} —
     * jamais un test {@code role == OWNER}. Le contexte {@code /users/create} est routé à part.
     */
    private void handleUsers(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.USER_MANAGE)) {
            forbidden(exchange, session, "/users");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/users") || path.equals("/users/")) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Http.text(exchange, 405, "GET requis");
                return;
            }
            Map<String, String> q = Http.query(exchange);
            String err = q.get("err");
            String ok = q.get("ok");
            String flash = err != null ? trimTo(err, 200) : ok != null ? trimTo(ok, 200) : null;
            String body = UsersPages.list(users.list(), currentPanelUser(session), flash, err != null);
            Http.html(exchange, 200, renderPage("Utilisateurs", session, "/users", body));
            return;
        }
        String rest = path.substring("/users/".length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String action = slash < 0 ? "" : rest.substring(slash + 1);
        if (id.isEmpty() || !USER_ID.matcher(id).matches()) {
            Http.redirect(exchange, "/users");
            return;
        }
        Optional<PanelUser> target = users.byId(id);
        if (target.isEmpty()) {
            Http.html(exchange, 404, renderPage("Introuvable", session, "/users",
                    Ui.banner("err", "Compte introuvable.")));
            return;
        }
        switch (action) {
            case "" -> {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    Http.text(exchange, 405, "GET requis");
                    return;
                }
                Map<String, String> q = Http.query(exchange);
                String okMsg = q.get("ok");
                String errMsg = q.get("err");
                String body = (okMsg == null ? "" : Ui.banner("ok", Http.esc(trimTo(okMsg, 200))))
                        + UsersPages.detail(target.get(), currentPanelUser(session), users.activeOwnerCount(),
                        errMsg == null ? null : trimTo(errMsg, 200))
                        // Issue #199 : appartenances + droits effectifs AVEC leur provenance.
                        + GroupsPages.membershipBlock(target.get(), groups.list(),
                                groups.effectiveFor(target.get()), session.effective())
                        // Issue #200 : liaison joueur + état RÉEL du pont + synchronisation.
                        + McBridgePages.linkBlock(target.get(), mcBridge.linkOf(target.get().id()),
                                mcBridgeStateHtml(session, target.get()));
                Http.html(exchange, 200, renderPage("Compte", session, "/users", body));
            }
            case "role" -> handleUserRole(exchange, session, target.get());
            case "active" -> handleUserActive(exchange, session, target.get());
            default -> Http.redirect(exchange, "/users/" + enc(id));
        }
    }


    // ---- Groupes (issue #199) ------------------------------------------------------------------
    //
    // Chaque route revérifie USER_MANAGE côté backend, sur les droits EFFECTIFS : une URL atteinte
    // directement, sans passer par un lien de l'interface, est contrôlée exactement comme les
    // autres. Et chaque mutation est journalisée, y compris quand elle est REFUSÉE — un refus est
    // précisément ce qu'on veut pouvoir relire après coup.

    /** Garde commune : session valide + {@code USER_MANAGE} effectif. */
    private Optional<Session> requireGroupAdmin(HttpExchange exchange, String back) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.USER_MANAGE)) {
            forbidden(exchange, session, back);
            return Optional.empty();
        }
        return maybe;
    }

    /** POST avec jeton CSRF valide, sinon 403. */
    private Map<String, String> csrfCheckedForm(HttpExchange exchange, Session session) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "POST requis");
            return null;
        }
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return null;
        }
        return form;
    }

    private void handleGroups(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/groups");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/groups") || path.equals("/groups/")) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                Http.text(exchange, 405, "GET requis");
                return;
            }
            Map<String, String> q = Http.query(exchange);
            String err = q.get("err");
            String ok = q.get("ok");
            String flash = err != null ? trimTo(err, 200) : ok != null ? trimTo(ok, 200) : null;
            List<PanelGroup> all = groups.list();
            Map<String, Integer> counts = new LinkedHashMap<>();
            all.forEach(group -> counts.put(group.id(), groups.membersOf(group.id()).size()));
            String body = GroupsPages.list(all, counts, session.effective(), flash, err != null);
            Http.html(exchange, 200, renderPage("Groupes", session, "/groups", body));
            return;
        }
        String id = path.substring("/groups/".length());
        if (id.isEmpty() || !USER_ID.matcher(id).matches()) {
            Http.redirect(exchange, "/groups");
            return;
        }
        Optional<PanelGroup> group = groups.byId(id);
        if (group.isEmpty()) {
            Http.html(exchange, 404, renderPage("Introuvable", session, "/groups",
                    Ui.banner("err", "Groupe introuvable.")));
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "GET requis");
            return;
        }
        Map<String, String> q = Http.query(exchange);
        String okMsg = q.get("ok");
        String errMsg = q.get("err");
        List<PanelUser> members = new ArrayList<>();
        for (String userId : groups.membersOf(id)) {
            users.byId(userId).ifPresent(members::add);
        }
        String body = (okMsg == null ? "" : Ui.banner("ok", Http.esc(trimTo(okMsg, 200))))
                + GroupsPages.detail(group.get(), members, session.effective(),
                        errMsg == null ? null : trimTo(errMsg, 200))
                + McBridgePages.groupNodesBlock(group.get(), mcBridge.nodesOf(id), knownWorlds(),
                        mcGroupSyncHtml(session, group.get()));
        Http.html(exchange, 200, renderPage("Groupe", session, "/groups", body));
    }

    /** Permissions cochées dans un formulaire. Un nom par permission : {@code perm_<NOM>}. */
    private static Set<Permission> submittedPermissions(Map<String, String> form) {
        Set<Permission> wanted = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (form.containsKey("perm_" + permission.name())) {
                wanted.add(permission);
            }
        }
        return wanted;
    }

    private void handleGroupCreate(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/groups");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String name = form.getOrDefault("name", "");
        GroupDirectory.Outcome o = groups.create(session.effective(), name,
                form.get("description"), submittedPermissions(form));
        if (!o.ok()) {
            audit.record(session.username(), "group.create", "name=" + trimTo(name, 48), "DENIED", o.error(), rid);
            Http.redirect(exchange, "/groups?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "group.create", "name=" + o.after().name(), "OK",
                "permissions=" + permissionNames(o.after().permissions()), rid);
        Http.redirect(exchange, "/groups/" + enc(o.after().id()) + "?ok=" + enc("Groupe créé."));
    }

    private void handleGroupRename(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/groups");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String groupId = form.getOrDefault("group", "");
        GroupDirectory.Outcome o = groups.rename(session.effective(), groupId,
                form.getOrDefault("name", ""), form.get("description"));
        if (!o.ok()) {
            audit.record(session.username(), "group.rename", "group=" + trimTo(groupId, 48), "DENIED", o.error(), rid);
            Http.redirect(exchange, "/groups/" + enc(groupId) + "?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "group.rename", "group=" + o.after().name(), "OK",
                "from=" + o.before().name(), rid);
        Http.redirect(exchange, "/groups/" + enc(groupId) + "?ok=" + enc("Groupe enregistré."));
    }

    private void handleGroupPermissions(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/groups");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String groupId = form.getOrDefault("group", "");
        GroupDirectory.Outcome o = groups.setPermissions(session.effective(), groupId,
                submittedPermissions(form));
        if (!o.ok()) {
            audit.record(session.username(), "group.permissions", "group=" + trimTo(groupId, 48),
                    "DENIED", o.error(), rid);
            Http.redirect(exchange, "/groups/" + enc(groupId) + "?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "group.permissions", "group=" + o.after().name(), "OK",
                "from=[" + permissionNames(o.before().permissions()) + "] to=["
                        + permissionNames(o.after().permissions()) + "]", rid);
        Http.redirect(exchange, "/groups/" + enc(groupId) + "?ok="
                + enc("Permissions enregistrées : effet immédiat sur les sessions ouvertes."));
    }

    private void handleGroupDelete(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/groups");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String groupId = form.getOrDefault("group", "");
        Optional<PanelGroup> existing = groups.byId(groupId);
        if (existing.isEmpty()) {
            Http.redirect(exchange, "/groups?err=" + enc("Groupe introuvable."));
            return;
        }
        // Le nom retapé protège contre le clic de trop : supprimer un groupe retire des droits à
        // tous ses membres d'un coup.
        if (!existing.get().name().equals(form.get("confirm"))) {
            audit.record(session.username(), "group.delete", "group=" + existing.get().name(),
                    "DENIED", "confirmation du nom incorrecte", rid);
            Http.redirect(exchange, "/groups/" + enc(groupId) + "?err="
                    + enc("Nom de confirmation incorrect : rien n'a été supprimé."));
            return;
        }
        int members = groups.membersOf(groupId).size();
        GroupDirectory.Outcome o = groups.delete(session.effective(), groupId);
        if (!o.ok()) {
            audit.record(session.username(), "group.delete", "group=" + existing.get().name(),
                    "DENIED", o.error(), rid);
            Http.redirect(exchange, "/groups/" + enc(groupId) + "?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "group.delete", "group=" + o.before().name(), "OK",
                "permissions=[" + permissionNames(o.before().permissions()) + "] members=" + members, rid);
        Http.redirect(exchange, "/groups?ok=" + enc("Groupe supprimé : " + members
                + " membre(s) ont perdu ses droits, immédiatement."));
    }

    /** {@code POST /users/groups} — appartenances d'un compte. */
    private void handleUserGroups(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/users");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String userId = form.getOrDefault("user", "");
        Optional<PanelUser> target = users.byId(userId);
        if (target.isEmpty()) {
            Http.redirect(exchange, "/users?err=" + enc("Compte introuvable."));
            return;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (PanelGroup group : groups.list()) {
            if (form.containsKey("group_" + group.id())) {
                wanted.add(group.id());
            }
        }
        GroupDirectory.MembershipOutcome o = groups.setMemberships(session.effective(), target.get(), wanted);
        if (!o.ok()) {
            audit.record(session.username(), "user.groups", "username=" + target.get().username(),
                    "DENIED", o.error(), rid);
            Http.redirect(exchange, "/users/" + enc(userId) + "?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "user.groups", "username=" + target.get().username(), "OK",
                "from=[" + groupNames(o.before()) + "] to=[" + groupNames(o.after()) + "]", rid);
        Http.redirect(exchange, "/users/" + enc(userId) + "?ok="
                + enc("Groupes enregistrés : effet immédiat, sans reconnexion."));
    }

    private static String permissionNames(Set<Permission> permissions) {
        List<String> out = new ArrayList<>();
        permissions.forEach(permission -> out.add(permission.name()));
        return String.join(",", out);
    }

    private static String groupNames(List<PanelGroup> list) {
        List<String> out = new ArrayList<>();
        list.forEach(group -> out.add(group.name()));
        return String.join(",", out);
    }


    // ---- Pont vers les droits Minecraft (issue #200) -------------------------------------------
    //
    // Ces trois routes ne touchent que l'état LOCAL du panel (quel compte est lié à quel joueur,
    // quels droits un groupe accorde). L'application réelle dans LuckPerms passe par les actions
    // agent mc.group.sync / mc.rights.sync, enfilées depuis les formulaires via /agents/action :
    // c'est le seul canal vers le serveur de jeu, et il porte déjà permission, CSRF et audit.

    private void handleGroupMcNodes(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/groups");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String groupId = form.getOrDefault("group", "");
        Optional<PanelGroup> group = groups.byId(groupId);
        if (group.isEmpty()) {
            Http.redirect(exchange, "/groups?err=" + enc("Groupe introuvable."));
            return;
        }
        Set<McBridgeRepository.GroupNode> wanted = new LinkedHashSet<>();
        for (McRight.Definition definition : McRight.catalogue(knownWorlds())) {
            String key = McBridgePages.sanitise(definition.node());
            if (!form.containsKey("mcr_" + key)) {
                continue;
            }
            wanted.add(new McBridgeRepository.GroupNode(groupId, definition.node(),
                    form.getOrDefault("mcw_" + key, "")));
        }
        McBridgeDirectory.NodeOutcome o = mcBridge.setNodes(groupId, wanted);
        if (!o.ok()) {
            audit.record(session.username(), "mc.group.nodes", "group=" + group.get().name(),
                    "DENIED", o.error(), rid);
            Http.redirect(exchange, "/groups/" + enc(groupId) + "?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "mc.group.nodes", "group=" + group.get().name(), "OK",
                "from=[" + describeNodes(o.before()) + "] to=[" + describeNodes(o.after()) + "]", rid);
        Http.redirect(exchange, "/groups/" + enc(groupId) + "?ok="
                + enc("Droits Minecraft enregistrés dans le panel. Lancez la synchronisation pour "
                        + "les appliquer en jeu : tant qu'elle n'a pas réussi, rien n'a changé sur le serveur."));
    }

    private void handleMcLink(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/users");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String userId = form.getOrDefault("user", "");
        Optional<PanelUser> target = users.byId(userId);
        if (target.isEmpty()) {
            Http.redirect(exchange, "/users?err=" + enc("Compte introuvable."));
            return;
        }
        McBridgeDirectory.Outcome o = mcBridge.link(target.get(), form.get("uuid"),
                form.get("name"), session.username());
        if (!o.ok()) {
            audit.record(session.username(), "mc.link", "username=" + target.get().username(),
                    "DENIED", o.error(), rid);
            Http.redirect(exchange, "/users/" + enc(userId) + "?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "mc.link", "username=" + target.get().username(), "OK",
                "from=" + (o.before() == null ? "aucun" : o.before().mcUuid())
                        + " to=" + o.after().mcUuid(), rid);
        Http.redirect(exchange, "/users/" + enc(userId) + "?ok="
                + enc("Joueur lié. Lancez la synchronisation pour appliquer ses droits en jeu."));
    }

    private void handleMcUnlink(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireGroupAdmin(exchange, "/users");
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = csrfCheckedForm(exchange, session);
        if (form == null) {
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String userId = form.getOrDefault("user", "");
        Optional<PanelUser> target = users.byId(userId);
        if (target.isEmpty()) {
            Http.redirect(exchange, "/users?err=" + enc("Compte introuvable."));
            return;
        }
        Optional<McBridgeRepository.Link> existing = mcBridge.linkOf(userId);
        if (existing.isEmpty()) {
            Http.redirect(exchange, "/users/" + enc(userId) + "?err="
                    + enc("Ce compte n'est lié à aucun joueur."));
            return;
        }
        // UUID retapé : dissocier est un geste qui laisse un joueur avec des droits à révoquer.
        if (!existing.get().mcUuid().equalsIgnoreCase(trimTo(form.getOrDefault("confirm", ""), 40).trim())) {
            audit.record(session.username(), "mc.unlink", "username=" + target.get().username(),
                    "DENIED", "confirmation de l'UUID incorrecte", rid);
            Http.redirect(exchange, "/users/" + enc(userId) + "?err="
                    + enc("UUID de confirmation incorrect : rien n'a été dissocié."));
            return;
        }
        McBridgeDirectory.Outcome o = mcBridge.unlink(target.get());
        if (!o.ok()) {
            Http.redirect(exchange, "/users/" + enc(userId) + "?err=" + enc(o.error()));
            return;
        }
        audit.record(session.username(), "mc.unlink", "username=" + target.get().username(), "OK",
                "uuid=" + o.before().mcUuid(), rid);
        Http.redirect(exchange, "/users/" + enc(userId) + "?ok="
                + enc("Joueur dissocié dans le panel. ATTENTION : ses droits gérés sont TOUJOURS "
                        + "en place en jeu — lancez une synchronisation à zéro groupe pour les révoquer."));
    }

    /**
     * Mondes connus du serveur, d'après le dernier relevé de l'agent — même source que l'éditeur de
     * contenu. Vide si aucun relevé : on ne devine jamais un nom de monde, car un droit de
     * construction accordé sur un monde inexistant ne ferait rien, en silence.
     */
    private List<String> knownWorlds() {
        return agentPages.referenceData(config.agents().defaultAgentId()).worlds();
    }

    private static String describeNodes(List<McBridgeRepository.GroupNode> nodes) {
        List<String> out = new ArrayList<>();
        for (McBridgeRepository.GroupNode node : nodes) {
            out.add(node.isGlobal() ? node.node() : node.node() + "@" + node.world());
        }
        return String.join(",", out);
    }


    /**
     * État <strong>réel</strong> du pont pour ce compte, plus le bouton de synchronisation.
     *
     * <p>Deux états sont distingués à l'écran, et c'est le point important : ce que le panel
     * <em>veut</em> (union des droits de ses groupes) et ce que le serveur <em>porte</em> (dernier
     * relevé {@code mc.rights.read}). S'ils diffèrent, ou si aucun relevé n'existe, le panel le dit
     * plutôt que d'afficher l'état voulu comme s'il était appliqué.</p>
     */
    private String mcBridgeStateHtml(Session session, PanelUser target) {
        String agentId = config.agents().defaultAgentId();
        Optional<McBridgeRepository.Link> link = mcBridge.linkOf(target.id());
        StringBuilder sb = new StringBuilder();

        List<McBridgeRepository.GroupNode> desired =
                mcBridge.desiredFor(groups.groupsOf(target.id()));
        sb.append("<h3>Droits Minecraft</h3>");
        sb.append("<p class=\"muted\">Voulu par le panel : <strong>").append(desired.size())
                .append("</strong> droit(s), somme de ses groupes. L'état réel vient du serveur.</p>");
        if (!desired.isEmpty()) {
            sb.append("<ul class=\"muted\">");
            for (McBridgeRepository.GroupNode node : desired) {
                sb.append("<li><code>").append(Http.esc(node.node())).append("</code>")
                        .append(node.isGlobal() ? "" : " <span class=\"muted\">(monde "
                                + Http.esc(node.world()) + ")</span>").append("</li>");
            }
            sb.append("</ul>");
        }

        if (link.isEmpty()) {
            return sb.append(Ui.banner("info", "Aucun joueur lié : rien ne peut être appliqué en jeu."))
                    .toString();
        }
        if (agentId == null) {
            return sb.append(Ui.banner("warn", "Aucun agent configuré : l'état réel en jeu est "
                    + "inconnu et aucune synchronisation n'est possible.")).toString();
        }

        Optional<AgentActionRow> survey = agentPages.latestForPlayerPublic(agentId, "mc.rights.read",
                link.get().mcUuid());
        if (survey.isEmpty()) {
            sb.append(Ui.banner("info", "État réel <strong>inconnu</strong> : aucun relevé encore "
                    + "demandé. Le bouton ci-dessous interroge le serveur."));
        } else {
            sb.append(agentPages.resultLinePublic("Dernier relevé", survey.get()));
        }

        sb.append(mcForm(session, agentId, "mc.rights.read", "/users/" + target.id(),
                "<input type=\"hidden\" name=\"player\" value=\"" + Http.esc(link.get().mcUuid()) + "\">",
                "refresh", "Lire l'état réel en jeu", false));

        StringBuilder groupFields = new StringBuilder();
        groupFields.append("<input type=\"hidden\" name=\"player\" value=\"")
                .append(Http.esc(link.get().mcUuid())).append("\">");
        int index = 0;
        for (PanelGroup group : groups.groupsOf(target.id())) {
            if (mcBridge.nodesOf(group.id()).isEmpty()) {
                continue;
            }
            groupFields.append("<input type=\"hidden\" name=\"group").append(index)
                    .append("\" value=\"").append(Http.esc(group.id())).append("\">");
            index++;
        }
        sb.append("<p class=\"muted\">La synchronisation applique exactement les groupes ci-dessus. "
                + "Elle ne touche <strong>jamais</strong> un droit posé directement sur le joueur ni "
                + "une appartenance à un groupe externe, même de même nom et même monde.</p>");
        sb.append(mcForm(session, agentId, "mc.rights.sync", "/users/" + target.id(),
                groupFields.toString(), "save", "Synchroniser les droits en jeu", true));
        return sb.toString();
    }

    /** Bouton de synchronisation de la définition d'un groupe, avec ses droits en champs cachés. */
    private String mcGroupSyncHtml(Session session, PanelGroup group) {
        String agentId = config.agents().defaultAgentId();
        List<McBridgeRepository.GroupNode> nodes = mcBridge.nodesOf(group.id());
        StringBuilder sb = new StringBuilder("<h3>Appliquer en jeu</h3>");
        if (agentId == null) {
            return sb.append(Ui.banner("warn", "Aucun agent configuré : impossible d'appliquer ces "
                    + "droits en jeu.")).toString();
        }
        StringBuilder fields = new StringBuilder();
        fields.append("<input type=\"hidden\" name=\"group\" value=\"").append(Http.esc(group.id())).append("\">");
        fields.append("<input type=\"hidden\" name=\"label\" value=\"").append(Http.esc(group.name())).append("\">");
        int index = 0;
        for (McBridgeRepository.GroupNode node : nodes) {
            fields.append("<input type=\"hidden\" name=\"node").append(index).append("\" value=\"")
                    .append(Http.esc(node.node())).append("\">");
            if (!node.isGlobal()) {
                fields.append("<input type=\"hidden\" name=\"world").append(index).append("\" value=\"")
                        .append(Http.esc(node.world())).append("\">");
            }
            index++;
        }
        sb.append("<p class=\"muted\">Crée ou met à jour le groupe LuckPerms dédié à ce groupe "
                + "PlugAdmin. Idempotent : relancé sans changement, il ne modifie rien et le dit.</p>");
        sb.append(mcForm(session, agentId, "mc.group.sync", "/groups/" + group.id(),
                fields.toString(), "save", "Appliquer les droits de ce groupe", false));
        sb.append("<p class=\"muted\">Retirer le groupe LuckPerms fait perdre à ses membres "
                + "exactement les droits qu'il portait, et rien d'autre.</p>");
        sb.append(mcForm(session, agentId, "mc.group.delete", "/groups/" + group.id(),
                "<input type=\"hidden\" name=\"group\" value=\"" + Http.esc(group.id()) + "\">",
                "trash", "Retirer le groupe LuckPerms", false));
        for (String type : new String[] {"mc.group.sync", "mc.group.delete"}) {
            agentPages.latestOfTypePublic(agentId, type)
                    .ifPresent(row -> sb.append(agentPages.resultLinePublic("Dernière opération", row)));
        }
        return sb.toString();
    }

    /** Formulaire d'action agent minimal, posté sur la route générique déjà gardée. */
    private String mcForm(Session session, String agentId, String type, String returnPath,
                          String hiddenFields, String icon, String label, boolean danger) {
        return "<form method=\"post\" action=\"/agents/action\" class=\"actform\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"" + Http.esc(type) + "\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + Http.esc(returnPath) + "\">"
                + "<input type=\"hidden\" name=\"confirm\" value=\"true\">"
                + hiddenFields
                + "<button class=\"btn btn-sm " + (danger ? "btn-outline-warning" : "btn-outline-secondary")
                + "\" type=\"submit\">" + Icons.icon(icon) + Http.esc(label) + "</button></form>";
    }

    /** {@code POST /users/create} — routé à part car {@code /users/create} est un contexte plus long. */
    private void handleUserCreate(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!exchange.getRequestURI().getPath().equals("/users/create")) {
            Http.redirect(exchange, "/users");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "POST requis");
            return;
        }
        if (!permissions.can(session.effective(), Permission.USER_MANAGE)) {
            forbidden(exchange, session, "/users");
            return;
        }
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String username = form.getOrDefault("username", "").trim();
        com.lodygames.rpgquest.panel.authz.Role role = Role.byNameOrNull(form.get("role"));
        UserDirectory.Outcome outcome = users.create(username, form.getOrDefault("password", ""), role);
        if (!outcome.ok()) {
            audit.record(session.username(), "user.create", "username=" + safeActor(username),
                    "DENIED", outcome.error(), rid);
            Http.redirect(exchange, "/users?err=" + enc(outcome.error()));
            return;
        }
        PanelUser created = outcome.after();
        audit.record(session.username(), "user.create", "username=" + created.username(),
                "OK", "role=" + created.role().name(), rid);
        LOG.log(System.Logger.Level.INFO, "event=user_created rid=" + rid + " username=" + created.username()
                + " role=" + created.role().name() + " by=" + session.username());
        Http.redirect(exchange, "/users?ok=" + enc("Compte « " + created.username() + " » créé ("
                + created.role().label() + ")."));
    }

    private void handleUserRole(HttpExchange exchange, Session session, PanelUser target) throws IOException {
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
        String rid = UUID.randomUUID().toString().substring(0, 8);
        com.lodygames.rpgquest.panel.authz.Role newRole = Role.byNameOrNull(form.get("role"));
        UserDirectory.Outcome o = users.changeRole(target.id(), newRole);
        if (!o.ok()) {
            audit.record(session.username(), "user.role.change", "username=" + target.username(),
                    "DENIED", o.error(), rid);
            Http.redirect(exchange, "/users/" + enc(target.id()) + "?err=" + enc(o.error()));
            return;
        }
        if (o.before() != null && o.after() != null && o.before().role() != o.after().role()) {
            audit.record(session.username(), "user.role.change", "username=" + target.username(), "OK",
                    "from=" + o.before().role().name() + " to=" + o.after().role().name(), rid);
            LOG.log(System.Logger.Level.INFO, "event=user_role_change rid=" + rid + " username=" + target.username()
                    + " from=" + o.before().role().name() + " to=" + o.after().role().name() + " by=" + session.username());
        }
        Http.redirect(exchange, "/users/" + enc(target.id()) + "?ok=" + enc("Rôle enregistré : "
                + o.after().role().label() + "."));
    }

    private void handleUserActive(HttpExchange exchange, Session session, PanelUser target) throws IOException {
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
        String rid = UUID.randomUUID().toString().substring(0, 8);
        boolean active = "true".equals(form.getOrDefault("active", "").trim());
        UserDirectory.Outcome o = users.setActive(target.id(), active, session.userId());
        if (!o.ok()) {
            audit.record(session.username(), "user.active.change", "username=" + target.username(),
                    "DENIED", o.error(), rid);
            Http.redirect(exchange, "/users/" + enc(target.id()) + "?err=" + enc(o.error()));
            return;
        }
        if (o.before() != null && o.after() != null && o.before().active() != o.after().active()) {
            audit.record(session.username(), "user.active.change", "username=" + target.username(), "OK",
                    "from=" + o.before().active() + " to=" + o.after().active(), rid);
            LOG.log(System.Logger.Level.INFO, "event=user_active_change rid=" + rid + " username=" + target.username()
                    + " active=" + o.after().active() + " by=" + session.username());
        }
        Http.redirect(exchange, "/users/" + enc(target.id()) + "?ok=" + enc(
                o.after().active() ? "Compte réactivé." : "Compte désactivé."));
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
        if (!permissions.can(session.effective(), Permission.DIAGNOSTICS_READ)) {
            forbidden(exchange, session, "/agents");
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

    // ---- Exploitation serveur (issue #95) ------------------------------------------------

    private void handleOps(HttpExchange exchange) throws IOException {
        handleBusinessPage(exchange, "/ops", "Exploitation serveur",
                Permission.OPS_VIEW, opsPages::render);
    }

    /**
     * Demande de redémarrage. <strong>Confirmations adaptées</strong> : un redémarrage différé est
     * annulable, une case suffit ; un redémarrage immédiat déconnecte tout le monde sans recours,
     * il exige donc de taper le mot de confirmation. Le single-flight de {@link RestartService} fait
     * office de protection contre le double-clic et le rejeu de formulaire.
     */
    private void handleOpsRestart(HttpExchange exchange) throws IOException {
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
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        if (!permissions.can(session.effective(), Permission.OPS_RESTART)) {
            forbidden(exchange, session, "/ops");
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        long delaySeconds;
        try {
            delaySeconds = Long.parseLong(form.getOrDefault("delay_seconds", "0").trim());
        } catch (NumberFormatException e) {
            Http.redirect(exchange, "/ops?err=" + enc("Délai de redémarrage invalide."));
            return;
        }
        if (delaySeconds < 0) {
            Http.redirect(exchange, "/ops?err=" + enc("Délai de redémarrage négatif."));
            return;
        }
        if (delaySeconds == 0) {
            String typed = form.getOrDefault(OpsPages.CONFIRM_FIELD, "").trim();
            if (!OpsPages.CONFIRM_WORD.equals(typed)) {
                audit.record(session.username(), "ops.restart", "delay=0", "DENIED",
                        "mot de confirmation absent ou incorrect", rid);
                Http.redirect(exchange, "/ops?err=" + enc("Pour un redémarrage immédiat, taper exactement « "
                        + OpsPages.CONFIRM_WORD + " »."));
                return;
            }
        } else if (!"true".equals(form.getOrDefault("confirm", "").trim())) {
            audit.record(session.username(), "ops.restart", "delay=" + delaySeconds, "DENIED",
                    "confirmation absente", rid);
            Http.redirect(exchange, "/ops?err=" + enc("Confirmation obligatoire."));
            return;
        }

        RestartService.Outcome outcome = restartService.request(session.username(), Duration.ofSeconds(delaySeconds));
        audit.record(session.username(), "ops.restart", "delay=" + delaySeconds,
                outcome.ok() ? "ACCEPTED" : "REFUSED", outcome.message(), rid);
        LOG.log(System.Logger.Level.INFO, "event=ops_restart rid=" + rid + " by=" + session.username()
                + " delay=" + delaySeconds + " ok=" + outcome.ok() + " op=" + outcome.operationId());
        Http.redirect(exchange, outcome.ok()
                ? "/ops?ok=" + enc(outcome.message())
                : "/ops?err=" + enc(outcome.message()));
    }

    private void handleOpsRestartCancel(HttpExchange exchange) throws IOException {
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
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        if (!permissions.can(session.effective(), Permission.OPS_RESTART)) {
            forbidden(exchange, session, "/ops");
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        RestartService.Outcome outcome =
                restartService.cancel(session.username(), form.getOrDefault("operation", "").trim());
        audit.record(session.username(), "ops.restart.cancel",
                "operation=" + form.getOrDefault("operation", ""),
                outcome.ok() ? "CANCELLED" : "REFUSED", outcome.message(), rid);
        Http.redirect(exchange, outcome.ok()
                ? "/ops?ok=" + enc(outcome.message())
                : "/ops?err=" + enc(outcome.message()));
    }

    /**
     * Console récente, en JSON (issue #95). <strong>Pull-through</strong> : on lit le dernier relevé
     * {@code server.logs.tail} réussi, et on en ré-enfile un s'il n'y en a pas déjà un en vol. Aucun
     * SSE, aucun WebSocket, aucun port entrant sur le serveur de jeu — le ticket demandait
     * d'évaluer SSE, mais l'agent sortant existant suffit et évite un second canal : la fraîcheur
     * réelle est bornée par sa scrutation (~15 s), ce que la page affiche explicitement.
     */
    private void handleOpsLogsJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Optional<Session> maybe = currentSession(exchange);
        if (maybe.isEmpty()) {
            Http.json(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        if (!permissions.can(maybe.get().effective(), Permission.OPS_LOGS)) {
            Http.json(exchange, 403, "{\"error\":\"forbidden\"}");
            return;
        }
        String agentId = config.agents().defaultAgentId();
        Map<String, Object> root = new LinkedHashMap<>();
        if (agentId == null || agentRegistry.byId(agentId).isEmpty()) {
            root.put("unavailable", "Aucun agent RPGQuest configuré : la console est indisponible.");
            Http.json(exchange, 200, com.lodygames.rpgquest.panel.json.Json.write(root));
            return;
        }
        long after = 0L;
        try {
            after = Math.max(0L, Long.parseLong(Http.query(exchange).getOrDefault("after", "0").trim()));
        } catch (NumberFormatException ignored) {
            after = 0L;
        }
        Optional<AgentActionRow> latest = agentStore.latestSuccessfulActionOfType(agentId, "server.logs.tail");
        boolean pending = agentStore.hasOpenActionOfType(agentId, "server.logs.tail");
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        long cursor = after;
        boolean gap = false;
        String limitation = null;
        if (latest.isPresent()) {
            Map<String, Object> result = parseResultDetails(latest.get());
            Object rawLimitation = result.get("limitation");
            if (rawLimitation != null) {
                limitation = String.valueOf(rawLimitation);
            }
            if (Boolean.TRUE.equals(result.get("gap"))) {
                gap = true;
            }
            Object rawLines = result.get("lines");
            if (rawLines instanceof List<?> list) {
                for (Object element : list) {
                    if (!(element instanceof Map<?, ?> map)) {
                        continue;
                    }
                    long seq = longOf(map.get("seq"));
                    if (seq <= after) {
                        continue; // déjà vu par ce navigateur
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("seq", seq);
                    item.put("at", longOf(map.get("at")));
                    item.put("level", textOf(map.get("level"), "INFO"));
                    item.put("source", textOf(map.get("source"), ""));
                    item.put("message", textOf(map.get("message"), ""));
                    lines.add(item);
                    cursor = Math.max(cursor, seq);
                }
            }
            root.put("age", ActionView.relativeTime(latest.get().createdAt(), Instant.now()));
        } else if (!pending) {
            root.put("age", "aucun relevé encore reçu");
        }
        // Un seul relevé en vol à la fois : le navigateur peut interroger toutes les 4 s sans
        // remplir la file d'actions de l'agent.
        if (!pending) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("after", Long.toString(cursor));
            params.put("limit", Integer.toString(config.ops().logTailLines()));
            agentStore.createAction(agentId, "server.logs.tail", params, "auto");
            pending = true;
            // Ménage ciblé : sans lui, consulter la console une heure laisserait quelques centaines
            // de relevés derrière elle et noierait l'historique réel des actions d'administration.
            // On garde les derniers (diagnostic) et on ne touche jamais une action en cours.
            agentStore.pruneTerminalActionsOfType(agentId, "server.logs.tail", LOG_SURVEYS_KEPT);
        }
        if (limitation != null) {
            root.put("unavailable", limitation);
        }
        root.put("lines", lines);
        root.put("cursor", cursor);
        root.put("gap", gap);
        root.put("pending", pending);
        Http.json(exchange, 200, com.lodygames.rpgquest.panel.json.Json.write(root));
    }

    /**
     * Nombre de relevés de console conservés (issue #95) : assez pour diagnostiquer un problème de
     * remontée, pas assez pour polluer l'historique.
     */
    private static final int LOG_SURVEYS_KEPT = 5;

    /** Suivi d'une opération de redémarrage sans rechargement complet (issue #95). */
    private void handleOpsStateJson(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.json(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Optional<Session> maybe = currentSession(exchange);
        if (maybe.isEmpty()) {
            Http.json(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.OPS_VIEW)) {
            Http.json(exchange, 403, "{\"error\":\"forbidden\"}");
            return;
        }
        RestartService.Operation operation = restartService.current();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", operation.id());
        root.put("phase", operation.phase().name());
        root.put("label", OpsPages.phaseLabel(operation.phase()));
        root.put("detail", operation.detail() == null ? "" : operation.detail());
        root.put("terminal", operation.phase().terminal() || operation.phase() == RestartService.Phase.IDLE);
        root.put("html", opsPages.operationCardHtml(session, operation));
        // stateHtml (correctif #95) : le bloc d'état se renouvelle par ce même appel, à la place
        // du rechargement complet de /ops qui effaçait les formulaires en cours de saisie.
        root.put("stateHtml", opsPages.stateBlockHtml(Http.query(exchange)));
        Http.json(exchange, 200, com.lodygames.rpgquest.panel.json.Json.write(root));
    }

    /**
     * Enfile une annonce via l'action agent whitelistée — jamais un second canal d'annonce. Un
     * intervalle minimal protège les joueurs d'un matraquage (double-clic, rejeu, compte à rebours
     * qui se superposerait à une annonce manuelle).
     */
    private void enqueueAnnounce(String agentId, String message) {
        if (agentId == null || message == null || message.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant previous = lastAnnounceAt.get();
        if (previous.plus(config.ops().announceMinInterval()).isAfter(now)) {
            LOG.log(System.Logger.Level.INFO, "event=ops_announce_throttled agent=" + agentId);
            return;
        }
        lastAnnounceAt.set(now);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("message", message.length() > 200 ? message.substring(0, 200) : message);
        params.put("channel", "chat");
        String id = agentStore.createAction(agentId, "server.announce", params, "auto");
        LOG.log(System.Logger.Level.INFO, "event=ops_announce_enqueued agent=" + agentId + " action=" + id);
    }

    /** Détails structurés d'un résultat d'action, ou une carte vide si le JSON est illisible. */
    private Map<String, Object> parseResultDetails(AgentActionRow row) {
        String json = row.resultJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = com.lodygames.rpgquest.panel.json.Json.parseObject(json);
            Object details = parsed.get("details");
            if (details instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            return parsed;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String textOf(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
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
        if (!permissions.can(maybe.get().effective(), Permission.DIAGNOSTICS_READ)) {
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
        if (!permissions.can(session.effective(), Permission.DIAGNOSTICS_READ)) {
            forbidden(exchange, session, "/actions");
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
        if (!permissions.can(session.effective(), permission)) {
            forbidden(exchange, session, path);
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
        // Issue #95 : une opération synchrone du panel (redémarrage demandé / annulé) a déjà son
        // résultat ; elle n'a rien à suivre, juste à le dire.
        String ok = query.get("ok");
        if (ok != null && !ok.isBlank()) {
            return ActionView.okToastHtml(trimTo(ok, 200));
        }
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
        if (!permissions.can(session.effective(), permission)) {
            forbidden(exchange, session, base);
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
            result = switch (kind) {
                case "quests" -> contentEditor.questPost(ref, form);
                case "stories" -> contentEditor.storyPost(ref, form);
                default -> contentEditor.dialoguePost(ref, form);
            };
            String actor = session.username();
            String act = form.getOrDefault("_action", "");
            if ("save".equals(act) && result instanceof ContentEditorPages.Result.Redirect rd) {
                audit.record(actor, kind + ".content.write", rd.location(), "OK", null,
                        UUID.randomUUID().toString().substring(0, 8));
                if ("dialogues".equals(kind)) {
                    result = new ContentEditorPages.Result.Redirect(
                            linkDialogueToNpc(session, form, rd.location()));
                }
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
            Map<String, String> query = Http.query(exchange);
            boolean saved = "1".equals(query.get("saved"));
            result = switch (kind) {
                case "quests" -> contentEditor.questPage(ref, slug, saved);
                case "stories" -> contentEditor.storyPage(ref, slug, saved);
                default -> contentEditor.dialoguePage(ref, slug, saved, query.get("npc"));
            };
        }

        if (result instanceof ContentEditorPages.Result.Redirect rd) {
            Http.redirect(exchange, rd.location());
            return;
        }
        String body = ((ContentEditorPages.Result.Html) result).body();
        String title = switch (kind) {
            case "quests" -> "Éditeur de quête";
            case "stories" -> "Éditeur de story";
            default -> "Éditeur de dialogue";
        };
        Http.html(exchange, 200, renderPage(title, session, base, body));
    }

    /**
     * Deuxième écriture du workflow #145 (§5) : après l'enregistrement <em>source</em> d'un dialogue
     * créé avec un PNJ sélectionné, met à jour la <strong>définition du PNJ</strong> pour qu'elle
     * pointe vers ce dialogue, via l'action agent existante {@code npc.definition.update} (jamais de
     * stockage parallèle). Le dialogue source est déjà écrit : si le rattachement ne peut pas être
     * préparé (PNJ absent du dernier relevé), on le signale sans défaire l'écriture — demi-état
     * explicite plutôt que silencieux.
     *
     * @return la {@code Location} de redirection à utiliser (avec {@code toast=} ou {@code err=})
     */
    private String linkDialogueToNpc(Session session, Map<String, String> form, String savedLocation) {
        String npc = form.getOrDefault("npc", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (npc.isEmpty()) {
            return savedLocation;
        }
        String agentId = config.agents().defaultAgentId();
        String dialogueId = "rpgquest:" + com.lodygames.rpgquest.panel.content.DialogueYaml.plainId(
                form.getOrDefault("id", ""));
        Optional<Map<String, String>> cur = agentPages.npcDefinitionFields(agentId, npc);
        if (cur.isEmpty()) {
            return "/dialogues?agent=" + enc(agentId) + "&err=" + enc("Dialogue enregistré dans la source. "
                    + "Le PNJ « " + npc + " » est absent du dernier relevé — rafraîchir « PNJ » puis rattacher "
                    + "le dialogue depuis la fiche du PNJ.");
        }
        Map<String, String> npcForm = new java.util.LinkedHashMap<>(cur.get());
        npcForm.put("npc_id", npc);
        npcForm.put("dialogue_id", dialogueId);
        npcForm.put("confirm", "true");
        com.lodygames.rpgquest.panel.agent.AgentActionCatalog.Validation v =
                com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("npc.definition.update", npcForm);
        if (!v.valid()) {
            return "/dialogues?agent=" + enc(agentId) + "&err=" + enc("Dialogue enregistré dans la source, mais "
                    + "le rattachement au PNJ n'a pas pu être préparé : " + v.error());
        }
        Optional<AgentIdentity> agent = agentRegistry.byId(agentId);
        if (agent.isEmpty()) {
            return savedLocation;
        }
        String id = agentStore.createAction(agentId, "npc.definition.update", v.params(), session.username());
        audit.record(session.username(), "agent.action.create",
                "agent=" + agentId + " type=npc.definition.update action=" + id, "PENDING",
                "dialogue=" + dialogueId + " npc=" + npc, UUID.randomUUID().toString().substring(0, 8));
        return "/dialogues?agent=" + enc(agentId) + "&toast=" + enc(id);
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
        if (!hasAllPermissions(session, spec.get())) {
            audit.record(session.username(), "agent.action.create", "type=" + type, "DENIED", "permission manquante", rid);
            forbidden(exchange, session, returnPath);
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
            case "/players", "/quests", "/stories", "/npcs", "/travel", "/dialogues", "/agents", "/diagnostics",
                 "/content/export", "/mobs", "/ops" -> requested;
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
        boolean canSend = permissions.can(session.effective(), Permission.ACTION_VARIABLE_GET);
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
        return finishPage(Layout.page(title, activeHref, content,
                Layout.Shell.of(null, null, session.username(), roleLabel(session)), navFilter(session)), session);
    }

    private String renderPage(String title, Session session, String activeHref, String content, Layout.Shell shell) {
        Layout.Shell withRole = Layout.Shell.of(shell.envLabel(), shell.serverState(),
                shell.username(), roleLabel(session));
        return finishPage(Layout.page(title, activeHref, content, withRole, navFilter(session)), session);
    }

    /** Filtre de navigation : un lien n'est rendu que si sa permission est accordée au rôle (#50). */
    private java.util.function.Predicate<Layout.NavItem> navFilter(Session session) {
        return it -> it.permission() == null || permissions.can(session.effective(), it.permission());
    }

    private static String roleLabel(Session session) {
        Role r = Role.byNameOrNull(session.role());
        return r == null ? session.role() : r.label();
    }

    /** Page 403 cohérente et humaine (#50, §11) — jamais de détail de permission sensible. */
    private void forbidden(HttpExchange exchange, Session session, String activeHref) throws IOException {
        Http.html(exchange, 403, renderPage("Accès refusé", session, activeHref,
                "<h1>Accès refusé</h1>"
                        + "<p class=\"sub\">Vous n'avez pas l'autorisation d'accéder à cette fonction.</p>"
                        + "<p class=\"muted\">Si vous pensez que c'est une erreur, demandez à un propriétaire "
                        + "du Control Panel de vérifier votre rôle.</p>"));
    }

    private String finishPage(String html, Session session) {
        return html
                .replace("%NOTIF%", notifBell(session))
                .replace("%CSRF%", "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">");
    }

    /** Cloche + centre de notifications de la topbar — visible seulement avec {@code DIAGNOSTICS_READ}. */
    private String notifBell(Session session) {
        if (!permissions.can(session.effective(), Permission.DIAGNOSTICS_READ)) {
            return "";
        }
        return notifications.bellHtml(config.agents().defaultAgentId(), Instant.now());
    }

    // ---- Sessions ----------------------------------------------------------------------

    private Optional<Session> currentSession(HttpExchange exchange) {
        Optional<Session> maybe = sessions.resolve(Http.cookies(exchange).get(SESSION_COOKIE));
        if (maybe.isEmpty()) {
            return maybe;
        }
        Session session = maybe.get();
        // Re-contrôle par requête (#50) : un compte supprimé ou désactivé perd sa session en cours,
        // et un changement de rôle prend effet sans reconnexion.
        Optional<PanelUser> user = users.byId(session.userId());
        if (user.isEmpty() || !user.get().active()) {
            sessions.invalidate(session.id());
            return Optional.empty();
        }
        // Droits effectifs recalculés à CHAQUE requête (issue #199) : rôle ∪ groupes. C'est ce qui
        // fait qu'une révocation prend effet immédiatement sur une session active — retirer un
        // groupe, retirer une permission d'un groupe ou supprimer un groupe agit à la requête
        // suivante, sans reconnexion. Il n'existe volontairement AUCUN cache plus long qu'une
        // requête : un cache serait une seconde source de vérité à invalider, donc un bug en
        // attente.
        session.refreshAuthz(user.get().role().name(), groups.effectiveFor(user.get()));
        return maybe;
    }

    /** Le compte {@link PanelUser} de la session, ou {@code null} (défensif). */
    private PanelUser currentPanelUser(Session session) {
        return users.byId(session.userId()).orElse(null);
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
