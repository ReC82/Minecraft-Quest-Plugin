package com.lodygames.rpgquest.discord.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration effective du service de synchronisation (issue #202).
 *
 * <p>Les secrets ({@link #discordBotToken()}, {@link #githubToken()}) ne sont <strong>jamais</strong>
 * journalisés ni inclus dans un rapport : {@link #toString()} est volontairement redéfini pour ne
 * rendre que les valeurs non sensibles.</p>
 *
 * @param guildId         serveur Discord surveillé — un seul, jamais « tous les serveurs »
 * @param forumChannelId  salon de forum surveillé — un seul
 * @param discordBotToken jeton du bot Discord
 * @param githubRepository dépôt cible, au format {@code propriétaire/dépôt}
 * @param githubToken     jeton GitHub à permissions Issues lecture/écriture sur ce seul dépôt
 * @param databasePath    base SQLite d'état, séparée de toutes les autres bases du projet
 * @param pollInterval    intervalle entre deux tours de synchronisation
 * @param dryRun          vrai = aucune écriture (ni GitHub, ni Discord), uniquement un diagnostic
 */
public record BotConfig(
        String guildId,
        String forumChannelId,
        String discordBotToken,
        String githubRepository,
        String githubToken,
        Path databasePath,
        Duration pollInterval,
        boolean dryRun) {

    /** Intervalle de scrutation par défaut : un forum communautaire n'a pas besoin de mieux. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(60);

    /** Plancher : protège les quotas Discord et GitHub d'une configuration trop agressive. */
    public static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(15);

    public String githubOwner() {
        return githubRepository.substring(0, githubRepository.indexOf('/'));
    }

    public String githubRepo() {
        return githubRepository.substring(githubRepository.indexOf('/') + 1);
    }

    /** Lien public du salon de forum surveillé. */
    public String forumUrl() {
        return "https://discord.com/channels/" + guildId + "/" + forumChannelId;
    }

    /** Lien public d'un sujet du forum. */
    public String threadUrl(String threadId) {
        return "https://discord.com/channels/" + guildId + "/" + threadId;
    }

    @Override
    public String toString() {
        return "BotConfig[guildId=" + guildId
                + ", forumChannelId=" + forumChannelId
                + ", githubRepository=" + githubRepository
                + ", databasePath=" + databasePath
                + ", pollInterval=" + pollInterval.toSeconds() + "s"
                + ", dryRun=" + dryRun
                + ", discordBotToken=<masqué>, githubToken=<masqué>]";
    }
}
