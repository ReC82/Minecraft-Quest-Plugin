package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.Role;
import java.time.Instant;

/**
 * Un compte PlugAdmin (issue #50). Stocké dans {@code control-panel.db}, table {@code panel_user} —
 * jamais dans {@code data.db} du plugin. Le mot de passe n'est jamais conservé en clair : seul
 * {@link #passwordHash} (PBKDF2-HMAC-SHA256, voir
 * {@link com.lodygames.rpgquest.panel.security.PasswordHasher}) est stocké.
 *
 * @param id           identifiant interne stable (UUID)
 * @param username     identifiant de connexion, unique (comparaison insensible à la casse)
 * @param passwordHash hash encodé du mot de passe — jamais rendu, jamais journalisé
 * @param role         rôle unique du compte (MVP : un seul rôle par utilisateur)
 * @param active       {@code false} = le compte ne peut plus s'authentifier ni utiliser sa session
 * @param createdAt    date de création
 * @param lastLoginAt  dernière connexion réussie, ou {@code null} si jamais connecté
 */
public record PanelUser(
        String id,
        String username,
        String passwordHash,
        Role role,
        boolean active,
        Instant createdAt,
        Instant lastLoginAt) {

    public boolean isOwner() {
        return role == Role.OWNER;
    }
}
