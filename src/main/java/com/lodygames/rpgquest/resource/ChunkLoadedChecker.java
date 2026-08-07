package com.lodygames.rpgquest.resource;

import org.bukkit.World;

/**
 * Indirection injectable sur {@code World#isChunkLoaded(int, int)}, comme le
 * {@code LongSupplier} de {@code CooldownManager} : rend {@code
 * ResourceNodeService} testable sans dépendre du comportement réel (ou
 * simulé) de chargement de chunks de MockBukkit.
 */
@FunctionalInterface
public interface ChunkLoadedChecker {

    boolean isChunkLoaded(World world, int chunkX, int chunkZ);

    ChunkLoadedChecker DEFAULT = (world, chunkX, chunkZ) -> world.isChunkLoaded(chunkX, chunkZ);
}
