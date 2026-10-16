package com.lodygames.rpgquest.webapi;

import com.lodygames.rpgquest.webapi.account.AccountSettings;
import com.lodygames.rpgquest.webapi.ingest.SnapshotIngestSettings;
import com.lodygames.rpgquest.webapi.privacy.AgePolicy;
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
 *
 * <p>{@code agePolicy} (#55) : politique d'âge des futurs comptes joueurs (#53), fermée tant que le
 * propriétaire n'a configuré aucune région.</p>
 *
 * <p>{@code accounts} (#53) : comptes joueurs et liaison Minecraft, désactivés par défaut.</p>
 *
 * <p>{@code site} (#56) : actualités et informations de connexion du portail public.</p>
 *
 * <p>{@code snapshotIngest} (#421) : réception authentifiée de l'instantané poussé par le serveur
 * Minecraft, fermée sans jeton dédié.</p>
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
        boolean trustProxyHeaders,
        AgePolicy agePolicy,
        AccountSettings accounts,
        SiteSettings site,
        SnapshotIngestSettings snapshotIngest
) {

    /** Sans réception d'instantané (#421) : {@code POST /api/snapshot} refuse tout. */
    public WebApiConfig(int port, Path snapshotFile, long snapshotMaxAgeSeconds, String authToken,
            int rateLimitPerMinute, String siteTitle, Path productsFile, Path storeDatabaseFile,
            String webhookSecret, String publicBaseUrl, String bindAddress, boolean storeEnabled,
            boolean playerDataPublic, boolean trustProxyHeaders, AgePolicy agePolicy, AccountSettings accounts,
            SiteSettings site) {
        this(port, snapshotFile, snapshotMaxAgeSeconds, authToken, rateLimitPerMinute, siteTitle, productsFile,
                storeDatabaseFile, webhookSecret, publicBaseUrl, bindAddress, storeEnabled, playerDataPublic,
                trustProxyHeaders, agePolicy, accounts, site, SnapshotIngestSettings.disabled());
    }

    /** Sans contenu éditorial explicite (#56) : dossier {@code news} par défaut. */
    public WebApiConfig(int port, Path snapshotFile, long snapshotMaxAgeSeconds, String authToken,
            int rateLimitPerMinute, String siteTitle, Path productsFile, Path storeDatabaseFile,
            String webhookSecret, String publicBaseUrl, String bindAddress, boolean storeEnabled,
            boolean playerDataPublic, boolean trustProxyHeaders, AgePolicy agePolicy, AccountSettings accounts) {
        this(port, snapshotFile, snapshotMaxAgeSeconds, authToken, rateLimitPerMinute, siteTitle, productsFile,
                storeDatabaseFile, webhookSecret, publicBaseUrl, bindAddress, storeEnabled, playerDataPublic,
                trustProxyHeaders, agePolicy, accounts, SiteSettings.defaults());
    }

    /** Sans comptes joueurs (#53). */
    public WebApiConfig(int port, Path snapshotFile, long snapshotMaxAgeSeconds, String authToken,
            int rateLimitPerMinute, String siteTitle, Path productsFile, Path storeDatabaseFile,
            String webhookSecret, String publicBaseUrl, String bindAddress, boolean storeEnabled,
            boolean playerDataPublic, boolean trustProxyHeaders, AgePolicy agePolicy) {
        this(port, snapshotFile, snapshotMaxAgeSeconds, authToken, rateLimitPerMinute, siteTitle, productsFile,
                storeDatabaseFile, webhookSecret, publicBaseUrl, bindAddress, storeEnabled, playerDataPublic,
                trustProxyHeaders, agePolicy, AccountSettings.disabled());
    }

    /** Sans politique d'âge explicite : comptes fermés. */
    public WebApiConfig(int port, Path snapshotFile, long snapshotMaxAgeSeconds, String authToken,
            int rateLimitPerMinute, String siteTitle, Path productsFile, Path storeDatabaseFile,
            String webhookSecret, String publicBaseUrl, String bindAddress, boolean storeEnabled,
            boolean playerDataPublic, boolean trustProxyHeaders) {
        this(port, snapshotFile, snapshotMaxAgeSeconds, authToken, rateLimitPerMinute, siteTitle, productsFile,
                storeDatabaseFile, webhookSecret, publicBaseUrl, bindAddress, storeEnabled, playerDataPublic,
                trustProxyHeaders, AgePolicy.closed());
    }

    /** Configuration historique (avant #52) : toutes interfaces, boutique et données joueurs actives. */
    public WebApiConfig(int port, Path snapshotFile, long snapshotMaxAgeSeconds, String authToken,
            int rateLimitPerMinute, String siteTitle, Path productsFile, Path storeDatabaseFile,
            String webhookSecret, String publicBaseUrl) {
        this(port, snapshotFile, snapshotMaxAgeSeconds, authToken, rateLimitPerMinute, siteTitle, productsFile,
                storeDatabaseFile, webhookSecret, publicBaseUrl, "0.0.0.0", true, true, false);
    }
}
