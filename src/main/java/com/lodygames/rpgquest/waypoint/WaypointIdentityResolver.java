package com.lodygames.rpgquest.waypoint;

import com.lodygames.rpgquest.waypoint.model.BiomeInstanceKey;

/**
 * Traduit une position + un biome en {@link BiomeInstanceKey} (issue #124). Sans état et sans
 * aucune dépendance Bukkit : entièrement testable en JUnit pur. {@code regionSize} est passé à
 * chaque appel plutôt que mémorisé, pour que {@code /rpgquest reload} le prenne en compte sans
 * reconstruire le service.
 */
public final class WaypointIdentityResolver {

    /** Coordonnée de région (tuile) contenant {@code blockCoord}. */
    public long regionOf(long regionSize, int blockCoord) {
        if (regionSize < 1) {
            throw new IllegalArgumentException("regionSize doit être >= 1");
        }
        return Math.floorDiv((long) blockCoord, regionSize);
    }

    public BiomeInstanceKey resolve(long regionSize, String world, String biomeKey, int blockX, int blockZ) {
        return new BiomeInstanceKey(world, biomeKey, regionOf(regionSize, blockX), regionOf(regionSize, blockZ));
    }

    /** {@code true} si les deux positions appartiennent à la même instance (même biome + même tuile). */
    public boolean sameInstance(long regionSize, String world, String biomeKeyA, int ax, int az,
                                String biomeKeyB, int bx, int bz) {
        return resolve(regionSize, world, biomeKeyA, ax, az)
                .equals(resolve(regionSize, world, biomeKeyB, bx, bz));
    }
}
