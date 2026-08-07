package com.lodygames.rpgquest.webapi;

import com.lodygames.rpgquest.webapi.store.ProductCatalog;
import com.lodygames.rpgquest.webapi.store.SandboxPaymentProvider;
import com.lodygames.rpgquest.webapi.store.StoreDatabase;
import com.lodygames.rpgquest.webapi.store.StoreRepository;
import com.lodygames.rpgquest.webapi.store.StoreService;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Point d'entrée du portail web-api, un processus JVM entièrement séparé du
 * plugin Paper (mission étape 21, point 1) : {@code java -jar web-api.jar},
 * lit {@code web-api.properties}/{@code products.json} et les variables
 * d'environnement {@code RPGQUEST_WEB_API_TOKEN}/
 * {@code RPGQUEST_STORE_WEBHOOK_SECRET}. Voir docs/WEB_API.md et
 * docs/STORE.md pour le déploiement local et production.
 */
public final class WebApiMain {

    private static final Logger LOGGER = Logger.getLogger("RPGQuest-WebApi");
    private static final long STORE_DB_INIT_TIMEOUT_SECONDS = 30;

    private WebApiMain() {
    }

    public static void main(String[] args) throws IOException {
        WebApiConfig config = WebApiConfigLoader.load();
        SnapshotStore snapshotStore = new SnapshotStore(config.snapshotFile(), config.snapshotMaxAgeSeconds(), LOGGER);

        ProductCatalog catalog = ProductCatalog.load(config.productsFile());
        StoreDatabase storeDatabase = new StoreDatabase(config.storeDatabaseFile());
        try {
            storeDatabase.initialize().get(STORE_DB_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Échec d'initialisation de store.db : " + config.storeDatabaseFile(), e);
        }
        StoreRepository storeRepository = new StoreRepository(storeDatabase);
        SandboxPaymentProvider paymentProvider = new SandboxPaymentProvider(config.publicBaseUrl(), config.webhookSecret());
        StoreService storeService = new StoreService(catalog, storeRepository, paymentProvider, config.webhookSecret());

        HttpServerBootstrap bootstrap = new HttpServerBootstrap(config, snapshotStore, storeService);
        bootstrap.start();

        LOGGER.info(() -> "RPGQuest web-api démarré sur le port " + config.port()
                + " (snapshot=" + config.snapshotFile() + ", store=" + config.storeDatabaseFile()
                + ", produits=" + catalog.all().size() + ").");
        if (config.authToken() == null || config.authToken().isBlank()) {
            LOGGER.warning(
                    "Aucun jeton d'authentification configuré (" + WebApiConfigLoader.TOKEN_ENV
                            + ") : toutes les requêtes /api/* seront refusées (401).");
        }
        if (config.webhookSecret() == null || config.webhookSecret().isBlank()) {
            LOGGER.warning(
                    "Aucun secret de webhook configuré (" + WebApiConfigLoader.WEBHOOK_SECRET_ENV
                            + ") : /store/webhook refusera toutes les notifications de paiement (401).");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Arrêt de RPGQuest web-api...");
            bootstrap.stop();
            storeDatabase.shutdown();
        }, "RPGQuest-WebApi-Shutdown"));
    }
}
