package com.lodygames.rpgquest.webapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Charge {@link WebApiConfig} depuis un fichier {@code .properties}
 * (chemin par défaut {@code web-api.properties}, remplaçable via la
 * variable d'environnement {@link #CONFIG_PATH_ENV}). Le jeton
 * d'authentification n'est <b>jamais</b> lu depuis ce fichier — uniquement
 * depuis {@link #TOKEN_ENV} — pour qu'il ne puisse jamais finir versionné
 * dans Git par erreur (mission étape 21, point 7).
 */
public final class WebApiConfigLoader {

    public static final String CONFIG_PATH_ENV = "RPGQUEST_WEB_API_CONFIG";
    public static final String TOKEN_ENV = "RPGQUEST_WEB_API_TOKEN";
    public static final String WEBHOOK_SECRET_ENV = "RPGQUEST_STORE_WEBHOOK_SECRET";

    private WebApiConfigLoader() {
    }

    public static WebApiConfig load() throws IOException {
        return load(resolveConfigPath(), System.getenv(TOKEN_ENV), System.getenv(WEBHOOK_SECRET_ENV));
    }

    static Path resolveConfigPath() {
        String override = System.getenv(CONFIG_PATH_ENV);
        return Path.of(override != null && !override.isBlank() ? override : "web-api.properties");
    }

    public static WebApiConfig load(Path propertiesFile, String tokenFromEnv, String webhookSecretFromEnv) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(propertiesFile)) {
            try (var in = Files.newInputStream(propertiesFile)) {
                properties.load(in);
            }
        }

        int port = parseInt(properties, "port", 8080);
        Path snapshotFile = Path.of(properties.getProperty("snapshot-file", "web-export/snapshot.json"));
        long snapshotMaxAgeSeconds = parseLong(properties, "snapshot-max-age-seconds", 120L);
        int rateLimitPerMinute = parseInt(properties, "rate-limit-per-minute", 60);
        String siteTitle = properties.getProperty("site-title", "RPGQuest");
        Path productsFile = Path.of(properties.getProperty("products-file", "products.json"));
        Path storeDatabaseFile = Path.of(properties.getProperty("store-database-file", "store.db"));
        String publicBaseUrl = properties.getProperty("public-base-url", "http://localhost:" + port);

        return new WebApiConfig(port, snapshotFile, snapshotMaxAgeSeconds, tokenFromEnv, rateLimitPerMinute, siteTitle,
                productsFile, storeDatabaseFile, webhookSecretFromEnv, publicBaseUrl);
    }

    private static int parseInt(Properties properties, String key, int defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "« " + key + " » doit être un entier dans web-api.properties, valeur trouvée : " + raw);
        }
    }

    private static long parseLong(Properties properties, String key, long defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "« " + key + " » doit être un entier dans web-api.properties, valeur trouvée : " + raw);
        }
    }
}
