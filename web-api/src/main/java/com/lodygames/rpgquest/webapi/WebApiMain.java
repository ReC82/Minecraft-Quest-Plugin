package com.lodygames.rpgquest.webapi;

import com.lodygames.rpgquest.webapi.account.AccountService;
import com.lodygames.rpgquest.webapi.account.AccountStore;
import com.lodygames.rpgquest.webapi.account.PasswordHasher;
import com.lodygames.rpgquest.webapi.store.ProductCatalog;
import com.lodygames.rpgquest.webapi.store.SandboxPaymentProvider;
import com.lodygames.rpgquest.webapi.store.StoreDatabase;
import com.lodygames.rpgquest.webapi.store.StoreRepository;
import com.lodygames.rpgquest.webapi.store.StoreService;
import java.io.IOException;
import java.time.Clock;
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

        // #52 : la boutique (mission étape 22) n'entre pas dans le portail communautaire V1 — sans
        // store-enabled=true, ni store.db ni products.json ne sont ouverts.
        StoreDatabase storeDatabase = config.storeEnabled() ? new StoreDatabase(config.storeDatabaseFile()) : null;
        StoreService storeService = storeDatabase == null ? null : openStore(config, storeDatabase);

        AccountStore accountStore = config.accounts().enabled() ? openAccounts(config) : null;
        AccountService accountService = accountStore == null ? null : new AccountService(accountStore,
                new PasswordHasher(), config.agePolicy(), config.accounts(), Clock.systemUTC());

        HttpServerBootstrap bootstrap = new HttpServerBootstrap(config, snapshotStore, storeService, accountService);
        bootstrap.start();

        LOGGER.info(() -> "RPGQuest web-api démarré sur " + config.bindAddress() + ":" + config.port()
                + " (snapshot=" + config.snapshotFile() + ", boutique=" + (storeService != null ? "active" : "désactivée")
                + ", données joueurs publiques=" + config.playerDataPublic()
                + ", comptes=" + (accountService == null ? "désactivés" : accountService.open() ? "ouverts" : "fermés (aucune région)")
                + ").");
        if (accountService != null && (config.accounts().linkToken() == null || config.accounts().linkToken().isBlank())) {
            LOGGER.warning("Aucun jeton de liaison configuré (" + WebApiConfigLoader.LINK_TOKEN_ENV
                    + ") : /api/link/confirm refusera toutes les preuves venues du serveur Minecraft (401).");
        }
        String snapshotToken = config.snapshotIngest().token();
        if (snapshotToken == null || snapshotToken.isBlank()) {
            LOGGER.info("Aucun jeton de réception d'instantané (" + WebApiConfigLoader.SNAPSHOT_TOKEN_ENV
                    + ") : POST /api/snapshot refuse tout (401), le portail lit seulement " + config.snapshotFile() + ".");
        }
        if (config.authToken() == null || config.authToken().isBlank()) {
            LOGGER.warning(
                    "Aucun jeton d'authentification configuré (" + WebApiConfigLoader.TOKEN_ENV
                            + ") : toutes les requêtes /api/* seront refusées (401).");
        }
        if (storeService != null && (config.webhookSecret() == null || config.webhookSecret().isBlank())) {
            LOGGER.warning(
                    "Aucun secret de webhook configuré (" + WebApiConfigLoader.WEBHOOK_SECRET_ENV
                            + ") : /store/webhook refusera toutes les notifications de paiement (401).");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Arrêt de RPGQuest web-api...");
            bootstrap.stop();
            if (storeDatabase != null) {
                storeDatabase.shutdown();
            }
            if (accountStore != null) {
                try {
                    accountStore.close();
                } catch (java.sql.SQLException ignored) {
                    // fermeture au mieux pendant l'arrêt
                }
            }
        }, "RPGQuest-WebApi-Shutdown"));
    }

    private static AccountStore openAccounts(WebApiConfig config) throws IOException {
        try {
            return new AccountStore(config.accounts().databaseFile());
        } catch (java.sql.SQLException e) {
            throw new IOException("Échec d'ouverture de la base des comptes : " + config.accounts().databaseFile(), e);
        }
    }

    private static StoreService openStore(WebApiConfig config, StoreDatabase storeDatabase) throws IOException {
        ProductCatalog catalog = ProductCatalog.load(config.productsFile());
        try {
            storeDatabase.initialize().get(STORE_DB_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Échec d'initialisation de store.db : " + config.storeDatabaseFile(), e);
        }
        StoreRepository storeRepository = new StoreRepository(storeDatabase);
        SandboxPaymentProvider paymentProvider = new SandboxPaymentProvider(config.publicBaseUrl(), config.webhookSecret());
        LOGGER.info(() -> "Boutique active : " + catalog.all().size() + " produit(s), store=" + config.storeDatabaseFile());
        return new StoreService(catalog, storeRepository, paymentProvider, config.webhookSecret());
    }
}
