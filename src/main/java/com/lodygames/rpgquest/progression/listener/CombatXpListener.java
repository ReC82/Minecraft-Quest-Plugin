package com.lodygames.rpgquest.progression.listener;

import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.progression.ProgressionService;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.function.Supplier;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Accorde de l'XP de combat à la mort d'un mob tué par un joueur ({@link
 * LivingEntity#getKiller()} : uniquement le coup fatal direct, jamais les
 * dégâts environnementaux ou d'un autre mob). Exclut deux sources de farm
 * (mission point 7) : les mobs issus d'un spawner (marqués par PDC à
 * {@link #onSpawn}, seul moment où {@link CreatureSpawnEvent#getSpawnReason()}
 * est disponible) et les descendants de division d'un mob spécial ({@link
 * SpecialMobService#isSplitOffspring}).
 *
 * <p>L'id d'événement (déduplication, mission point 5) est l'UUID de
 * l'entité tuée : une entité ne meurt qu'une fois, c'est un identifiant
 * naturellement stable et unique par mort.</p>
 */
public final class CombatXpListener implements Listener {

    private static final String SPAWNER_TAG_KEY_NAME = "progression-spawner-origin";

    private final ProgressionService progression;
    private final SpecialMobService mobService;
    private final Supplier<ProgressionConfig> config;
    private final NamespacedKey spawnerTagKey;

    public CombatXpListener(Plugin plugin, ProgressionService progression, SpecialMobService mobService,
                             Supplier<ProgressionConfig> config) {
        this.progression = progression;
        this.mobService = mobService;
        this.config = config;
        this.spawnerTagKey = new NamespacedKey(plugin, SPAWNER_TAG_KEY_NAME);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            event.getEntity().getPersistentDataContainer().set(spawnerTagKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        ProgressionConfig current = config.get();
        if (event.isCancelled() || current.combatKillXp() <= 0) {
            return;
        }
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }
        if (!current.keepVanillaXp()) {
            event.setDroppedExp(0);
        }
        if (isSpawnerOrigin(victim) || mobService.isSplitOffspring(victim)) {
            return;
        }

        String eventId = "combat:" + victim.getUniqueId();
        progression.awardXp(killer.getUniqueId(), SkillType.COMBAT, current.combatKillXp(), "combat_kill", eventId);
    }

    private boolean isSpawnerOrigin(LivingEntity entity) {
        Byte tag = entity.getPersistentDataContainer().get(spawnerTagKey, PersistentDataType.BYTE);
        return tag != null && tag == 1;
    }
}
