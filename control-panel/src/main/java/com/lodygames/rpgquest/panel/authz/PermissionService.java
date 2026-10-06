package com.lodygames.rpgquest.panel.authz;

/**
 * Point unique d'autorisation. Les handlers appellent {@link #can(EffectivePermissions, Permission)}
 * — jamais un test ad hoc du nom d'utilisateur, du rôle ou de l'appartenance à un groupe. L'ajout de
 * rôles, de permissions ou de groupes ne touche pas les handlers.
 *
 * <p>Depuis l'issue #199, l'autorisation porte sur les droits <strong>effectifs</strong> (rôle ∪
 * groupes) et non plus sur le seul nom de rôle. La surcharge historique
 * {@link #can(String, Permission)} subsiste pour les contrôles qui n'ont qu'un rôle sous la main
 * (bootstrap, outils) : elle ignore les groupes <strong>par construction</strong>, et c'est
 * volontairement visible dans son nom de paramètre — s'en servir sur un chemin de requête
 * laisserait silencieusement tomber les droits de groupe.</p>
 */
public final class PermissionService {

    /** Autorisation réelle : rôle de base <strong>plus</strong> groupes. */
    public boolean can(EffectivePermissions effective, Permission permission) {
        return effective != null && effective.has(permission);
    }

    /**
     * Autorisation d'après le seul rôle, <strong>sans</strong> les groupes. Réservée aux contextes
     * où aucun compte n'est résolu. Sur un chemin de requête, utiliser
     * {@link #can(EffectivePermissions, Permission)}.
     */
    public boolean canByRoleOnly(String roleName, Permission permission) {
        Role role = Role.byNameOrNull(roleName);
        return role != null && role.has(permission);
    }
}
