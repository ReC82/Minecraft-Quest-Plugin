package com.lodygames.rpgquest.discord.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validation de la configuration (issue #202).
 *
 * <p>Plusieurs de ces cas ne sont pas théoriques : <strong>la toute première configuration réelle
 * portait le jeton GitHub sous la clé {@code DISCORD_BOT_TOKEN}, et n'avait pas de clé
 * {@code GITHUB_TOKEN}</strong>. Sans contrôle de format, le seul symptôme était un
 * « 401 Unauthorized » de Discord, qui ne dit pas lequel des deux jetons est en cause. Ces tests
 * figent le diagnostic exact.</p>
 */
class BotConfigLoaderTest {

    // Les deux valeurs ci-dessous sont des FAUX jetons, assemblés à l'exécution plutôt qu'écrits
    // en clair. Raison concrète : une chaîne littérale ayant la forme d'un jeton Discord est
    // détectée par la protection de poussée de GitHub, qui refuse alors le push — ce qui est
    // arrivé. Faire autoriser un faux secret serait la mauvaise réponse : on émousse la
    // protection pour un fichier de test. L'assemblage conserve exactement ce que le validateur
    // observe (nombre de points, longueur, préfixe) sans produire de motif reconnaissable.

    /** Forme d'un jeton de bot Discord : trois parties séparées par des points. */
    private static final String DISCORD_LIKE =
            String.join(".", "A".repeat(24), "B".repeat(6), "C".repeat(38));

    /** Forme d'un jeton GitHub à permissions fines. */
    private static final String GITHUB_LIKE = "github" + "_pat_" + "D".repeat(70);

    private static Map<String, String> validValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("DISCORD_GUILD_ID", "1445814722401144873");
        values.put("DISCORD_FORUM_CHANNEL_ID", "1556300141164503190");
        values.put("DISCORD_BOT_TOKEN", DISCORD_LIKE);
        values.put("GITHUB_REPOSITORY", "ReC82/Minecraft-Quest-Plugin");
        values.put("GITHUB_TOKEN", GITHUB_LIKE);
        return values;
    }

    @Test
    @DisplayName("Une configuration complète est acceptée et découpe le dépôt")
    void acceptsCompleteConfiguration() {
        BotConfig config = BotConfigLoader.from(validValues(), Path.of("/tmp/bot.env"));

        assertEquals("ReC82", config.githubOwner());
        assertEquals("Minecraft-Quest-Plugin", config.githubRepo());
        assertEquals(BotConfig.DEFAULT_POLL_INTERVAL, config.pollInterval());
        assertFalse(config.dryRun());
        assertEquals("https://discord.com/channels/1445814722401144873/42", config.threadUrl("42"));
    }

    @Test
    @DisplayName("Un jeton GitHub placé sous DISCORD_BOT_TOKEN est diagnostiqué, pas subi")
    void detectsSwappedTokens() {
        Map<String, String> values = validValues();
        values.put("DISCORD_BOT_TOKEN", GITHUB_LIKE);

        BotConfigException error = assertThrows(BotConfigException.class,
                () -> BotConfigLoader.from(values, Path.of("/tmp/bot.env")));

        assertTrue(error.getMessage().contains("ressemble à un jeton GitHub"), error.getMessage());
        assertTrue(error.getMessage().contains("inversées"), error.getMessage());
        assertFalse(error.getMessage().contains(GITHUB_LIKE), "le message ne doit jamais citer le secret");
    }

    @Test
    @DisplayName("Un jeton Discord placé sous GITHUB_TOKEN est diagnostiqué symétriquement")
    void detectsSwappedTokensTheOtherWay() {
        Map<String, String> values = validValues();
        values.put("GITHUB_TOKEN", DISCORD_LIKE);

        BotConfigException error = assertThrows(BotConfigException.class,
                () -> BotConfigLoader.from(values, Path.of("/tmp/bot.env")));

        assertTrue(error.getMessage().contains("ressemble à un jeton de bot Discord"), error.getMessage());
        assertFalse(error.getMessage().contains(DISCORD_LIKE), "le message ne doit jamais citer le secret");
    }

    @Test
    @DisplayName("Une clé absente nomme la clé et le fichier attendu")
    void missingKeyIsExplicit() {
        Map<String, String> values = validValues();
        values.remove("GITHUB_TOKEN");

        BotConfigException error = assertThrows(BotConfigException.class,
                () -> BotConfigLoader.from(values, Path.of("/chemin/bot.env")));

        assertTrue(error.getMessage().startsWith("GITHUB_TOKEN est absent"), error.getMessage());
        assertTrue(error.getMessage().contains("/chemin/bot.env"), error.getMessage());
    }

    @Test
    @DisplayName("Un identifiant Discord qui n'est pas un snowflake est refusé avec la marche à suivre")
    void rejectsNonSnowflake() {
        Map<String, String> values = validValues();
        values.put("DISCORD_FORUM_CHANNEL_ID", "bugs-et-suggestions");

        BotConfigException error = assertThrows(BotConfigException.class,
                () -> BotConfigLoader.from(values, null));

        assertTrue(error.getMessage().contains("snowflake"), error.getMessage());
        assertTrue(error.getMessage().contains("Copier l'identifiant"), error.getMessage());
    }

    @Test
    @DisplayName("Le préfixe « Bot » et les guillemets d'un copier-coller sont tolérés")
    void toleratesCopyPasteArtifacts() {
        Map<String, String> values = validValues();
        values.put("DISCORD_BOT_TOKEN", "Bot " + DISCORD_LIKE);
        values.put("GITHUB_TOKEN", "\"" + GITHUB_LIKE + "\"");

        BotConfig config = BotConfigLoader.from(values, null);

        assertEquals(DISCORD_LIKE, config.discordBotToken());
        assertEquals(GITHUB_LIKE, config.githubToken());
    }

    @Test
    @DisplayName("Un jeton coupé sur deux lignes est signalé comme tel")
    void rejectsTokenWithWhitespace() {
        Map<String, String> values = validValues();
        values.put("DISCORD_BOT_TOKEN", DISCORD_LIKE.substring(0, 30) + " "
                + DISCORD_LIKE.substring(30));

        BotConfigException error = assertThrows(BotConfigException.class,
                () -> BotConfigLoader.from(values, null));

        assertTrue(error.getMessage().contains("espace"), error.getMessage());
    }

    @Test
    @DisplayName("Un dépôt mal formé est refusé ; ce champ n'est pas un secret, il peut être cité")
    void rejectsMalformedRepository() {
        Map<String, String> values = validValues();
        values.put("GITHUB_REPOSITORY", "Minecraft-Quest-Plugin");

        BotConfigException error = assertThrows(BotConfigException.class,
                () -> BotConfigLoader.from(values, null));

        assertTrue(error.getMessage().contains("propriétaire/dépôt"), error.getMessage());
    }

    @Test
    @DisplayName("Une scrutation sous le plancher est refusée, pour ne pas user les quotas")
    void rejectsTooAggressivePolling() {
        Map<String, String> values = validValues();
        values.put("LODYQUESTS_POLL_SECONDS", "2");

        BotConfigException error = assertThrows(BotConfigException.class,
                () -> BotConfigLoader.from(values, null));

        assertTrue(error.getMessage().contains("plancher"), error.getMessage());
    }

    @Test
    @DisplayName("toString() ne laisse fuir aucun secret")
    void toStringHidesSecrets() {
        BotConfig config = BotConfigLoader.from(validValues(), null);

        String rendered = config.toString();

        assertFalse(rendered.contains(DISCORD_LIKE), rendered);
        assertFalse(rendered.contains(GITHUB_LIKE), rendered);
        assertTrue(rendered.contains("<masqué>"), rendered);
    }

    @Test
    @DisplayName("Le fichier d'environnement est lu, commentaires et lignes vides ignorés")
    void readsEnvFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bot.env");
        Files.writeString(file, """
                # commentaire
                DISCORD_GUILD_ID=1445814722401144873

                export DISCORD_FORUM_CHANNEL_ID='1556300141164503190'
                ligne-sans-signe-egal
                """);

        Map<String, String> values = BotConfigLoader.readEnvFile(file);

        assertEquals("1445814722401144873", values.get("DISCORD_GUILD_ID"));
        assertEquals("1556300141164503190", values.get("DISCORD_FORUM_CHANNEL_ID"));
        assertEquals(2, values.size());
    }

    @Test
    @DisplayName("Un fichier absent donne une carte vide, pas une exception")
    void missingEnvFileIsEmpty(@TempDir Path dir) {
        assertTrue(BotConfigLoader.readEnvFile(dir.resolve("absent.env")).isEmpty());
    }
}
