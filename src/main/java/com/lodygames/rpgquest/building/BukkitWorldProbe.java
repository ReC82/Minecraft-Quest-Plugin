package com.lodygames.rpgquest.building;

import com.lodygames.rpgquest.building.model.BuildingFootprint;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.Campfire;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

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

    /**
     * Relevé avant capture (issue #246). Les conteneurs sont lus dans la liste des blocs à contenu de
     * chaque chunk — pas en interrogeant chaque bloc — et aucun chunk n'est chargé de force.
     */
    @Override
    public Optional<CaptureSurvey> survey(BuildingFootprint footprint) {
        World world = footprint == null ? null : Bukkit.getWorld(footprint.world());
        if (world == null) {
            return Optional.empty();
        }
        for (int cx = footprint.minX() >> 4; cx <= footprint.maxX() >> 4; cx++) {
            for (int cz = footprint.minZ() >> 4; cz <= footprint.maxZ() >> 4; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return Optional.of(CaptureSurvey.incomplete("Le chunk " + cx + "/" + cz
                            + " (blocs " + (cx << 4) + "/" + (cz << 4) + ") n'est pas chargé : "
                            + "un joueur doit se trouver près de la construction pendant la "
                            + "capture. Aucun chunk n'est chargé de force."));
                }
            }
        }

        long nonAir = 0;
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                for (int y = footprint.minY(); y <= footprint.maxY(); y++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (!type.isAir()) {
                        nonAir++;
                        counts.merge(type, 1, Integer::sum);
                    }
                }
            }
        }

        List<CaptureSurvey.ContainerSpot> containers = new ArrayList<>();
        for (int cx = footprint.minX() >> 4; cx <= footprint.maxX() >> 4; cx++) {
            for (int cz = footprint.minZ() >> 4; cz <= footprint.maxZ() >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                for (BlockState state : chunk.getTileEntities()) {
                    if (!footprint.contains(footprint.world(), state.getX(), state.getY(), state.getZ())) {
                        continue;
                    }
                    Boolean filled = contentOf(state);
                    if (filled != null) {
                        containers.add(new CaptureSurvey.ContainerSpot(state.getX(), state.getY(),
                                state.getZ(), state.getType().getKey().getKey(), filled));
                    }
                }
            }
        }

        Map<String, Integer> entities = new TreeMap<>();
        BoundingBox box = new BoundingBox(footprint.minX(), footprint.minY(), footprint.minZ(),
                footprint.maxX() + 1, footprint.maxY() + 1, footprint.maxZ() + 1);
        for (Entity entity : world.getNearbyEntities(box)) {
            if (!(entity instanceof Player)) {
                entities.merge(entity.getType().getKey().getKey(), 1, Integer::sum);
            }
        }

        List<String> materials = counts.entrySet().stream()
                .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                .limit(8)
                .map(e -> e.getKey().getKey().getKey())
                .toList();
        return Optional.of(new CaptureSurvey(true, "", nonAir, containers, entities, materials));
    }

    /**
     * {@code null} si le bloc n'a pas de contenu transportable ; sinon, s'il contient quelque chose.
     * Les enseignes, bannières et têtes ne sont PAS des conteneurs : leur texte et leurs motifs sont
     * du décor, et ils sont capturés.
     */
    private static Boolean contentOf(BlockState state) {
        if (state instanceof BlockInventoryHolder holder) {
            return !holder.getInventory().isEmpty();
        }
        if (state instanceof Jukebox jukebox) {
            return jukebox.hasRecord();
        }
        if (state instanceof Campfire campfire) {
            for (int i = 0; i < campfire.getSize(); i++) {
                ItemStack item = campfire.getItem(i);
                if (item != null && !item.getType().isAir()) {
                    return true;
                }
            }
            return false;
        }
        if (state instanceof BrushableBlock brushable) {
            return !brushable.getItem().getType().isAir();
        }
        return null;
    }
}
