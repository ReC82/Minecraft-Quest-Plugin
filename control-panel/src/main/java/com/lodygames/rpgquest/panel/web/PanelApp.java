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
import com.lodygames.rpgquest.panel.building.BuildingPlanDraft;
import com.lodygames.rpgquest.panel.building.BuildingPlanDraftStore;
import com.lodygames.rpgquest.panel.building.BuildingPlanWorkbench;
import com.lodygames.rpgquest.panel.bridge.BridgeException;
import com.lodygames.rpgquest.panel.bridge.BridgeHealth;
import com.lodygames.rpgquest.panel.config.PanelConfig;
import com.lodygames.rpgquest.panel.config.Target;
import com.lodygames.rpgquest.panel.content.ContentPackImport;
import com.lodygames.rpgquest.panel.content.ContentPackSchema;
import com.lodygames.rpgquest.panel.content.ContentPackTemplates;
import com.lodygames.rpgquest.panel.content.StoryKit;
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
import com.lodygames.rpgquest.panel.npc.NpcCreationPlanner;
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
    private final com.lodygames.rpgquest.panel.ai.AiProviderRegistry aiProviders =
            new com.lodygames.rpgquest.panel.ai.AiProviderRegistry();
    private final com.lodygames.rpgquest.panel.ai.AiSettingsStore aiSettings;
    /** Issue #42 — base MariaDB cible d'une future migration (mot de passe chiffré au repos). */
    private final com.lodygames.rpgquest.panel.dbtarget.DbTargetStore dbTarget;
    private com.lodygames.rpgquest.panel.dbtarget.DbConnectionTester dbTester =
            new com.lodygames.rpgquest.panel.dbtarget.MariaDbConnectionTester();
    /** Issue #43 — base MariaDB propre au panel : enregistrée et testée, jamais utilisée. */
    private final com.lodygames.rpgquest.panel.dbtarget.DbTargetStore panelDbTarget;
    private com.lodygames.rpgquest.panel.dbtarget.DbConnectionTester panelDbTester =
            com.lodygames.rpgquest.panel.dbtarget.MariaDbConnectionTester.forPanel();
    private final com.lodygames.rpgquest.panel.ai.AiContentStudio aiStudio;
    /** Issue #94 — atelier de plans de bâtiment, distinct de l'atelier de contenu. */
    private final com.lodygames.rpgquest.panel.ai.AiBuildingStudio aiBuildingStudio;
    /** Issue #256 — les plans proposés par l'IA, conservés comme brouillons de travail. */
    private final BuildingPlanDraftStore planDrafts;
    private final BuildingPlanWorkbench planWorkbench;
    /** #282 : vérification automatique des nouveaux plans et réparation par le moteur. */
    private final com.lodygames.rpgquest.panel.building.WorkshopAutopilot workshopAutopilot;
    /** #282 : sessions de conception automatique (IA + moteur), sous enveloppe consentie. */
    private final com.lodygames.rpgquest.panel.building.BuildingDesignRunStore designRuns;
    /** #283 : images de référence des projets (pixels transmis au modèle, jamais une URL). */
    private final com.lodygames.rpgquest.panel.building.ReferenceImageStore referenceImages;
    /** #289 : la conception détaillée de chaque projet, versionnée. */
    private final com.lodygames.rpgquest.panel.building.BuildingDesignDocStore designDocs;
    private final com.lodygames.rpgquest.panel.ai.BuildingDesignPipeline designPipeline;
    /** Issue #267 — les projets de l'Atelier, indépendants des emplacements. */
    private final com.lodygames.rpgquest.panel.building.BuildingProjectStore planProjects;
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
        // Issue #146 : les réglages d'IA vivent dans la base du panel — hors dépôt Git, en mode 600,
        // lisible par le seul compte du service. C'est le stockage serveur exigé pour une clé API.
        this.aiSettings = new com.lodygames.rpgquest.panel.ai.AiSettingsStore(config.panelDbPath());
        // Issue #42 : clé de chiffrement dérivée de RPGQUEST_PANEL_SECRET (environnement), jamais
        // stockée à côté du chiffré.
        this.dbTarget = new com.lodygames.rpgquest.panel.dbtarget.DbTargetStore(config.panelDbPath(),
                new com.lodygames.rpgquest.panel.dbtarget.SecretBox(config.sessionSecret(),
                        com.lodygames.rpgquest.panel.dbtarget.DbTargetKind.MIGRATION.secretPurpose()));
        // Issue #43 : même secret maître, usage distinct — un chiffré d'une cible ne s'ouvre pas
        // pour l'autre.
        this.panelDbTarget = new com.lodygames.rpgquest.panel.dbtarget.DbTargetStore(config.panelDbPath(),
                new com.lodygames.rpgquest.panel.dbtarget.SecretBox(config.sessionSecret(),
                        com.lodygames.rpgquest.panel.dbtarget.DbTargetKind.PANEL.secretPurpose()),
                com.lodygames.rpgquest.panel.dbtarget.DbTargetKind.PANEL);
        this.aiStudio = new com.lodygames.rpgquest.panel.ai.AiContentStudio(aiProviders, aiSettings);
        // Issue #94 — même registre de fournisseurs et mêmes réglages que l'atelier de contenu :
        // une seule clé API à gérer, un seul endroit où elle vit.
        this.aiBuildingStudio =
                new com.lodygames.rpgquest.panel.ai.AiBuildingStudio(aiProviders, aiSettings);
        // Issue #256 — même base que le reste du panel : un plan payé survit à un redémarrage.
        this.planDrafts = new BuildingPlanDraftStore(config.panelDbPath());
        this.planWorkbench = new BuildingPlanWorkbench(agentStore, planDrafts);
        this.workshopAutopilot = new com.lodygames.rpgquest.panel.building.WorkshopAutopilot(agentStore,
                planDrafts, audit);
        this.planProjects = new com.lodygames.rpgquest.panel.building.BuildingProjectStore(config.panelDbPath());
        this.designRuns = new com.lodygames.rpgquest.panel.building.BuildingDesignRunStore(config.panelDbPath());
        this.referenceImages = new com.lodygames.rpgquest.panel.building.ReferenceImageStore(config.panelDbPath());
        this.designDocs = new com.lodygames.rpgquest.panel.building.BuildingDesignDocStore(config.panelDbPath());
        // Une session « en cours » au démarrage a perdu son fil : interrompue, jamais relancée seule.
        this.designRuns.interruptOrphans();
        this.designPipeline = new com.lodygames.rpgquest.panel.ai.BuildingDesignPipeline(aiBuildingStudio,
                designRuns, planProjects, planDrafts, planWorkbench, agentStore, workshopAutopilot, audit,
                referenceImages, Duration.ofSeconds(Long.getLong("rpgquest.design.verdictTimeoutSeconds", 180)))
                .withDesignDocs(designDocs);
        this.agentPages = new AgentPages(agentStore, agentRegistry, config.agents().defaultAgentId(),
                permissions, new com.lodygames.rpgquest.panel.content.SourceCatalog(contentWorkspace));
        this.agentPages.useWorkshopProjects(planProjects);
        this.notifications = new NotificationCenter(agentStore, agentRegistry,
                new NotificationReadStore(config.panelDbPath()));
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
        this.agentEndpoints.onResult(workshopAutopilot::onResult);
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
        route("/notifications/read-all", this::handleNotificationsReadAll);
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
        // Issue #248 — même parcours pour un dialogue, avec le relevé du serveur dans l'aperçu.
        route("/dialogues/delete", exchange -> handleContentDelete(exchange, "dialogues"));
        route("/npcs", exchange -> handleBusinessPage(exchange, "/npcs", "PNJ",
                Permission.NPC_READ, agentPages::npcs));
        // Issue #226 : GET = aperçu des dépendances couche par couche, POST = une opération précise
        // après confirmation tapée. Même discipline que #194, sur un objet qui vit sur cinq couches.
        route("/npcs/delete", this::handleNpcDelete);
        // Issue #325 — le parcours simple : une actualisation commune, une création en un geste,
        // un rattachement en un geste, et une page à part pour le diagnostic.
        route("/npcs/refresh", this::handleNpcRefresh);
        route("/npcs/create", this::handleNpcCreate);
        route("/npcs/attach", this::handleNpcAttach);
        route("/npcs/diagnostic", exchange -> handleBusinessPage(exchange, "/npcs", "Diagnostic des PNJ",
                Permission.NPC_READ, agentPages::npcDiagnostic));
        // Issue #13 — PNJ récurrents. Lecture gardée par NPC_READ ; les actions passent par
        // /agents/action, donc par le catalogue et NPC_SPAWN_WRITE / NPC_WRITE.
        route("/npcs/encounters", exchange -> handleBusinessPage(exchange, "/npcs", "PNJ récurrents",
                Permission.NPC_READ, agentPages::npcEncounters));
        // Issue #213 — emplacements de construction. Lecture gardée par BUILDING_READ ; les
        // mutations passent par /agents/action, donc par le catalogue et ses propres permissions.
        route("/buildings/sites", exchange -> handleBusinessPage(exchange, "/buildings/sites",
                "Emplacements de construction", Permission.BUILDING_READ, agentPages::buildingSites));
        // Lot « placement » de #213 — la bibliothèque. Lecture seule : aucune route d'écriture,
        // parce qu'une définition de bâtiment est du contenu versionné qui se modifie dans son
        // fichier. Poser et restaurer passent par /agents/action et leurs permissions dédiées.
        // Issue #47 — « Changements en attente » : la vue d'ensemble de ce qui n'est pas synchronisé,
        // et la publication groupée. Lecture gardée par CONTENT_READ ; la publication a sa propre
        // route POST et exige CONTENT_PUBLISH.
        route("/content/pending", exchange -> handleBusinessPage(exchange, "/content/pending",
                "Changements en attente", Permission.CONTENT_READ, agentPages::contentPending));
        route("/content/pending/publish", this::handlePendingPublish);
        route("/buildings/library", exchange -> handleBusinessPage(exchange, "/buildings/library",
                "Bibliothèque de bâtiments", Permission.BUILDING_READ,
                agentPages::buildingLibrary));
        // Issue #178 — villes. Lecture gardée par TRAVEL_READ ; les mutations passent par
        // /agents/action, donc par le catalogue et CITY_WRITE / CITY_DELETE.
        route("/cities", exchange -> handleBusinessPage(exchange, "/cities", "Villes",
                Permission.TRAVEL_READ, agentPages::cities));
        route("/travel", exchange -> handleBusinessPage(exchange, "/travel", "Réseau de voyage",
                Permission.TRAVEL_READ, agentPages::travel));
        route("/mobs", exchange -> handleBusinessPage(exchange, "/mobs", "Mobs spéciaux & boss",
                Permission.MOB_READ, agentPages::mobs));
        // Issue #332 — dialogues, version simple : une liste, un éditeur, « Enregistrer » puis
        // « Mettre en jeu » par la publication à chaud (#47). L'ancien écran reste en « Détails
        // techniques ».
        // Issue #335 — paramètres généraux du jeu.
        route("/settings", this::handleSettings);
        route("/database", this::handleDatabaseTarget);
        route("/settings/refresh", this::handleSettingsRefresh);
        route("/settings/save", this::handleSettingsSave);
        route("/dialogues", this::handleDialogueList);
        route("/dialogues/diagnostic", exchange -> handleBusinessPage(exchange, "/dialogues",
                "Dialogues — détails techniques", Permission.DIALOGUE_READ, agentPages::dialogues));
        route("/dialogues/new", this::handleDialogueNew);
        route("/dialogues/create", this::handleDialogueCreate);
        route("/dialogues/edit", this::handleDialogueEdit);
        route("/dialogues/save", this::handleDialogueSave);
        route("/dialogues/publish", this::handleDialoguePublish);
        route("/dialogues/refresh", this::handleDialogueRefresh);
        route("/dialogues/link", this::handleDialogueLink);
        route("/content/export", this::handleContentExport);
        route("/content/export/download", this::handleContentExportDownload);
        // Issue #110 — contrat de contenu machine-readable. Trois téléchargements en lecture pure,
        // tous GÉNÉRÉS depuis les descripteurs du moteur : aucun fichier maintenu à la main, donc
        // rien qui puisse décrire un type inexistant ou oublier un type existant.
        route("/content/import", this::handleContentImport);
        // Issue #146 — atelier IA. Deux routes distinctes : l'atelier (usage) et la configuration
        // des fournisseurs (clés API), avec deux permissions différentes.
        route("/ai/studio", this::handleAiStudio);
        route("/ai/providers", this::handleAiProviders);
        // Issue #94 — génération d'un bâtiment par IA. Route DISTINCTE de l'atelier de contenu :
        // la famille « bâtiment » ne produit pas du YAML de contenu importable, mais un plan
        // géométrique que seul le plugin sait valider et exécuter.
        route("/buildings/plan", this::handleBuildingPlan);
        // Issues #265 / #267 — l'Atelier de création, indépendant des emplacements.
        route(WorkshopPages.BASE, this::handleWorkshop);
        route(WorkshopPages.BASE + "/new", this::handleWorkshopNew);
        route(WorkshopPages.BASE + "/import", this::handleWorkshopImport);
        route(WorkshopPages.BASE + "/project", this::handleWorkshopProject);
        route(WorkshopPages.BASE + "/image", this::handleWorkshopImage);
        route("/content/schema.json", this::handleContentSchema);
        route("/content/template", this::handleContentTemplate);
        route("/content/contract.md", this::handleContentContractDoc);
        // Issue #211 — modèle d'histoire pour une IA externe (archive ou fichier de la liste blanche).
        route("/content/story-kit", this::handleStoryKit);
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
        designPipeline.shutdown();
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

    /** Issue #293 — « Tout marquer comme lu » : POST + CSRF, pour le seul compte connecté. */
    private void handleNotificationsReadAll(HttpExchange exchange) throws IOException {
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
        if (!permissions.can(session.get().effective(), Permission.DIAGNOSTICS_READ)) {
            forbidden(exchange, session.get(), "/");
            return;
        }
        notifications.markAllRead(session.get().userId(), Instant.now());
        Http.redirect(exchange, "/actions");
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

    // ---- Atelier IA (issue #146) ---------------------------------------------------------

    /**
     * Atelier « Créer avec une IA ». GET rend le formulaire ; POST génère ou demande une correction.
     *
     * <p>Cette route <strong>n'écrit jamais de contenu</strong> : elle s'arrête à l'aperçu validé.
     * Le bouton d'enregistrement de la page poste vers {@code /content/import}, qui applique le
     * pipeline de #109 avec sa confirmation explicite. C'est ce qui garantit, par construction et
     * non par vigilance, qu'aucune proposition d'IA ne peut être publiée automatiquement.</p>
     *
     * <p><strong>Un seul chemin pour générer et pour corriger (issue #222).</strong> Les deux
     * actions lisent le formulaire de la même façon et reconstruisent la même demande : une
     * correction ne peut donc pas perdre les consignes d'origine, et l'écran se rouvre rempli. Seul
     * le prompt diffère — celui de la correction y ajoute la sortie refusée et les diagnostics.</p>
     *
     * <p><strong>Trois refus arrivent avant le moindre appel payant.</strong> Une intention vide
     * (#146), un identifiant inutilisable (#223) et un PNJ déjà pourvu d'un dialogue sous un autre
     * nom (#225) sont tranchés ici, à coût nul.</p>
     */
    private void handleAiStudio(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.AI_USE)) {
            forbidden(exchange, session, "/ai/studio");
            return;
        }

        String agentId = config.agents().defaultAgentId();
        RefData ref = agentPages.referenceData(agentId);
        boolean canImport = permissions.can(session.effective(), Permission.CONTENT_IMPORT);
        String error = null;
        com.lodygames.rpgquest.panel.ai.AiContentStudio.Generation generation = null;
        com.lodygames.rpgquest.panel.ai.ContentPromptBuilder.QuestRequest questForm = null;
        com.lodygames.rpgquest.panel.ai.ContentPromptBuilder.DialogueRequest dialogueForm = null;
        com.lodygames.rpgquest.panel.ai.ContentPromptBuilder.StoryRequest storyForm = null;
        com.lodygames.rpgquest.panel.npc.DialogueTargetDecision.Outcome decision = null;
        com.lodygames.rpgquest.panel.ai.AiContentStudio.Correction fallback = null;
        String rawNpc = "";
        // En GET, la famille vient du lien choisi : c'est ce qui permet de changer de formulaire
        // sans une ligne de JavaScript, la politique de sécurité du panel interdisant l'inline.
        com.lodygames.rpgquest.panel.ai.ContentPromptBuilder.Kind kind =
                com.lodygames.rpgquest.panel.ai.ContentPromptBuilder.Kind
                        .of(Http.query(exchange).getOrDefault("kind", ""));
        String provider = "";

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Optional<Map<String, String>> body = Http.formBody(exchange, 1024 * 1024);
            if (body.isEmpty()) {
                error = "Requête trop volumineuse.";
            } else {
                Map<String, String> f = body.get();
                if (!constantTimeEquals(session.csrfToken(), f.get("_csrf"))) {
                    Http.html(exchange, 403, Layout.bare("CSRF",
                            "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                    return;
                }
                provider = f.getOrDefault("provider", "").trim();
                kind = com.lodygames.rpgquest.panel.ai.ContentPromptBuilder.Kind
                        .of(f.getOrDefault("kind", ""));
                boolean correcting = "correct".equals(f.get("_action"));
                String rid = UUID.randomUUID().toString().substring(0, 8);

                com.lodygames.rpgquest.panel.ai.ContentPromptBuilder.Demand request = null;
                switch (kind) {
                    case QUEST -> {
                        // #223 — l'identifiant est normalisé AVANT l'appel : les deux écritures
                        // sont acceptées, un namespace étranger est refusé sans dépenser de jeton.
                        var id = com.lodygames.rpgquest.panel.content.ContentId
                                .quest(f.getOrDefault("questId", ""));
                        if (!id.ok()) {
                            error = id.error();
                        }
                        questForm = new com.lodygames.rpgquest.panel.ai.ContentPromptBuilder
                                .QuestRequest(
                                f.getOrDefault("intent", ""), f.getOrDefault("title", ""),
                                id.canonical(), f.getOrDefault("category", ""),
                                f.getOrDefault("giver", ""), f.getOrDefault("difficulty", ""),
                                f.getOrDefault("duration", ""), parseIntOr(f.get("stepCount"), 0),
                                "on".equals(f.get("repeatable")),
                                f.getOrDefault("rewardIntent", ""),
                                f.getOrDefault("constraints", ""));
                        request = questForm;
                    }
                    case DIALOGUE -> {
                        rawNpc = f.getOrDefault("npc", "").trim();
                        var id = com.lodygames.rpgquest.panel.content.ContentId.dialogue(rawNpc);
                        if (!id.ok()) {
                            error = id.error();
                        }
                        // #225 — quel dialogue viser ? La question est posée avant l'appel quand le
                        // PNJ porte déjà un dialogue qui ne s'appelle pas comme lui.
                        decision = com.lodygames.rpgquest.panel.npc.DialogueTargetDecision.resolve(
                                agentPages.npcDirectory(agentId), id.key(),
                                f.get("dialogueTarget"));
                        if (decision.needsDecision() && error == null) {
                            error = "Ce PNJ porte déjà un dialogue sous un autre nom : choisissez "
                                    + "ci-dessous ce que l'IA doit faire. Aucun appel n'a été fait.";
                        }
                        dialogueForm = new com.lodygames.rpgquest.panel.ai.ContentPromptBuilder
                                .DialogueRequest(
                                f.getOrDefault("intent", ""), decision.dialogueKey(),
                                f.getOrDefault("speaker", ""), f.getOrDefault("tone", ""),
                                parseIntOr(f.get("nodeCount"), 0),
                                f.getOrDefault("quest", ""),
                                f.getOrDefault("constraints", ""));
                        request = decision.needsDecision() ? null : dialogueForm;
                    }
                    case STORY -> {
                        var id = com.lodygames.rpgquest.panel.content.ContentId
                                .story(f.getOrDefault("storyId", ""));
                        if (!id.ok()) {
                            error = id.error();
                        }
                        storyForm = new com.lodygames.rpgquest.panel.ai.ContentPromptBuilder
                                .StoryRequest(
                                f.getOrDefault("intent", ""), id.canonical(),
                                f.getOrDefault("title", ""), f.getOrDefault("quests", ""),
                                f.getOrDefault("constraints", ""));
                        request = storyForm;
                    }
                }

                if (error != null) {
                    request = null;
                } else if (request != null && !request.valid()) {
                    error = switch (kind) {
                        case QUEST -> "Décrivez d'abord ce que la quête doit raconter.";
                        case DIALOGUE -> "Décrivez d'abord ce que la conversation doit raconter.";
                        case STORY -> "Décrivez d'abord le fil de l'enchaînement.";
                    };
                    request = null;
                }

                if (request != null && correcting) {
                    // #222 — la sortie refusée et les diagnostics voyagent dans le formulaire. Les
                    // diagnostics tiennent dans UN champ, une ligne par problème : le lecteur de
                    // formulaire ne garde qu'une valeur par nom, et une série de champs homonymes
                    // n'en transportait donc qu'un seul.
                    List<String> problems = new ArrayList<>();
                    for (String line : f.getOrDefault("problems", "").split("\\R")) {
                        if (!line.isBlank()) {
                            problems.add(line.trim());
                        }
                    }
                    var correction = new com.lodygames.rpgquest.panel.ai.AiContentStudio.Correction(
                            request, provider, f.getOrDefault("previousYaml", ""), problems);
                    if (!correction.usable()) {
                        error = "Impossible de relancer la correction : la proposition précédente "
                                + "ou ses diagnostics n'ont pas été transmis. Relancez une "
                                + "génération — rien n'a été enregistré.";
                    } else {
                        // Conservé pour que l'écran garde le bouton si l'appel lui-même échoue.
                        fallback = correction;
                        generation = aiStudio.correct(correction, ref, contentWorkspace);
                    }
                } else if (request != null) {
                    generation = aiStudio.generate(provider, request, ref, contentWorkspace);
                }

                if (generation != null) {
                    // L'audit retient le fournisseur, le modèle, les jetons et le verdict — jamais
                    // la clé, jamais le prompt complet (contenu éditorial, et volumineux).
                    audit.record(session.username(), correcting ? "ai.correct" : "ai.generate",
                            "kind=" + generation.kind()
                                    + " provider=" + generation.providerId()
                                    + " model=" + generation.model()
                                    + " usage=" + generation.usage()
                                    + " unmet=" + generation.unmet().size(),
                            generation.callOk() ? "OK" : "FAILED",
                            generation.callOk()
                                    ? (generation.acceptable() ? "proposition valide"
                                        : generation.unmet().isEmpty()
                                            ? "proposition refusée par la validation"
                                            : "proposition refusée : demande non respectée ("
                                                    + String.join(" ; ", generation.unmet()) + ")")
                                    : generation.error(),
                            rid);
                }
            }
        }

        String body = AiStudioPages.render(aiStudio.usableProviders(), kind, questForm, dialogueForm,
                storyForm, provider, generation, ref, canImport, error, decision, fallback, rawNpc);
        Http.html(exchange, 200, renderPage("Créer avec une IA", session, "/ai/studio", body,
                Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    // ================================================================================
    //  Atelier de création (issues #265, #267) — remplace /buildings/plan (#94, #256)
    // ================================================================================

    /**
     * {@code /buildings/plan} : l'ancienne adresse du générateur, rattaché à un emplacement.
     *
     * <p>Elle n'exécute plus rien. Un lien vers un brouillon mène à son projet ; un lien vers un
     * emplacement mène à son projet s'il a été importé, sinon à l'Atelier, où l'import est proposé.
     * Un ancien formulaire encore ouvert dans un onglet ne déclenche donc aucun geste — surtout pas un
     * appel payant.</p>
     */
    private void handleBuildingPlan(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        String agentId = config.agents().defaultAgentId();
        Map<String, String> q = Http.query(exchange);
        Optional<BuildingPlanDraft> draft = planDrafts.find(q.getOrDefault("draft", ""));
        if (draft.isPresent() && !draft.get().projectId().isEmpty()) {
            Http.redirect(exchange, projectLocation(draft.get().projectId(), draft.get().id(), null, null));
            return;
        }
        String siteId = draft.map(BuildingPlanDraft::siteId).orElse(q.getOrDefault("site", "")).trim();
        Optional<com.lodygames.rpgquest.panel.building.BuildingProject> imported =
                siteId.isEmpty() ? Optional.empty() : planProjects.importedFrom(agentId, siteId);
        if (imported.isPresent()) {
            Http.redirect(exchange, projectLocation(imported.get().id(),
                    draft.map(BuildingPlanDraft::id).orElse(""), null, null));
            return;
        }
        Http.redirect(exchange, WorkshopPages.BASE + (siteId.isEmpty() ? ""
                : "?ok=" + enc("La création de bâtiments a déménagé dans l'Atelier. Le travail commencé "
                        + "sur « " + siteId + " » peut y être repris ci-dessous.")));
    }

    /** {@code GET /buildings/workshop} : les projets, et ce qui reste à reprendre des emplacements. */
    private void handleWorkshop(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.AI_USE)) {
            forbidden(exchange, session, WorkshopPages.BASE);
            return;
        }
        String agentId = config.agents().defaultAgentId();
        Map<String, String> q = Http.query(exchange);
        List<WorkshopPages.ProjectRow> rows = new ArrayList<>();
        for (var p : planProjects.list(agentId)) {
            List<BuildingPlanDraft> drafts = planDrafts.forProject(agentId, p.id());
            rows.add(new WorkshopPages.ProjectRow(p, projectState(p, drafts), drafts.size()));
        }
        Http.html(exchange, 200, renderPage("Atelier de création", session, WorkshopPages.BASE,
                WorkshopPages.index(session, rows, importable(agentId), q.get("ok"), q.get("err")),
                Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    /** Une phrase d'état pour la liste des projets, dans les mots du parcours. */
    private String projectState(com.lodygames.rpgquest.panel.building.BuildingProject p,
                                List<BuildingPlanDraft> drafts) {
        if (!p.hasBrief()) {
            return "À décrire";
        }
        if (drafts.isEmpty()) {
            return "Prêt à demander un plan";
        }
        BuildingPlanDraft latest = drafts.get(0);
        var generate = planWorkbench.state(latest, BuildingPlanDraftStore.Check.GENERATE);
        if (generate.current() && generate.row().map(r -> "SUCCESS".equals(r.resultStatus())).orElse(false)) {
            // #290 : le dernier fait connu de la bibliothèque, pas le seul succès de l'enregistrement.
            var catalog = com.lodygames.rpgquest.panel.building.CatalogState.of(agentStore,
                    p.agentId(), p.buildingId());
            return catalog.gone() ? catalog.label() : "Dans la bibliothèque";
        }
        if (com.lodygames.rpgquest.panel.building.SaveFailure.of(generate).isPresent()) {
            return "Enregistrement échoué";
        }
        var audit = planWorkbench.state(latest, BuildingPlanDraftStore.Check.AUDIT);
        if (!audit.current()) {
            return "Plan à vérifier";
        }
        if (audit.status() == BuildingPlanWorkbench.Status.PENDING) {
            return "Vérification en cours";
        }
        var verdict = com.lodygames.rpgquest.panel.building.BuildingPlanVerdict.from(audit.details());
        return verdict.available() && verdict.accepted() ? "Prêt à enregistrer" : "À corriger";
    }

    /**
     * Ce qui reste à reprendre des emplacements (migration #267) : un emplacement dont la fiche porte
     * encore une description de bâtiment, ou dont des plans ne sont rattachés à aucun projet — et qui
     * n'a pas déjà été importé.
     */
    private List<WorkshopPages.Importable> importable(String agentId) {
        var directory = agentPages.buildingSiteDirectory(agentId);
        java.util.LinkedHashMap<String, WorkshopPages.Importable> out = new java.util.LinkedHashMap<>();
        java.util.Set<String> withDrafts = new java.util.LinkedHashSet<>(planDrafts.sitesWithUnattachedDrafts(agentId));
        for (var site : directory.sites()) {
            if ((site.hasAiBrief() || withDrafts.contains(site.id()))
                    && planProjects.importedFrom(agentId, site.id()).isEmpty()) {
                out.put(site.id(), new WorkshopPages.Importable(site.id(), site.name(), site.aiBrief(),
                        (int) planDrafts.forSite(agentId, site.id()).stream()
                                .filter(d -> d.projectId().isEmpty()).count()));
            }
        }
        // Des plans d'un emplacement absent du relevé (supprimé depuis) ne doivent pas être perdus.
        for (String siteId : withDrafts) {
            if (!out.containsKey(siteId) && planProjects.importedFrom(agentId, siteId).isEmpty()) {
                out.put(siteId, new WorkshopPages.Importable(siteId, siteId, "",
                        (int) planDrafts.forSite(agentId, siteId).stream()
                                .filter(d -> d.projectId().isEmpty()).count()));
            }
        }
        return List.copyOf(out.values());
    }

    /** {@code /buildings/workshop/new} : le formulaire (GET), la création (POST). */
    private void handleWorkshopNew(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.AI_USE)) {
            forbidden(exchange, session, WorkshopPages.BASE);
            return;
        }
        String agentId = config.agents().defaultAgentId();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.html(exchange, 200, renderPage("Nouveau projet", session, WorkshopPages.BASE,
                    WorkshopPages.newForm(session, Map.of(), null),
                    Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
            return;
        }
        Map<String, String> f = Http.formBody(exchange);
        if (!constantTimeEquals(session.csrfToken(), f.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        var fields = projectFields(f);
        Optional<String> refusal = fields.refusal();
        if (refusal.isPresent()) {
            Http.html(exchange, 200, renderPage("Nouveau projet", session, WorkshopPages.BASE,
                    WorkshopPages.newForm(session, f, refusal.get()),
                    Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
            return;
        }
        var created = planProjects.create(agentId, fields, "", session.username());
        audit.record(session.username(), "building.project.create", "project=" + created.id(), "OK",
                "building_id=" + created.buildingId(), UUID.randomUUID().toString().substring(0, 8));
        Http.redirect(exchange, projectLocation(created.id(), "", "Projet créé. Prochaine étape : demander un "
                + "plan à l'IA.", null));
    }

    /** Les champs d'un projet tels que le formulaire les envoie ; nom technique déduit si absent. */
    private static com.lodygames.rpgquest.panel.building.BuildingProjectStore.Fields projectFields(
            Map<String, String> f) {
        String name = f.getOrDefault("name", "").trim();
        String id = f.getOrDefault("building_id", "").trim();
        return new com.lodygames.rpgquest.panel.building.BuildingProjectStore.Fields(name,
                f.getOrDefault("brief", "").replace("\r\n", "\n"),
                id.isEmpty() ? com.lodygames.rpgquest.panel.building.BuildingProject.suggestBuildingId(name) : id,
                f.get("front"), parseIntOr(f.get("max_width"), 19), parseIntOr(f.get("max_height"), 14));
    }

    /**
     * {@code POST /buildings/workshop/import} : reprendre dans l'Atelier ce qui avait été commencé sur
     * un emplacement. <strong>Non destructif</strong> : la description est COPIÉE dans un projet neuf,
     * les plans y sont rattachés (leur emplacement d'origine reste noté), et la fiche de l'emplacement
     * n'est pas touchée — aucune action n'est envoyée au serveur. Idempotent.
     */
    private void handleWorkshopImport(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.AI_USE)) {
            forbidden(exchange, session, WorkshopPages.BASE);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.redirect(exchange, WorkshopPages.BASE);
            return;
        }
        Map<String, String> f = Http.formBody(exchange);
        if (!constantTimeEquals(session.csrfToken(), f.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        String agentId = config.agents().defaultAgentId();
        String siteId = f.getOrDefault("site", "").trim();
        var already = planProjects.importedFrom(agentId, siteId);
        if (already.isPresent()) {
            Http.redirect(exchange, projectLocation(already.get().id(), "", "Déjà repris dans l'Atelier : "
                    + "voici son projet.", null));
            return;
        }
        var site = agentPages.buildingSiteDirectory(agentId).find(siteId);
        List<BuildingPlanDraft> siteDrafts = planDrafts.forSite(agentId, siteId);
        if (site.isEmpty() && siteDrafts.isEmpty()) {
            Http.redirect(exchange, WorkshopPages.BASE + "?err=" + enc("Emplacement « " + siteId
                    + " » introuvable, et aucun plan à reprendre : rien n'a été fait."));
            return;
        }
        String name = site.map(com.lodygames.rpgquest.panel.building.BuildingSiteView::name).orElse(siteId);
        String brief = site.map(com.lodygames.rpgquest.panel.building.BuildingSiteView::aiBrief).orElse("");
        int width = siteDrafts.stream().mapToInt(BuildingPlanDraft::maxWidth).filter(w -> w > 0).findFirst().orElse(19);
        int height = siteDrafts.stream().mapToInt(BuildingPlanDraft::maxHeight).filter(h -> h > 0).findFirst().orElse(14);
        var fields = new com.lodygames.rpgquest.panel.building.BuildingProjectStore.Fields(name, brief,
                com.lodygames.rpgquest.panel.building.BuildingProject.suggestBuildingId(name),
                site.map(com.lodygames.rpgquest.panel.building.BuildingSiteView::facing).orElse("SOUTH"),
                width, height);
        if (fields.name().length() > com.lodygames.rpgquest.panel.building.BuildingProject.MAX_NAME) {
            fields = new com.lodygames.rpgquest.panel.building.BuildingProjectStore.Fields(
                    fields.name().substring(0, com.lodygames.rpgquest.panel.building.BuildingProject.MAX_NAME),
                    fields.brief(), fields.buildingId(), fields.front(), fields.maxWidth(), fields.maxHeight());
        }
        var project = planProjects.create(agentId, fields, siteId, session.username());
        int attached = planDrafts.attachSiteDrafts(agentId, siteId, project.id());
        audit.record(session.username(), "building.project.import", "site=" + siteId + " project="
                        + project.id(), "OK", "brouillons=" + attached + " description=" + brief.length() + " car.",
                UUID.randomUUID().toString().substring(0, 8));
        String latest = planDrafts.forProject(agentId, project.id()).stream().findFirst()
                .map(BuildingPlanDraft::id).orElse("");
        Http.redirect(exchange, projectLocation(project.id(), latest, "Repris dans l'Atelier : description copiée"
                + (attached > 0 ? ", " + attached + " plan(s) rattaché(s)" : "") + ". La fiche de l'emplacement "
                + "n'a pas été modifiée.", null));
    }

    /**
     * {@code /buildings/workshop/project} : la page d'un projet (GET) et ses gestes (POST).
     *
     * <p>Mêmes garanties que #256 : chaque soumission se termine par une redirection, les deux
     * gestes payants portent un jeton à usage unique, une version périmée n'écrit rien, et chaque
     * vérification est rattachée à la version du plan qu'elle a vue.</p>
     */
    private void handleWorkshopProject(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.AI_USE)) {
            forbidden(exchange, session, WorkshopPages.BASE);
            return;
        }
        String agentId = config.agents().defaultAgentId();
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Optional<Map<String, String>> body = Http.formBody(exchange, 1024 * 1024);
            if (body.isEmpty()) {
                Http.redirect(exchange, WorkshopPages.BASE + "?err=" + enc("Requête trop volumineuse."));
                return;
            }
            Map<String, String> f = body.get();
            if (!constantTimeEquals(session.csrfToken(), f.get("_csrf"))) {
                Http.html(exchange, 403, Layout.bare("CSRF",
                        "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                return;
            }
            var project = planProjects.find(f.get("id")).filter(p -> !p.archived() && p.agentId().equals(agentId));
            if (project.isEmpty()) {
                Http.redirect(exchange, WorkshopPages.BASE + "?err=" + enc("Projet introuvable : rien n'a été fait."));
                return;
            }
            PlanOutcome outcome = switch (f.getOrDefault("_action", "").trim()) {
                case "project_save" -> projectSave(session, project.get(), f);
                case "propose" -> planPropose(session, agentId, project.get(), f);
                case "correct" -> planCorrect(session, agentId, project.get(), f);
                case "design" -> planDesign(session, agentId, project.get(), f);
                case "restart" -> planRestart(session, agentId, project.get(), f);
                case "image_remove" -> imageRemove(session, project.get(), f);
                case "save", "audit", "preview", "generate" -> planCheck(session, agentId, project.get(), f);
                case "recover" -> planRecover(session, agentId, project.get(), f);
                case "resume" -> planResume(session, agentId, project.get(), f);
                case "design_write" -> designWrite(session, agentId, project.get(), f);
                case "design_save" -> designSave(session, project.get(), f);
                case "archive" -> planArchive(session, project.get(), f);
                default -> PlanOutcome.redirect(projectLocation(project.get().id(), f.get("draft"), null,
                        "Geste inconnu : rien n'a été fait."));
            };
            if (outcome.conflictText() == null && outcome.designText() == null) {
                Http.redirect(exchange, outcome.location());
                return;
            }
            // Conflit de version : rien n'est écrit, et le texte saisi est réaffiché plutôt que perdu.
            renderWorkshopProject(exchange, session, agentId, project.get().id(), f.getOrDefault("draft", ""),
                    null, outcome.error(), outcome.conflictText(), -1, -1, outcome.designText());
            return;
        }
        Map<String, String> q = Http.query(exchange);
        // #273 : orientation et coupe de la vue en 3D — de simples paramètres d'URL, bornés au rendu.
        renderWorkshopProject(exchange, session, agentId, q.getOrDefault("id", "").trim(),
                q.getOrDefault("draft", "").trim(), q.get("ok"), q.get("err"), null,
                parseIntOr(q.get("view"), -1), parseIntOr(q.get("cut"), -1));
    }

    /** Issue d'une soumission : une redirection, ou un conflit à montrer sur place. */
    private record PlanOutcome(String location, String error, String conflictText, String designText) {
        PlanOutcome(String location, String error, String conflictText) {
            this(location, error, conflictText, null);
        }

        static PlanOutcome redirect(String location) {
            return new PlanOutcome(location, null, null);
        }
    }

    private void renderWorkshopProject(HttpExchange exchange, Session session, String agentId, String projectId,
                                       String draftId, String notice, String error, String conflictText,
                                       int isoView, int isoCut)
            throws IOException {
        renderWorkshopProject(exchange, session, agentId, projectId, draftId, notice, error, conflictText,
                isoView, isoCut, null);
    }

    private void renderWorkshopProject(HttpExchange exchange, Session session, String agentId, String projectId,
                                       String draftId, String notice, String error, String conflictText,
                                       int isoView, int isoCut, String designText)
            throws IOException {
        var project = planProjects.find(projectId).filter(p -> !p.archived() && p.agentId().equals(agentId));
        if (project.isEmpty()) {
            Http.html(exchange, 404, renderPage("Projet introuvable", session, WorkshopPages.BASE,
                    "<h1>Projet introuvable</h1><p class=\"muted\"><a href=\"" + WorkshopPages.BASE
                            + "\">Retour à l'Atelier</a></p>"));
            return;
        }
        List<BuildingPlanDraft> drafts = planDrafts.forProject(agentId, project.get().id());
        // Sans brouillon désigné, la version la plus récente : reprendre un projet, c'est reprendre là
        // où on en était.
        BuildingPlanDraft draft = planDrafts.find(draftId)
                .filter(d -> !d.archived() && project.get().id().equals(d.projectId()))
                .orElse(drafts.isEmpty() ? null : drafts.get(0));
        var none = new BuildingPlanWorkbench.CheckState(BuildingPlanWorkbench.Status.NONE, Optional.empty(), 0, false);
        // #290 : l'état RÉEL du bâtiment dans la bibliothèque — retrait, relevé, enregistrement — et
        // non le seul succès de la version affichée.
        var catalog = com.lodygames.rpgquest.panel.building.CatalogState.of(agentStore, agentId,
                project.get().buildingId());
        boolean idTaken = catalog.inLibrary() || catalog.state()
                == com.lodygames.rpgquest.panel.building.CatalogState.State.UNKNOWN
                && agentPages.buildingLibraryDirectory(agentId).find(project.get().buildingId()).isPresent();
        boolean ownBuilding = producedByProject(drafts, project.get().buildingId());
        var view = new WorkshopPages.ProjectView(session, agentId, project.get(), aiBuildingStudio.usableProviders(),
                permissions.can(session.effective(), Permission.BUILDING_WRITE), drafts, draft,
                draft == null ? none : planWorkbench.state(draft, BuildingPlanDraftStore.Check.AUDIT),
                draft == null ? none : planWorkbench.state(draft, BuildingPlanDraftStore.Check.PREVIEW),
                draft == null ? none : planWorkbench.state(draft, BuildingPlanDraftStore.Check.GENERATE),
                draft == null ? List.of() : planDrafts.revisions(draft.id()),
                planWorkbench.recoverable(agentId, 10), UUID.randomUUID().toString(), notice, error,
                conflictText, idTaken, isoView, isoCut, designRuns.latest(project.get().id()),
                referenceImages.list(project.get().id()), catalog, ownBuilding,
                designDocs.history(project.get().id()), designText);
        Http.html(exchange, 200, renderPage(project.get().name(), session, WorkshopPages.BASE,
                WorkshopPages.project(view), Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    /**
     * Ce projet a-t-il déjà enregistré ce bâtiment (#290) ? Un enregistrement réussi rattaché à l'une
     * de ses versions, pour cet identifiant. C'est ce qui l'autorise à en publier une NOUVELLE
     * version ; un bâtiment d'un autre projet, ou fait à la main, reste protégé.
     */
    private boolean producedByProject(List<BuildingPlanDraft> drafts, String buildingId) {
        for (BuildingPlanDraft d : drafts) {
            for (var link : planDrafts.links(d.id(), BuildingPlanDraftStore.Check.GENERATE)) {
                var row = agentStore.action(link.actionId());
                if (row.isPresent() && "SUCCESS".equals(row.get().resultStatus())
                        && buildingId.equals(com.lodygames.rpgquest.panel.building.CatalogState
                                .generatedIdOf(row.get()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reprendre la conception (#290) : une nouvelle version, copie de la version désignée — en
     * général celle qui est dans la bibliothèque. Aucun appel à l'IA, rien de remplacé ; la
     * vérification part d'elle-même, et toutes les actions de l'Atelier redeviennent disponibles.
     */
    private PlanOutcome planResume(Session session, String agentId,
                                   com.lodygames.rpgquest.panel.building.BuildingProject project,
                                   Map<String, String> f) {
        List<BuildingPlanDraft> drafts = planDrafts.forProject(agentId, project.id());
        Optional<BuildingPlanDraft> source = planDrafts.find(f.get("draft"))
                .filter(d -> !d.archived() && project.id().equals(d.projectId()) && d.hasPlan());
        if (source.isEmpty()) {
            // Depuis la bibliothèque : la version qui a été enregistrée en dernier, sinon la plus récente.
            source = drafts.stream().filter(d -> d.hasPlan() && planDrafts.links(d.id(),
                    BuildingPlanDraftStore.Check.GENERATE).stream().anyMatch(l -> agentStore.action(l.actionId())
                    .map(r -> "SUCCESS".equals(r.resultStatus())).orElse(false))).findFirst()
                    .or(() -> drafts.stream().filter(BuildingPlanDraft::hasPlan).findFirst());
        }
        if (source.isEmpty()) {
            return PlanOutcome.redirect(projectLocation(project.id(), "", null,
                    "Aucune version à reprendre : ce projet n'a pas encore de plan."));
        }
        BuildingPlanDraft from = source.get();
        BuildingPlanDraft created = planDrafts.create(new BuildingPlanDraftStore.NewDraft(agentId, from.siteId(),
                from.plan(), BuildingPlanDraft.Origin.RESUMED, from.id(), "", "", "", "", "", "",
                "Reprise de la version " + from.id().substring(0, 8) + ", sans appel à l'IA.",
                project.maxWidth(), project.maxHeight(), session.username(), project.id()));
        audit.record(session.username(), "building.plan.resume", "project=" + project.id() + " from="
                + from.id() + " draft=" + created.id(), "OK", "", UUID.randomUUID().toString().substring(0, 8));
        workshopAutopilot.verify(created, session.username());
        planProjects.touch(project.id(), session.username());
        return PlanOutcome.redirect(projectLocation(project.id(), created.id(), "Conception reprise : cette "
                + "nouvelle version part de la version enregistrée. Modifiez-la, améliorez-la avec l'IA, puis "
                + "publiez-la. Le bâtiment de la bibliothèque et ses exemplaires posés ne changent pas.", null));
    }

    /** Modifier la fiche du projet, sous contrôle de version. */
    private PlanOutcome projectSave(Session session, com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        var fields = projectFields(f);
        String draft = f.getOrDefault("draft", "");
        Optional<String> refusal = fields.refusal();
        if (refusal.isPresent()) {
            return PlanOutcome.redirect(projectLocation(project.id(), draft, null, refusal.get()
                    + " Rien n'a été enregistré."));
        }
        var outcome = planProjects.update(project.id(), parseIntOr(f.get("project_version"), -1), fields,
                session.username());
        audit.record(session.username(), "building.project.save", "project=" + project.id(), outcome.name(), "",
                UUID.randomUUID().toString().substring(0, 8));
        return PlanOutcome.redirect(switch (outcome) {
            case SAVED -> projectLocation(project.id(), draft, "Modifications enregistrées.", null);
            case STALE -> projectLocation(project.id(), draft, null, "Le projet a été modifié ailleurs entre-"
                    + "temps : rien n'a été enregistré. Relisez la version actuelle, puis recommencez.");
            case NOT_FOUND -> WorkshopPages.BASE + "?err=" + enc("Projet introuvable.");
        });
    }

    /**
     * La demande faite à l'IA, à partir du projet — jamais d'un emplacement. #289 : avec la
     * conception détaillée en vigueur, que chaque plan, correction et critique doit respecter.
     */
    private com.lodygames.rpgquest.panel.ai.BuildingPromptBuilder.Demand demandOf(
            com.lodygames.rpgquest.panel.building.BuildingProject p) {
        return new com.lodygames.rpgquest.panel.ai.BuildingPromptBuilder.Demand(p.id(), p.name(), p.front(),
                p.brief(), p.maxWidth(), p.maxHeight(), p.buildingId(),
                designDocs.latest(p.id()).map(com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Doc::text)
                        .orElse(""));
    }

    /**
     * #289 — rédiger (ou réécrire) la conception détaillée avec l'IA : UN appel facturé, jeton à usage
     * unique comme toute demande payante. La réponse devient une nouvelle version de la conception.
     */
    private PlanOutcome designWrite(Session session, String agentId,
                                    com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        if (!project.hasBrief()) {
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), null,
                    "Décrivez d'abord le bâtiment souhaité : la conception part de votre description."));
        }
        Optional<PlanOutcome> replay = replayedRequest(session, project.id(), f);
        if (replay.isPresent()) {
            return replay.get();
        }
        var proposal = aiBuildingStudio.design(f.getOrDefault("provider", "").trim(), demandOf(project),
                referencesOf(project.id()));
        audit.record(session.username(), "building.design.write", "project=" + project.id() + " provider="
                        + proposal.providerId() + " model=" + proposal.model() + " usage=" + proposal.usage(),
                proposal.callOk() ? "OK" : "FAILED", proposal.callOk() ? "" : proposal.error(),
                UUID.randomUUID().toString().substring(0, 8));
        if (!proposal.callOk()) {
            planDrafts.fulfil(f.get("request_token"), "");
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), null,
                    "L'appel au fournisseur d'IA a échoué : " + proposal.error()));
        }
        var saved = designDocs.save(project.id(), proposal.rawResponse(),
                com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Origin.AI, proposal.providerId(),
                proposal.model(), proposal.usage(), session.username(), -1);
        planDrafts.fulfil(f.get("request_token"), "");
        planProjects.touch(project.id(), session.username());
        return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), saved.doc().isPresent()
                ? "Conception rédigée (version " + saved.doc().get().version() + ") : relisez-la, modifiez-la si "
                        + "besoin ; « Créer un plan avec l'IA » la traduira." : null,
                saved.doc().isPresent() ? null : saved.refusal()));
    }

    /** #289 — modifier la conception à la main : une nouvelle version, sous contrôle de version. */
    private PlanOutcome designSave(Session session,
                                   com.lodygames.rpgquest.panel.building.BuildingProject project,
                                   Map<String, String> f) {
        var saved = designDocs.save(project.id(), f.getOrDefault("design", ""),
                com.lodygames.rpgquest.panel.building.BuildingDesignDocStore.Origin.MANUAL, "", "", "",
                session.username(), parseIntOr(f.get("design_version"), 0));
        audit.record(session.username(), "building.design.save", "project=" + project.id(),
                saved.doc().isPresent() ? "OK" : "DENIED", saved.refusal(), UUID.randomUUID().toString().substring(0, 8));
        if (saved.doc().isEmpty()) {
            // Rien n'est écrit, et le texte saisi est réaffiché plutôt que perdu.
            return new PlanOutcome("", saved.refusal(), null, f.getOrDefault("design", ""));
        }
        planProjects.touch(project.id(), session.username());
        return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), "Conception enregistrée (version "
                + saved.doc().get().version() + "). Le prochain plan demandé à l'IA la suivra.", null));
    }

    /**
     * Geste payant n° 1 : demander un plan neuf. Le résultat devient une version du projet.
     *
     * <p>Le jeton est réservé <strong>avant</strong> l'appel : une seconde soumission du même
     * formulaire mène à la version déjà produite, sans rappeler le modèle.</p>
     */
    private PlanOutcome planPropose(Session session, String agentId,
                                    com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        if (!project.hasBrief()) {
            // Refus à coût nul : pas un jeton dépensé pour une description vide.
            return PlanOutcome.redirect(projectLocation(project.id(), "", null,
                    "Décrivez d'abord le bâtiment souhaité : sans description, il n'y a rien à proposer."));
        }
        Optional<PlanOutcome> replay = replayedRequest(session, project.id(), f);
        if (replay.isPresent()) {
            return replay.get();
        }
        var demand = demandOf(project);
        var proposal = aiBuildingStudio.propose(f.getOrDefault("provider", "").trim(), demand,
                referencesOf(project.id()));
        return keepProposal(session, agentId, project, f.get("request_token"), proposal,
                BuildingPlanDraft.Origin.AI_PROPOSAL, "", demand, false);
    }

    /**
     * Geste payant n° 2 : « Améliorer avec l'IA ». La réponse devient une <strong>nouvelle</strong>
     * version ; celle qu'on améliore n'est jamais écrasée.
     */
    private PlanOutcome planCorrect(Session session, String agentId,
                                    com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        Optional<BuildingPlanDraft> found = planDrafts.find(f.get("draft"))
                .filter(d -> !d.archived() && project.id().equals(d.projectId()));
        if (found.isEmpty()) {
            return PlanOutcome.redirect(projectLocation(project.id(), "", null,
                    "Version introuvable : rien n'a été demandé à l'IA."));
        }
        BuildingPlanDraft draft = found.get();
        if (parseIntOr(f.get("version"), -1) != draft.version()) {
            return PlanOutcome.redirect(projectLocation(project.id(), draft.id(), null,
                    "Le plan a changé depuis l'affichage de la page : rien n'a été demandé à l'IA. Relisez-le, "
                            + "puis recommencez."));
        }
        // #282 : le motif part de lui-même — c'est le diagnostic du DERNIER audit de CETTE version.
        // Les consignes de l'administrateur sont facultatives et s'y ajoutent. « reason » reste lu
        // pour les formulaires ouverts avant la mise à jour.
        var audit = planWorkbench.state(draft, BuildingPlanDraftStore.Check.AUDIT);
        var generate = planWorkbench.state(draft, BuildingPlanDraftStore.Check.GENERATE);
        String reason = correctionMotive(project, audit, generate,
                f.getOrDefault("notes", f.getOrDefault("reason", "")));
        if (reason.isEmpty()) {
            boolean judged = audit.current()
                    && audit.status() == BuildingPlanWorkbench.Status.DONE;
            return PlanOutcome.redirect(projectLocation(project.id(), draft.id(), null, judged
                    ? "Le serveur n'a rien relevé sur cette version : dites ce que vous voulez changer. "
                            + "Rien n'a été demandé à l'IA."
                    : "Cette version n'a pas encore été vérifiée (ou sa vérification est en cours) : "
                            + "le diagnostic du serveur partira de lui-même avec la demande dès qu'il "
                            + "sera connu. Rien n'a été demandé à l'IA."));
        }
        Optional<PlanOutcome> replay = replayedRequest(session, project.id(), f);
        if (replay.isPresent()) {
            return replay.get();
        }
        // Les mesures qui partent avec le motif sont celles de la vérification de CETTE version.
        String measured = audit.current()
                ? com.lodygames.rpgquest.panel.ai.BuildingCorrectionLoop.formatMetrics(
                        com.lodygames.rpgquest.panel.building.BuildingPlanVerdict.from(audit.details()).metrics())
                : "";
        var demand = demandOf(project);
        var proposal = aiBuildingStudio.correct(f.getOrDefault("provider", "").trim(), demand,
                draft.plan(), reason, measured, referencesOf(project.id()));
        return keepProposal(session, agentId, project, f.get("request_token"), proposal,
                BuildingPlanDraft.Origin.AI_CORRECTION, draft.id(), demand, true);
    }

    /**
     * « Conception automatique » (issue #282) : plusieurs passes d'IA et de moteur, sous une
     * enveloppe que l'administrateur a vue et consentie AVANT le lancement.
     *
     * <p>Mêmes garanties que les gestes payants unitaires : jeton à usage unique (un double clic ne
     * lance jamais deux sessions), une seule session en cours par projet, et la dépense est bornée
     * par le niveau choisi et par {@link com.lodygames.rpgquest.panel.ai.BuildingDesignPipeline#MAX_CALLS_CEILING}.</p>
     */
    private PlanOutcome planDesign(Session session, String agentId,
                                   com.lodygames.rpgquest.panel.building.BuildingProject project,
                                   Map<String, String> f) {
        if (!project.hasBrief()) {
            return PlanOutcome.redirect(projectLocation(project.id(), "", null,
                    "Décrivez d'abord le bâtiment souhaité : sans description, il n'y a rien à concevoir."));
        }
        String token = f.getOrDefault("request_token", "").trim();
        var existing = token.isEmpty() ? Optional.<com.lodygames.rpgquest.panel.building.BuildingDesignRunStore.Run>empty()
                : designRuns.byToken(token);
        if (existing.isPresent()) {
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"),
                    "Cette conception a déjà été lancée : aucun nouvel appel n'a été émis.", null));
        }
        if (designRuns.latest(project.id()).filter(r -> r.running()).isPresent()) {
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), null,
                    "Une conception automatique est déjà en cours sur ce projet : rien n'a été lancé."));
        }
        if (!"yes".equals(f.getOrDefault("consent", ""))) {
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), null,
                    "Cochez l'acceptation de l'enveloppe : rien n'est dépensé sans votre accord explicite."));
        }
        if (token.isEmpty()) {
            return PlanOutcome.redirect(projectLocation(project.id(), "", null,
                    "Formulaire périmé : rien n'a été lancé. Rechargez la page."));
        }
        String provider = f.getOrDefault("provider", "").trim();
        if (aiBuildingStudio.usableProviders().stream().noneMatch(p -> p.id().equals(provider))) {
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), null,
                    "Fournisseur d'IA inconnu ou inutilisable : rien n'a été lancé."));
        }
        var quality = com.lodygames.rpgquest.panel.ai.BuildingDesignPipeline.Quality.of(f.get("quality"));
        int calls = Math.min(quality.calls(), Math.max(1, parseIntOr(f.get("max_calls"), quality.calls())));
        boolean fresh = "yes".equals(f.getOrDefault("fresh", ""));
        boolean rewriteDesign = "yes".equals(f.getOrDefault("rewrite_design", ""));
        var started = designRuns.start(agentId, project.id(), token, provider, quality.name(), calls, fresh,
                rewriteDesign, session.username());
        if (started.isEmpty()) {
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"),
                    "Cette conception a déjà été lancée : aucun nouvel appel n'a été émis.", null));
        }
        audit.record(session.username(), "building.design.start", "project=" + project.id() + " run="
                + started.get().id() + " quality=" + quality.name() + " max_calls=" + calls
                + " provider=" + provider + " fresh=" + fresh + " rewrite_design=" + rewriteDesign, "OK",
                "enveloppe consentie", UUID.randomUUID().toString().substring(0, 8));
        designPipeline.launch(started.get());
        return PlanOutcome.redirect(projectLocation(project.id(), "", "Conception automatique lancée ("
                + quality.label() + ", " + calls + " appel(s) au plus). Suivez les passes ci-dessous.", null));
    }

    /**
     * « Recommencer depuis ma description » (issue #283) : une génération NEUVE, qui n'hérite ni du
     * plan ni des erreurs de la version affichée. Elle devient une version sans parent ; toutes les
     * versions existantes restent consultables — rien n'est supprimé ni écrasé. Geste payant : même
     * jeton à usage unique que « Créer un plan ».
     */
    private PlanOutcome planRestart(Session session, String agentId,
                                    com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        if (!"yes".equals(f.getOrDefault("confirm", ""))) {
            return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"), null,
                    "Cochez la confirmation : recommencer est un appel facturé. Rien n'a été demandé."));
        }
        return planPropose(session, agentId, project, f);
    }

    /** Les images de référence d'un projet, en pixels (#283). */
    private List<com.lodygames.rpgquest.panel.ai.AiProvider.Image> referencesOf(String projectId) {
        List<com.lodygames.rpgquest.panel.ai.AiProvider.Image> out = new java.util.ArrayList<>();
        for (var info : referenceImages.list(projectId)) {
            referenceImages.data(projectId, info.id()).ifPresent(
                    bytes -> out.add(new com.lodygames.rpgquest.panel.ai.AiProvider.Image(info.mime(), bytes)));
        }
        return out;
    }

    private PlanOutcome imageRemove(Session session,
                                    com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        String imageId = f.getOrDefault("image", "").trim();
        boolean removed = referenceImages.remove(project.id(), imageId);
        audit.record(session.username(), "building.project.image.remove", "project=" + project.id()
                + " image=" + imageId, removed ? "OK" : "NOT_FOUND", "", UUID.randomUUID().toString().substring(0, 8));
        return PlanOutcome.redirect(projectLocation(project.id(), f.get("draft"),
                removed ? "Image retirée du projet (effacée de la base du panel)." : null,
                removed ? null : "Image introuvable : rien n'a été retiré."));
    }

    /**
     * {@code /buildings/workshop/image} (issue #283) : POST = ajout d'une image de référence
     * (multipart), GET = l'image elle-même, pour la vignette.
     *
     * <p>Le GET exige la même session et la même permission que l'Atelier, vérifie que l'image
     * appartient au projet demandé, et sert le type <em>détecté</em> à l'enregistrement avec
     * {@code nosniff} : un fichier ne peut pas devenir une page.</p>
     */
    private void handleWorkshopImage(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.AI_USE)) {
            forbidden(exchange, session, WorkshopPages.BASE);
            return;
        }
        String agentId = config.agents().defaultAgentId();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> q = Http.query(exchange);
            var project = planProjects.find(q.getOrDefault("project", "")).filter(p -> p.agentId().equals(agentId));
            var info = project.flatMap(p -> referenceImages.find(p.id(), q.getOrDefault("id", "")));
            var bytes = info.flatMap(i -> referenceImages.data(i.projectId(), i.id()));
            if (bytes.isEmpty()) {
                Http.html(exchange, 404, Layout.bare("Image introuvable", "<h1>Image introuvable</h1>"));
                return;
            }
            Http.securityHeaders(exchange);
            exchange.getResponseHeaders().set("Content-Type", info.get().mime());
            exchange.getResponseHeaders().set("Content-Disposition", "inline");
            exchange.getResponseHeaders().set("Cache-Control", "private, max-age=300");
            exchange.sendResponseHeaders(200, bytes.get().length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes.get());
            }
            return;
        }
        int max = com.lodygames.rpgquest.panel.building.ReferenceImageStore.MAX_BYTES + 64 * 1024;
        byte[] body = exchange.getRequestBody().readNBytes(max + 1);
        var form = com.lodygames.rpgquest.panel.http.Multipart.parse(
                exchange.getRequestHeaders().getFirst("Content-Type"), body);
        if (body.length > max || form.isEmpty()) {
            Http.redirect(exchange, WorkshopPages.BASE + "?err=" + enc(body.length > max
                    ? "Image trop lourde : " + (com.lodygames.rpgquest.panel.building.ReferenceImageStore.MAX_BYTES / 1024)
                            + " Kio au plus. Rien n'a été enregistré."
                    : "Envoi illisible : rien n'a été enregistré."));
            return;
        }
        Map<String, String> f = form.get().fields();
        if (!constantTimeEquals(session.csrfToken(), f.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        var project = planProjects.find(f.get("id")).filter(p -> !p.archived() && p.agentId().equals(agentId));
        if (project.isEmpty()) {
            Http.redirect(exchange, WorkshopPages.BASE + "?err=" + enc("Projet introuvable : rien n'a été fait."));
            return;
        }
        var file = form.get().file("image");
        var added = referenceImages.add(project.get().id(), file.map(p -> p.fileName()).orElse(""),
                file.map(p -> p.data()).orElse(new byte[0]), session.username());
        audit.record(session.username(), "building.project.image.add", "project=" + project.get().id()
                        + added.image().map(i -> " image=" + i.id() + " mime=" + i.mime() + " size=" + i.size()).orElse(""),
                added.image().isPresent() ? "OK" : "DENIED", added.refusal(), UUID.randomUUID().toString().substring(0, 8));
        Http.redirect(exchange, projectLocation(project.get().id(), f.get("draft"),
                added.image().isPresent() ? "Image de référence ajoutée. Elle sera montrée à l'IA à chaque "
                        + "demande de ce projet." : null, added.image().isPresent() ? null : added.refusal()));
    }

    /**
     * Le motif envoyé à l'IA : le diagnostic du serveur sur la version courante, puis les consignes
     * de l'administrateur s'il en a donné (issue #282). Un audit périmé n'est jamais utilisé : il
     * parlerait d'un autre plan.
     */
    static String correctionMotive(com.lodygames.rpgquest.panel.building.BuildingProject project,
                                   BuildingPlanWorkbench.CheckState audit, String notes) {
        return correctionMotive(project, audit, null, notes);
    }

    /** Même chose, avec l'échec d'enregistrement de la version s'il porte sur le contenu (#292). */
    static String correctionMotive(com.lodygames.rpgquest.panel.building.BuildingProject project,
                                   BuildingPlanWorkbench.CheckState audit,
                                   BuildingPlanWorkbench.CheckState generate, String notes) {
        String diagnosis = audit.current() || com.lodygames.rpgquest.panel.building.SaveFailure.of(generate)
                .isPresent() ? WorkshopPages.diagnosis(project, audit, generate) : "";
        String extra = notes == null ? "" : notes.trim();
        // Un ancien formulaire renvoyait le diagnostic prérempli dans « reason » : ne pas le doubler.
        if (!extra.isEmpty() && !diagnosis.isEmpty() && extra.contains(diagnosis)) {
            extra = extra.replace(diagnosis, "").trim();
        }
        if (extra.isEmpty()) {
            return diagnosis;
        }
        return diagnosis.isEmpty() ? "Consignes de l'administrateur :\n" + extra
                : diagnosis + "\n\nConsignes de l'administrateur, en plus :\n" + extra;
    }

    /**
     * Le jeton d'une demande payante a-t-il déjà servi ? Si oui, on renvoie vers ce qu'il a produit
     * et <strong>on n'appelle pas</strong> le modèle.
     */
    private Optional<PlanOutcome> replayedRequest(Session session, String projectId, Map<String, String> f) {
        String token = f.getOrDefault("request_token", "").trim();
        if (token.isEmpty()) {
            return Optional.of(PlanOutcome.redirect(projectLocation(projectId, "", null,
                    "Formulaire périmé : rien n'a été demandé à l'IA. Rechargez la page.")));
        }
        var reservation = planDrafts.reserve(token, session.username());
        if (reservation.fresh()) {
            return Optional.empty();
        }
        audit.record(session.username(), "building.plan.request.replayed", "project=" + projectId,
                "DENIED", "jeton déjà utilisé — aucun appel émis", UUID.randomUUID().toString().substring(0, 8));
        return Optional.of(PlanOutcome.redirect(reservation.draftId().isEmpty()
                ? projectLocation(projectId, "", null, "Cette demande a déjà été envoyée (double clic ou "
                        + "rechargement) : aucun nouvel appel n'a été émis.")
                : projectLocation(projectId, reservation.draftId(), "Cette demande avait déjà abouti : voici son "
                        + "plan. Aucun nouvel appel n'a été émis.", null)));
    }

    /** Conserve la réponse du modèle comme version du projet, et journalise l'appel. */
    private PlanOutcome keepProposal(Session session, String agentId,
                                     com.lodygames.rpgquest.panel.building.BuildingProject project, String token,
                                     com.lodygames.rpgquest.panel.ai.AiBuildingStudio.Proposal proposal,
                                     BuildingPlanDraft.Origin origin, String parentId,
                                     com.lodygames.rpgquest.panel.ai.BuildingPromptBuilder.Demand demand,
                                     boolean correction) {
        // L'audit retient le fournisseur, le modèle et l'issue — jamais la clé, jamais la description.
        audit.record(session.username(), "building.plan.generate",
                "project=" + project.id() + " provider=" + proposal.providerId() + " model=" + proposal.model()
                        + " usage=" + proposal.usage() + " correction=" + correction,
                proposal.callOk() ? "OK" : "FAILED",
                proposal.callOk() ? (proposal.hasPlan() ? "plan proposé" : "aucun plan exploitable") : proposal.error(),
                UUID.randomUUID().toString().substring(0, 8));
        if (!proposal.callOk()) {
            return PlanOutcome.redirect(projectLocation(project.id(), parentId, null,
                    "L'IA n'a pas répondu : " + proposal.error() + " Aucune version n'a été créée."));
        }
        // Même sans plan exploitable, la réponse a été payée : elle est conservée.
        BuildingPlanDraft created = planDrafts.create(new BuildingPlanDraftStore.NewDraft(agentId, "",
                proposal.hasPlan() ? proposal.plan() : "", origin, parentId, "", proposal.providerId(),
                proposal.providerLabel(), proposal.model(), proposal.usage(), proposal.rawResponse(),
                proposal.extractionNote(), demand.maxWidth(), demand.maxHeight(), session.username(), project.id()));
        planDrafts.fulfil(token, created.id());
        planProjects.touch(project.id(), session.username());
        // #282 : un plan neuf est vérifié et dessiné aussitôt, gratuitement. Si le serveur sait le
        // réparer, l'Atelier crée la version réparée dès le retour de l'audit.
        Optional<String> verifyRefused = created.hasPlan()
                ? workshopAutopilot.verify(created, session.username()) : Optional.empty();
        audit.record(session.username(), "building.plan.draft.create",
                "project=" + project.id() + " draft=" + created.id() + " origin=" + origin, "OK",
                parentId.isEmpty() ? "" : "parent=" + parentId, UUID.randomUUID().toString().substring(0, 8));
        return PlanOutcome.redirect(projectLocation(project.id(), created.id(), proposal.hasPlan()
                ? (verifyRefused.isEmpty()
                        ? "Plan reçu : vérification et aperçu demandés au serveur, le résultat s'affiche ici "
                                + "dans quelques secondes."
                        : "Plan reçu. La vérification n'a pas pu partir : " + verifyRefused.get())
                : "L'IA a répondu sans plan exploitable. Sa réponse est conservée dans les détails techniques ; "
                        + "redemandez un plan.", null));
    }

    /**
     * Les gestes gratuits : enregistrer le texte, vérifier, calculer les coupes, enregistrer dans la
     * bibliothèque. Le texte est enregistré d'abord s'il a changé (nouvelle version, contrôle de
     * version), puis le geste part sur la version enregistrée et y est rattaché.
     */
    private PlanOutcome planCheck(Session session, String agentId,
                                  com.lodygames.rpgquest.panel.building.BuildingProject project,
                                  Map<String, String> f) {
        String gesture = f.getOrDefault("_action", "").trim();
        Optional<BuildingPlanDraft> found = planDrafts.find(f.get("draft"))
                .filter(d -> !d.archived() && d.agentId().equals(agentId) && project.id().equals(d.projectId()));
        if (found.isEmpty()) {
            return PlanOutcome.redirect(projectLocation(project.id(), "", null,
                    "Version introuvable ou retirée : rien n'a été fait."));
        }
        BuildingPlanDraft draft = found.get();
        int expected = parseIntOr(f.get("version"), -1);
        String submitted = f.getOrDefault("plan", "").replace("\r\n", "\n");
        var saved = planDrafts.save(draft.id(), expected, submitted, session.username());
        switch (saved.outcome()) {
            case STALE -> {
                audit.record(session.username(), "building.plan.draft.save", "draft=" + draft.id(), "DENIED",
                        "version périmée : " + expected + " ≠ " + draft.version(),
                        UUID.randomUUID().toString().substring(0, 8));
                return new PlanOutcome("", "Le plan a été modifié ailleurs pendant que vous l'éditiez : rien n'a "
                        + "été enregistré ni envoyé.", submitted);
            }
            case NOT_FOUND -> {
                return PlanOutcome.redirect(projectLocation(project.id(), "", null,
                        "Version introuvable ou retirée : rien n'a été fait."));
            }
            case SAVED -> audit.record(session.username(), "building.plan.draft.save", "draft=" + draft.id(), "OK",
                    "version=" + saved.draft().map(BuildingPlanDraft::version).orElse(0),
                    UUID.randomUUID().toString().substring(0, 8));
            case UNCHANGED -> {
                // Rien à enregistrer : le geste part sur la version courante.
            }
        }
        BuildingPlanDraft current = saved.draft().orElse(draft);
        // #267 : le nom technique est celui du PROJET, pas celui que l'IA a écrit. S'ils diffèrent, le
        // plan est aligné — en nouvelle version, donc tracé et réversible — avant d'être envoyé :
        // sinon « Enregistrer » viserait le nom de l'IA, par exemple celui d'un bâtiment existant.
        String aligned = !gesture.equals("save") && !current.buildingId().equals(project.buildingId())
                && current.hasPlan() ? BuildingPlanDraft.withBuildingId(current.plan(), project.buildingId()) : null;
        if (aligned != null) {
            var renamed = planDrafts.save(current.id(), current.version(), aligned, session.username());
            if (renamed.outcome() == BuildingPlanDraftStore.SaveOutcome.SAVED) {
                current = renamed.draft().orElse(current);
            }
        }
        if ("save".equals(gesture)) {
            return PlanOutcome.redirect(projectLocation(project.id(), current.id(),
                    saved.outcome() == BuildingPlanDraftStore.SaveOutcome.SAVED
                            ? "Texte enregistré (version " + current.version() + ")." : "Aucune modification.", null));
        }
        BuildingPlanDraftStore.Check check = switch (gesture) {
            case "audit" -> BuildingPlanDraftStore.Check.AUDIT;
            case "preview" -> BuildingPlanDraftStore.Check.PREVIEW;
            default -> BuildingPlanDraftStore.Check.GENERATE;
        };
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("plan", current.plan());
        if (check != BuildingPlanDraftStore.Check.GENERATE) {
            // #282 : les bornes du projet, pour la réparation déterministe du plugin.
            fields.put("max_width", String.valueOf(project.maxWidth()));
            fields.put("max_height", String.valueOf(project.maxHeight()));
        }
        if (check == BuildingPlanDraftStore.Check.GENERATE) {
            // #265 : jamais de remplacement IMPLICITE. Un nom technique pris par un autre bâtiment est
            // signalé à l'écran. #290 : republier le bâtiment de CE projet est un geste distinct,
            // coché, qui envoie la version vue — le serveur refuse si elle a changé entre-temps.
            boolean republish = "yes".equals(f.getOrDefault("republish", ""));
            if (republish && !"yes".equals(f.getOrDefault("republish_confirm", ""))) {
                return PlanOutcome.redirect(projectLocation(project.id(), current.id(), null,
                        "Cochez la confirmation : publier remplace la version de la bibliothèque. Rien n'a "
                                + "été envoyé."));
            }
            if (republish && !producedByProject(planDrafts.forProject(agentId, project.id()),
                    project.buildingId())) {
                return PlanOutcome.redirect(projectLocation(project.id(), current.id(), null,
                        "Ce bâtiment n'a pas été publié par ce projet : il ne sera pas remplacé. Rien n'a été "
                                + "envoyé."));
            }
            fields.put("replace", republish ? "true" : "false");
            String expectedVersion = f.getOrDefault("expected_version", "").trim();
            if (republish && !expectedVersion.isEmpty() && !"0".equals(expectedVersion)) {
                fields.put("expected_version", expectedVersion);
            }
            fields.put("confirm", "true");
        }
        Queued queued = enqueueValidated(session, agentId, check.actionType(), fields);
        if (queued.error() != null) {
            return PlanOutcome.redirect(projectLocation(project.id(), current.id(), null, queued.error()));
        }
        planDrafts.link(current.id(), queued.actionId(), check, current.version());
        if (check == BuildingPlanDraftStore.Check.AUDIT) {
            // #273 : la vérification s'accompagne de l'aperçu en 3D — gratuit, sans écriture, calculé
            // sur la même version. S'il ne peut pas partir, la vérification reste valable : l'écran
            // dira simplement que l'aperçu manque.
            Queued drawn = enqueueValidated(session, agentId, BuildingPlanDraftStore.Check.PREVIEW.actionType(),
                    Map.of("plan", current.plan(), "max_width", String.valueOf(project.maxWidth()),
                            "max_height", String.valueOf(project.maxHeight())));
            if (drawn.error() == null) {
                planDrafts.link(current.id(), drawn.actionId(), BuildingPlanDraftStore.Check.PREVIEW,
                        current.version());
            }
        }
        planProjects.touch(project.id(), session.username());
        return PlanOutcome.redirect(projectLocation(project.id(), current.id(), switch (check) {
            case AUDIT -> "Vérification demandée au serveur : le résultat s'affiche ici dans quelques secondes.";
            case PREVIEW -> "Calcul de l'aperçu demandé au serveur.";
            case GENERATE -> "Enregistrement dans la bibliothèque demandé au serveur.";
        }, null));
    }

    /**
     * Reprendre le plan d'une action existante dans ce projet, <strong>sans appel à l'IA</strong>.
     * Idempotent ; l'action d'origine est rattachée à la version créée, donc son verdict s'affiche.
     */
    private PlanOutcome planRecover(Session session, String agentId,
                                    com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        String actionId = f.getOrDefault("action_id", "").trim();
        Optional<BuildingPlanDraft> already = planDrafts.recoveredFrom(actionId)
                .or(() -> planDrafts.linkOf(actionId).flatMap(l -> planDrafts.find(l.draftId())));
        if (already.isPresent()) {
            BuildingPlanDraft d = already.get();
            if (d.projectId().isEmpty()) {
                planDrafts.attachDraft(d.id(), project.id());
                d = planDrafts.find(d.id()).orElse(d);
            }
            return PlanOutcome.redirect(projectLocation(d.projectId(), d.id(),
                    "Ce plan avait déjà été repris : le voici.", null));
        }
        Optional<AgentActionRow> row = actionId.isEmpty() ? Optional.empty()
                : agentStore.action(actionId).filter(r -> r.agentId().equals(agentId));
        Optional<String> plan = row.flatMap(BuildingPlanWorkbench::planOf);
        if (plan.isEmpty()) {
            return PlanOutcome.redirect(projectLocation(project.id(), "", null, "L'action « " + actionId
                    + " » ne contient pas de plan de bâtiment : rien n'a été repris."));
        }
        BuildingPlanDraft created = planDrafts.create(new BuildingPlanDraftStore.NewDraft(agentId, "", plan.get(),
                BuildingPlanDraft.Origin.RECOVERED, "", actionId, "", "", "", "", "", "", project.maxWidth(),
                project.maxHeight(), session.username(), project.id()));
        BuildingPlanDraftStore.Check.ofActionType(row.get().type())
                .ifPresent(check -> planDrafts.link(created.id(), actionId, check, 1));
        planProjects.touch(project.id(), session.username());
        audit.record(session.username(), "building.plan.draft.recover", "project=" + project.id() + " draft="
                + created.id() + " action=" + actionId, "OK", "aucun appel IA",
                UUID.randomUUID().toString().substring(0, 8));
        return PlanOutcome.redirect(projectLocation(project.id(), created.id(),
                "Plan repris depuis l'historique — sans appel à l'IA.", null));
    }

    private PlanOutcome planArchive(Session session, com.lodygames.rpgquest.panel.building.BuildingProject project,
                                    Map<String, String> f) {
        String draftId = f.getOrDefault("draft", "").trim();
        boolean done = planDrafts.find(draftId).filter(d -> project.id().equals(d.projectId())).isPresent()
                && planDrafts.archive(draftId, session.username());
        if (done) {
            audit.record(session.username(), "building.plan.draft.archive", "draft=" + draftId, "OK", "",
                    UUID.randomUUID().toString().substring(0, 8));
        }
        return PlanOutcome.redirect(projectLocation(project.id(), "", done
                ? "Version retirée de la liste. Elle reste conservée dans la base du panel." : null,
                done ? null : "Version introuvable ou déjà retirée."));
    }

    /** L'adresse d'un projet, sur une version si on en a une. */
    private static String projectLocation(String projectId, String draftId, String ok, String err) {
        StringBuilder sb = new StringBuilder(WorkshopPages.BASE + "/project?id=").append(enc(projectId));
        if (draftId != null && !draftId.isBlank()) {
            sb.append("&draft=").append(enc(draftId));
        }
        if (ok != null && !ok.isBlank()) {
            sb.append("&ok=").append(enc(ok));
        }
        if (err != null && !err.isBlank()) {
            sb.append("&err=").append(enc(err));
        }
        return sb.toString();
    }

    /** Une action mise en file, ou la raison du refus. */
    private record Queued(String actionId, String error) {
    }

    /**
     * Met en file une action whitelistée avec exactement les contrôles de {@code /agents/action} :
     * type connu, toutes les permissions exigées, agent connu, paramètres validés par le catalogue,
     * puis journalisation.
     */
    private Queued enqueueValidated(Session session, String agentId, String type,
                                    Map<String, String> form) {
        String rid = UUID.randomUUID().toString().substring(0, 8);
        var spec = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.spec(type);
        if (spec.isEmpty()) {
            audit.record(session.username(), "agent.action.create", "type=" + type, "DENIED",
                    "type non whitelisté", rid);
            return new Queued(null, "Type d'action non autorisé.");
        }
        if (!hasAllPermissions(session, spec.get())) {
            audit.record(session.username(), "agent.action.create", "type=" + type, "DENIED",
                    "permission manquante", rid);
            return new Queued(null, "Permission manquante pour « " + type + " ».");
        }
        if (agentRegistry.byId(agentId).isEmpty()) {
            audit.record(session.username(), "agent.action.create", "type=" + type, "DENIED",
                    "agent inconnu : " + agentId, rid);
            return new Queued(null, "Agent inconnu.");
        }
        var v = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate(type, form);
        if (!v.valid()) {
            audit.record(session.username(), "agent.action.create",
                    "agent=" + agentId + " type=" + type, "DENIED", v.error(), rid);
            return new Queued(null, v.error());
        }
        String id = agentStore.createAction(agentId, type, v.params(), session.username());
        audit.record(session.username(), "agent.action.create",
                "agent=" + agentId + " type=" + type + " action=" + id, "PENDING",
                safeParams(v.params()), rid);
        LOG.log(System.Logger.Level.INFO, "event=agent_action_created rid=" + rid + " agent="
                + agentId + " type=" + type + " action=" + id + " by=" + session.username());
        return new Queued(id, null);
    }

    /** Tests : remplace le testeur de connexion réel (aucun serveur MariaDB requis). */
    public void useDbConnectionTester(com.lodygames.rpgquest.panel.dbtarget.DbConnectionTester tester) {
        this.dbTester = tester;
    }

    /** Tests : remplace le testeur réel de la base du panel (issue #43). */
    public void usePanelDbConnectionTester(com.lodygames.rpgquest.panel.dbtarget.DbConnectionTester tester) {
        this.panelDbTester = tester;
    }

    /**
     * Issue #42 — base MariaDB cible : paramètres, mot de passe chiffré, test de connexion en lecture
     * seule et assistant de migration. Issue #43 — mêmes gestes pour la base propre au panel
     * (champ {@code scope=panel}), avec test d'isolement. Préparation uniquement : aucune route ne
     * migre ni ne bascule.
     */
    private void handleDatabaseTarget(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.DATABASE_CONFIGURE)) {
            forbidden(exchange, session, "/database");
            return;
        }
        if (!"/database".equals(exchange.getRequestURI().getPath())) {
            // le contexte HTTP capte aussi /database/… : aucune sous-route n'existe, en particulier
            // aucune route de migration ou de bascule
            Http.html(exchange, 404, Layout.bare("Introuvable", "<h1>Page introuvable</h1>"));
            return;
        }
        String notice = null;
        String error = null;
        com.lodygames.rpgquest.panel.dbtarget.DbConnectionTester.Result testResult = null;
        com.lodygames.rpgquest.panel.dbtarget.DbTargetKind kind =
                com.lodygames.rpgquest.panel.dbtarget.DbTargetKind.MIGRATION;

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> f = Http.formBody(exchange);
            if (!constantTimeEquals(session.csrfToken(), f.get("_csrf"))) {
                Http.html(exchange, 403, Layout.bare("CSRF",
                        "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                return;
            }
            String rid = UUID.randomUUID().toString().substring(0, 8);
            String action = f.getOrDefault("_action", "save");
            boolean panelScope = "panel".equals(f.get("scope"));
            if (panelScope) {
                kind = com.lodygames.rpgquest.panel.dbtarget.DbTargetKind.PANEL;
            }
            com.lodygames.rpgquest.panel.dbtarget.DbTargetStore store = panelScope ? panelDbTarget : dbTarget;
            String auditPrefix = panelScope ? "database.panelTarget." : "database.target.";
            String noun = panelScope ? "Base du panel" : "Cible";
            if ("clear".equals(action)) {
                if (!"on".equals(f.get("confirm"))) {
                    error = "Effacement non confirmé : cocher la case de confirmation.";
                } else {
                    store.clearPassword(session.username());
                    // L'audit dit QUE le secret a été effacé, jamais ce qu'il valait.
                    audit.record(session.username(), auditPrefix + "clearPassword", "target=" + kind.auditLabel(),
                            "OK", "mot de passe effacé", rid);
                    notice = "Mot de passe effacé.";
                }
            } else {
                String host = f.getOrDefault("host", "").trim();
                String port = f.getOrDefault("port", "3306").trim();
                String database = f.getOrDefault("database", "").trim();
                String username = f.getOrDefault("username", "").trim();
                String ssl = f.getOrDefault("sslMode", "disable").trim();
                String password = f.getOrDefault("password", "");
                error = com.lodygames.rpgquest.panel.dbtarget.DbTargetSettings.validate(host, port, database,
                        username, ssl);
                if (error == null) {
                    // Issue #43 : la base du panel ne peut pas être celle de RPGQuest (et inversement).
                    com.lodygames.rpgquest.panel.dbtarget.DbTargetSettings other =
                            (panelScope ? dbTarget : panelDbTarget).get();
                    if (other.configured() && other.host().equalsIgnoreCase(host)
                            && String.valueOf(other.port()).equals(port) && other.database().equals(database)) {
                        error = "Base refusée : la base du panel doit être distincte de la base RPGQuest "
                                + "(même hôte, port et nom de base que l'autre cible).";
                    }
                }
                if (error == null) {
                    store.save(host, Integer.parseInt(port), database, username, ssl, password,
                            session.username());
                    // Ni hôte ni secret dans l'audit : la base, le mode TLS et le fait qu'un mot de
                    // passe a été remplacé suffisent à tracer le geste.
                    audit.record(session.username(), auditPrefix + "save", "database=" + database
                                    + " ssl=" + ssl + " passwordReplaced=" + !password.isEmpty(),
                            "OK", noun.toLowerCase(java.util.Locale.ROOT) + " enregistrée", rid);
                    notice = noun + " enregistrée.";
                    if ("test".equals(action)) {
                        testResult = store.test(panelScope ? panelDbTester : dbTester);
                        audit.record(session.username(), auditPrefix + "test", "database=" + database,
                                testResult.ok() ? "OK" : "FAILED", testResult.ok() ? "connexion réussie"
                                        : "échec de connexion", rid);
                        notice = null;
                    }
                }
            }
        }

        boolean canWrite = permissions.can(session.effective(), Permission.DATABASE_CONFIGURE);
        String body = com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.render(dbTarget.get(),
                panelDbSection(), activeBackend(), canWrite, notice, error, kind, testResult);
        Http.html(exchange, 200, renderPage("Base de données", session, "/database", body,
                Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    /** Issue #43 : stockage actif du panel, cible MariaDB du panel et avertissement de partage de compte. */
    private com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.PanelSection panelDbSection() {
        com.lodygames.rpgquest.panel.dbtarget.DbTargetSettings panel = panelDbTarget.get();
        com.lodygames.rpgquest.panel.dbtarget.DbTargetSettings migration = dbTarget.get();
        String warning = panel.configured() && migration.configured()
                && panel.username().equals(migration.username())
                ? "Même compte SQL que la cible RPGQuest : utiliser un compte dédié au panel, sans droit sur "
                        + "la base du plugin."
                : null;
        return new com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.PanelSection(panel,
                com.lodygames.rpgquest.panel.dbtarget.PanelDbInventory.read(config.panelDbPath()), warning);
    }

    /** Backend réellement actif, lu dans le dernier heartbeat de l'agent par défaut (issue #42). */
    private com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.ActiveBackend activeBackend() {
        String agentId = config.agents().defaultAgentId();
        if (agentId == null || agentId.isBlank()) {
            return com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.ActiveBackend.unknown("aucun agent configuré");
        }
        Optional<HeartbeatRecord> hb = agentStore.latestHeartbeat(agentId);
        if (hb.isEmpty()) {
            return com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.ActiveBackend.unknown("aucun heartbeat reçu");
        }
        String age = AgentLiveness.ageHuman(hb.get().receivedAt(), Instant.now());
        try {
            Object database = com.lodygames.rpgquest.panel.json.Json.parseObject(hb.get().rawJson()).get("database");
            if (database instanceof Map<?, ?> map && map.get("engine") instanceof String engine) {
                int version = map.get("schema_version") instanceof Number n ? n.intValue() : 0;
                return new com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.ActiveBackend(engine, version, age);
            }
        } catch (RuntimeException e) {
            // heartbeat illisible ou tronqué : backend présenté comme inconnu
        }
        return com.lodygames.rpgquest.panel.dbtarget.DbTargetPages.ActiveBackend.unknown(
                "heartbeat " + age + " sans information de base (plugin antérieur à #42)");
    }

    /**
     * Configuration des fournisseurs. Permission distincte de l'usage : manipuler une clé d'API
     * tierce n'est pas un geste d'édition de contenu.
     */
    private void handleAiProviders(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.AI_CONFIGURE)) {
            forbidden(exchange, session, "/ai/providers");
            return;
        }

        String notice = null;
        String error = null;
        String tested = null;
        com.lodygames.rpgquest.panel.ai.AiProvider.Result testResult = null;

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Map<String, String> f = Http.formBody(exchange);
            if (!constantTimeEquals(session.csrfToken(), f.get("_csrf"))) {
                Http.html(exchange, 403, Layout.bare("CSRF",
                        "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                return;
            }
            String id = f.getOrDefault("provider", "").trim();
            var provider = aiProviders.byId(id);
            if (provider.isEmpty()) {
                error = "Fournisseur inconnu.";
            } else {
                String action = f.getOrDefault("_action", "save");
                String rid = UUID.randomUUID().toString().substring(0, 8);
                if ("clear".equals(action)) {
                    aiSettings.clearKey(id, session.username());
                    // L'audit dit QUE la clé a été effacée, jamais ce qu'elle valait.
                    audit.record(session.username(), "ai.provider.clearKey", "provider=" + id,
                            "OK", "clé effacée, fournisseur désactivé", rid);
                    notice = "Clé effacée et fournisseur désactivé.";
                } else {
                    var settings = new com.lodygames.rpgquest.panel.ai.AiProviderSettings(id,
                            "on".equals(f.get("enabled")), f.getOrDefault("apiKey", ""),
                            f.getOrDefault("baseUrl", ""), f.getOrDefault("model", ""),
                            parseIntOr(f.get("maxOutputTokens"), 0),
                            parseIntOr(f.get("timeoutSeconds"), 0));
                    aiSettings.save(settings, session.username());
                    var saved = aiSettings.get(id);
                    // L'empreinte, jamais la clé : elle permet de tracer une rotation sans fuite.
                    audit.record(session.username(), "ai.provider.save",
                            "provider=" + id + " enabled=" + saved.enabled()
                                    + " model=" + saved.model() + " baseUrl=" + saved.baseUrl()
                                    + " keyFingerprint=" + (saved.hasKey() ? saved.fingerprint() : "aucune"),
                            "OK", "configuration enregistrée", rid);
                    notice = "Configuration enregistrée.";
                    if ("test".equals(action)) {
                        tested = id;
                        testResult = provider.get().testConnection(saved);
                        audit.record(session.username(), "ai.provider.test",
                                "provider=" + id + " model=" + saved.model(),
                                testResult.ok() ? "OK" : "FAILED",
                                testResult.ok() ? "connexion réussie" : testResult.error(), rid);
                        notice = null;
                    }
                }
            }
        }

        String body = AiProviderPages.render(aiProviders, aiSettings, notice, error, tested, testResult);
        Http.html(exchange, 200, renderPage("Fournisseurs d'IA", session, "/ai/providers", body,
                Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    private static int parseIntOr(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- Import de content pack (issue #109) ---------------------------------------------

    /**
     * GET rend la page ; POST analyse, puis — seulement si {@code confirm} est présent et que
     * l'analyse est importable — enregistre dans la source.
     *
     * <p>L'analyse est <strong>refaite à chaque soumission</strong> plutôt que mise en cache : une
     * analyse conservée entre deux requêtes deviendrait fausse dès qu'un éditeur enregistre en
     * parallèle, et la confirmation écrirait alors d'après un état périmé. Ici la confirmation
     * re-valide tout et redétecte les collisions apparues entre-temps.</p>
     */
    /** Issue #211 : les créations de PNJ logiques d'un pack importé, par la file d'actions habituelle. */
    private List<String> enqueueImportedNpcs(Session session, List<Map<String, String>> creations, String rid) {
        List<String> notes = new ArrayList<>();
        if (creations.isEmpty()) {
            return notes;
        }
        String agentId = config.agents().defaultAgentId();
        if (!permissions.can(session.effective(), Permission.NPC_WRITE)) {
            notes.add("PNJ du pack non créés : votre rôle ne permet pas de créer des PNJ (à faire depuis la page des PNJ).");
            return notes;
        }
        // Les fichiers de la source sont déjà écrits à ce stade : un échec ici doit être DIT sur la
        // page (le PNJ se crée alors depuis la page des PNJ), jamais faire échouer la réponse.
        if (agentId == null || agentId.isBlank()) {
            notes.add("PNJ du pack non créés : aucun agent serveur configuré (à créer depuis la page des PNJ).");
            return notes;
        }
        for (Map<String, String> form : creations) {
            var v = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("npc.definition.create", form);
            if (!v.valid()) {
                notes.add("PNJ « " + form.get("npc_id") + " » non créé : " + v.error());
                continue;
            }
            String id;
            try {
                id = agentStore.createAction(agentId, "npc.definition.create", v.params(), session.username());
            } catch (IllegalStateException e) {
                audit.record(session.username(), "content.import.npc", "npc=" + form.get("npc_id"), "FAILED",
                        e.getMessage(), rid);
                notes.add("PNJ « " + form.get("npc_id") + " » non créé : l'action n'a pas pu être enregistrée "
                        + "(à créer depuis la page des PNJ).");
                continue;
            }
            audit.record(session.username(), "content.import.npc", "npc=" + form.get("npc_id") + " action=" + id,
                    "OK", "", rid);
            notes.add("PNJ logique « " + form.get("npc_id") + " » : création envoyée au serveur (action " + id.substring(0, 8)
                    + "). Le lier ensuite à un PNJ Citizens depuis la page des PNJ.");
        }
        return notes;
    }

    private void handleContentImport(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.CONTENT_IMPORT)) {
            forbidden(exchange, session, "/content/import");
            return;
        }

        String error = null;
        String pack = null;
        List<String> importNotes = List.of();
        ContentPackImport.Analysis analysis = null;
        List<ContentPackImport.WriteOutcome> applied = null;
        Map<String, ContentPackImport.Decision> decisions = new LinkedHashMap<>();

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            // Lecture bornée qui SIGNALE le dépassement : un pack tronqué en silence serait pire
            // qu'un refus, car il s'importerait amputé de ses derniers éléments.
            // Issue #211 : un fichier peut aussi être DÉPOSÉ (multipart) — même pipeline ensuite.
            Optional<Map<String, String>> body;
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data")) {
                byte[] raw = exchange.getRequestBody().readNBytes(4 * 1024 * 1024 + 1);
                var parsed = raw.length > 4 * 1024 * 1024 ? Optional.<com.lodygames.rpgquest.panel.http.Multipart.Form>empty()
                        : com.lodygames.rpgquest.panel.http.Multipart.parse(contentType, raw);
                body = parsed.map(f -> {
                    Map<String, String> fields = new LinkedHashMap<>(f.fields());
                    f.file("pack_file").filter(file -> file.data().length > 0).ifPresent(file ->
                            fields.put("pack", new String(file.data(), StandardCharsets.UTF_8)));
                    return fields;
                });
            } else {
                body = Http.formBody(exchange, 4 * 1024 * 1024);
            }
            if (body.isEmpty()) {
                error = "Requête trop volumineuse. Taille maximale d'un pack : "
                        + (ContentPackImport.MAX_BYTES / 1024) + " Kio.";
            } else {
                Map<String, String> form = body.get();
                if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
                    Http.html(exchange, 403, Layout.bare("CSRF",
                            "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                    return;
                }
                pack = form.getOrDefault("pack", "");
                for (Map.Entry<String, String> e : form.entrySet()) {
                    if (e.getKey().startsWith("decision.")) {
                        String key = e.getKey().substring("decision.".length());
                        try {
                            decisions.put(key, ContentPackImport.Decision.valueOf(e.getValue()));
                        } catch (IllegalArgumentException ignored) {
                            // Valeur d'arbitrage inconnue : traitée comme absente, donc la collision
                            // reste un conflit. Jamais interprétée comme « remplacer ».
                        }
                    }
                }
                String rid = UUID.randomUUID().toString().substring(0, 8);
                RefData ref = agentPages.referenceData(config.agents().defaultAgentId());
                analysis = ContentPackImport.analyze(pack, contentWorkspace, ref, decisions);
                boolean confirm = "true".equals(form.get("confirm"));
                audit.record(session.username(), confirm ? "content.import.confirm" : "content.import.analyze",
                        "bytes=" + pack.getBytes(StandardCharsets.UTF_8).length
                                + " elements=" + analysis.elements().size()
                                + " write=" + analysis.writing()
                                + " conflicts=" + analysis.conflicts()
                                + " invalid=" + analysis.invalid()
                                + " decisions=" + decisions.size(),
                        analysis.importable() ? "OK" : "BLOCKED",
                        analysis.readable() ? "analyse" : "pack refusé", rid);
                if (confirm) {
                    if (!analysis.importable()) {
                        error = "Import refusé : l'analyse n'est pas confirmable en l'état.";
                    } else {
                        applied = ContentPackImport.apply(analysis, contentWorkspace);
                        // Issue #211 : les PNJ logiques du pack, créés sur le serveur par l'action
                        // existante — seulement si toute la source a été écrite (aucun ensemble partiel).
                        boolean allWritten = applied.stream().allMatch(o -> o.result() == null || o.result().ok());
                        if (allWritten) {
                            importNotes = enqueueImportedNpcs(session, ContentPackImport.npcCreations(analysis, pack), rid);
                        }
                        for (ContentPackImport.WriteOutcome o : applied) {
                            if (o.result() != null) {
                                audit.record(session.username(), "content.import.write",
                                        o.element().key() + " status=" + o.element().status(),
                                        o.result().ok() ? "OK" : "FAILED",
                                        o.result().ok() ? o.result().repoPath() : o.result().message(), rid);
                            }
                        }
                        // Après écriture, on réanalyse pour que la page montre l'état RÉEL de la
                        // source (les éléments écrits deviennent « inchangé »), au lieu de réafficher
                        // l'intention d'écrire.
                        analysis = ContentPackImport.analyze(pack, contentWorkspace, ref, decisions);
                    }
                }
            }
        }

        String body = ContentImportPages.render(contentWorkspace, pack, analysis, applied, decisions, error,
                importNotes);
        Http.html(exchange, 200, renderPage("Importer un content pack", session, "/content/import", body,
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
     * Le modèle d'histoire (issue #211) : sans paramètre, l'archive complète ; avec {@code file=},
     * un seul fichier de la liste blanche {@link StoryKit#FILES}. Lecture pure, comme le contrat ;
     * ouvert à qui peut importer OU exporter, puisque c'est l'outil de la page d'import.
     */
    private void handleStoryKit(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.CONTENT_IMPORT)
                && !permissions.can(session.effective(), Permission.CONTENT_EXPORT)) {
            forbidden(exchange, session, "/content/import");
            return;
        }
        String name = Http.query(exchange).getOrDefault("file", "").trim();
        String rid = UUID.randomUUID().toString().substring(0, 8);
        if (name.isEmpty()) {
            byte[] zip = StoryKit.zip();
            audit.record(session.username(), "content.storykit.download", "bytes=" + zip.length, "OK",
                    StoryKit.ZIP_NAME, rid);
            Http.attachment(exchange, StoryKit.ZIP_NAME, "application/zip", zip);
            return;
        }
        Optional<StoryKit.KitFile> file = StoryKit.file(name);
        if (file.isEmpty()) {
            Http.html(exchange, 404, Layout.bare("Introuvable",
                    "<h1>Fichier inconnu</h1><p class=\"muted\">Ce fichier ne fait pas partie du modèle "
                            + "d'histoire.</p>"));
            return;
        }
        byte[] bytes = StoryKit.content(name).orElseThrow().getBytes(StandardCharsets.UTF_8);
        audit.record(session.username(), "content.storykit.download", "bytes=" + bytes.length, "OK", name, rid);
        Http.attachment(exchange, name, file.get().contentType(), bytes);
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
     * Suppression d'une quête, d'une story (#194) ou d'un dialogue (#248).
     *
     * <p>{@code GET} affiche l'aperçu des conséquences ; {@code POST} applique, et seulement si
     * l'identifiant a été <strong>retapé</strong> et que la source n'a pas changé depuis l'aperçu.
     * Après une suppression source réussie, la suppression de la copie serveur est enfilée comme
     * action agent — sans quoi le contenu resterait chargé et donnerait l'impression de
     * réapparaître.</p>
     *
     * <p>#248 : le plan prend en compte les dialogues <strong>réellement chargés</strong> par le
     * serveur (dernier {@code dialogue.list}, s'il est assez récent), y compris pour une quête. Le
     * serveur revérifie de son côté au moment d'agir.</p>
     */
    private void handleContentDelete(HttpExchange exchange, String kind) throws IOException {
        String base = switch (kind) {
            case "quests" -> "/quests";
            case "dialogues" -> "/dialogues";
            default -> "/stories";
        };
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
        String expectedSha = null;
        String ackValue = null;
        if (post) {
            Map<String, String> form = Http.formBody(exchange);
            if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
                Http.html(exchange, 403, Layout.bare("CSRF",
                        "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                return;
            }
            slug = form.getOrDefault("slug", "").trim();
            typed = form.getOrDefault(ContentDeletionPages.CONFIRM_FIELD, "");
            expectedSha = form.get(ContentDeletionPages.EXPECTED_SHA_FIELD);
            ackValue = form.get(ContentDeletionPages.ACK_FIELD);
        } else {
            slug = Http.query(exchange).getOrDefault("slug", "").trim();
        }

        var inventory = agentPages.runtimeDialogueInventory(agentId);
        Instant now = Instant.now();
        boolean runtimePresent = "dialogues".equals(kind)
                ? inventory.find(slug).isPresent()
                : agentPages.runtimeKnowsContent(agentId, kind, slug);
        // #194 : sans relevé, « le serveur ne connaît pas ce contenu » serait une affirmation
        // fausse — on ne lui a jamais demandé. L'aperçu doit dire la différence.
        boolean listingAvailable = "dialogues".equals(kind)
                ? inventory.available()
                : agentPages.runtimeListingAvailable(agentId, kind);
        String title = switch (kind) {
            case "quests" -> "Supprimer une quête";
            case "dialogues" -> "Supprimer un dialogue";
            default -> "Supprimer une story";
        };
        // Le plan est TOUJOURS recalculé, au GET comme au POST : la décision n'est jamais prise sur
        // ce que le formulaire prétend.
        var plan = contentDeletion.plan(kind, slug, runtimePresent, listingAvailable, inventory, now);
        String refresh = dialogueRefreshForm(session, agentId, base);

        boolean shortDialogue = "dialogues".equals(kind);
        DialoguePages.Ctx dctx = shortDialogue ? agentPages.dialogueCtx(agentId) : null;
        String dTitle = shortDialogue ? DialoguePages.title(plan.plainId(),
                contentWorkspace.read("dialogues", plan.plainId())
                        .map(f -> com.lodygames.rpgquest.panel.content.DialogueYaml.read(f.text()).draft()).orElse(null),
                dctx.users(plan.plainId())) : "";
        java.util.function.UnaryOperator<String> npcName = id -> dctx == null ? id
                : dctx.npcs().stream().filter(n -> n.id().equals(id)).map(DialoguePages.Npc::name).findFirst().orElse(id);
        if (!post) {
            Http.html(exchange, 200, renderPage(title, session, base, shortDialogue
                    ? contentDeletion.dialoguePreview(plan, dTitle, npcName, session.csrfToken(), null, refresh)
                    : contentDeletion.preview(plan, session.csrfToken(), null, refresh)));
            return;
        }

        String refusal = null;
        if (shortDialogue && !"1".equals(ackValue)) {
            refusal = "Cochez « Je veux supprimer ce dialogue » pour confirmer. Rien n'a été supprimé.";
        } else if (!ContentDeletionPages.confirmationMatches(plan, typed)) {
            refusal = "Confirmation incorrecte : il faut retaper exactement l'identifiant. "
                    + "Aucune modification n'a été faite.";
        } else if (plan.sourcePresent() && !java.util.Objects.equals(
                expectedSha == null ? "" : expectedSha, plan.sourceSha())) {
            // Course entre édition et suppression : on ne supprime pas ce qu'on n'a pas vu.
            refusal = "Le fichier source a changé depuis l'affichage de cet aperçu : relire les "
                    + "conséquences ci-dessous, puis confirmer à nouveau. Aucune modification n'a "
                    + "été faite.";
        }
        if (refusal != null) {
            Http.html(exchange, 200, renderPage(title, session, base, shortDialogue
                    ? contentDeletion.dialoguePreview(plan, dTitle, npcName, session.csrfToken(), refusal, refresh)
                    : contentDeletion.preview(plan, session.csrfToken(), refusal, refresh)));
            return;
        }

        String rid = UUID.randomUUID().toString().substring(0, 8);
        var result = contentDeletion.apply(plan, typed);
        boolean queued = false;
        String runtimeActionId = null;
        if (result != null && result.ok() && plan.runtimePresent()) {
            // Double clic, rechargement : une suppression serveur déjà en file n'est pas doublée.
            runtimeActionId = openContentDeletion(agentId, kind, plan.plainId()).orElse(null);
            if (runtimeActionId == null) {
                runtimeActionId = agentStore.createAction(agentId, "content.definition.delete",
                        Map.of("kind", kind, "id", plan.plainId()), session.username());
            }
            queued = true;
        }
        audit.record(session.username(), kind + ".content.delete", base + "/" + slug,
                result != null && result.ok() ? "OK" : "REFUSED",
                result == null ? "confirmation invalide"
                        : result.code() + (runtimeActionId == null ? "" : " action=" + runtimeActionId),
                rid);
        LOG.log(System.Logger.Level.INFO, "event=content_delete rid=" + rid + " kind=" + kind
                + " id=" + plan.plainId() + " ok=" + (result != null && result.ok())
                + " runtime_queued=" + queued + " by=" + session.username());

        Http.html(exchange, 200, renderPage(title, session, base, shortDialogue && result != null
                ? contentDeletion.dialogueResult(dTitle, result, queued, runtimeActionId)
                : contentDeletion.result(kind, plan.plainId(), result, queued, runtimeActionId)));
    }

    /** Une suppression serveur de ce contenu déjà en file (PENDING / DELIVERED), s'il y en a une. */
    private Optional<String> openContentDeletion(String agentId, String kind, String plainId) {
        return agentStore.recentActions(agentId, 200).stream()
                .filter(a -> "content.definition.delete".equals(a.type()))
                .filter(a -> !a.status().terminal())
                .filter(a -> kind.equals(a.params().get("kind"))
                        && plainId.equals(a.params().get("id")))
                .map(AgentActionRow::id)
                .findFirst();
    }

    /** Le bouton qui rafraîchit le relevé des dialogues, proposé quand la vérification est incomplète. */
    private String dialogueRefreshForm(Session session, String agentId, String base) {
        var spec = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.spec("dialogue.list");
        if (spec.isEmpty() || !hasAllPermissions(session, spec.get())) {
            return "";
        }
        return "<form method=\"post\" action=\"/agents/action\" class=\"actform\">"
                + "<input type=\"hidden\" name=\"_csrf\" value=\"" + Http.esc(session.csrfToken()) + "\">"
                + "<input type=\"hidden\" name=\"agent\" value=\"" + Http.esc(agentId) + "\">"
                + "<input type=\"hidden\" name=\"type\" value=\"dialogue.list\">"
                + "<input type=\"hidden\" name=\"return\" value=\"" + Http.esc(base) + "\">"
                + "<button class=\"btn btn-sm btn-outline-primary\" type=\"submit\">"
                + Icons.icon("refresh") + "Rafraîchir le relevé des dialogues</button>"
                + "<p class=\"field-help\">Retour au catalogue ; rouvrir ensuite cet aperçu.</p>"
                + "</form>";
    }

    // ---- Suppression d'un PNJ (issue #226) -----------------------------------------------

    /**
     * {@code /npcs/delete} : aperçu des dépendances (GET), puis <strong>une</strong> opération
     * choisie et confirmée (POST).
     *
     * <p><strong>Trois vérifications indépendantes avant la moindre action.</strong> Le rôle doit
     * porter {@link Permission#NPC_DELETE} — et {@link Permission#NPC_SPAWN_WRITE} en plus pour
     * détruire une entité Citizens, parce que c'est l'inverse exact de sa création. Le plan doit
     * proposer l'opération demandée : une URL forgée ne contourne donc pas un blocage affiché.
     * Et la confirmation doit être exacte, identifiant Citizens compris.</p>
     *
     * <p>Rien n'est écrit ici : les opérations deviennent des actions agent whitelistées, que le
     * serveur exécute après avoir <strong>revérifié</strong> les dépendances de son côté. Le panel
     * ne touche ni au disque du serveur, ni au registre Citizens.</p>
     */
    private void handleNpcDelete(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.NPC_DELETE)) {
            forbidden(exchange, session, "/npcs");
            return;
        }
        boolean canDeleteCitizens = permissions.can(session.effective(), Permission.NPC_SPAWN_WRITE);

        String agentId = config.agents().defaultAgentId();
        boolean post = "POST".equalsIgnoreCase(exchange.getRequestMethod());
        String rawNpc;
        String typedId = null;
        String typedCitizens = null;
        String rawOp = null;
        if (post) {
            Map<String, String> form = Http.formBody(exchange);
            if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
                Http.html(exchange, 403, Layout.bare("CSRF",
                        "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
                return;
            }
            rawNpc = form.getOrDefault("npc", "").trim();
            rawOp = form.get("op");
            typedId = form.get(NpcDeletionPages.CONFIRM_FIELD);
            typedCitizens = form.get(NpcDeletionPages.CONFIRM_CITIZENS_FIELD);
        } else {
            rawNpc = Http.query(exchange).getOrDefault("npc", "").trim();
        }

        // Le plan est TOUJOURS recalculé sur le dernier relevé, y compris au POST : la décision
        // n'est jamais prise sur ce que le formulaire prétend, mais sur ce que le catalogue dit.
        var plan = com.lodygames.rpgquest.panel.npc.NpcDeletionAnalyzer.analyze(
                agentPages.npcDirectory(agentId), rawNpc);

        if (!post) {
            Http.html(exchange, 200, renderPage("Supprimer un PNJ", session, "/npcs",
                    NpcDeletionPages.preview(plan, session.csrfToken(), null, canDeleteCitizens, deletionContext(agentId, plan)),
                    Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
            return;
        }

        var op = com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op.of(rawOp).orElse(null);
        String refusal = refuseNpcDeletion(plan, op, typedId, typedCitizens, canDeleteCitizens);
        if (refusal != null) {
            audit.record(session.username(), "npc.delete", "npc=" + plan.npcId() + " op=" + rawOp,
                    "REFUSED", refusal, UUID.randomUUID().toString().substring(0, 8));
            Http.html(exchange, 200, renderPage("Supprimer un PNJ", session, "/npcs",
                    NpcDeletionPages.preview(plan, session.csrfToken(), refusal, canDeleteCitizens, deletionContext(agentId, plan)),
                    Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
            return;
        }

        String rid = UUID.randomUUID().toString().substring(0, 8);
        List<String> queued = queueNpcDeletion(agentId, plan, op, session.username());
        audit.record(session.username(), "npc.delete",
                "npc=" + plan.npcId() + " op=" + op.name()
                        + " citizens=" + plan.citizensNumericId(),
                "OK", String.join(" + ", queued), rid);
        LOG.log(System.Logger.Level.INFO, "event=npc_delete rid=" + rid + " npc=" + plan.npcId()
                + " op=" + op.name() + " citizens=" + plan.citizensNumericId()
                + " actions=" + String.join(",", queued) + " by=" + session.username());

        Http.html(exchange, 200, renderPage("Supprimer un PNJ", session, "/npcs",
                NpcDeletionPages.result(plan, op, queued, deletionContext(agentId, plan)),
                Layout.Shell.of(config.defaultTarget().label(), null, session.username())));
    }

    /** Partage du dialogue du PNJ et son fichier source, pour la page de suppression (#325). */
    private NpcDeletionPages.Context deletionContext(String agentId,
                                                     com.lodygames.rpgquest.panel.npc.NpcDeletionPlan plan) {
        var npc = plan.npc().orElse(null);
        if (npc == null || !npc.hasLinkedDialogue()) {
            return NpcDeletionPages.Context.none();
        }
        int holders = agentPages.npcDirectory(agentId).npcsLinkedToDialogue(npc.linkedDialogueId()).size();
        String key = npc.linkedDialogueKey();
        String slug = contentWorkspace.read("dialogues", key).map(ContentWorkspace.ContentFile::slug).orElse("");
        return new NpcDeletionPages.Context(holders, slug);
    }

    /**
     * Pourquoi la suppression est refusée, ou {@code null} si elle peut partir.
     *
     * <p>L'ordre compte : on vérifie que l'opération <em>existe et est proposée par le plan</em>
     * avant de regarder la confirmation. Une URL forgée vers une opération bloquée est donc refusée
     * pour la bonne raison, et le message le dit.</p>
     */
    private String refuseNpcDeletion(com.lodygames.rpgquest.panel.npc.NpcDeletionPlan plan,
                                     com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op op,
                                     String typedId, String typedCitizens,
                                     boolean canDeleteCitizens) {
        if (op == null) {
            return "Opération inconnue. Rien n'a été fait.";
        }
        var proposed = plan.operation(op).orElse(null);
        if (proposed == null || !proposed.available()) {
            return "Cette opération n'est pas disponible pour ce PNJ : "
                    + (proposed == null ? "elle ne s'applique pas ici."
                            : String.join(" ", proposed.blockers()))
                    + " Rien n'a été fait.";
        }
        if (op.touchesCitizens() && op != com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op
                .UNLINK_CITIZENS && !canDeleteCitizens) {
            return "Détruire un PNJ Citizens exige le droit d'apparition de PNJ, que votre rôle "
                    + "n'a pas. Rien n'a été fait.";
        }
        if (!NpcDeletionPages.confirmationMatches(plan, op, typedId, typedCitizens)) {
            return "Confirmation incorrecte : il faut retaper exactement l'identifiant du PNJ"
                    + (op.touchesCitizens() && plan.citizensNumericId() != null
                            ? " et l'identifiant numérique Citizens" : "")
                    + ". Rien n'a été fait.";
        }
        return null;
    }

    /**
     * Met en file les actions agent de l'opération choisie.
     *
     * <p>Le nettoyage complet en enchaîne deux, Citizens d'abord. L'ordre est celui du journal, pas
     * une dépendance : chaque action vérifie ses propres préconditions côté serveur et reste
     * correcte même exécutée seule — détruire l'entité retire sa liaison sans avoir besoin de la
     * définition, et supprimer la définition n'a pas besoin que la liaison ait disparu.</p>
     */
    private List<String> queueNpcDeletion(String agentId,
                                          com.lodygames.rpgquest.panel.npc.NpcDeletionPlan plan,
                                          com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op op,
                                          String username) {
        List<String> queued = new ArrayList<>();
        Integer citizens = plan.citizensNumericId();
        // L'identifiant envoyé au serveur est celui que le SERVEUR a donné, pas celui que l'URL
        // portait : le plan a résolu la fiche sans tenir compte de la casse, et c'est son id réel
        // qui nomme le fichier à supprimer.
        String npcId = plan.npc().map(com.lodygames.rpgquest.panel.npc.NpcView::id)
                .orElse(plan.npcId());
        String dialogue = plan.npc()
                .map(com.lodygames.rpgquest.panel.npc.NpcView::declaredDialogueId).orElse("");

        if (op == com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op.UNLINK_CITIZENS) {
            agentStore.createAction(agentId, "npc.citizens.unlink",
                    Map.of("npc_id", npcId, "citizens_id", String.valueOf(citizens)), username);
            queued.add("npc.citizens.unlink");
            return queued;
        }
        if (op == com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op.DELETE_CITIZENS
                || (op == com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op.FULL_CLEANUP
                        && citizens != null)) {
            agentStore.createAction(agentId, "npc.citizens.delete",
                    Map.of("npc_id", npcId, "citizens_id", String.valueOf(citizens)), username);
            queued.add("npc.citizens.delete");
        }
        if (op == com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op.DEFINITION_ONLY
                || op == com.lodygames.rpgquest.panel.npc.NpcDeletionPlan.Op.FULL_CLEANUP) {
            Map<String, String> params = new java.util.LinkedHashMap<>();
            params.put("npc_id", npcId);
            if (!dialogue.isEmpty()) {
                // Ce que l'écran croyait vrai : le serveur refuse si cela ne correspond plus.
                params.put("expect_dialogue", dialogue);
            }
            agentStore.createAction(agentId, "npc.definition.delete", params, username);
            queued.add("npc.definition.delete");
        }
        return queued;
    }

    // ---- PNJ : parcours simple (issue #325) ----------------------------------------------

    /** Session + POST + CSRF + agent connu, ou {@code null} après avoir répondu. */
    private record NpcPost(Session session, Map<String, String> form, String agentId) {
    }

    private NpcPost npcPost(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return null;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.redirect(exchange, "/npcs");
            return null;
        }
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(maybe.get().csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return null;
        }
        String agentId = form.getOrDefault("agent", "").trim();
        if (agentId.isEmpty()) {
            agentId = config.agents().defaultAgentId();
        }
        if (agentId == null || agentRegistry.byId(agentId).isEmpty()) {
            Http.redirect(exchange, withError("/npcs", "", null, "Serveur de jeu inconnu."));
            return null;
        }
        return new NpcPost(maybe.get(), form, agentId);
    }

    /** Vrai si la session détient toutes les permissions de ces types d'action. */
    private boolean mayQueue(Session session, String... types) {
        for (String type : types) {
            var spec = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.spec(type);
            if (spec.isEmpty() || !hasAllPermissions(session, spec.get())) {
                return false;
            }
        }
        return true;
    }

    /**
     * « Actualiser les PNJ » : relit le catalogue RPGQuest ET les personnages du jeu, en une fois.
     * Un relevé déjà en attente n'est pas réempilé.
     */
    private void handleNpcRefresh(HttpExchange exchange) throws IOException {
        NpcPost post = npcPost(exchange);
        if (post == null) {
            return;
        }
        String back = "/npcs/diagnostic".equals(post.form().get("return")) ? "/npcs/diagnostic" : "/npcs";
        if (!permissions.can(post.session().effective(), Permission.NPC_READ)) {
            forbidden(exchange, post.session(), back);
            return;
        }
        String toast = "";
        for (String type : List.of("npc.list", "npc.citizens.list")) {
            if (!agentStore.hasOpenActionOfType(post.agentId(), type)) {
                String id = agentStore.createAction(post.agentId(), type, Map.of(), post.session().username());
                toast = toast.isEmpty() ? id : toast;
            }
        }
        Http.redirect(exchange, appendContext(back, post.agentId(), null)
                + (toast.isEmpty() ? "" : "&toast=" + enc(toast)));
    }

    /**
     * « Créer un PNJ » (et « Reprendre » une création inachevée).
     *
     * <p>Trois étapes, envoyées dans l'ordre et exécutées dans cet ordre par l'agent : le dialogue
     * initial est écrit dans la source puis publié sur le serveur, puis le personnage est créé avec
     * sa définition, sa liaison et son apparence. Rien n'est envoyé si la demande est invalide.</p>
     *
     * <p><strong>Anti-doublon.</strong> Chaque affichage du formulaire porte un jeton unique, recopié
     * sur l'action de création : un double clic ou un renvoi retombe sur la création déjà demandée.
     * Le plugin refuse en plus tout identifiant déjà défini.</p>
     *
     * <p><strong>Reprise.</strong> « Reprendre » rejoue seulement ce qui manque, avec les paramètres
     * de la tentative précédente et le même identifiant : un dialogue déjà préparé n'est pas réécrit,
     * un dialogue déjà publié n'est pas republié.</p>
     */
    private void handleNpcCreate(HttpExchange exchange) throws IOException {
        NpcPost post = npcPost(exchange);
        if (post == null) {
            return;
        }
        Session session = post.session();
        Map<String, String> form = post.form();
        String agentId = post.agentId();
        String rid = UUID.randomUUID().toString().substring(0, 8);
        if (!mayQueue(session, "npc.citizens.provision", "content.publish")
                || !permissions.can(session.effective(), Permission.DIALOGUE_WRITE)) {
            forbidden(exchange, session, "/npcs");
            return;
        }
        String token = form.getOrDefault("request_token", "").trim();
        var already = agentPages.npcCreationByToken(agentId, token);
        if (already.isPresent()) {
            Http.redirect(exchange, appendContext("/npcs", agentId, null) + "&focus="
                    + enc(already.get().params().getOrDefault("npc_id", "")) + "&toast=" + enc(already.get().id()));
            return;
        }

        Map<String, String> request = new java.util.LinkedHashMap<>();
        String npcId;
        String name;
        String resume = form.getOrDefault("resume", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!resume.isEmpty()) {
            var last = agentPages.npcLastProvision(agentId, resume);
            if (last.isEmpty()) {
                Http.redirect(exchange, withError("/npcs", agentId, null,
                        "Rien à reprendre pour ce PNJ."));
                return;
            }
            request.putAll(last.get().params());
            npcId = resume;
            name = request.getOrDefault("display_name", resume);
        } else {
            name = com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.visibleName(form.get("display_name"));
            if (name.isEmpty() || name.length() > com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.MAX_NAME) {
                Http.redirect(exchange, withError("/npcs", agentId, null,
                        "Donnez un nom de 1 à " + com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.MAX_NAME
                                + " caractères."));
                return;
            }
            if (!agentPages.npcCatalogLoaded(agentId)) {
                Http.redirect(exchange, withError("/npcs", agentId, null,
                        "Cliquez d'abord sur « Actualiser les PNJ », puis recommencez."));
                return;
            }
            var free = com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.freeId(name, agentPages.npcTakenIds(agentId));
            if (free.isEmpty()) {
                Http.redirect(exchange, withError("/npcs", agentId, null,
                        "Ce nom ne convient pas : utilisez au moins une lettre ou un chiffre."));
                return;
            }
            npcId = free.get();
            request.put("display_name", name);
            String skin = form.getOrDefault("skin_source", "default").trim();
            if ("url".equals(skin)) {
                request.put("skin_source", "url");
                request.put("skin_url", form.getOrDefault("skin_url", "").trim());
                String problem = com.lodygames.rpgquest.panel.agent.AgentActionCatalog
                        .mineSkinProblem(request.get("skin_url"));
                if (problem != null) {
                    Http.redirect(exchange, withError("/npcs", agentId, null, problem));
                    return;
                }
            } else if ("player".equals(skin)) {
                request.put("skin_source", "player");
                request.put("skin_player", form.getOrDefault("skin_player", "").trim());
            }
            if ("here".equals(form.getOrDefault("place", "auto").trim())) {
                for (String k : List.of("world", "x", "y", "z")) {
                    request.put(k, form.getOrDefault(k, "").trim());
                }
            }
        }
        request.put("npc_id", npcId);
        request.put("dialogue_id", com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.dialogueRef(npcId));
        request.put("request_token", token.isEmpty() ? UUID.randomUUID().toString().replace("-", "") : token);
        request.put("confirm", "true");
        var provision = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("npc.citizens.provision", request);
        if (!provision.valid()) {
            Http.redirect(exchange, withError("/npcs", agentId, null, provision.error()));
            return;
        }

        // 1) + 2) Le dialogue initial, propre à ce PNJ : écrit dans la source s'il n'y est pas, puis
        // publié — sauf si une publication a déjà réussi (reprise).
        List<String> queued = new ArrayList<>();
        if (agentPages.lastPublishOf(agentId, "dialogues", npcId).isEmpty()) {
            InitialDialogue dialogue = queueInitialDialogue(agentId, npcId, name, session.username());
            if (dialogue.actionId() == null) {
                audit.record(session.username(), "npc.create", "npc=" + npcId, "FAILED", dialogue.error(), rid);
                Http.redirect(exchange, withError("/npcs", agentId, null,
                        "Le dialogue de départ n'a pas pu être préparé (" + dialogue.error()
                                + "). Le PNJ n'a pas été créé."));
                return;
            }
            queued.add(dialogue.actionId());
        }
        // 3) Le personnage, sa définition, sa liaison et son apparence.
        String provisionId = agentStore.createAction(agentId, "npc.citizens.provision", provision.params(),
                session.username());
        queued.add(provisionId);
        audit.record(session.username(), "npc.create", "agent=" + agentId + " npc=" + npcId,
                "PENDING", (resume.isEmpty() ? "création" : "reprise") + " · actions=" + String.join(",", queued), rid);
        LOG.log(System.Logger.Level.INFO, "event=npc_create rid=" + rid + " npc=" + npcId + " resume="
                + !resume.isEmpty() + " actions=" + String.join(",", queued) + " by=" + session.username());
        Http.redirect(exchange, appendContext("/npcs", agentId, null) + "&focus=" + enc(npcId)
                + "&toast=" + enc(provisionId));
    }

    /** Issue de la préparation du dialogue initial : l'action de publication, ou la raison de l'échec. */
    private record InitialDialogue(String actionId, String error) {
    }

    /**
     * Écrit le dialogue initial « Salut ! » dans la source s'il n'y est pas, puis demande sa
     * publication sur le serveur.
     */
    private InitialDialogue queueInitialDialogue(String agentId, String npcId, String name, String username) {
        Optional<ContentWorkspace.ContentFile> source = contentWorkspace.read("dialogues", npcId);
        if (source.isEmpty()) {
            ContentWorkspace.WriteResult wr = contentWorkspace.write("dialogues", npcId,
                    com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.initialDialogueYaml(npcId, name), "");
            if (!wr.ok()) {
                return new InitialDialogue(null, wr.message());
            }
            source = contentWorkspace.read("dialogues", npcId);
        }
        if (source.isEmpty()) {
            return new InitialDialogue(null, "le dialogue écrit est illisible.");
        }
        Map<String, String> publishForm = new java.util.LinkedHashMap<>();
        publishForm.put("kind", "dialogues");
        publishForm.put("id", npcId);
        publishForm.put("expected_dev_sha", "");
        publishForm.put("expected_source_sha", source.get().sha256());
        publishForm.put("confirm", "true");
        var publish = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("content.publish", publishForm);
        if (!publish.valid()) {
            return new InitialDialogue(null, publish.error());
        }
        PublishEnrichment enriched = enrichPublish(publish.params());
        return enriched.error() != null ? new InitialDialogue(null, enriched.error())
                : new InitialDialogue(agentStore.createAction(agentId, "content.publish", enriched.params(), username), null);
    }

    /**
     * « Rattacher à RPGQuest » : un personnage du jeu sans fiche reçoit ce qui lui manque, sans être
     * recréé. L'identifiant vient de son nom en jeu — c'est ce qui retrouve un dialogue
     * {@code rpgquest:<id>} ou des quêtes déjà écrits pour lui, qui sont donc conservés. Une fiche
     * existante et libre de ce nom est simplement liée ; une fiche déjà liée à un autre personnage
     * fait choisir un identifiant voisin.
     */
    private void handleNpcAttach(HttpExchange exchange) throws IOException {
        NpcPost post = npcPost(exchange);
        if (post == null) {
            return;
        }
        Session session = post.session();
        String agentId = post.agentId();
        String rid = UUID.randomUUID().toString().substring(0, 8);
        if (!mayQueue(session, "npc.definition.create", "npc.citizens.link")) {
            forbidden(exchange, session, "/npcs");
            return;
        }
        String digits = post.form().getOrDefault("citizens_id", "").replaceAll("[^0-9]", "");
        var row = digits.isEmpty() ? Optional.<Map<String, Object>>empty()
                : agentPages.citizensRowByNumeric(agentId, digits);
        if (row.isEmpty()) {
            Http.redirect(exchange, withError("/npcs", agentId, null,
                    "Ce personnage n'est plus dans la liste : cliquez sur « Actualiser les PNJ »."));
            return;
        }
        Object linked = row.get().get("linkedNpcId");
        if (linked != null && !String.valueOf(linked).isBlank() && !"null".equals(String.valueOf(linked))) {
            Http.redirect(exchange, appendContext("/npcs", agentId, null) + "&focus=" + enc(String.valueOf(linked))
                    + "&ok=" + enc("Ce personnage est déjà rattaché."));
            return;
        }
        // Un rattachement déjà en route pour ce personnage n'est pas réempilé (double clic).
        boolean pending = agentStore.recentActions(agentId, 50).stream().anyMatch(a -> !a.status().terminal()
                && "npc.citizens.link".equals(a.type()) && digits.equals(a.params().get("citizens_id")));
        if (pending) {
            Http.redirect(exchange, appendContext("/npcs", agentId, null)
                    + "&ok=" + enc("Le rattachement de ce personnage est déjà en cours."));
            return;
        }
        String rawName = String.valueOf(row.get().getOrDefault("name", ""));
        String name = MiniText.plain("null".equals(rawName) ? "" : rawName).trim();
        if (name.isEmpty()) {
            name = "PNJ " + digits;
        }
        var directory = agentPages.npcDirectory(agentId);
        String npcId = AgentPages.citizensNameToId(name, digits);
        var existing = directory.find(npcId);
        boolean definitionExists = existing.map(com.lodygames.rpgquest.panel.npc.NpcView::definitionPresent).orElse(false);
        boolean boundElsewhere = existing.map(com.lodygames.rpgquest.panel.npc.NpcView::citizensBound).orElse(false);
        if (boundElsewhere) {
            java.util.Set<String> taken = new java.util.HashSet<>(agentPages.npcTakenIds(agentId));
            npcId = com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.freeId(name, taken)
                    .orElse("citizens_" + digits);
            definitionExists = false;
        }
        List<String> queued = new ArrayList<>();
        if (!definitionExists) {
            Map<String, String> def = new java.util.LinkedHashMap<>();
            def.put("npc_id", npcId);
            def.put("display_name", name);
            def.put("enabled", "true");
            def.put("confirm", "true");
            // Un dialogue qui porte déjà ce nom est le sien : on le déclare, on n'en crée pas d'autre.
            // Sinon on lui prépare le même « Salut ! » qu'à un PNJ neuf, s'il en a le droit.
            boolean hasDialogue = agentPages.dialogueKnown(agentId, npcId);
            if (!hasDialogue && mayQueue(session, "content.publish")
                    && permissions.can(session.effective(), Permission.DIALOGUE_WRITE)) {
                InitialDialogue dialogue = queueInitialDialogue(agentId, npcId, name, session.username());
                if (dialogue.actionId() != null) {
                    queued.add(dialogue.actionId());
                    hasDialogue = true;
                }
            }
            if (hasDialogue) {
                def.put("dialogue_id", com.lodygames.rpgquest.panel.npc.NpcCreationPlanner.dialogueRef(npcId));
            }
            var v = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("npc.definition.create", def);
            if (!v.valid()) {
                Http.redirect(exchange, withError("/npcs", agentId, null, v.error()));
                return;
            }
            queued.add(agentStore.createAction(agentId, "npc.definition.create", v.params(), session.username()));
        }
        Map<String, String> link = new java.util.LinkedHashMap<>();
        link.put("npc_id", npcId);
        link.put("citizens_id", digits);
        link.put("confirm", "true");
        var lv = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("npc.citizens.link", link);
        if (!lv.valid()) {
            Http.redirect(exchange, withError("/npcs", agentId, null, lv.error()));
            return;
        }
        String linkId = agentStore.createAction(agentId, "npc.citizens.link", lv.params(), session.username());
        queued.add(linkId);
        audit.record(session.username(), "npc.attach", "agent=" + agentId + " npc=" + npcId + " citizens=" + digits,
                "PENDING", String.join(",", queued), rid);
        Http.redirect(exchange, appendContext("/npcs", agentId, null) + "&focus=" + enc(npcId) + "&toast=" + enc(linkId));
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
        root.put("badge", notifications.badgeCount(notifications.recent(), now, maybe.get().userId()));
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
            // Issue #293 : ouvrir la fiche d'une notification la marque lue pour CE compte, et elle seule.
            notifications.markRead(session.userId(), row.get().id());
            Http.html(exchange, 200, renderPage("Action", session, "/actions",
                    ActionsPages.detail(row.get(), now, planContext(session, row.get()),
                            ActionsPages.safeBack(Http.query(exchange).get("back")))));
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

    /**
     * Issue #256 — sur la fiche d'une action qui porte un plan, le chemin du retour au brouillon,
     * ou la reprise du plan sans appel à l'IA. Réservé à qui peut utiliser la génération.
     */
    private String planContext(Session session, AgentActionRow row) {
        if (!permissions.can(session.effective(), Permission.AI_USE)
                || BuildingPlanWorkbench.planOf(row).isEmpty()) {
            return "";
        }
        Optional<BuildingPlanDraft> owner = planDrafts.recoveredFrom(row.id())
                .or(() -> planDrafts.linkOf(row.id()).flatMap(l -> planDrafts.find(l.draftId())))
                .filter(d -> !d.archived());
        return WorkshopPages.actionContext(session, row, owner, planProjects.list(row.agentId()));
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
        agentPages.refreshCurrency(config.agents().defaultAgentId());
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
            result = "quests".equals(kind) ? contentEditor.questPost(ref, form) : contentEditor.storyPost(ref, form);
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
            Map<String, String> query = Http.query(exchange);
            boolean saved = "1".equals(query.get("saved"));
            result = "quests".equals(kind) ? contentEditor.questPage(ref, slug, saved, query.get("giver"))
                    : contentEditor.storyPage(ref, slug, saved);
        }

        if (result instanceof ContentEditorPages.Result.Redirect rd) {
            Http.redirect(exchange, rd.location());
            return;
        }
        String body = ((ContentEditorPages.Result.Html) result).body();
        String title = "quests".equals(kind) ? "Éditeur de quête" : "Éditeur de story";
        Http.html(exchange, 200, renderPage(title, session, base, body));
    }

    // ================================================================================
    //  Paramètres généraux (issue #335)
    // ================================================================================

    private void handleSettings(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.SETTINGS_READ)) {
            forbidden(exchange, session, "/settings");
            return;
        }
        Map<String, String> query = Http.query(exchange);
        String agentId = config.agents().defaultAgentId();
        Http.html(exchange, 200, renderPage("Paramètres généraux", session, "/settings", actionFeedback(query)
                + SettingsPages.page(session.csrfToken(), agentId == null ? "" : agentId, agentPages.settingsState(agentId),
                        permissions.can(session.effective(), Permission.SETTINGS_WRITE), query.getOrDefault("tab", ""))));
    }

    private static String settingsBack(Map<String, String> form) {
        String tab = form.getOrDefault("tab", "");
        return "/settings" + (tab.matches("[a-z]{1,20}") ? "?tab=" + tab : "");
    }

    private void handleSettingsRefresh(HttpExchange exchange) throws IOException {
        NpcPost post = dialoguePost(exchange, false);
        if (post == null) {
            return;
        }
        if (!permissions.can(post.session().effective(), Permission.SETTINGS_READ)) {
            forbidden(exchange, post.session(), "/settings");
            return;
        }
        String back = settingsBack(post.form());
        String id = agentStore.hasOpenActionOfType(post.agentId(), "settings.get") ? ""
                : agentStore.createAction(post.agentId(), "settings.get", Map.of(), post.session().username());
        Http.redirect(exchange, back + (id.isEmpty() ? "" : (back.contains("?") ? "&" : "?") + "toast=" + enc(id)));
    }

    /**
     * Enregistre les paramètres d'une rubrique. Validés ici puis par le plugin, qui écrit, relit et
     * rend les valeurs réelles. L'auteur part avec la demande (il est inscrit dans settings.yml), et
     * l'action est journalisée comme toute mutation.
     */
    private void handleSettingsSave(HttpExchange exchange) throws IOException {
        NpcPost post = dialoguePost(exchange, false);
        if (post == null) {
            return;
        }
        Session session = post.session();
        if (!permissions.can(session.effective(), Permission.SETTINGS_WRITE)) {
            forbidden(exchange, session, "/settings");
            return;
        }
        String back = settingsBack(post.form());
        String sep = back.contains("?") ? "&" : "?";
        if (agentStore.hasOpenActionOfType(post.agentId(), "settings.set")) {
            Http.redirect(exchange, back + sep + "err=" + enc("Un enregistrement est déjà en cours."));
            return;
        }
        Map<String, String> form = new java.util.LinkedHashMap<>();
        for (String key : com.lodygames.rpgquest.panel.agent.AgentActionCatalog.SETTINGS_KEYS) {
            if (post.form().containsKey(key)) {
                form.put(key, post.form().get(key));
            }
        }
        form.put("actor", session.username());
        var v = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("settings.set", form);
        if (!v.valid()) {
            Http.redirect(exchange, back + sep + "err=" + enc(v.error()));
            return;
        }
        String id = agentStore.createAction(post.agentId(), "settings.set", v.params(), session.username());
        audit.record(session.username(), "agent.action.create",
                "agent=" + post.agentId() + " type=settings.set action=" + id, "PENDING",
                "keys=" + String.join(",", form.keySet()), UUID.randomUUID().toString().substring(0, 8));
        Http.redirect(exchange, back + sep + "toast=" + enc(id));
    }

    // ================================================================================
    //  Dialogues, version simple (issue #332)
    // ================================================================================

    private DialoguePages.Rights dialogueRights(Session session) {
        return new DialoguePages.Rights(
                permissions.can(session.effective(), Permission.DIALOGUE_WRITE) && contentWorkspace.writable("dialogues"),
                permissions.can(session.effective(), Permission.CONTENT_PUBLISH),
                permissions.can(session.effective(), Permission.CONTENT_DELETE));
    }

    private void dialoguePage(HttpExchange exchange, Session session, String title, String body) throws IOException {
        Http.html(exchange, 200, renderPage(title, session, "/dialogues", body));
    }

    private void handleDialogueList(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.DIALOGUE_READ)) {
            forbidden(exchange, session, "/dialogues");
            return;
        }
        Map<String, String> query = Http.query(exchange);
        DialoguePages.Ctx ctx = agentPages.dialogueCtx(config.agents().defaultAgentId());
        dialoguePage(exchange, session, "Dialogues", actionFeedback(query)
                + DialoguePages.list(session.csrfToken(), ctx, dialogueRights(session), agentPages.dialogueRows(ctx)));
    }

    private void handleDialogueNew(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!dialogueRights(session).write()) {
            forbidden(exchange, session, "/dialogues");
            return;
        }
        Map<String, String> query = Http.query(exchange);
        DialoguePages.Ctx ctx = agentPages.dialogueCtx(config.agents().defaultAgentId());
        dialoguePage(exchange, session, "Créer un dialogue", DialoguePages.create(session.csrfToken(), ctx,
                query.getOrDefault("npc", ""), "", NpcCreationPlanner.GREETING, ""));
    }

    /** Lecture d'un POST de dialogue : session, jeton CSRF, droit d'écriture. {@code null} = déjà répondu. */
    private NpcPost dialoguePost(HttpExchange exchange, boolean needWrite) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return null;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.redirect(exchange, "/dialogues");
            return null;
        }
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(maybe.get().csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return null;
        }
        Session session = maybe.get();
        boolean allowed = needWrite ? dialogueRights(session).write()
                : permissions.can(session.effective(), Permission.DIALOGUE_READ);
        if (!allowed) {
            forbidden(exchange, session, "/dialogues");
            return null;
        }
        return new NpcPost(session, form, config.agents().defaultAgentId());
    }

    /**
     * « Créer un dialogue » : le fichier est écrit dans la source avec la première réplique et une
     * réponse « Au revoir ». S'il est pour un personnage qui n'a pas encore de dialogue, il prend son
     * identifiant (le jeu l'ouvre alors tout seul) ; sinon le personnage est rattaché au nouveau
     * dialogue par l'action existante {@code npc.definition.update}.
     */
    private void handleDialogueCreate(HttpExchange exchange) throws IOException {
        NpcPost post = dialoguePost(exchange, true);
        if (post == null) {
            return;
        }
        Map<String, String> form = post.form();
        DialoguePages.Ctx ctx = agentPages.dialogueCtx(post.agentId());
        String npcId = form.getOrDefault("npc", "").trim().toLowerCase(java.util.Locale.ROOT);
        String name = form.getOrDefault("name", "").trim();
        String line = form.getOrDefault("first_line", "").trim();
        DialoguePages.Npc npc = ctx.npcs().stream().filter(n -> n.id().equals(npcId)).findFirst().orElse(null);
        String error = null;
        if (!npcId.isEmpty() && npc == null) {
            error = "Ce personnage est inconnu : actualisez la liste des dialogues puis réessayez.";
        } else if (npc == null && name.isEmpty()) {
            error = "Choisissez un personnage ou donnez un nom au dialogue.";
        } else if (line.isEmpty()) {
            error = "Écrivez ce que dit le personnage en premier.";
        } else if (line.length() > 512) {
            error = "La première réplique est trop longue (512 caractères au plus).";
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        contentWorkspace.list("dialogues").forEach(f -> taken.add(f.slug()));
        taken.addAll(ctx.serverKeys());
        String slug = null;
        if (error == null) {
            boolean conventional = npc != null && (npc.dialogueKey().isEmpty() || npc.dialogueKey().equals(npc.id()))
                    && !taken.contains(npc.id());
            slug = conventional ? npc.id()
                    : NpcCreationPlanner.freeId(name.isEmpty() ? npc.id() + " dialogue" : name, taken).orElse(null);
            if (slug == null) {
                error = "Ce nom ne permet pas de créer un dialogue : ajoutez au moins une lettre ou un chiffre.";
            }
        }
        if (error != null) {
            dialoguePage(exchange, post.session(), "Créer un dialogue",
                    DialoguePages.create(post.session().csrfToken(), ctx, npcId, name, line, error));
            return;
        }
        String speaker = npc != null ? MiniText.plain(npc.name()) : name;
        com.lodygames.rpgquest.panel.content.DialogueDraft draft = new com.lodygames.rpgquest.panel.content.DialogueDraft();
        draft.id = slug;
        draft.start = "start";
        com.lodygames.rpgquest.panel.content.DialogueDraft.Node start = new com.lodygames.rpgquest.panel.content.DialogueDraft.Node("start");
        start.speaker = speaker;
        start.text = line;
        start.choices.add(new com.lodygames.rpgquest.panel.content.DialogueDraft.Choice(NpcCreationPlanner.FAREWELL, "", true));
        draft.nodes.add(start);
        ContentWorkspace.WriteResult wr = contentWorkspace.write("dialogues", slug,
                com.lodygames.rpgquest.panel.content.DialogueYaml.write(draft), "");
        if (!wr.ok()) {
            dialoguePage(exchange, post.session(), "Créer un dialogue", DialoguePages.create(post.session().csrfToken(),
                    ctx, npcId, name, line, "Le dialogue n'a pas pu être enregistré : " + wr.message()));
            return;
        }
        audit.record(post.session().username(), "dialogues.content.write", "/dialogues/edit/" + slug, "OK",
                "création", UUID.randomUUID().toString().substring(0, 8));
        String dest = "/dialogues/edit/" + slug + "?created=1";
        if (npc != null && !slug.equals(npc.id())) {
            String linked = queueDialogueLink(post.session(), post.agentId(), npc.id(), slug);
            dest += linked.startsWith("!") ? "&err=" + enc(linked.substring(1)) : "&toast=" + enc(linked);
        }
        Http.redirect(exchange, dest);
    }

    /**
     * Met le personnage sur ce dialogue ({@code npc.definition.update}).
     *
     * @return l'id de l'action, ou {@code "!"} suivi du motif si rien n'a été envoyé
     */
    private String queueDialogueLink(Session session, String agentId, String npcId, String slug) {
        if (!mayQueue(session, "npc.definition.update")) {
            return "!Vous n'avez pas le droit de modifier les personnages : demandez à un administrateur de "
                    + "lui donner ce dialogue.";
        }
        Optional<Map<String, String>> cur = agentPages.npcDefinitionFields(agentId, npcId);
        if (cur.isEmpty()) {
            return "!Ce personnage n'est plus dans la liste : actualisez, puis associez-le depuis le dialogue.";
        }
        Map<String, String> npcForm = new java.util.LinkedHashMap<>(cur.get());
        npcForm.put("npc_id", npcId);
        npcForm.put("dialogue_id", "rpgquest:" + slug);
        npcForm.put("confirm", "true");
        var v = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("npc.definition.update", npcForm);
        if (!v.valid()) {
            return "!" + v.error();
        }
        String id = agentStore.createAction(agentId, "npc.definition.update", v.params(), session.username());
        // Le personnage relu après la mise à jour : sa fiche et cette page le montrent sur le bon dialogue.
        if (!agentStore.hasOpenActionOfType(agentId, "npc.list")) {
            agentStore.createAction(agentId, "npc.list", Map.of(), session.username());
        }
        audit.record(session.username(), "agent.action.create",
                "agent=" + agentId + " type=npc.definition.update action=" + id, "PENDING",
                "dialogue=rpgquest:" + slug + " npc=" + npcId, UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private void handleDialogueLink(HttpExchange exchange) throws IOException {
        NpcPost post = dialoguePost(exchange, true);
        if (post == null) {
            return;
        }
        String slug = com.lodygames.rpgquest.panel.content.DialogueYaml.plainId(post.form().getOrDefault("slug", ""));
        String npc = post.form().getOrDefault("npc", "").trim().toLowerCase(java.util.Locale.ROOT);
        String back = "/dialogues/edit/" + slug;
        if (slug.isEmpty() || npc.isEmpty() || !contentWorkspace.exists("dialogues", slug)) {
            Http.redirect(exchange, back + "?err=" + enc("Choisissez un personnage."));
            return;
        }
        String linked = queueDialogueLink(post.session(), post.agentId(), npc, slug);
        Http.redirect(exchange, back + (linked.startsWith("!") ? "?err=" + enc(linked.substring(1))
                : "?toast=" + enc(linked)));
    }

    private void handleDialogueRefresh(HttpExchange exchange) throws IOException {
        NpcPost post = dialoguePost(exchange, false);
        if (post == null) {
            return;
        }
        String back = post.form().getOrDefault("return", "/dialogues");
        if (!back.startsWith("/dialogues")) {
            back = "/dialogues";
        }
        String toast = "";
        List<String> types = new ArrayList<>(List.of("dialogue.list", "npc.list"));
        if (permissions.can(post.session().effective(), Permission.CONTENT_READ)) {
            types.add("quest.list");
            types.add("content.dev.state");
        }
        for (String type : types) {
            if (!agentStore.hasOpenActionOfType(post.agentId(), type)) {
                String id = agentStore.createAction(post.agentId(), type, Map.of(), post.session().username());
                toast = toast.isEmpty() ? id : toast;
            }
        }
        Http.redirect(exchange, back + (back.contains("?") ? "&" : "?") + (toast.isEmpty() ? "" : "toast=" + enc(toast)));
    }

    private void handleDialogueEdit(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        Session session = maybe.get();
        if (!permissions.can(session.effective(), Permission.DIALOGUE_READ)) {
            forbidden(exchange, session, "/dialogues");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String slug = path.startsWith("/dialogues/edit/") ? path.substring("/dialogues/edit/".length()) : "";
        slug = com.lodygames.rpgquest.panel.content.DialogueYaml.plainId(slug.replace("/", ""));
        Map<String, String> query = Http.query(exchange);
        DialoguePages.Ctx ctx = agentPages.dialogueCtx(config.agents().defaultAgentId());
        DialoguePages.Editing editing = dialogueEditing(ctx, slug, query);
        if (editing == null) {
            Http.redirect(exchange, "/dialogues?err=" + enc("Ce dialogue est introuvable."));
            return;
        }
        dialoguePage(exchange, session, DialoguePages.title(slug, editing.draft(), ctx.users(slug)),
                actionFeedback(query) + DialoguePages.editor(session.csrfToken(), ctx, dialogueRights(session), editing));
    }

    /** L'éditeur ouvert sur le fichier enregistré (ou, s'il n'est que sur le serveur, en lecture seule). */
    private DialoguePages.Editing dialogueEditing(DialoguePages.Ctx ctx, String slug, Map<String, String> query) {
        Optional<ContentWorkspace.ContentFile> file = slug.isEmpty() ? Optional.empty()
                : contentWorkspace.read("dialogues", slug);
        // « Enregistré » est déjà dit par le bandeau d'état : pas de second message.
        String notice = "1".equals(query.get("created")) ? "Dialogue créé. Ajoutez des réponses, enregistrez, puis mettez-le en jeu."
                : "";
        if (file.isPresent()) {
            var rr = com.lodygames.rpgquest.panel.content.DialogueYaml.read(file.get().text());
            String readOnly = rr.draft() == null || !rr.ok()
                    ? "Ce dialogue utilise une écriture que l'éditeur ne sait pas relire sans rien perdre ("
                            + String.join(" ; ", rr.problems()) + "). Il s'affiche en lecture seule : modifiez son "
                            + "fichier à la main."
                    : "";
            var draft = rr.draft() == null ? com.lodygames.rpgquest.panel.content.DialogueDraft.blank() : rr.draft();
            return new DialoguePages.Editing(slug, draft, file.get().sha256(), readOnly, notice, "ok", Map.of(),
                    false, file.get().text().lines().anyMatch(l -> l.trim().startsWith("#")));
        }
        if (!ctx.serverKeys().contains(slug)) {
            return null;
        }
        // Seulement sur le serveur : on montre ce que le relevé en dit, sans rien modifier.
        var draft = agentPages.serverDialogueDraft(ctx.agentId(), slug).orElse(null);
        if (draft == null) {
            return null;
        }
        return new DialoguePages.Editing(slug, draft, "", "Ce dialogue n'existe que sur le serveur (il vient du "
                + "plugin ou a été déposé à la main) : il s'affiche en lecture seule.", notice, "ok", Map.of(), false, false);
    }

    /**
     * POST de l'éditeur : un geste (ajouter, retirer…) réaffiche le formulaire sans rien écrire ;
     * « Enregistrer » valide puis écrit ; « Enregistrer et mettre en jeu » enchaîne la publication.
     * En cas d'erreur, la saisie est réaffichée telle quelle, avec le message près du champ.
     */
    private void handleDialogueSave(HttpExchange exchange) throws IOException {
        NpcPost post = dialoguePost(exchange, true);
        if (post == null) {
            return;
        }
        Map<String, String> form = post.form();
        String slug = com.lodygames.rpgquest.panel.content.DialogueYaml.plainId(form.getOrDefault("slug", ""));
        String expectedSha = form.getOrDefault("expected_sha", "");
        String op = form.getOrDefault("_op", "save");
        DialoguePages.Ctx ctx = agentPages.dialogueCtx(post.agentId());
        Optional<ContentWorkspace.ContentFile> file = contentWorkspace.read("dialogues", slug);
        if (slug.isEmpty() || file.isEmpty()) {
            Http.redirect(exchange, "/dialogues?err=" + enc("Ce dialogue est introuvable : il a peut-être été supprimé."));
            return;
        }
        var original = com.lodygames.rpgquest.panel.content.DialogueYaml.read(file.get().text());
        if (original.draft() == null || !original.ok()) {
            Http.redirect(exchange, "/dialogues/edit/" + slug);
            return;
        }
        var parsed = com.lodygames.rpgquest.panel.content.DialogueForm.parse(form);
        var draft = parsed.draft();
        // L'identifiant ne change jamais depuis l'éditeur : c'est par lui que personnages et quêtes le retrouvent.
        draft.id = original.draft().id;
        boolean comments = file.get().text().lines().anyMatch(l -> l.trim().startsWith("#"));

        String notice = "";
        String tone = "info";
        String[] parts = op.split(":");
        switch (parts[0]) {
            case "addreply" -> com.lodygames.rpgquest.panel.content.DialogueForm.addReply(draft, intAt(parts, 1));
            case "delreply" -> {
                int i = intAt(parts, 1);
                int j = intAt(parts, 2);
                if (i >= 0 && i < draft.nodes.size() && j >= 0 && j < draft.nodes.get(i).choices.size()) {
                    draft.nodes.get(i).choices.remove(j);
                    notice = "Réponse supprimée. Enregistrez pour la retirer du dialogue.";
                }
            }
            case "addnode" -> {
                com.lodygames.rpgquest.panel.content.DialogueForm.addNode(draft);
                notice = "Réplique ajoutée en bas de la page. Choisissez-la dans « Ensuite… » d'une réponse pour "
                        + "qu'on puisse l'atteindre.";
            }
            case "delnode" -> {
                int n = com.lodygames.rpgquest.panel.content.DialogueForm.removeNode(draft, intAt(parts, 1));
                notice = n < 0 ? "" : n == 0 ? "Réplique retirée." : "Réplique retirée. " + n + " réponse(s) qui y menaient "
                        + "terminent maintenant la conversation.";
                tone = n > 0 ? "warn" : "info";
            }
            case "addcd", "addfx" -> {
                String kind = parts[0].substring(3);
                int i = intAt(parts, 1);
                int j = intAt(parts, 2);
                com.lodygames.rpgquest.panel.content.DialogueForm.addEntry(draft, i, j, kind,
                        form.getOrDefault("add_" + kind + "_" + i + "_" + j, ""));
            }
            case "delcd", "delfx" -> {
                String kind = parts[0].substring(3);
                int i = intAt(parts, 1);
                int j = intAt(parts, 2);
                int k = intAt(parts, 3);
                removeEntry(draft, i, j, kind, k);
            }
            default -> {
                if (parsed.createdNodes().isEmpty() && com.lodygames.rpgquest.panel.content.DialogueYaml.write(draft)
                        .equals(com.lodygames.rpgquest.panel.content.DialogueYaml.write(original.draft()))) {
                    keepUnchangedDialogue(exchange, post, slug, file.get().sha256(), "save_publish".equals(op));
                    return;
                }
                saveDialogue(exchange, post, ctx, slug, expectedSha, draft, parsed.createdNodes(), comments,
                        "save_publish".equals(op));
                return;
            }
        }
        DialoguePages.Editing editing = new DialoguePages.Editing(slug, draft, expectedSha, "", notice, tone, Map.of(),
                true, comments);
        dialoguePage(exchange, post.session(), DialoguePages.title(slug, draft, ctx.users(slug)),
                DialoguePages.editor(post.session().csrfToken(), ctx, dialogueRights(post.session()), editing));
    }

    private static int intAt(String[] parts, int index) {
        try {
            return index < parts.length ? Integer.parseInt(parts[index]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Retire la k-ième condition (cd) ou le k-ième autre effet (fx) tel que l'éditeur les numérote. */
    private static void removeEntry(com.lodygames.rpgquest.panel.content.DialogueDraft d, int i, int j, String kind, int k) {
        if (i < 0 || i >= d.nodes.size() || j < 0 || j >= d.nodes.get(i).choices.size() || k < 0) {
            return;
        }
        var c = d.nodes.get(i).choices.get(j);
        if ("cd".equals(kind)) {
            if (k < c.conditions.size()) {
                c.conditions.remove(k);
            }
            return;
        }
        var reply = com.lodygames.rpgquest.panel.content.DialogueForm.reply(c);
        if (k >= reply.otherEffects().size()) {
            return;
        }
        Map<String, String> target = reply.otherEffects().get(k);
        for (int x = 0; x < c.actions.size(); x++) {
            if (c.actions.get(x) == target) {
                c.actions.remove(x);
                return;
            }
        }
    }

    /**
     * Issue #373 : « Enregistrer » sans aucune modification ne réécrit pas le fichier. Il garde ainsi
     * ses commentaires et sa mise en forme (scalaires {@code >}…), et son empreinte ne bouge pas :
     * l'état « À jour en jeu » reste vrai. « Enregistrer et mettre en jeu » publie la version
     * existante telle quelle.
     */
    private void keepUnchangedDialogue(HttpExchange exchange, NpcPost post, String slug, String sha,
                                       boolean publish) throws IOException {
        String dest = "/dialogues/edit/" + slug + "?ok=" + enc("Aucune modification : le dialogue est resté tel quel.");
        if (publish && dialogueRights(post.session()).publish()) {
            String queued = queueDialoguePublish(post.session(), post.agentId(), slug, sha);
            dest = "/dialogues/edit/" + slug + (queued.startsWith("!") ? "?err=" + enc(queued.substring(1))
                    : "?toast=" + enc(queued));
        }
        Http.redirect(exchange, dest);
    }

    private void saveDialogue(HttpExchange exchange, NpcPost post, DialoguePages.Ctx ctx, String slug,
                              String expectedSha, com.lodygames.rpgquest.panel.content.DialogueDraft draft,
                              List<String> created, boolean comments, boolean publish) throws IOException {
        var diags = com.lodygames.rpgquest.panel.content.DialogueValidator.validate(draft);
        Map<String, List<String>> errors = DialoguePages.placeErrors(draft, diags);
        String yaml = com.lodygames.rpgquest.panel.content.DialogueYaml.write(draft);
        List<String> rtp = com.lodygames.rpgquest.panel.content.DialogueYaml.roundTripProblems(yaml);
        if (!rtp.isEmpty()) {
            errors = new java.util.LinkedHashMap<>(errors);
            errors.computeIfAbsent("dialogue", k -> new ArrayList<>()).add("Ce dialogue ne peut pas être enregistré "
                    + "sans risque de perte : " + String.join(" ; ", rtp));
        }
        if (!errors.isEmpty()) {
            String msg = created.isEmpty() ? "Rien n'a été enregistré : corrigez les points signalés en rouge."
                    : "Une nouvelle réplique a été ajoutée en bas : écrivez ce que dit le personnage, puis enregistrez.";
            dialoguePage(exchange, post.session(), DialoguePages.title(slug, draft, ctx.users(slug)),
                    DialoguePages.editor(post.session().csrfToken(), ctx, dialogueRights(post.session()),
                            new DialoguePages.Editing(slug, draft, expectedSha, "", msg, created.isEmpty() ? "err" : "info",
                                    errors, true, comments)));
            return;
        }
        ContentWorkspace.WriteResult wr = contentWorkspace.write("dialogues", slug, yaml, expectedSha);
        if (!wr.ok()) {
            String msg = "CONFLICT".equals(wr.code())
                    ? "Ce dialogue a été modifié ailleurs pendant que vous l'éditiez. Vos modifications sont toujours "
                            + "affichées ci-dessous mais n'ont pas été enregistrées : rechargez la page pour voir l'autre "
                            + "version (copiez d'abord ce que vous voulez garder)."
                    : "Rien n'a été enregistré : " + wr.message();
            dialoguePage(exchange, post.session(), DialoguePages.title(slug, draft, ctx.users(slug)),
                    DialoguePages.editor(post.session().csrfToken(), ctx, dialogueRights(post.session()),
                            new DialoguePages.Editing(slug, draft, expectedSha, "", msg, "err", Map.of(), true, comments)));
            return;
        }
        audit.record(post.session().username(), "dialogues.content.write", "/dialogues/edit/" + slug, "OK", null,
                UUID.randomUUID().toString().substring(0, 8));
        String dest = "/dialogues/edit/" + slug + "?saved=1";
        if (publish && dialogueRights(post.session()).publish()) {
            String queued = queueDialoguePublish(post.session(), post.agentId(), slug, wr.sha256());
            dest += queued.startsWith("!") ? "&err=" + enc(queued.substring(1)) : "&toast=" + enc(queued);
        }
        Http.redirect(exchange, dest);
    }

    /**
     * « Mettre en jeu » : la publication à chaud existante (#47), pour ce seul dialogue. Les deux
     * empreintes attendues voyagent avec l'action : une version modifiée entre-temps, d'un côté ou
     * de l'autre, est refusée au lieu d'être écrasée.
     *
     * @return l'id de l'action, ou {@code "!"} suivi du motif
     */
    private String queueDialoguePublish(Session session, String agentId, String slug, String sourceSha) {
        DialoguePages.LiveState live = agentPages.dialogueLive(agentId, slug);
        if (live.live() == DialoguePages.Live.PUBLISHING) {
            return "!Une mise en jeu de ce dialogue est déjà en cours.";
        }
        if (live.live() == DialoguePages.Live.CONFLICT) {
            return "!Le serveur a une autre version de ce dialogue : comparez-les dans « Détails techniques » avant "
                    + "de la remplacer.";
        }
        Map<String, String> publishForm = new java.util.LinkedHashMap<>();
        publishForm.put("kind", "dialogues");
        publishForm.put("id", slug);
        publishForm.put("expected_dev_sha", live.expectedDev() == null ? "" : live.expectedDev());
        publishForm.put("expected_source_sha", sourceSha);
        publishForm.put("confirm", "true");
        var v = com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate("content.publish", publishForm);
        if (!v.valid()) {
            return "!" + v.error();
        }
        PublishEnrichment enriched = enrichPublish(v.params());
        if (enriched.error() != null) {
            return "!" + enriched.error();
        }
        String id = agentStore.createAction(agentId, "content.publish", enriched.params(), session.username());
        audit.record(session.username(), "agent.action.create",
                "agent=" + agentId + " type=content.publish action=" + id, "PENDING", "dialogue=" + slug,
                UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private void handleDialoguePublish(HttpExchange exchange) throws IOException {
        NpcPost post = dialoguePost(exchange, false);
        if (post == null) {
            return;
        }
        if (!dialogueRights(post.session()).publish()) {
            forbidden(exchange, post.session(), "/dialogues");
            return;
        }
        String slug = com.lodygames.rpgquest.panel.content.DialogueYaml.plainId(post.form().getOrDefault("slug", ""));
        Optional<ContentWorkspace.ContentFile> file = contentWorkspace.read("dialogues", slug);
        String back = "/dialogues/edit/" + slug;
        if (file.isEmpty()) {
            Http.redirect(exchange, "/dialogues?err=" + enc("Ce dialogue est introuvable."));
            return;
        }
        String seen = post.form().getOrDefault("expected_sha", "");
        if (!seen.isEmpty() && !seen.equals(file.get().sha256())) {
            Http.redirect(exchange, back + "?err=" + enc("Le dialogue a changé depuis l'affichage de la page : "
                    + "vérifiez-le, puis mettez-le en jeu."));
            return;
        }
        String queued = queueDialoguePublish(post.session(), post.agentId(), slug, file.get().sha256());
        Http.redirect(exchange, back + (queued.startsWith("!") ? "?err=" + enc(queued.substring(1))
                : "?toast=" + enc(queued)));
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
        if ("building.definition.retire".equals(type)) {
            // #284 : l'auteur du retrait est noté par le plugin dans l'archive — c'est la session,
            // jamais un champ du formulaire.
            form.put("by", session.username());
        }
        com.lodygames.rpgquest.panel.agent.AgentActionCatalog.Validation v =
                com.lodygames.rpgquest.panel.agent.AgentActionCatalog.validate(type, form);
        if (!v.valid()) {
            audit.record(session.username(), "agent.action.create", "agent=" + agentId + " type=" + type,
                    "DENIED", v.error(), rid);
            Http.redirect(exchange, withError(returnPath, agentId, form.get("player"), v.error()));
            return;
        }
        // Issue #210 : un double clic n'empile pas deux changements d'OP pour le même joueur.
        if (("player.op".equals(type) || "player.deop".equals(type)) && agentStore.recentActions(agentId, 50)
                .stream().anyMatch(a -> !a.status().terminal()
                        && ("player.op".equals(a.type()) || "player.deop".equals(a.type()))
                        && a.params().getOrDefault("player", "").equalsIgnoreCase(v.params().getOrDefault("player", "")))) {
            audit.record(session.username(), "agent.action.create", "agent=" + agentId + " type=" + type,
                    "DENIED", "changement d'OP déjà en attente pour ce joueur", rid);
            Http.redirect(exchange, withError(returnPath, agentId, form.get("player"),
                    "Un changement d'OP est déjà en cours pour ce joueur : rien n'a été envoyé en double."));
            return;
        }
        // Issue #284 : un bâtiment posé ne se retire pas de la bibliothèque — refus avant la file,
        // sur le dernier relevé des emplacements (le plugin revérifie de son côté).
        if ("building.definition.retire".equals(type)) {
            String buildingId = v.params().getOrDefault("id", "");
            List<String> usedOn = agentPages.buildingSiteDirectory(agentId).sites().stream()
                    .map(st -> agentPages.buildingSiteDirectory(agentId).placementFor(st.id()))
                    .flatMap(Optional::stream).filter(p -> p.buildingId().equals(buildingId))
                    .map(p -> p.siteId()).toList();
            if (!usedOn.isEmpty()) {
                audit.record(session.username(), "agent.action.create", "agent=" + agentId + " type=" + type,
                        "DENIED", "bâtiment posé sur " + String.join(", ", usedOn), rid);
                Http.redirect(exchange, withError(returnPath, agentId, null, "« " + buildingId
                        + " » est posé sur " + String.join(", ", usedOn) + " : retirez-le d'abord de "
                        + "chaque emplacement. Rien n'a été retiré."));
                return;
            }
        }
        // Issue #239 : le plugin accepte de supprimer le repère d'un emplacement OCCUPÉ ; le bâtiment
        // resterait dans le monde sans plus aucun moyen de restaurer le terrain. Refus avant la file.
        if ("building.site.delete".equals(type)) {
            String siteId = v.params().getOrDefault("id", "");
            var sites = agentPages.buildingSiteDirectory(agentId);
            boolean occupied = sites.placementFor(siteId).isPresent()
                    || sites.find(siteId).map(st -> "OCCUPIED".equals(st.status())).orElse(false);
            if (occupied) {
                audit.record(session.username(), "agent.action.create", "agent=" + agentId + " type=" + type,
                        "DENIED", "emplacement occupé : " + siteId, rid);
                Http.redirect(exchange, withError(returnPath, agentId, null, "Un bâtiment est encore posé "
                        + "sur « " + siteId + " » : retirez-le d'abord (« Retirer le bâtiment et restaurer "
                        + "le terrain »). Rien n'a été supprimé."));
                return;
            }
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
        // Issue #47 — la publication de contenu est enrichie CÔTÉ SERVEUR : le navigateur n'envoie
        // qu'une famille, un identifiant et deux empreintes ; c'est le panel qui lit la source et
        // joint le YAML. Deux raisons, et les deux comptent : le navigateur ne peut donc pas forger
        // le contenu publié, et il n'a jamais besoin de connaître un chemin.
        java.util.Map<String, String> outgoing = v.params();
        if ("content.publish".equals(type)) {
            PublishEnrichment enriched = enrichPublish(outgoing);
            if (enriched.error() != null) {
                audit.record(session.username(), "agent.action.create",
                        "agent=" + agentId + " type=" + type, "DENIED", enriched.error(), rid);
                Http.redirect(exchange, withError(returnPath, agentId, null, enriched.error()));
                return;
            }
            outgoing = enriched.params();
        }
        String id = agentStore.createAction(agentId, type, outgoing, session.username());
        audit.record(session.username(), "agent.action.create",
                "agent=" + agentId + " type=" + type + " action=" + id, "PENDING",
                safeParams(outgoing), rid);
        LOG.log(System.Logger.Level.INFO, "event=agent_action_created rid=" + rid + " agent=" + agentId
                + " type=" + type + " action=" + id + " by=" + session.username());
        // Issue #241 : la fiche ouverte (« focus ») reste ouverte après l'action. Valeur filtrée :
        // un identifiant de contenu, jamais un fragment d'URL arbitraire.
        String focus = AgentPages.cleanFocus(form.get("focus"));
        Http.redirect(exchange, appendContext(returnPath, agentId, v.params().get("player"))
                + (focus.isEmpty() ? "" : "&focus=" + enc(focus)) + "&toast=" + enc(id));
    }

    /**
     * Publication groupée depuis « Changements en attente » (issue #47).
     *
     * <h2>Ce n'est PAS une opération globale</h2>
     *
     * <p>Chaque ressource cochée produit une action {@code content.publish}
     * <strong>individuelle</strong>, qui repasse par la validation du catalogue puis par
     * l'enrichissement côté serveur — donc par ses propres {@code expected_source_sha} et
     * {@code expected_dev_sha}, sa propre sauvegarde, son propre rechargement et sa propre
     * vérification du runtime. Aucune protection n'est contournée, et aucune n'est mutualisée.</p>
     *
     * <p><strong>Succès partiel assumé.</strong> Une ressource refusée n'annule pas les autres :
     * chacune a sa sauvegarde et son rechargement, donc un retour arrière global serait une
     * invention dangereuse. Le compte rendu distingue ce qui est parti de ce qui a été refusé, et la
     * page montre ensuite l'issue réelle de chaque action.</p>
     *
     * <p>Les noms de champs sont uniques par ligne, parce que le parseur de formulaire du panel
     * écrase les valeurs répétées : un {@code name="sel"} multiple ne livrerait qu'une seule case.</p>
     */
    private void handlePendingPublish(HttpExchange exchange) throws IOException {
        Optional<Session> maybe = requireSession(exchange);
        if (maybe.isEmpty()) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.text(exchange, 405, "POST requis");
            return;
        }
        Session session = maybe.get();
        Map<String, String> form = Http.formBody(exchange);
        if (!constantTimeEquals(session.csrfToken(), form.get("_csrf"))) {
            Http.html(exchange, 403, Layout.bare("CSRF",
                    "<h1>Requête refusée</h1><p class=\"muted\">Jeton CSRF invalide.</p>"));
            return;
        }
        String rid = UUID.randomUUID().toString().substring(0, 8);
        String agentId = form.getOrDefault("agent", "").trim();
        String returnPath = "/content/pending";

        if (!permissions.can(session.effective(), Permission.CONTENT_PUBLISH)) {
            audit.record(session.username(), "content.pending.publish", "agent=" + agentId,
                    "DENIED", "permission CONTENT_PUBLISH absente", rid);
            Http.redirect(exchange, withError(returnPath, agentId, null,
                    "Vous n'avez pas le droit de publier sur DEV."));
            return;
        }
        if (agentRegistry.byId(agentId).isEmpty()) {
            Http.redirect(exchange, withError(returnPath, agentId, null, "Agent inconnu."));
            return;
        }

        // Les cases cochées, dédupliquées par construction : la clé EST le nom du champ.
        java.util.List<String> selected = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (entry.getKey().startsWith("sel_") && !entry.getValue().isBlank()) {
                selected.add(entry.getKey().substring("sel_".length()));
            }
        }
        if (selected.isEmpty()) {
            Http.redirect(exchange, withError(returnPath, agentId, null,
                    "Aucune ressource sélectionnée : rien n'a été publié."));
            return;
        }
        // Ordre déterministe : deux soumissions identiques produisent la même séquence d'actions.
        java.util.Collections.sort(selected);

        int queued = 0;
        java.util.List<String> refused = new java.util.ArrayList<>();
        for (String key : selected) {
            int slash = key.indexOf('/');
            if (slash <= 0 || slash == key.length() - 1) {
                refused.add(key + " (clé invalide)");
                continue;
            }
            String kind = key.substring(0, slash);
            String slug = key.substring(slash + 1);
            Map<String, String> one = new java.util.LinkedHashMap<>();
            one.put("kind", kind);
            one.put("id", slug);
            one.put("expected_source_sha", form.getOrDefault("src_" + key, ""));
            one.put("expected_dev_sha", form.getOrDefault("dev_" + key, ""));
            one.put("confirm", "true");

            // Exactement la même validation que pour une publication unitaire.
            var validation = com.lodygames.rpgquest.panel.agent.AgentActionCatalog
                    .validate("content.publish", one);
            if (!validation.valid()) {
                refused.add(slug + " : " + validation.error());
                continue;
            }
            PublishEnrichment enriched = enrichPublish(validation.params());
            if (enriched.error() != null) {
                refused.add(slug + " : " + enriched.error());
                continue;
            }
            String id = agentStore.createAction(agentId, "content.publish", enriched.params(),
                    session.username());
            audit.record(session.username(), "agent.action.create",
                    "agent=" + agentId + " type=content.publish action=" + id + " batch=" + rid,
                    "PENDING", safeParams(enriched.params()), rid);
            queued++;
        }

        audit.record(session.username(), "content.pending.publish",
                "agent=" + agentId + " selected=" + selected.size(),
                refused.isEmpty() ? "OK" : "PARTIAL",
                queued + " enfilée(s), " + refused.size() + " refusée(s)", rid);
        LOG.log(System.Logger.Level.INFO, "event=content_batch_publish rid=" + rid
                + " agent=" + agentId + " queued=" + queued + " refused=" + refused.size()
                + " by=" + session.username());

        String message = queued + " publication(s) demandée(s)"
                + (refused.isEmpty() ? "." : " ; " + refused.size() + " refusée(s) : "
                        + String.join(" | ", refused.subList(0, Math.min(3, refused.size())))
                        + (refused.size() > 3 ? " …" : ""));
        if (queued == 0) {
            Http.redirect(exchange, withError(returnPath, agentId, null, message));
            return;
        }
        Http.redirect(exchange, appendContext(returnPath, agentId, null)
                + "&batch=" + enc(message));
    }

    /** Résultat de l'enrichissement d'une publication : des paramètres prêts, ou un refus. */
    private record PublishEnrichment(java.util.Map<String, String> params, String error) {
    }

    /**
     * Joint le YAML de la source à une action {@code content.publish} (issue #47).
     *
     * <p>Trois choses se passent ici, et chacune évite un défaut précis :</p>
     * <ol>
     *   <li>la source est lue <strong>par le serveur</strong> — le navigateur ne peut donc pas
     *       publier un contenu qu'il aurait fabriqué, ni désigner un fichier ;</li>
     *   <li>l'empreinte de la source est <strong>revérifiée</strong> contre celle de l'aperçu : si
     *       quelqu'un a réenregistré la ressource entre-temps, on refuse. #47 exige la même
     *       protection des deux côtés, et celle-ci est la moitié qu'on oublie ;</li>
     *   <li>l'identifiant déclaré dans le YAML est transmis séparément, parce que c'est
     *       <strong>lui</strong> que le moteur devra porter — vérifier le nom du fichier donnerait
     *       un faux échec quand les deux diffèrent.</li>
     * </ol>
     */
    private PublishEnrichment enrichPublish(java.util.Map<String, String> params) {
        String kind = params.get("kind");
        String slug = params.get("id");
        String expectedSource = params.getOrDefault("expected_source_sha", "");
        if (!contentWorkspace.configured()) {
            return new PublishEnrichment(null,
                    "Aucun espace de travail source configuré : rien à publier.");
        }
        java.util.Optional<ContentWorkspace.ContentFile> file = contentWorkspace.read(kind, slug);
        if (file.isEmpty()) {
            return new PublishEnrichment(null,
                    "La ressource « " + slug + " » n'existe pas dans la source : "
                            + "enregistrez-la avant de publier.");
        }
        if (!expectedSource.equals(file.get().sha256())) {
            return new PublishEnrichment(null,
                    "La source a changé depuis votre analyse : relancez-la avant de publier, "
                            + "pour ne pas publier une version que vous n'avez pas vue.");
        }
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>(params);
        // L'empreinte source a joué son rôle ici : inutile de l'envoyer au serveur de jeu.
        out.remove("expected_source_sha");
        out.put("yaml", file.get().text());
        out.put("expected_id", declaredId(kind, file.get().text(), slug));
        return new PublishEnrichment(out, null);
    }

    /**
     * L'identifiant que le moteur portera, lu dans le YAML.
     *
     * <p>Lecture volontairement minimale : la première clé {@code id:} de premier niveau. Une
     * analyse complète n'apporterait rien — si le YAML est invalide, le rechargement côté serveur
     * le dira mieux que nous, et c'est lui qui a le dernier mot.</p>
     */
    private static String declaredId(String kind, String yaml, String fallback) {
        for (String line : yaml.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("id:")) {
                String value = trimmed.substring(3).strip();
                if (value.startsWith("\"") || value.startsWith("'")) {
                    value = value.substring(1, Math.max(1, value.length() - 1));
                }
                if (!value.isEmpty()) {
                    return value;
                }
            }
            // On ne lit que l'en-tête : un « id: » indenté appartient à une étape ou un nœud.
            if (!trimmed.isEmpty() && !line.startsWith(" ") && trimmed.endsWith(":")) {
                break;
            }
        }
        return fallback;
    }

    /**
     * Chemins de retour acceptés après une action agent, <strong>dérivés de la navigation</strong>
     * (issue #227).
     *
     * <p>Le but de ce filtre est d'empêcher une redirection ouverte : on ne renvoie l'utilisateur
     * que vers une page connue du panel, jamais vers une URL fournie par la requête.</p>
     *
     * <p><strong>Pourquoi dérivé et non énuméré.</strong> C'était une liste écrite à la main, et
     * elle a fait exactement ce qu'une liste écrite à la main finit par faire : une page neuve
     * ({@code /buildings/sites}, issue #213) n'y figurait pas, donc <em>toutes</em> ses actions
     * renvoyaient silencieusement sur {@code /agents}. Les mutations partaient et s'appliquaient
     * correctement — le journal d'actions le montre — mais l'utilisateur ne revoyait jamais sa page,
     * et le bouton « Rafraîchir » souffrant du même défaut, il ne pouvait pas non plus y revenir
     * constater le résultat. D'où la conclusion, compréhensible et pourtant fausse, que rien
     * n'était enregistré.</p>
     *
     * <p>La navigation est la seule énumération des pages du panel qui soit déjà obligatoire pour
     * qu'une page existe. L'adosser à elle rend l'oubli <strong>impossible</strong> plutôt
     * qu'improbable.</p>
     */
    private static final java.util.Set<String> RETURN_PATHS = returnPaths();

    private static java.util.Set<String> returnPaths() {
        java.util.Set<String> paths = new java.util.LinkedHashSet<>();
        for (Layout.NavGroup group : Layout.nav()) {
            for (Layout.NavItem item : group.items()) {
                if (item.href() != null && item.href().startsWith("/")) {
                    paths.add(item.href());
                }
            }
        }
        // Pages atteignables sans entrée de navigation propre : elles existent, et une action peut
        // légitimement vouloir y revenir.
        paths.add("/content/import");
        paths.add("/ai/studio");
        paths.add("/npcs/diagnostic");
        paths.add("/npcs/encounters");
        return java.util.Set.copyOf(paths);
    }

    private static String safeReturnPath(String requested, String fallback) {
        return RETURN_PATHS.contains(requested == null ? "" : requested) ? requested : fallback;
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
        return notifications.bellHtml(config.agents().defaultAgentId(), Instant.now(), session.userId(),
                session.csrfToken());
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
