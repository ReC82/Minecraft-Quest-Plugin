package com.lodygames.rpgquest.discord.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture et <strong>validation stricte</strong> de la configuration (issue #202).
 *
 * <p><strong>Pourquoi cette validation est aussi bavarde.</strong> Les deux jetons de ce service
 * se ressemblent de loin — ce sont deux longues chaînes opaques — mais ils n'ont rien à voir, et
 * une confusion donne une erreur distante inutilisable (« 401 Unauthorized », sans dire lequel des
 * deux est en cause). Le format de chaque jeton est donc vérifié <em>avant</em> tout appel réseau :
 * un jeton de bot Discord est fait de trois parties séparées par des points, un jeton GitHub porte
 * un préfixe reconnaissable ({@code ghp_}, {@code github_pat_}…). Le service sait donc dire
 * « ces deux valeurs semblent inversées » au lieu de « non autorisé ».</p>
 *
 * <p><strong>Aucune valeur de secret n'apparaît dans un message d'erreur</strong> : seuls le nom de
 * la clé, le fichier attendu et la nature du problème sont cités.</p>
 */
public final class BotConfigLoader {

    /** Emplacement par défaut du fichier de secrets, hors dépôt et jamais versionné. */
    public static final Path DEFAULT_ENV_FILE =
            Path.of(System.getProperty("user.home"), ".config", "lodyquests-discord", "bot.env");

    /** Emplacement par défaut de la base d'état du service. */
    public static final Path DEFAULT_DB_PATH = Path.of("/var/lib/lodyquests-discord/sync.db");

    private static final List<String> GITHUB_TOKEN_PREFIXES =
            List.of("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_");

    private BotConfigLoader() {
    }

    /**
     * Charge la configuration depuis l'environnement du processus, complété par le fichier de
     * secrets s'il existe. L'environnement gagne : systemd injecte déjà
     * {@code EnvironmentFile=…/bot.env}, et une variable explicite doit pouvoir le surcharger pour
     * un diagnostic ponctuel.
     */
    public static BotConfig fromEnvironment() {
        Map<String, String> values = new LinkedHashMap<>(readEnvFile(DEFAULT_ENV_FILE));
        System.getenv().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                values.put(k, v);
            }
        });
        return from(values, DEFAULT_ENV_FILE);
    }

    /**
     * Lit un fichier au format {@code CLÉ=valeur}. Absent ou illisible ⇒ carte vide : l'absence
     * d'une clé sera signalée plus loin, avec un message qui nomme le fichier attendu.
     */
    public static Map<String, String> readEnvFile(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (file == null || !Files.isReadable(file)) {
            return values;
        }
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                if (key.startsWith("export ")) {
                    key = key.substring("export ".length()).strip();
                }
                values.put(key, unquote(line.substring(eq + 1).strip()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture impossible de " + file, e);
        }
        return values;
    }

    /** Valide une carte de valeurs déjà lue. {@code envFile} ne sert qu'aux messages d'erreur. */
    public static BotConfig from(Map<String, String> values, Path envFile) {
        String where = envFile == null ? "le fichier de configuration" : envFile.toString();

        String guildId = snowflake(values, "DISCORD_GUILD_ID", where);
        String forumId = snowflake(values, "DISCORD_FORUM_CHANNEL_ID", where);
        String discordToken = discordToken(values, where);
        String repository = repository(values, where);
        String githubToken = githubToken(values, where);

        Path db = value(values, "LODYQUESTS_SYNC_DB") == null
                ? DEFAULT_DB_PATH
                : Path.of(value(values, "LODYQUESTS_SYNC_DB"));

        Duration poll = BotConfig.DEFAULT_POLL_INTERVAL;
        String pollRaw = value(values, "LODYQUESTS_POLL_SECONDS");
        if (pollRaw != null) {
            try {
                poll = Duration.ofSeconds(Long.parseLong(pollRaw));
            } catch (NumberFormatException e) {
                throw new BotConfigException("LODYQUESTS_POLL_SECONDS doit être un nombre de "
                        + "secondes (valeur rejetée, non citée ici).");
            }
            if (poll.compareTo(BotConfig.MIN_POLL_INTERVAL) < 0) {
                throw new BotConfigException("LODYQUESTS_POLL_SECONDS est inférieur au plancher de "
                        + BotConfig.MIN_POLL_INTERVAL.toSeconds() + " s : une scrutation plus "
                        + "agressive userait les quotas Discord et GitHub sans gain réel.");
            }
        }

        boolean dryRun = Boolean.parseBoolean(String.valueOf(value(values, "LODYQUESTS_DRY_RUN")));

        return new BotConfig(guildId, forumId, discordToken, repository, githubToken, db, poll, dryRun);
    }

    // ---- Validations par clé ---------------------------------------------------------------

    private static String snowflake(Map<String, String> values, String key, String where) {
        String raw = required(values, key, where);
        if (!raw.chars().allMatch(Character::isDigit) || raw.length() < 15 || raw.length() > 20) {
            throw new BotConfigException(key + " doit être un identifiant Discord (« snowflake ») : "
                    + "uniquement des chiffres, 15 à 20 caractères. Dans Discord : activer le mode "
                    + "développeur, puis clic droit sur le serveur ou le salon → « Copier l'identifiant ».");
        }
        return raw;
    }

    private static String discordToken(Map<String, String> values, String where) {
        String raw = required(values, "DISCORD_BOT_TOKEN", where);
        // Tolérance utile : le jeton est souvent copié avec le préfixe de l'en-tête HTTP.
        if (raw.regionMatches(true, 0, "Bot ", 0, 4)) {
            raw = raw.substring(4).strip();
        }
        if (looksLikeGitHubToken(raw)) {
            throw new BotConfigException("DISCORD_BOT_TOKEN contient ce qui ressemble à un jeton "
                    + "GitHub, pas à un jeton de bot Discord. Les deux valeurs semblent inversées "
                    + "ou mal placées dans " + where + " : DISCORD_BOT_TOKEN attend le jeton de "
                    + "l'application Discord (onglet « Bot » → « Reset Token »), et GITHUB_TOKEN le "
                    + "jeton GitHub. Aucune valeur n'est affichée ici.");
        }
        if (raw.chars().anyMatch(Character::isWhitespace)) {
            throw new BotConfigException("DISCORD_BOT_TOKEN contient une espace ou un retour à la "
                    + "ligne : la valeur a probablement été copiée tronquée ou sur deux lignes.");
        }
        if (raw.chars().filter(c -> c == '.').count() != 2 || raw.length() < 50) {
            throw new BotConfigException("DISCORD_BOT_TOKEN n'a pas la forme d'un jeton de bot "
                    + "Discord (trois parties séparées par des points, plus de 50 caractères). "
                    + "Attention : la page Discord affiche aussi un « Client Secret » et un "
                    + "« Public Key », qui ne sont pas le jeton du bot. Aucune valeur n'est affichée ici.");
        }
        return raw;
    }

    private static String githubToken(Map<String, String> values, String where) {
        String raw = required(values, "GITHUB_TOKEN", where);
        if (raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
            raw = raw.substring(7).strip();
        }
        if (raw.chars().filter(c -> c == '.').count() == 2 && !looksLikeGitHubToken(raw)) {
            throw new BotConfigException("GITHUB_TOKEN contient ce qui ressemble à un jeton de bot "
                    + "Discord, pas à un jeton GitHub. Les deux valeurs semblent inversées dans "
                    + where + ". Aucune valeur n'est affichée ici.");
        }
        if (raw.chars().anyMatch(Character::isWhitespace)) {
            throw new BotConfigException("GITHUB_TOKEN contient une espace ou un retour à la ligne : "
                    + "la valeur a probablement été copiée tronquée ou sur deux lignes.");
        }
        if (!looksLikeGitHubToken(raw)) {
            throw new BotConfigException("GITHUB_TOKEN ne porte aucun préfixe de jeton GitHub connu "
                    + "(" + String.join(", ", GITHUB_TOKEN_PREFIXES) + "). Créer un jeton à "
                    + "permissions fines avec « Issues : Read and write » sur le seul dépôt visé. "
                    + "Aucune valeur n'est affichée ici.");
        }
        return raw;
    }

    private static String repository(Map<String, String> values, String where) {
        String raw = required(values, "GITHUB_REPOSITORY", where);
        int slash = raw.indexOf('/');
        if (slash <= 0 || slash != raw.lastIndexOf('/') || slash == raw.length() - 1) {
            throw new BotConfigException("GITHUB_REPOSITORY doit être au format "
                    + "« propriétaire/dépôt » (exemple : ReC82/Minecraft-Quest-Plugin). Valeur lue : "
                    + raw + " — ce champ n'est pas un secret, il peut donc être cité.");
        }
        return raw;
    }

    private static boolean looksLikeGitHubToken(String raw) {
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        return GITHUB_TOKEN_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private static String required(Map<String, String> values, String key, String where) {
        String raw = value(values, key);
        if (raw == null) {
            throw new BotConfigException(key + " est absent ou vide. Le service attend cette clé "
                    + "dans " + where + " (format « " + key + "=valeur », un couple par ligne), ou "
                    + "dans son environnement. Ce fichier ne doit jamais être versionné.");
        }
        return raw;
    }

    private static String value(Map<String, String> values, String key) {
        String raw = values.get(key);
        if (raw == null) {
            return null;
        }
        raw = unquote(raw.strip());
        return raw.isEmpty() ? null : raw;
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2
                && ((raw.startsWith("\"") && raw.endsWith("\""))
                || (raw.startsWith("'") && raw.endsWith("'")))) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }
}
