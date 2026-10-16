package com.lodygames.rpgquest.webapi;

import com.lodygames.rpgquest.webapi.account.AccountPagesHandler;
import com.lodygames.rpgquest.webapi.account.AccountService;
import com.lodygames.rpgquest.webapi.account.LinkConfirmHandler;
import com.lodygames.rpgquest.webapi.ingest.SnapshotIngestHandler;
import com.lodygames.rpgquest.webapi.api.AnnouncementsHandler;
import com.lodygames.rpgquest.webapi.api.CatalogHandler;
import com.lodygames.rpgquest.webapi.api.HealthHandler;
import com.lodygames.rpgquest.webapi.api.LeaderboardsHandler;
import com.lodygames.rpgquest.webapi.api.NotFoundHandler;
import com.lodygames.rpgquest.webapi.api.PlayersHandler;
import com.lodygames.rpgquest.webapi.api.ProfilesHandler;
import com.lodygames.rpgquest.webapi.api.StatusHandler;
import com.lodygames.rpgquest.webapi.http.AccessLogger;
import com.lodygames.rpgquest.webapi.http.AuthFilter;
import com.lodygames.rpgquest.webapi.http.RateLimiter;
import com.lodygames.rpgquest.webapi.http.RequestPipeline;
import com.lodygames.rpgquest.webapi.news.NewsRepository;
import com.lodygames.rpgquest.webapi.site.HomePageHandler;
import com.lodygames.rpgquest.webapi.site.InfoPageHandler;
import com.lodygames.rpgquest.webapi.site.LeaderboardsPageHandler;
import com.lodygames.rpgquest.webapi.site.NewsPageHandler;
import com.lodygames.rpgquest.webapi.site.PlayersPageHandler;
import com.lodygames.rpgquest.webapi.site.PrivacyPageHandler;
import com.lodygames.rpgquest.webapi.site.SeoHandler;
import com.lodygames.rpgquest.webapi.site.Site;
import com.lodygames.rpgquest.webapi.site.StatusPageHandler;
import com.lodygames.rpgquest.webapi.site.UnpublishedPageHandler;
import com.lodygames.rpgquest.webapi.site.WikiPageHandler;
import com.lodygames.rpgquest.webapi.store.AckDeliveryHandler;
import com.lodygames.rpgquest.webapi.store.CheckoutHandler;
import com.lodygames.rpgquest.webapi.store.OrdersHistoryHandler;
import com.lodygames.rpgquest.webapi.store.PayFlowHandler;
import com.lodygames.rpgquest.webapi.store.PendingDeliveriesHandler;
import com.lodygames.rpgquest.webapi.store.RefundHandler;
import com.lodygames.rpgquest.webapi.store.StorePageHandler;
import com.lodygames.rpgquest.webapi.store.StoreService;
import com.lodygames.rpgquest.webapi.store.WebhookHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Assemble le serveur HTTP ({@code com.sun.net.httpserver}, JDK — aucune
 * dépendance externe, mission étape 21) : routes {@code /api/*}
 * authentifiées (point 5), pages du portail public non authentifiées
 * (point 8), toutes passant par {@link RequestPipeline} (rate limit,
 * journalisation, validation). Les routes boutique (mission étape 22)
 * suivent le même schéma, à l'exception de {@code /store/webhook} qui n'est
 * pas authentifié par jeton mais par signature (voir {@link WebhookHandler}).
 *
 * <p>Hébergement public (#52) : les routes boutique ne sont enregistrées que si
 * {@code store-enabled=true} ({@code storeService} vaut alors non nul), et les pages à pseudos ne
 * lisent l'instantané que si {@code player-data-public=true}.</p>
 *
 * <p>V1 publique (#56) : actualités ({@code /actualites}, lues dans {@code news-dir}, indépendantes de
 * l'instantané), classements ({@code /classements}, alias {@code /leaderboards}), profils, infos, et
 * {@code robots.txt} / {@code sitemap.xml}.</p>
 */
public final class HttpServerBootstrap {

    private final WebApiConfig config;
    private final SnapshotStore snapshotStore;
    private final StoreService storeService;
    private final AccountService accountService;
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "RPGQuest-WebApi");
        thread.setDaemon(true);
        return thread;
    });

    /** Relecture du dossier des actualités au plus toutes les 30 s : une publication apparaît vite, sans disque par requête. */
    static final Duration NEWS_REFRESH = Duration.ofSeconds(30);

    private HttpServer server;

    public HttpServerBootstrap(WebApiConfig config, SnapshotStore snapshotStore, StoreService storeService) {
        this(config, snapshotStore, storeService, null);
    }

    /** {@code accountService} nul : comptes joueurs désactivés (#53), {@code /compte} l'annonce. */
    public HttpServerBootstrap(WebApiConfig config, SnapshotStore snapshotStore, StoreService storeService,
            AccountService accountService) {
        this.config = config;
        this.snapshotStore = snapshotStore;
        this.storeService = storeService;
        this.accountService = accountService;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        server.setExecutor(requestExecutor);

        RequestPipeline pipeline = new RequestPipeline(
                new RateLimiter(config.rateLimitPerMinute()),
                new AuthFilter(config.authToken()),
                new AccessLogger(java.util.logging.Logger.getLogger("RPGQuest-WebApi")),
                config.trustProxyHeaders());
        boolean storeEnabled = config.storeEnabled() && storeService != null;
        Site site = new Site(config.siteTitle(), config.publicBaseUrl());
        NewsRepository news = new NewsRepository(config.site().newsDir(), NEWS_REFRESH, Clock.systemUTC(),
                java.util.logging.Logger.getLogger("RPGQuest-WebApi"));

        server.createContext("/health", pipeline.wrap(false,
                new HealthHandler(snapshotStore, config.playerDataPublic(), storeEnabled, news)));

        // API authentifiée (mission point 3, 5).
        server.createContext("/api/status", pipeline.wrap(true, new StatusHandler(snapshotStore)));
        server.createContext("/api/players", pipeline.wrap(true, new PlayersHandler(snapshotStore, config.playerDataPublic())));
        server.createContext("/api/leaderboards", pipeline.wrap(true, new LeaderboardsHandler(snapshotStore, config.playerDataPublic())));
        server.createContext("/api/catalog", pipeline.wrap(true, new CatalogHandler(snapshotStore)));
        server.createContext("/api/announcements", pipeline.wrap(true, new AnnouncementsHandler(snapshotStore)));
        server.createContext("/api/profiles", pipeline.wrap(true, new ProfilesHandler(snapshotStore, config.playerDataPublic())));

        // #53 : preuve de liaison venue du serveur Minecraft — jeton DÉDIÉ vérifié par le handler,
        // jamais le jeton général ci-dessus (d'où requiresAuth=false au niveau du pipeline).
        if (accountService != null) {
            server.createContext("/api/link/confirm", pipeline.wrap(false,
                    new LinkConfirmHandler(accountService, config.accounts().linkToken())));
        }

        // #421 : instantané poussé par le serveur Minecraft, authentifié par son jeton dédié.
        server.createContext(SnapshotIngestHandler.PATH, pipeline.wrap(false, new SnapshotIngestHandler(config.snapshotFile(),
                config.snapshotIngest(), Clock.systemUTC())));
        server.createContext("/api/", pipeline.wrap(true, new NotFoundHandler()));

        // Portail public (mission point 8) : jamais authentifié, jamais de connexion joueur (point 11).
        server.createContext("/status", pipeline.wrap(false, new StatusPageHandler(snapshotStore, config.siteTitle())));
        server.createContext("/wiki", pipeline.wrap(false, new WikiPageHandler(snapshotStore, config.siteTitle())));
        server.createContext("/actualites", pipeline.wrap(false, new NewsPageHandler(news, site)));
        server.createContext("/infos", pipeline.wrap(false, new InfoPageHandler(site, config.site())));
        SeoHandler seo = new SeoHandler(news, site);
        server.createContext("/robots.txt", pipeline.wrap(false, seo));
        server.createContext("/sitemap.xml", pipeline.wrap(false, seo));
        var boards = pipeline.wrap(false, config.playerDataPublic()
                ? new LeaderboardsPageHandler(snapshotStore, site)
                : new UnpublishedPageHandler(site, "Classements", "/classements"));
        server.createContext("/classements", boards);
        server.createContext("/leaderboards", boards);
        server.createContext("/joueurs", pipeline.wrap(false, config.playerDataPublic()
                ? new PlayersPageHandler(snapshotStore, site)
                : new UnpublishedPageHandler(site, "Joueurs", "/joueurs")));
        server.createContext("/confidentialite", pipeline.wrap(false, new PrivacyPageHandler(snapshotStore, config.siteTitle(),
                config.playerDataPublic(), config.agePolicy())));

        // #53 : comptes joueurs, strictement distincts de PlugAdmin (aucun rôle, aucune permission).
        server.createContext("/compte", pipeline.wrap(false, accountService != null
                ? new AccountPagesHandler(accountService, config.accounts(), config.siteTitle(), config.publicBaseUrl())
                : this::accountsClosedPage));

        if (storeEnabled) {
            registerStore(pipeline);
        }

        server.createContext("/", pipeline.wrap(false, new HomePageHandler(snapshotStore, news, site,
                config.playerDataPublic())));

        server.start();
    }

    private int accountsClosedPage(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        com.lodygames.rpgquest.webapi.http.HttpResponses.sendHtml(exchange, 200,
                com.lodygames.rpgquest.webapi.site.PageLayout.render(config.siteTitle(), "Mon compte", true,
                        "<p>Les comptes du portail ne sont <strong>pas encore ouverts</strong>. Ils ouvriront quand "
                                + "les règles de confidentialité et d'âge auront été validées.</p>"
                                + "<p><a href=\"/confidentialite\">En savoir plus</a></p>"));
        return 200;
    }

    private void registerStore(RequestPipeline pipeline) {
        // API boutique authentifiée (mission étape 22, points 5, 8, 11) : sondage de livraisons,
        // accusé de réception, historique admin, remboursement.
        server.createContext("/api/store/deliveries/pending", pipeline.wrap(true, new PendingDeliveriesHandler(storeService)));
        server.createContext("/api/store/deliveries/", pipeline.wrap(true, new AckDeliveryHandler(storeService)));
        server.createContext("/api/store/orders", pipeline.wrap(true, new OrdersHistoryHandler(storeService)));
        server.createContext("/api/store/orders/", pipeline.wrap(true, new RefundHandler(storeService)));

        // Boutique publique (mission étape 22, point 8) : /store/webhook n'est pas authentifié par
        // jeton (requiresAuth=false) — sa propre vérification de signature en tient lieu.
        server.createContext("/store/checkout", pipeline.wrap(false, new CheckoutHandler(storeService, config.siteTitle())));
        server.createContext("/store/pay/", pipeline.wrap(false, new PayFlowHandler(storeService, config.siteTitle())));
        server.createContext("/store/webhook", pipeline.wrap(false, new WebhookHandler(storeService)));
        server.createContext("/store", pipeline.wrap(false, new StorePageHandler(storeService, config.siteTitle())));
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        requestExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                requestExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requestExecutor.shutdownNow();
        }
    }

    /** Port effectivement lié (utile en test avec {@code port=0}). */
    public int boundPort() {
        return server.getAddress().getPort();
    }
}
