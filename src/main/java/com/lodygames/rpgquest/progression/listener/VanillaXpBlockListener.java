package com.lodygames.rpgquest.progression.listener;

import com.lodygames.rpgquest.config.ProgressionConfig;
import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;

/**
 * Issue #183 — supprime la progression de niveaux Minecraft quand {@code
 * progression.vanilla-xp-progression} vaut {@code false} (défaut) : la seule
 * progression du joueur est alors l'XP RPG de {@code ProgressionService}.
 *
 * <p>Deux couches, sans aucune tâche répétitive ni scan d'entités : les sources connues
 * ne font plus apparaître d'orbe (mobs et joueurs morts, casse de blocs, fours, pêche,
 * reproduction, fioles), et {@link PlayerExpChangeEvent} ramène à zéro tout gain restant
 * (orbes des villageois, de la meule, d'un autre plugin…). Rien n'est converti en XP RPG
 * et les niveaux vanilla déjà acquis ne sont jamais modifiés (voir docs/PROGRESSION.md,
 * « Politique de migration »). Les commandes d'administration ({@code /xp}) restent
 * volontairement hors périmètre.</p>
 *
 * <p>Priorité {@code HIGHEST} : passe après les écouteurs qui fixent une XP vanilla
 * (profil de mob {@code xp-reward}…), mais avant {@code MONITOR}.</p>
 */
public final class VanillaXpBlockListener implements Listener {

    private final Supplier<ProgressionConfig> config;

    public VanillaXpBlockListener(Supplier<ProgressionConfig> config) {
        this.config = config;
    }

    private boolean blocked() {
        return !config.get().keepVanillaXp();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (blocked()) {
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (blocked()) {
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        if (blocked()) {
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (blocked()) {
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (blocked()) {
            event.setExperience(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExpBottle(ExpBottleEvent event) {
        if (blocked()) {
            event.setExperience(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExpChange(PlayerExpChangeEvent event) {
        if (blocked() && event.getAmount() > 0) {
            event.setAmount(0);
        }
    }
}
