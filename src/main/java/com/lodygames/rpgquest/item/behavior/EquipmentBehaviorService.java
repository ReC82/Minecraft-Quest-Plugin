package com.lodygames.rpgquest.item.behavior;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

/**
 * Orchestre les comportements d'armes/outils personnalisés : possède le
 * {@link CooldownManager} partagé par les deux listeners et une tâche
 * périodique (asynchrone, peu fréquente) qui purge les cooldowns expirés —
 * rule 5 de la mission, satisfaite activement plutôt que par simple nettoyage
 * paresseux à l'accès. Le flag de debug ({@code config.yml} → {@code debug})
 * est lu à chaque événement via {@link ConfigService#current()}, donc son
 * activation/désactivation prend effet immédiatement après {@code /rpgquest
 * reload}, sans recréer les listeners.
 */
public final class EquipmentBehaviorService implements PluginService {

    private static final long PURGE_PERIOD_TICKS = 20L * 60 * 5; // 5 minutes : peu fréquent, tâche async légère.

    private final RPGQuestPlugin plugin;
    private final YamlCustomItemRegistry registry;
    private final ConfigService configService;
    private final CooldownManager cooldowns;

    private BukkitTask purgeTask;

    public EquipmentBehaviorService(RPGQuestPlugin plugin, YamlCustomItemRegistry registry, ConfigService configService) {
        this(plugin, registry, configService, new CooldownManager());
    }

    EquipmentBehaviorService(RPGQuestPlugin plugin, YamlCustomItemRegistry registry,
                              ConfigService configService, CooldownManager cooldowns) {
        this.plugin = plugin;
        this.registry = registry;
        this.configService = configService;
        this.cooldowns = cooldowns;
    }

    @Override
    public void start() {
        purgeTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, cooldowns::purgeExpired, PURGE_PERIOD_TICKS, PURGE_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
    }

    public Listener weaponListener() {
        return new WeaponBehaviorListener(registry, cooldowns, () -> configService.current().debug(), plugin.getSLF4JLogger());
    }

    public Listener toolListener() {
        return new ToolBehaviorListener(registry, cooldowns, () -> configService.current().debug(), plugin.getSLF4JLogger());
    }

    public Listener cooldownCleanupListener() {
        return new EquipmentCooldownCleanupListener(cooldowns);
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }
}
