package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Dépôt de comptes en mémoire — tests uniquement. */
public final class InMemoryUserRepository implements UserRepository {

    private final Map<String, PanelUser> byId = new ConcurrentHashMap<>();

    @Override
    public Optional<PanelUser> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized Optional<PanelUser> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        String needle = username.trim().toLowerCase(Locale.ROOT);
        return byId.values().stream()
                .filter(u -> u.username().toLowerCase(Locale.ROOT).equals(needle))
                .findFirst();
    }

    @Override
    public List<PanelUser> all() {
        List<PanelUser> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparing(PanelUser::createdAt).thenComparing(PanelUser::username));
        return list;
    }

    @Override
    public synchronized void insert(PanelUser user) {
        if (findByUsername(user.username()).isPresent()) {
            throw new DuplicateUsernameException(user.username());
        }
        byId.put(user.id(), user);
    }

    @Override
    public void updateRole(String id, Role role) {
        byId.computeIfPresent(id, (k, u) -> new PanelUser(
                u.id(), u.username(), u.passwordHash(), role, u.active(), u.createdAt(), u.lastLoginAt()));
    }

    @Override
    public void updateActive(String id, boolean active) {
        byId.computeIfPresent(id, (k, u) -> new PanelUser(
                u.id(), u.username(), u.passwordHash(), u.role(), active, u.createdAt(), u.lastLoginAt()));
    }

    @Override
    public void updatePasswordHash(String id, String passwordHash) {
        byId.computeIfPresent(id, (k, u) -> new PanelUser(
                u.id(), u.username(), passwordHash, u.role(), u.active(), u.createdAt(), u.lastLoginAt()));
    }

    @Override
    public void recordLogin(String id, Instant when) {
        byId.computeIfPresent(id, (k, u) -> new PanelUser(
                u.id(), u.username(), u.passwordHash(), u.role(), u.active(), u.createdAt(), when));
    }

    @Override
    public int countActiveOwners() {
        return (int) byId.values().stream().filter(u -> u.isOwner() && u.active()).count();
    }
}
