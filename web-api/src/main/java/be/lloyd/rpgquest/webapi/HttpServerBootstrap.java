package be.lloyd.rpgquest.webapi;

import be.lloyd.rpgquest.webapi.api.AnnouncementsHandler;
import be.lloyd.rpgquest.webapi.api.CatalogHandler;
import be.lloyd.rpgquest.webapi.api.LeaderboardsHandler;
import be.lloyd.rpgquest.webapi.api.NotFoundHandler;
import be.lloyd.rpgquest.webapi.api.PlayersHandler;
import be.lloyd.rpgquest.webapi.api.StatusHandler;
import be.lloyd.rpgquest.webapi.http.AccessLogger;
import be.lloyd.rpgquest.webapi.http.AuthFilter;
import be.lloyd.rpgquest.webapi.http.RateLimiter;
import be.lloyd.rpgquest.webapi.http.RequestPipeline;
import be.lloyd.rpgquest.webapi.site.HomePageHandler;
import be.lloyd.rpgquest.webapi.site.LeaderboardsPageHandler;
import be.lloyd.rpgquest.webapi.site.StatusPageHandler;
import be.lloyd.rpgquest.webapi.site.WikiPageHandler;
import be.lloyd.rpgquest.webapi.store.AckDeliveryHandler;
import be.lloyd.rpgquest.webapi.store.CheckoutHandler;
import be.lloyd.rpgquest.webapi.store.OrdersHistoryHandler;
import be.lloyd.rpgquest.webapi.store.PayFlowHandler;
import be.lloyd.rpgquest.webapi.store.PendingDeliveriesHandler;
import be.lloyd.rpgquest.webapi.store.RefundHandler;
import be.lloyd.rpgquest.webapi.store.StorePageHandler;
import be.lloyd.rpgquest.webapi.store.StoreService;
import be.lloyd.rpgquest.webapi.store.WebhookHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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
 */
public final class HttpServerBootstrap {

    private final WebApiConfig config;
    private final SnapshotStore snapshotStore;
    private final StoreService storeService;
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "RPGQuest-WebApi");
        thread.setDaemon(true);
        return thread;
    });

    private HttpServer server;

    public HttpServerBootstrap(WebApiConfig config, SnapshotStore snapshotStore, StoreService storeService) {
        this.config = config;
        this.snapshotStore = snapshotStore;
        this.storeService = storeService;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(requestExecutor);

        RequestPipeline pipeline = new RequestPipeline(
                new RateLimiter(config.rateLimitPerMinute()),
                new AuthFilter(config.authToken()),
                new AccessLogger(java.util.logging.Logger.getLogger("RPGQuest-WebApi")));

        // API authentifiée (mission point 3, 5).
        server.createContext("/api/status", pipeline.wrap(true, new StatusHandler(snapshotStore)));
        server.createContext("/api/players", pipeline.wrap(true, new PlayersHandler(snapshotStore)));
        server.createContext("/api/leaderboards", pipeline.wrap(true, new LeaderboardsHandler(snapshotStore)));
        server.createContext("/api/catalog", pipeline.wrap(true, new CatalogHandler(snapshotStore)));
        server.createContext("/api/announcements", pipeline.wrap(true, new AnnouncementsHandler(snapshotStore)));

        // API boutique authentifiée (mission étape 22, points 5, 8, 11) : sondage de livraisons,
        // accusé de réception, historique admin, remboursement.
        server.createContext("/api/store/deliveries/pending", pipeline.wrap(true, new PendingDeliveriesHandler(storeService)));
        server.createContext("/api/store/deliveries/", pipeline.wrap(true, new AckDeliveryHandler(storeService)));
        server.createContext("/api/store/orders", pipeline.wrap(true, new OrdersHistoryHandler(storeService)));
        server.createContext("/api/store/orders/", pipeline.wrap(true, new RefundHandler(storeService)));

        server.createContext("/api/", pipeline.wrap(true, new NotFoundHandler()));

        // Portail public (mission point 8) : jamais authentifié, jamais de connexion joueur (point 11).
        server.createContext("/status", pipeline.wrap(false, new StatusPageHandler(snapshotStore, config.siteTitle())));
        server.createContext("/leaderboards", pipeline.wrap(false, new LeaderboardsPageHandler(snapshotStore, config.siteTitle())));
        server.createContext("/wiki", pipeline.wrap(false, new WikiPageHandler(snapshotStore, config.siteTitle())));

        // Boutique publique (mission étape 22, point 8) : /store/webhook n'est pas authentifié par
        // jeton (requiresAuth=false) — sa propre vérification de signature en tient lieu.
        server.createContext("/store/checkout", pipeline.wrap(false, new CheckoutHandler(storeService, config.siteTitle())));
        server.createContext("/store/pay/", pipeline.wrap(false, new PayFlowHandler(storeService, config.siteTitle())));
        server.createContext("/store/webhook", pipeline.wrap(false, new WebhookHandler(storeService)));
        server.createContext("/store", pipeline.wrap(false, new StorePageHandler(storeService, config.siteTitle())));

        server.createContext("/", pipeline.wrap(false, new HomePageHandler(snapshotStore, config.siteTitle())));

        server.start();
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
