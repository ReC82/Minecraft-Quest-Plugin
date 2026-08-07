package be.lloyd.rpgquest.progression.listener;

import be.lloyd.rpgquest.config.ProgressionConfig;
import be.lloyd.rpgquest.progression.PlacedBlockTracker;
import be.lloyd.rpgquest.progression.ProgressionService;
import be.lloyd.rpgquest.progression.model.SkillType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Accorde de l'XP de minage à chaque bloc cassé, sauf : une culture ({@link
 * Ageable}, récompensée par {@code FarmingXpListener} à la place, jamais les
 * deux pour un même bloc) et une position posée par un joueur (mission
 * point 7, anti-farm — vérifiée puis toujours effacée, qu'elle ait ou non
 * accordé d'XP : le bloc n'existe plus après ce cassage).
 *
 * <p>L'id d'événement combine position et un compteur monotone : un même
 * bloc peut légitimement être re-miné après avoir repoussé (nœud de
 * ressource, {@code resource.ResourceNodeService}), donc la position seule
 * ne peut pas servir de clé de déduplication.</p>
 */
public final class MiningXpListener implements Listener {

    private final ProgressionService progression;
    private final PlacedBlockTracker placedBlocks;
    private final Supplier<ProgressionConfig> config;
    private final AtomicLong sequence = new AtomicLong();

    public MiningXpListener(ProgressionService progression, PlacedBlockTracker placedBlocks, Supplier<ProgressionConfig> config) {
        this.progression = progression;
        this.placedBlocks = placedBlocks;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ProgressionConfig current = config.get();
        if (event.isCancelled() || current.miningBlockXp() <= 0) {
            return;
        }
        if (!current.keepVanillaXp()) {
            event.setExpToDrop(0);
        }

        Block block = event.getBlock();
        if (block.getBlockData() instanceof Ageable) {
            return; // culture : voir FarmingXpListener.
        }

        boolean placedByPlayer = placedBlocks.isPlayerPlaced(block);
        placedBlocks.clearPlacement(block);
        if (placedByPlayer) {
            return;
        }

        Player player = event.getPlayer();
        String eventId = "mining:" + block.getWorld().getName() + ":" + block.getX() + ":" + block.getY()
                + ":" + block.getZ() + ":" + sequence.incrementAndGet();
        progression.awardXp(player.getUniqueId(), SkillType.MINING, current.miningBlockXp(), "mining_break", eventId);
    }
}
