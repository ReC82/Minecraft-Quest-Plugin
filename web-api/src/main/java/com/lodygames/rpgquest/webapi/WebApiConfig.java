package com.lodygames.rpgquest.webapi;

import java.nio.file.Path;

/**
 * Configuration du serveur web-api. {@code authToken}/{@code webhookSecret}
 * ne doivent jamais venir d'un fichier versionné dans Git (mission étapes
 * 21 point 7, 22 point 4/9) : voir {@link WebApiConfigLoader}, qui les lit
 * exclusivement depuis les variables d'environnement
 * {@code RPGQUEST_WEB_API_TOKEN}/{@code RPGQUEST_STORE_WEBHOOK_SECRET}.
 *
 * <p>Hébergement public (#52) : {@code bindAddress} limite l'écoute (127.0.0.1 derrière nginx),
 * {@code storeEnabled} n'enregistre les routes boutique que sur demande (aucun paiement en V1),
 * {@code playerDataPublic} masque pseudos, profils et classements tant que le propriétaire n'a pas
 * validé la confidentialité (#55), et {@code trustProxyHeaders} fait lire l'IP du visiteur dans
 * {@code X-Real-IP} posé par le proxy local, pour que la limite de fréquence reste par visiteur.</p>
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
        String publicBaseUrl,
        String bindAddress,
        boolean storeEnabled,
        boolean playerDataPublic,
        boolean trustProxyHeaders
) {

    /** Configuration historique (avant #52) : toutes interfaces, boutique et données joueurs actives. */
    public WebApiConfig(int port, Path snapshotFile, long snapshotMaxAgeSeconds, String authToken,
            int rateLimitPerMinute, String siteTitle, Path productsFile, Path storeDatabaseFile,
            String webhookSecret, String publicBaseUrl) {
        this(port, snapshotFile, snapshotMaxAgeSeconds, authToken, rateLimitPerMinute, siteTitle, productsFile,
                storeDatabaseFile, webhookSecret, publicBaseUrl, "0.0.0.0", true, true, false);
    }
}
