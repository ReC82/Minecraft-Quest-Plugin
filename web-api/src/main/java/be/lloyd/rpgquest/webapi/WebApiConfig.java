package be.lloyd.rpgquest.webapi;

import java.nio.file.Path;

/**
 * Configuration du serveur web-api. {@code authToken} ne doit jamais venir
 * d'un fichier versionné dans Git (mission étape 21, point 7) : voir
 * {@link WebApiConfigLoader}, qui le lit exclusivement depuis la variable
 * d'environnement {@code RPGQUEST_WEB_API_TOKEN}.
 */
public record WebApiConfig(
        int port,
        Path snapshotFile,
        long snapshotMaxAgeSeconds,
        String authToken,
        int rateLimitPerMinute,
        String siteTitle
) {
}
