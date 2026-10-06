package com.lodygames.rpgquest.panel.users;

import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.Permission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groupes en mémoire (tests). Même contrat que {@link SqliteGroupRepository}, y compris le
 * remplacement atomique des permissions et des appartenances et l'unicité du nom insensible à la
 * casse — sans quoi un test passerait là où la production échoue.
 */
public final class InMemoryGroupRepository implements GroupRepository {

    private final Map<String, PanelGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> membershipsByUser = new ConcurrentHashMap<>();

    @Override
    public List<PanelGroup> all() {
        List<PanelGroup> out = new ArrayList<>(groups.values());
        out.sort(Comparator.comparing(group -> group.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    @Override
    public Optional<PanelGroup> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(groups.get(id));
    }

    @Override
    public Optional<PanelGroup> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        return groups.values().stream()
                .filter(group -> group.name().trim().toLowerCase(Locale.ROOT).equals(key))
                .findFirst();
    }

    @Override
    public void insert(PanelGroup group) {
        if (findByName(group.name()).isPresent()) {
            throw new DuplicateGroupNameException(group.name());
        }
        groups.put(group.id(), group);
    }

    @Override
    public void updateDetails(String id, String name, String description) {
        PanelGroup existing = groups.get(id);
        if (existing == null) {
            return;
        }
        Optional<PanelGroup> clash = findByName(name);
        if (clash.isPresent() && !clash.get().id().equals(id)) {
            throw new DuplicateGroupNameException(name);
        }
        groups.put(id, new PanelGroup(id, name, description, existing.permissions(), existing.createdAt()));
    }

    @Override
    public void replacePermissions(String id, Set<Permission> permissions) {
        PanelGroup existing = groups.get(id);
        if (existing == null) {
            return;
        }
        groups.put(id, new PanelGroup(id, existing.name(), existing.description(), permissions,
                existing.createdAt()));
    }

    @Override
    public void delete(String id) {
        groups.remove(id);
        membershipsByUser.values().forEach(set -> set.remove(id));
    }

    @Override
    public List<PanelGroup> groupsOf(String userId) {
        Set<String> ids = membershipsByUser.getOrDefault(userId, Set.of());
        List<PanelGroup> out = new ArrayList<>();
        for (String id : ids) {
            PanelGroup group = groups.get(id);
            if (group != null) {
                out.add(group);
            }
        }
        out.sort(Comparator.comparing(group -> group.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    @Override
    public List<String> membersOf(String groupId) {
        List<String> out = new ArrayList<>();
        new LinkedHashMap<>(membershipsByUser).forEach((userId, ids) -> {
            if (ids.contains(groupId)) {
                out.add(userId);
            }
        });
        return out;
    }

    @Override
    public void replaceMemberships(String userId, Set<String> groupIds) {
        Set<String> kept = new LinkedHashSet<>();
        for (String id : groupIds) {
            if (groups.containsKey(id)) {
                kept.add(id);
            }
        }
        membershipsByUser.put(userId, java.util.Collections.synchronizedSet(kept));
    }

    @Override
    public void removeAllMemberships(String userId) {
        membershipsByUser.remove(userId);
    }
}
