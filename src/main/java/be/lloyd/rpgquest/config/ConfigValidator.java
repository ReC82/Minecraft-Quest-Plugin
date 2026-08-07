package be.lloyd.rpgquest.config;

import be.lloyd.rpgquest.backpack.model.BackpackSize;
import be.lloyd.rpgquest.progression.model.SkillType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        ClaimConfig claims = validateClaims(section);
        ProgressionConfig progression = validateProgression(section);
        BackpackConfig backpacks = validateBackpacks(section);
        WebExportConfig webExport = validateWebExport(section);
        StoreConfig store = validateStore(section);
        return new PluginConfig(
                debug, locale, databaseFile, resourcePack, dialogue, journal, adminFlatten, claims, progression,
                backpacks, webExport, store);
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

    private static ClaimConfig validateClaims(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection claims = section.getConfigurationSection("claims");
        if (claims == null) {
            return defaultClaims();
        }

        int maxWidth = claims.getInt("max-width", 64);
        if (maxWidth <= 0) {
            throw new ConfigValidationException(
                    "« claims.max-width » doit être strictement positif, valeur trouvée : " + maxWidth);
        }
        int maxHeight = claims.getInt("max-height", 384);
        if (maxHeight <= 0) {
            throw new ConfigValidationException(
                    "« claims.max-height » doit être strictement positif, valeur trouvée : " + maxHeight);
        }
        int maxClaimsPerPlayer = claims.getInt("max-claims-per-player", 3);
        if (maxClaimsPerPlayer < 0) {
            throw new ConfigValidationException(
                    "« claims.max-claims-per-player » ne peut pas être négatif, valeur trouvée : " + maxClaimsPerPlayer);
        }
        int portalBufferBlocks = claims.getInt("portal-buffer-blocks", 16);
        if (portalBufferBlocks < 0) {
            throw new ConfigValidationException(
                    "« claims.portal-buffer-blocks » ne peut pas être négatif, valeur trouvée : " + portalBufferBlocks);
        }

        return new ClaimConfig(maxWidth, maxHeight, maxClaimsPerPlayer, portalBufferBlocks);
    }

    private static ClaimConfig defaultClaims() {
        return new ClaimConfig(64, 384, 3, 16);
    }

    private static ProgressionConfig validateProgression(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection progression = section.getConfigurationSection("progression");
        if (progression == null) {
            return defaultProgression();
        }

        long baseXp = progression.getLong("base-xp", 100L);
        if (baseXp <= 0) {
            throw new ConfigValidationException(
                    "« progression.base-xp » doit être strictement positif, valeur trouvée : " + baseXp);
        }
        double growthFactor = progression.getDouble("growth-factor", 1.15);
        if (growthFactor < 1.0) {
            throw new ConfigValidationException(
                    "« progression.growth-factor » doit être supérieur ou égal à 1.0, valeur trouvée : " + growthFactor);
        }
        int maxLevel = progression.getInt("max-level", 100);
        if (maxLevel < 1) {
            throw new ConfigValidationException(
                    "« progression.max-level » doit être au moins 1, valeur trouvée : " + maxLevel);
        }
        double globalMirrorRatio = progression.getDouble("global-mirror-ratio", 0.5);
        if (globalMirrorRatio < 0.0 || globalMirrorRatio > 1.0) {
            throw new ConfigValidationException(
                    "« progression.global-mirror-ratio » doit être compris entre 0.0 et 1.0, valeur trouvée : "
                            + globalMirrorRatio);
        }
        int maxGrantsPerMinute = progression.getInt("max-grants-per-minute", 60);
        if (maxGrantsPerMinute <= 0) {
            throw new ConfigValidationException(
                    "« progression.max-grants-per-minute » doit être strictement positif, valeur trouvée : "
                            + maxGrantsPerMinute);
        }

        String rawDisplayMode = progression.getString("display-mode", "action_bar");
        DisplayMode displayMode;
        try {
            displayMode = DisplayMode.valueOf(rawDisplayMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(
                    "« progression.display-mode » invalide : \"" + rawDisplayMode
                            + "\" (valides : action_bar, boss_bar, off).");
        }

        boolean keepVanillaXp = progression.getBoolean("keep-vanilla-xp", true);

        int questCompletionXp = nonNegativeInt(progression, "quest-completion-xp", 50);
        int combatKillXp = nonNegativeInt(progression, "sources.combat-kill-xp", 15);
        int miningBlockXp = nonNegativeInt(progression, "sources.mining-block-xp", 5);
        int farmingHarvestXp = nonNegativeInt(progression, "sources.farming-harvest-xp", 4);
        int fishingCatchXp = nonNegativeInt(progression, "sources.fishing-catch-xp", 10);
        int explorationZoneXp = nonNegativeInt(progression, "sources.exploration-zone-xp", 100);

        return new ProgressionConfig(baseXp, growthFactor, maxLevel, globalMirrorRatio, maxGrantsPerMinute,
                displayMode, keepVanillaXp, questCompletionXp, combatKillXp, miningBlockXp, farmingHarvestXp,
                fishingCatchXp, explorationZoneXp);
    }

    private static int nonNegativeInt(ConfigurationSection section, String key, int defaultValue)
            throws ConfigValidationException {
        int value = section.getInt(key, defaultValue);
        if (value < 0) {
            throw new ConfigValidationException(
                    "« progression." + key + " » ne peut pas être négatif, valeur trouvée : " + value);
        }
        return value;
    }

    private static ProgressionConfig defaultProgression() {
        return new ProgressionConfig(100L, 1.15, 100, 0.5, 60, DisplayMode.ACTION_BAR, true, 50, 15, 5, 4, 10, 100);
    }

    private static BackpackConfig validateBackpacks(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection backpacks = section.getConfigurationSection("backpacks");
        if (backpacks == null) {
            return defaultBackpacks();
        }

        int smallRows = rowCount(backpacks, "small-rows", 1);
        int mediumRows = rowCount(backpacks, "medium-rows", 3);
        int largeRows = rowCount(backpacks, "large-rows", 6);
        if (!(smallRows < mediumRows && mediumRows < largeRows)) {
            throw new ConfigValidationException(
                    "« backpacks.small-rows » < « medium-rows » < « large-rows » doit être strictement croissant, "
                            + "valeurs trouvées : " + smallRows + ", " + mediumRows + ", " + largeRows + ".");
        }

        Set<Material> forbidden = new LinkedHashSet<>();
        for (String raw : backpacks.getStringList("forbidden-materials")) {
            if (raw == null || raw.isBlank()) {
                throw new ConfigValidationException("« backpacks.forbidden-materials » contient une entrée vide.");
            }
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                throw new ConfigValidationException(
                        "« backpacks.forbidden-materials » contient un matériau inconnu : \"" + raw + "\".");
            }
            forbidden.add(material);
        }

        String rawFallback = backpacks.getString("fallback-size", "SMALL");
        BackpackSize fallbackSize;
        try {
            fallbackSize = BackpackSize.valueOf(rawFallback.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigValidationException(
                    "« backpacks.fallback-size » invalide : \"" + rawFallback + "\" (valides : SMALL, MEDIUM, LARGE).");
        }

        String rawOpenItem = backpacks.getString("open-item-material", "BUNDLE");
        Material openItemMaterial = Material.matchMaterial(rawOpenItem);
        if (openItemMaterial == null) {
            throw new ConfigValidationException(
                    "« backpacks.open-item-material » invalide : \"" + rawOpenItem + "\".");
        }

        return new BackpackConfig(smallRows, mediumRows, largeRows, Set.copyOf(forbidden), fallbackSize, openItemMaterial);
    }

    private static int rowCount(ConfigurationSection section, String key, int defaultValue)
            throws ConfigValidationException {
        int value = section.getInt(key, defaultValue);
        if (value < 1 || value > 6) {
            throw new ConfigValidationException(
                    "« backpacks." + key + " » doit être compris entre 1 et 6, valeur trouvée : " + value);
        }
        return value;
    }

    private static BackpackConfig defaultBackpacks() {
        return new BackpackConfig(1, 3, 6, Set.of(), BackpackSize.SMALL, Material.BUNDLE);
    }

    private static WebExportConfig validateWebExport(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection web = section.getConfigurationSection("web-export");
        if (web == null) {
            return defaultWebExport();
        }

        boolean enabled = web.getBoolean("enabled", false);

        String outputDirectory = web.getString("output-dir", "web-export");
        if (outputDirectory.isBlank() || outputDirectory.contains("..")
                || outputDirectory.contains("/") || outputDirectory.contains("\\")) {
            throw new ConfigValidationException(
                    "« web-export.output-dir » doit être un simple nom de dossier (sans séparateur ni \"..\"), "
                            + "valeur trouvée : " + outputDirectory);
        }

        int intervalSeconds = web.getInt("interval-seconds", 30);
        if (intervalSeconds < 5) {
            throw new ConfigValidationException(
                    "« web-export.interval-seconds » doit être au moins 5, valeur trouvée : " + intervalSeconds);
        }

        boolean includeConnectedPlayers = web.getBoolean("include-connected-players", false);

        int leaderboardSize = web.getInt("leaderboard-size", 10);
        if (leaderboardSize < 1 || leaderboardSize > 100) {
            throw new ConfigValidationException(
                    "« web-export.leaderboard-size » doit être compris entre 1 et 100, valeur trouvée : " + leaderboardSize);
        }

        List<String> rawSkills = web.getStringList("leaderboard-skills");
        List<SkillType> leaderboardSkills = new ArrayList<>();
        if (rawSkills.isEmpty()) {
            leaderboardSkills.addAll(List.of(SkillType.values()));
        } else {
            for (String raw : rawSkills) {
                try {
                    leaderboardSkills.add(SkillType.valueOf(raw.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new ConfigValidationException(
                            "« web-export.leaderboard-skills » contient une compétence inconnue : \"" + raw + "\".");
                }
            }
        }

        List<WebExportConfig.Announcement> announcements = new ArrayList<>();
        for (Map<?, ?> raw : web.getMapList("announcements")) {
            Object title = raw.get("title");
            if (!(title instanceof String titleText) || titleText.isBlank()) {
                throw new ConfigValidationException(
                        "« web-export.announcements » contient une entrée sans « title » valide.");
            }
            Object body = raw.get("body");
            announcements.add(new WebExportConfig.Announcement(titleText, body instanceof String bodyText ? bodyText : ""));
        }

        return new WebExportConfig(enabled, outputDirectory, intervalSeconds, includeConnectedPlayers,
                leaderboardSize, List.copyOf(leaderboardSkills), List.copyOf(announcements));
    }

    private static WebExportConfig defaultWebExport() {
        return new WebExportConfig(false, "web-export", 30, false, 10, List.of(SkillType.values()), List.of());
    }

    private static StoreConfig validateStore(ConfigurationSection section) throws ConfigValidationException {
        ConfigurationSection store = section.getConfigurationSection("store");
        if (store == null) {
            return defaultStore();
        }

        boolean enabled = store.getBoolean("enabled", false);

        String webApiBaseUrl = store.getString("web-api-base-url", "http://localhost:8080");
        if (webApiBaseUrl.isBlank()) {
            throw new ConfigValidationException("« store.web-api-base-url » ne peut pas être vide.");
        }
        try {
            java.net.URI uri = new java.net.URI(webApiBaseUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ConfigValidationException(
                        "« store.web-api-base-url » doit utiliser http ou https, valeur trouvée : " + webApiBaseUrl);
            }
        } catch (java.net.URISyntaxException e) {
            throw new ConfigValidationException("« store.web-api-base-url » n'est pas une URL valide : " + webApiBaseUrl);
        }

        int pollIntervalSeconds = store.getInt("poll-interval-seconds", 30);
        if (pollIntervalSeconds < 5) {
            throw new ConfigValidationException(
                    "« store.poll-interval-seconds » doit être au moins 5, valeur trouvée : " + pollIntervalSeconds);
        }

        return new StoreConfig(enabled, webApiBaseUrl, pollIntervalSeconds);
    }

    private static StoreConfig defaultStore() {
        return new StoreConfig(false, "http://localhost:8080", 30);
    }
}
