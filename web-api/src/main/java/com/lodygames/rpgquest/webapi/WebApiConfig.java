package com.lodygames.rpgquest.webapi;

import java.nio.file.Path;

/**
 * Configuration du serveur web-api. {@code authToken}/{@code webhookSecret}
 * ne doivent jamais venir d'un fichier versionné dans Git (mission étapes
 * 21 point 7, 22 point 4/9) : voir {@link WebApiConfigLoader}, qui les lit
 * exclusivement depuis les variables d'environnement
 * {@code RPGQUEST_WEB_API_TOKEN}/{@code RPGQUEST_STORE_WEBHOOK_SECRET}.
 */
public record WebApiConfig(
        int port,
        Path snapshotFile,
        long snapshotMaxAgeSeconds,
        String authToken,
        int rateLimitPerMinute,
        String siteTitle,
        Path productsFile,
        Path storeDatabaseFile,
        String webhookSecret,
        String publicBaseUrl
) {
}
