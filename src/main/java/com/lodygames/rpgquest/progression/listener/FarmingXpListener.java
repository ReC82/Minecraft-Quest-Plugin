package com.lodygames.rpgquest.progression.listener;

import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Accorde de l'XP d'agriculture à la récolte d'une culture <b>mûre</b>
 * uniquement ({@code Ageable#getAge() == getMaximumAge()}) : une culture
 * non mûre ne rapporte jamais rien (mission point 7, anti-farm — empêche la
 * boucle planter/re-casser immédiatement). Ne se soucie pas de savoir si la
 * position a été posée par un joueur : contrairement au minage, une culture
 * est presque toujours plantée par un joueur, poser puis récolter une fois
 * mûre est le fonctionnement normal de l'agriculture, pas un contournement.
 */
public final class FarmingXpListener implements Listener {

    private final ProgressionService progression;
    private final Supplier<ProgressionConfig> config;
    private final AtomicLong sequence = new AtomicLong();

    public FarmingXpListener(ProgressionService progression, Supplier<ProgressionConfig> config) {
        this.progression = progression;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        ProgressionConfig current = config.get();
        if (event.isCancelled() || current.farmingHarvestXp() <= 0) {
            return;
        }
        if (!current.keepVanillaXp()) {
            event.setExpToDrop(0);
        }

        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Ageable ageable)) {
            return;
        }
        if (ageable.getAge() < ageable.getMaximumAge()) {
            return; // immature : pas de récompense.
        }

        Player player = event.getPlayer();
        String eventId = "farming:" + block.getWorld().getName() + ":" + block.getX() + ":" + block.getY()
                + ":" + block.getZ() + ":" + sequence.incrementAndGet();
        progression.awardXp(player.getUniqueId(), SkillType.FARMING, current.farmingHarvestXp(), "farming_harvest", eventId);
    }
}
