package com.lodygames.rpgquest.panel.authz;

/**
 * Point unique d'autorisation. Les handlers appellent {@link #can(String, Permission)} — jamais un
 * test ad hoc du nom d'utilisateur ou du rôle. L'ajout de rôles/permissions ne touchera pas les
 * handlers.
 */
public final class PermissionService {

    public boolean can(String roleName, Permission permission) {
        if (roleName == null) {
            return false;
        }
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return role.has(permission);
    }
}
