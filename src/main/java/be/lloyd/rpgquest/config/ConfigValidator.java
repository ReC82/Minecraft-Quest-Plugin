package be.lloyd.rpgquest.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Valide le contenu de {@code config.yml} et produit un {@link PluginConfig}
 * immuable, ou échoue avec un message précis. Ne dépend que de
 * {@link ConfigurationSection} (pas de {@code JavaPlugin}) : testable en
 * JUnit pur, sans MockBukkit.
 */
public final class ConfigValidator {

    private static final Set<String> VALID_LANGUAGE_CODES = Set.of(Locale.getISOLanguages());
    private static final int SHA1_HEX_LENGTH = 40;

    private ConfigValidator() {
    }

    public static PluginConfig validate(ConfigurationSection section) throws ConfigValidationException {
        boolean debug = validateDebug(section);
        String locale = validateLocale(section);
        String databaseFile = validateDatabaseFile(section);
        ResourcePackConfig resourcePack = validateResourcePack(section);
        return new PluginConfig(debug, locale, databaseFile, resourcePack);
    }

    private static boolean validateDebug(ConfigurationSection section) throws ConfigValidationException {
        if (!section.isSet("debug")) {
            return false;
        }
        if (!section.isBoolean("debug")) {
            throw new ConfigValidationException(
                    "« debug » doit être un booléen (true/false), valeur trouvée : " + section.get("debug"));
        }
        return section.getBoolean("debug");
    }

    private static String validateLocale(ConfigurationSection section) throws ConfigValidationException {
        String locale = section.getString("locale", "fr");
        if (locale.isBlank()) {
            throw new ConfigValidationException("« locale » ne peut pas être vide.");
        }
        if (!VALID_LANGUAGE_CODES.contains(locale.toLowerCase(Locale.ROOT))) {
            throw new ConfigValidationException(
                    "« locale » invalide : \"" + locale + "\" n'est pas un code de langue ISO 639-1 reconnu.");
        }
        return locale.toLowerCase(Locale.ROOT);
    }

    private static String validateDatabaseFile(ConfigurationSection section) throws ConfigValidationException {
        String file = section.getString("database.file", "data.db");
        if (file.isBlank()) {
            throw new ConfigValidationException("« database.file » ne peut pas être vide.");
        }
        if (file.contains("/") || file.contains("\\") || file.contains("..")) {
            throw new ConfigValidationException(
                    "« database.file » doit être un simple nom de fichier (sans séparateur de dossier ni \"..\"), "
                            + "valeur trouvée : " + file);
        }
        return file;
    }

    private static ResourcePackConfig validateResourcePack(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection resourcePack = section.getConfigurationSection("resource-pack");
        if (resourcePack == null) {
            return new ResourcePackConfig(false, "", "");
        }

        boolean enabled = resourcePack.getBoolean("enabled", false);
        String url = resourcePack.getString("url", "");
        String sha1 = resourcePack.getString("sha1", "");

        if (!enabled) {
            return new ResourcePackConfig(false, url, sha1);
        }

        if (url.isBlank()) {
            throw new ConfigValidationException(
                    "« resource-pack.url » est requis quand « resource-pack.enabled » vaut true.");
        }
        String scheme;
        try {
            scheme = new URI(url).getScheme();
        } catch (URISyntaxException e) {
            throw new ConfigValidationException("« resource-pack.url » n'est pas une URL valide : " + url);
        }
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new ConfigValidationException(
                    "« resource-pack.url » doit utiliser http ou https, valeur trouvée : " + url);
        }

        if (!sha1.matches("(?i)[0-9a-f]{" + SHA1_HEX_LENGTH + "}")) {
            throw new ConfigValidationException(
                    "« resource-pack.sha1 » doit être un hash SHA-1 hexadécimal de 40 caractères "
                            + "quand le resource pack est activé.");
        }

        return new ResourcePackConfig(true, url, sha1.toLowerCase(Locale.ROOT));
    }
}
