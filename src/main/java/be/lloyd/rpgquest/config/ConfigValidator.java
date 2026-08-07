package be.lloyd.rpgquest.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
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
        DialogueConfig dialogue = validateDialogue(section);
        JournalConfig journal = validateJournal(section);
        AdminFlattenConfig adminFlatten = validateAdminFlatten(section);
        return new PluginConfig(debug, locale, databaseFile, resourcePack, dialogue, journal, adminFlatten);
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
            return new ResourcePackConfig(false, "", "", false);
        }

        boolean enabled = resourcePack.getBoolean("enabled", false);
        String url = resourcePack.getString("url", "");
        String sha1 = resourcePack.getString("sha1", "");
        boolean required = resourcePack.getBoolean("required", false);

        if (!enabled) {
            return new ResourcePackConfig(false, url, sha1, required);
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

        return new ResourcePackConfig(true, url, sha1.toLowerCase(Locale.ROOT), required);
    }

    private static DialogueConfig validateDialogue(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection dialogue = section.getConfigurationSection("dialogue");

        String rawRenderer = dialogue != null ? dialogue.getString("renderer", "chat") : "chat";
        RendererKind renderer = switch (rawRenderer.toLowerCase(Locale.ROOT)) {
            case "chat" -> RendererKind.CHAT;
            case "paper-dialog" -> RendererKind.PAPER_DIALOG;
            default -> throw new ConfigValidationException(
                    "« dialogue.renderer » invalide : \"" + rawRenderer + "\" (valides : chat, paper-dialog).");
        };

        List<String> rawAllowedCommands = dialogue != null ? dialogue.getStringList("allowed-commands") : List.of();
        List<String> allowedCommands = new ArrayList<>();
        for (String command : rawAllowedCommands) {
            if (command == null || command.isBlank()) {
                throw new ConfigValidationException("« dialogue.allowed-commands » contient une entrée vide.");
            }
            allowedCommands.add(command.toLowerCase(Locale.ROOT));
        }

        return new DialogueConfig(renderer, allowedCommands);
    }

    private static JournalConfig validateJournal(ConfigurationSection section) {
        ConfigurationSection journal = section.getConfigurationSection("journal");
        boolean trackerEnabled = journal == null || journal.getBoolean("tracker-enabled", true);
        return new JournalConfig(trackerEnabled);
    }

    private static AdminFlattenConfig validateAdminFlatten(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection flatten = section.getConfigurationSection("admin.flatten");
        if (flatten == null) {
            return defaultAdminFlatten();
        }

        int maxRadius = flatten.getInt("max-radius", 48);
        if (maxRadius <= 0) {
            throw new ConfigValidationException(
                    "« admin.flatten.max-radius » doit être strictement positif, valeur trouvée : " + maxRadius);
        }

        String rawShape = flatten.getString("default-shape", "SQUARE");
        FlattenShape defaultShape;
        try {
            defaultShape = FlattenShape.valueOf(rawShape.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(
                    "« admin.flatten.default-shape » invalide : \"" + rawShape + "\" (valides : SQUARE, CIRCLE).");
        }

        Material topLayer = parseBlockMaterial(flatten, "top-layer-material", "GRASS_BLOCK");
        Material subLayer = parseBlockMaterial(flatten, "sub-layer-material", "DIRT");

        int subLayerDepth = flatten.getInt("sub-layer-depth", 3);
        if (subLayerDepth < 0) {
            throw new ConfigValidationException(
                    "« admin.flatten.sub-layer-depth » ne peut pas être négatif, valeur trouvée : " + subLayerDepth);
        }

        int clearAboveHeight = flatten.getInt("clear-above-height", 10);
        if (clearAboveHeight < 0) {
            throw new ConfigValidationException(
                    "« admin.flatten.clear-above-height » ne peut pas être négatif, valeur trouvée : " + clearAboveHeight);
        }

        int confirmationTimeoutSeconds = flatten.getInt("confirmation-timeout-seconds", 30);
        if (confirmationTimeoutSeconds <= 0) {
            throw new ConfigValidationException(
                    "« admin.flatten.confirmation-timeout-seconds » doit être strictement positif, valeur trouvée : "
                            + confirmationTimeoutSeconds);
        }

        int blocksPerTick = flatten.getInt("blocks-per-tick", 4000);
        if (blocksPerTick <= 0) {
            throw new ConfigValidationException(
                    "« admin.flatten.blocks-per-tick » doit être strictement positif, valeur trouvée : " + blocksPerTick);
        }

        List<String> forbiddenWorlds = new ArrayList<>();
        for (String world : flatten.getStringList("forbidden-worlds")) {
            if (world == null || world.isBlank()) {
                throw new ConfigValidationException("« admin.flatten.forbidden-worlds » contient une entrée vide.");
            }
            forbiddenWorlds.add(world);
        }

        return new AdminFlattenConfig(maxRadius, defaultShape, topLayer, subLayer, subLayerDepth,
                clearAboveHeight, confirmationTimeoutSeconds, blocksPerTick, forbiddenWorlds);
    }

    private static Material parseBlockMaterial(ConfigurationSection section, String key, String defaultValue)
            throws ConfigValidationException {
        String raw = section.getString(key, defaultValue);
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isBlock()) {
            throw new ConfigValidationException(
                    "« admin.flatten." + key + " » doit être un bloc vanilla valide, valeur trouvée : " + raw);
        }
        return material;
    }

    private static AdminFlattenConfig defaultAdminFlatten() {
        return new AdminFlattenConfig(48, FlattenShape.SQUARE, Material.GRASS_BLOCK, Material.DIRT, 3, 10, 30, 4000, List.of());
    }
}
