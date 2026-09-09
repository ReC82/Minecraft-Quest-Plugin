package com.lodygames.rpgquest.panel.authz;

import java.util.EnumSet;
import java.util.Set;

/**
 * Rôles du Control Panel. V1 : seul {@code OWNER} est réellement attribué (à l'unique compte). Les
 * autres sont déclarés pour cadrer l'évolution (RBAC minimal, extensible sans refonte des
 * handlers) — voir docs/control-panel/SECURITY.md.
 */
public enum Role {

    OWNER(EnumSet.allOf(Permission.class)),
    TESTER(EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.PLAYERS_READ, Permission.NPC_READ,
            Permission.CONTENT_READ, Permission.CONTENT_EXPORT, Permission.DIAGNOSTICS_READ,
            Permission.AUDIT_READ, Permission.ACTION_QUEST, Permission.ACTION_STORY,
            Permission.ACTION_VARIABLE_GET, Permission.DOCS_READ)),
    CONTENT_EDITOR(EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.CONTENT_READ, Permission.CONTENT_EXPORT,
            Permission.DIAGNOSTICS_READ, Permission.AUDIT_READ, Permission.ACTION_CONTENT_RELOAD,
            Permission.DOCS_READ, Permission.QUEST_CONTENT_WRITE, Permission.STORY_CONTENT_WRITE)),
    READ_ONLY(EnumSet.of(
            Permission.DASHBOARD_VIEW, Permission.PLAYERS_READ, Permission.NPC_READ,
            Permission.CONTENT_READ, Permission.CONTENT_EXPORT, Permission.DIAGNOSTICS_READ,
            Permission.AUDIT_READ, Permission.DOCS_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
