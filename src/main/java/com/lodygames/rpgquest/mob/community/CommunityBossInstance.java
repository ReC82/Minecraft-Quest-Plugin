package com.lodygames.rpgquest.mob.community;

import java.util.UUID;

/**
 * Issue #175 — verrou persistant d'un boss communautaire actif. L'identifiant stable est l'UUID de
 * l'entité (également posé en PDC sur l'entité, voir {@link CommunityBossService#instanceKey()}).
 *
 * <p>La position est la <em>dernière connue</em> : rafraîchie tant que le chunk est chargé et au
 * déchargement des entités. Elle sert à reconnaître une instance disparue sans jamais charger de
 * chunk : quand ce chunk est chargé et que l'entité n'y est pas, le verrou est libéré.</p>
 *
 * @param waypointId   waypoint réel le plus proche à l'apparition, {@code null} si aucun à portée
 * @param waypointName nom canonique de ce waypoint, {@code null} si aucun
 * @param test         apparition de test déclenchée depuis PlugAdmin
 */
public record CommunityBossInstance(UUID entityId, String profileId, String profileName, String world,
                                    int x, int y, int z, String biome, String waypointId, String waypointName,
                                    int waypointDistance, String waypointDirection, long spawnedAtMillis,
                                    boolean test) {

    public CommunityBossInstance {
        if (entityId == null || profileId == null || profileId.isBlank() || world == null || world.isBlank()) {
            throw new IllegalArgumentException("Instance de boss communautaire incomplète.");
        }
        profileName = profileName == null || profileName.isBlank() ? profileId : profileName;
        biome = biome == null ? "" : biome;
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public boolean inChunk(String worldName, int cx, int cz) {
        return world.equals(worldName) && chunkX() == cx && chunkZ() == cz;
    }

    public CommunityBossInstance movedTo(String newWorld, int nx, int ny, int nz) {
        return new CommunityBossInstance(entityId, profileId, profileName, newWorld, nx, ny, nz, biome, waypointId,
                waypointName, waypointDistance, waypointDirection, spawnedAtMillis, test);
    }
}
