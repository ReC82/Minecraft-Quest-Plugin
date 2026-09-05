package com.lodygames.rpgquest.progression;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.PlacedBlockRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Suit les positions posées par un joueur (mission étape 19, anti-farm point
 * 7) : une vue en mémoire autoritaire (comme {@code
 * ResourceNodeService#nodes}), persistée de façon asynchrone pour survivre à
 * un redémarrage, chargée intégralement au démarrage.
 *
 * <p>Ne réagit <b>jamais</b> à {@code BlockBreakEvent} lui-même — {@link
 * #isPlayerPlaced(Block)}/{@link #clearPlacement(Block)} sont appelés
 * explicitement par les écouteurs d'XP (minage) dans le bon ordre (lire puis
 * effacer), pour ne jamais dépendre de l'ordre d'exécution entre deux
 * listeners Bukkit distincts sur le même événement.</p>
 */
public final class PlacedBlockTracker implements PluginService, Listener {

    private final Plugin plugin;
    private final PlacedBlockRepository repository;
    private final Logger logger;

    private final Set<String> placed = ConcurrentHashMap.newKeySet();

    public PlacedBlockTracker(Plugin plugin, PlacedBlockRepository repository, Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = logger;
    }

    @Override
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        repository.findAll()
                .thenAccept(placed::addAll)
                .exceptionally(error -> {
                    logger.error("Impossible de charger les positions posées par les joueurs.", error);
                    return null;
                });
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        placed.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        placed.add(keyOf(block));
        repository.markPlaced(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                .exceptionally(error -> {
                    logger.error("Impossible de persister la position posée {}.", block.getLocation(), error);
                    return null;
                });
    }

    public boolean isPlayerPlaced(Block block) {
        return placed.contains(keyOf(block));
    }

    /** À appeler une fois qu'un bloc est cassé (posé ou non) : la position ne représente plus rien. */
    public void clearPlacement(Block block) {
        String key = keyOf(block);
        if (placed.remove(key)) {
            repository.clear(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())
                    .exceptionally(error -> {
                        logger.error("Impossible de retirer la position posée {}.", block.getLocation(), error);
                        return null;
                    });
        }
    }

    private String keyOf(Block block) {
        return PlacedBlockRepository.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    int trackedCount() {
        return placed.size();
    }
}
