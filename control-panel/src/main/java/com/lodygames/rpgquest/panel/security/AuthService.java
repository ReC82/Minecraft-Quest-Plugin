package com.lodygames.rpgquest.panel.security;

import com.lodygames.rpgquest.panel.authz.Role;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Authentification V1 : un seul compte {@code owner} (nom + hash de mot de passe issus de
 * l'environnement). Aucune logique {@code if username == …} dispersée ailleurs — tout passe par
 * {@link #authenticate}.
 */
public final class AuthService {

    private final String ownerUsername;
    private final String ownerPasswordHash;
    private final PasswordHasher hasher;

    public AuthService(String ownerUsername, String ownerPasswordHash, PasswordHasher hasher) {
        this.ownerUsername = ownerUsername;
        this.ownerPasswordHash = ownerPasswordHash;
        this.hasher = hasher;
    }

    /**
     * @return le rôle si les identifiants sont valides, sinon vide. Le coût de vérification du mot
     *         de passe est payé même quand le nom d'utilisateur ne correspond pas, pour ne pas
     *         révéler par le temps de réponse si le compte existe.
     */
    public Optional<Role> authenticate(String username, String password) {
        boolean userMatches = MessageDigest.isEqual(
                safe(username).getBytes(StandardCharsets.UTF_8),
                ownerUsername.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = hasher.verify(safe(password), ownerPasswordHash);
        return userMatches && passwordMatches ? Optional.of(Role.OWNER) : Optional.empty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
