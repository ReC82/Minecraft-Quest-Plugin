package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.Role;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stockage des comptes PlugAdmin (issue #50). Deux implémentations : {@link SqliteUserRepository}
 * (production, {@code control-panel.db}) et {@link InMemoryUserRepository} (tests). Toute règle
 * métier (protection du dernier OWNER, validation) vit dans {@link UserDirectory}, jamais ici.
 */
public interface UserRepository {

    Optional<PanelUser> findById(String id);

    /** Recherche insensible à la casse sur le nom d'utilisateur. */
    Optional<PanelUser> findByUsername(String username);

    /** Tous les comptes, triés par date de création croissante. */
    List<PanelUser> all();

    /**
     * Insère un nouveau compte.
     *
     * @throws DuplicateUsernameException si le nom d'utilisateur est déjà pris (casse ignorée)
     */
    void insert(PanelUser user);

    void updateRole(String id, Role role);

    void updateActive(String id, boolean active);

    void updatePasswordHash(String id, String passwordHash);

    void recordLogin(String id, Instant when);

    /** Nombre de comptes {@code OWNER} actuellement actifs — garde-fou anti-verrouillage. */
    int countActiveOwners();

    /** Levée par {@link #insert} quand le nom d'utilisateur existe déjà. */
    final class DuplicateUsernameException extends RuntimeException {
        public DuplicateUsernameException(String username) {
            super("Nom d'utilisateur déjà pris : " + username);
        }
    }
}
