package com.lodygames.rpgquest.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;

/**
 * Fichier {@code .properties} local et <em>non versionné</em> du dossier du plugin, dont chaque clé
 * peut être surchargée par une variable d'environnement (modèle de l'agent PlugAdmin). Partagé par
 * les canaux sortants vers le portail ({@code /lien} #53, instantané #421) : lecture tolérante (un
 * fichier illisible vaut un fichier vide), aucune valeur n'est jamais journalisée.
 */
public final class LocalSettingsFile {

    private final Properties props;
    private final UnaryOperator<String> env;

    private LocalSettingsFile(Properties props, UnaryOperator<String> env) {
        this.props = props;
        this.env = env;
    }

    public static LocalSettingsFile read(Path file, UnaryOperator<String> env, Logger logger, String label) {
        Properties props = new Properties();
        if (file != null && Files.isReadable(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                logger.warn("{} : lecture de {} impossible ({}) — configuration ignorée.", label, file, e.getMessage());
            }
        }
        return new LocalSettingsFile(props, env);
    }

    /** Valeur de la variable d'environnement si elle est renseignée, sinon du fichier, sinon {@code fallback}. */
    public String text(String fileKey, String envKey, String fallback) {
        String fromEnv = envKey == null ? null : env.apply(envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String value = props.getProperty(fileKey);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public boolean flag(String fileKey, String envKey) {
        return "true".equalsIgnoreCase(text(fileKey, envKey, "false"));
    }

    /** Entier du fichier borné à {@code [min, max]} ; {@code fallback} s'il est absent ou invalide. */
    public int boundedInt(String fileKey, int fallback, int min, int max) {
        int value;
        try {
            String raw = props.getProperty(fileKey);
            value = raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            value = fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Adresse de base sans barre finale. */
    public String baseUrl(String fileKey, String envKey) {
        String url = text(fileKey, envKey, "");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** HTTPS obligatoire, sauf en local (tests, tunnel). */
    public static boolean isAcceptableBaseUrl(String baseUrl) {
        return baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1")
                || baseUrl.startsWith("http://localhost");
    }
}
