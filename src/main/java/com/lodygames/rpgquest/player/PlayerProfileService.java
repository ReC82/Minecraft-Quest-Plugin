package com.lodygames.rpgquest.player;

import com.lodygames.rpgquest.database.PlayerProfile;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps an in-memory {@link PlayerProfile} cache scoped to connected
 * players: entries are added on join and removed on quit, so its size is
 * naturally bounded by the online player count.
 */
public final class PlayerProfileService {

    private final PlayerProfileRepository repository;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();

    public PlayerProfileService(PlayerProfileRepository repository) {
        this.repository = repository;
    }

    public CompletableFuture<PlayerProfile> loadOnJoin(UUID uuid, String currentName) {
        return repository.findOrCreate(uuid, currentName)
                .thenApply(profile -> {
                    cache.put(uuid, profile);
                    return profile;
                });
    }

    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    public Optional<PlayerProfile> cached(UUID uuid) {
        return Optional.ofNullable(cache.get(uuid));
    }

    /** Returns the cached profile if the player is online, otherwise queries the database. */
    public CompletableFuture<Optional<PlayerProfile>> getOrLoad(UUID uuid) {
        PlayerProfile cached = cache.get(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return repository.find(uuid);
    }
}
