package com.lodygames.rpgquest.panel.security;

import com.lodygames.rpgquest.panel.authz.EffectivePermissions;
import com.lodygames.rpgquest.panel.authz.Role;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * Droits <strong>effectifs</strong> (rôle ∪ groupes), recalculés à CHAQUE requête par
     * {@code PanelApp#currentSession} (issue #199). C'est ce qui fait qu'une révocation — retrait
     * d'un groupe, retrait d'une permission d'un groupe, suppression d'un groupe — prend effet
     * immédiatement sur les sessions actives, sans attendre une reconnexion et sans cache à
     * invalider : il n'existe aucun cache plus long qu'une requête.
     */
    private volatile EffectivePermissions effective;
    private final String csrfToken;
    private final Instant createdAt;
    private volatile Instant lastSeenAt;
    /**
     * Formulaire refusé par le panel AVANT la file d'actions, par type d'action (issue #177) : la
     * page suivante le réaffiche une seule fois, saisies intactes. En mémoire seulement, jamais le
     * jeton CSRF, un seul par type.
     */
    private final Map<String, Map<String, String>> refusedForms = new ConcurrentHashMap<>();

    Session(String id, String userId, String username, String role, String csrfToken, Instant now) {
        this.id = id;
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.effective = EffectivePermissions.ofRoleOnly(Role.byNameOrNull(role));
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

    /**
     * Droits effectifs de cette session, relus à chaque requête. Jamais {@code null} : un montage
     * sans groupes rend exactement les droits du rôle.
     */
    public EffectivePermissions effective() {
        return effective;
    }

    /**
     * Réaligne rôle <strong>et</strong> droits effectifs sur l'état réel du compte (issue #199).
     * Appelé une fois par requête : rien n'est mis en cache au-delà.
     */
    public void refreshAuthz(String currentRole, EffectivePermissions currentEffective) {
        this.role = currentRole;
        this.effective = currentEffective == null
                ? EffectivePermissions.ofRoleOnly(Role.byNameOrNull(currentRole))
                : currentEffective;
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

    /** Garde le formulaire refusé (sans {@code _csrf}) et le message de refus, pour le réafficher. */
    public void keepRefusedForm(String type, Map<String, String> form, String error) {
        Map<String, String> copy = new LinkedHashMap<>(form);
        copy.remove("_csrf");
        copy.put("_error", error == null ? "" : error);
        refusedForms.put(type, Map.copyOf(copy));
    }

    /** Le formulaire refusé de ce type, une seule fois. */
    public Optional<Map<String, String>> takeRefusedForm(String type) {
        return Optional.ofNullable(refusedForms.remove(type));
    }
}
