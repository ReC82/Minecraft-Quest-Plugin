package be.lloyd.rpgquest.webapi;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Point d'entrée du portail web-api, un processus JVM entièrement séparé du
 * plugin Paper (mission étape 21, point 1) : {@code java -jar web-api.jar},
 * lit {@code web-api.properties} et la variable d'environnement
 * {@code RPGQUEST_WEB_API_TOKEN}. Voir docs/WEB_API.md pour le déploiement
 * local et production.
 */
public final class WebApiMain {

    private static final Logger LOGGER = Logger.getLogger("RPGQuest-WebApi");

    private WebApiMain() {
    }

    public static void main(String[] args) throws IOException {
        WebApiConfig config = WebApiConfigLoader.load();
        SnapshotStore store = new SnapshotStore(config.snapshotFile(), config.snapshotMaxAgeSeconds(), LOGGER);
        HttpServerBootstrap bootstrap = new HttpServerBootstrap(config, store);
        bootstrap.start();

        LOGGER.info(() -> "RPGQuest web-api démarré sur le port " + config.port()
                + " (snapshot=" + config.snapshotFile() + ").");
        if (config.authToken() == null || config.authToken().isBlank()) {
            LOGGER.warning(
                    "Aucun jeton d'authentification configuré (" + WebApiConfigLoader.TOKEN_ENV
                            + ") : toutes les requêtes /api/* seront refusées (401).");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Arrêt de RPGQuest web-api...");
            bootstrap.stop();
        }, "RPGQuest-WebApi-Shutdown"));
    }
}
