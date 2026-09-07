package com.lodygames.rpgquest.panel.security;

import java.time.Instant;

/**
 * Session serveur-side. L'identifiant ({@code id}) est aléatoire (256 bits) et n'apparaît côté
 * navigateur que dans un cookie signé (voir {@link SessionStore#signedCookieValue}). {@code
 * csrfToken} est le jeton synchroniseur exigé sur chaque POST authentifié.
 */
public final class Session {

    private final String id;
    private final String username;
    private final String role;
    private final String csrfToken;
    private final Instant createdAt;
    private volatile Instant lastSeenAt;

    Session(String id, String username, String role, String csrfToken, Instant now) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.csrfToken = csrfToken;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public String id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String role() {
        return role;
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
