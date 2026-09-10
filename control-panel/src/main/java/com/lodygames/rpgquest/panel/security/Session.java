package com.lodygames.rpgquest.panel.security;

import java.time.Instant;

/**
 * Session serveur-side. L'identifiant ({@code id}) est aléatoire (256 bits) et n'apparaît côté
 * navigateur que dans un cookie signé (voir {@link SessionStore#signedCookieValue}). {@code
 * csrfToken} est le jeton synchroniseur exigé sur chaque POST authentifié.
 */
public final class Session {

    private final String id;
    private final String userId;
    private final String username;
    private volatile String role;
    private final String csrfToken;
    private final Instant createdAt;
    private volatile Instant lastSeenAt;

    Session(String id, String userId, String username, String role, String csrfToken, Instant now) {
        this.id = id;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.csrfToken = csrfToken;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public String id() {
        return id;
    }

    /** Identifiant interne du compte {@code panel_user} — stable, sert au re-contrôle par requête. */
    public String userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public String role() {
        return role;
    }

    /**
     * Réaligne le rôle porté par la session sur celui du compte en base : un changement de rôle
     * prend effet à la requête suivante, sans reconnexion (issue #50).
     */
    public void refreshRole(String currentRole) {
        this.role = currentRole;
    }

    public String csrfToken() {
        return csrfToken;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }

    void touch(Instant now) {
        this.lastSeenAt = now;
    }
}
