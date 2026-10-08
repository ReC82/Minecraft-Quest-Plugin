package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingFootprint;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * {@link WorldProbe} adossé à Bukkit (issue #213, lot « placement »).
 *
 * <p>Seule implémentation à toucher l'API du serveur, et volontairement minuscule : toutes les
 * décisions sont prises par {@link BuildingPlacementService}, qui est testable. Ici il n'y a que
 * trois lectures.</p>
 *
 * <p><strong>À appeler depuis le thread principal.</strong> Lire des blocs hors du thread
 * principal n'est pas sûr sous Paper ; le comptage est donc fait pendant l'aperçu, qui est déclenché
 * par une action agent exécutée sur le thread principal.</p>
 */
public final class BukkitWorldProbe implements WorldProbe {

    /** Au-delà, on ne compte pas : le coût deviendrait sensible pour une simple indication. */
    static final long MAX_COUNTED_BLOCKS = 32_768L;

    @Override
    public boolean loaded(String world) {
        return world != null && Bukkit.getWorld(world) != null;
    }

    @Override
    public OptionalInt minHeight(String world) {
        World found = world == null ? null : Bukkit.getWorld(world);
        return found == null ? OptionalInt.empty() : OptionalInt.of(found.getMinHeight());
    }

    @Override
    public OptionalInt maxHeight(String world) {
        World found = world == null ? null : Bukkit.getWorld(world);
        return found == null ? OptionalInt.empty() : OptionalInt.of(found.getMaxHeight());
    }

    @Override
    public OptionalLong countNonAir(BuildingFootprint footprint) {
        if (footprint == null) {
            return OptionalLong.empty();
        }
        World world = Bukkit.getWorld(footprint.world());
        if (world == null || footprint.blockCount() > MAX_COUNTED_BLOCKS) {
            return OptionalLong.empty();
        }
        long count = 0;
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                // Chunk non chargé : on renonce au comptage plutôt que de forcer le chargement.
                // Ce n'est qu'une indication, elle ne vaut pas de générer du terrain.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return OptionalLong.empty();
                }
                for (int y = footprint.minY(); y <= footprint.maxY(); y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type != Material.AIR && type != Material.CAVE_AIR
                            && type != Material.VOID_AIR) {
                        count++;
                    }
                }
            }
        }
        return OptionalLong.of(count);
    }
}
