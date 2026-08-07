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
 * journalisation, validation).
 */
public final class HttpServerBootstrap {

    private final WebApiConfig config;
    private final SnapshotStore store;
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "RPGQuest-WebApi");
        thread.setDaemon(true);
        return thread;
    });

    private HttpServer server;

    public HttpServerBootstrap(WebApiConfig config, SnapshotStore store) {
        this.config = config;
        this.store = store;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        server.setExecutor(requestExecutor);

        RequestPipeline pipeline = new RequestPipeline(
                new RateLimiter(config.rateLimitPerMinute()),
                new AuthFilter(config.authToken()),
                new AccessLogger(java.util.logging.Logger.getLogger("RPGQuest-WebApi")));

        // API authentifiée (mission point 3, 5).
        server.createContext("/api/status", pipeline.wrap(true, new StatusHandler(store)));
        server.createContext("/api/players", pipeline.wrap(true, new PlayersHandler(store)));
        server.createContext("/api/leaderboards", pipeline.wrap(true, new LeaderboardsHandler(store)));
        server.createContext("/api/catalog", pipeline.wrap(true, new CatalogHandler(store)));
        server.createContext("/api/announcements", pipeline.wrap(true, new AnnouncementsHandler(store)));
        server.createContext("/api/", pipeline.wrap(true, new NotFoundHandler()));

        // Portail public (mission point 8) : jamais authentifié, jamais de paiement/login/écriture (point 11).
        server.createContext("/status", pipeline.wrap(false, new StatusPageHandler(store, config.siteTitle())));
        server.createContext("/leaderboards", pipeline.wrap(false, new LeaderboardsPageHandler(store, config.siteTitle())));
        server.createContext("/wiki", pipeline.wrap(false, new WikiPageHandler(store, config.siteTitle())));
        server.createContext("/", pipeline.wrap(false, new HomePageHandler(store, config.siteTitle())));

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
