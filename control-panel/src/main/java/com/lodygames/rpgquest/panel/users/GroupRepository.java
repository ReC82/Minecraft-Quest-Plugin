package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.Permission;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stockage des groupes PlugAdmin et des appartenances (issue #199). Deux implémentations :
 * {@link SqliteGroupRepository} (production, {@code control-panel.db}) et
 * {@link InMemoryGroupRepository} (tests). Toute règle métier — anti-élévation de privilège,
 * protection du dernier propriétaire, validation des noms — vit dans {@link GroupDirectory},
 * jamais ici.
 */
public interface GroupRepository {

    List<PanelGroup> all();

    Optional<PanelGroup> findById(String id);

    /** Recherche insensible à la casse sur le nom du groupe. */
    Optional<PanelGroup> findByName(String name);

    /**
     * @throws DuplicateGroupNameException si le nom est déjà pris (casse ignorée)
     */
    void insert(PanelGroup group);

    void updateDetails(String id, String name, String description);

    /** Remplace l'ensemble des permissions du groupe par {@code permissions}. */
    void replacePermissions(String id, Set<Permission> permissions);

    /** Supprime le groupe <strong>et</strong> toutes ses appartenances. */
    void delete(String id);

    /** Groupes d'un utilisateur, triés par nom. */
    List<PanelGroup> groupsOf(String userId);

    /** Identifiants des utilisateurs membres d'un groupe. */
    List<String> membersOf(String groupId);

    /** Remplace l'ensemble des appartenances de l'utilisateur. */
    void replaceMemberships(String userId, Set<String> groupIds);

    /** Retire toutes les appartenances d'un utilisateur (suppression de compte). */
    void removeAllMemberships(String userId);

    final class DuplicateGroupNameException extends RuntimeException {
        public DuplicateGroupNameException(String name) {
            super("Nom de groupe déjà pris : " + name);
        }
    }
}
