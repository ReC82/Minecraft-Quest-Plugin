package com.lodygames.rpgquest.webapi.account;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Réglages des comptes joueurs du portail (issue #53).
 *
 * <p>{@code enabled=false} (défaut) : aucune inscription, aucune connexion, aucune liaison ; les
 * pages {@code /compte} l'annoncent simplement. Même activés, les comptes restent fermés tant que la
 * politique d'âge (#55) n'a aucune région configurée.</p>
 *
 * <p>{@code linkToken} authentifie le serveur Minecraft sur {@code POST /api/link/confirm}. Il vient
 * exclusivement de la variable d'environnement {@code RPGQUEST_PORTAL_LINK_TOKEN}, jamais d'un fichier
 * versionné, et n'est <em>pas</em> le jeton général de l'API : il n'ouvre que cette route.</p>
 *
 * @param secureCookies cookies {@code Secure} — vrai dès que le portail est servi en HTTPS
 */
public record AccountSettings(
        boolean enabled,
        Path databaseFile,
        Duration sessionTtl,
        Duration linkCodeTtl,
        String linkToken,
        boolean secureCookies
) {

    public static AccountSettings disabled() {
        return new AccountSettings(false, Path.of("accounts.db"), Duration.ofDays(7), Duration.ofMinutes(10), null, true);
    }
}
