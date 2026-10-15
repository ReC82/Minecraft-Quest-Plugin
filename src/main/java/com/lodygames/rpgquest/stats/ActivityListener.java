package com.lodygames.rpgquest.stats;

import com.lodygames.rpgquest.database.ActivityLogRepository;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.progression.listener.CombatXpListener;
import java.time.Clock;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Le seul listener des événements de classement (issue #57) : il note dans
 * {@code stat_activity_log} chaque action comptable, quel que soit le concours. Un « Zombie
 * Weekend » ou un concours de pêche ne demande qu'une entrée de {@code statistics.yml}.
 *
 * <p>Écoute en {@link EventPriority#MONITOR} et ignore les événements annulés : n'est compté que ce
 * qui a réellement eu lieu. Exclus d'office, pour que l'admin et le debug ne faussent jamais une
 * compétition : joueur en créatif ou spectateur, mode bypass administrateur actif (#35), mob de test
 * ({@code /rpgadmin mob test}), mob de spawner ou issu d'une division (mêmes règles anti-farm que
 * l'XP de combat), victime joueur (PvP entre comptes). L'écriture est asynchrone ; la clé
 * d'événement (UUID de l'entité tuée, de l'hameçon) rend tout rejeu sans effet.</p>
 */
public final class ActivityListener implements Listener {

    private final ActivityLogRepository journal;
    private final SpecialMobService mobs;
    private final AdminBypassMode adminBypass;
    private final NamespacedKey spawnerTagKey;
    private final Logger logger;
    private final Clock clock;

    public ActivityListener(Plugin plugin, ActivityLogRepository journal, SpecialMobService mobs,
                            AdminBypassMode adminBypass, Logger logger) {
        this.journal = journal;
        this.mobs = mobs;
        this.adminBypass = adminBypass;
        this.spawnerTagKey = CombatXpListener.spawnerTagKey(plugin);
        this.logger = logger;
        this.clock = Clock.systemUTC();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || victim instanceof Player || !eligible(killer)) {
            return;
        }
        if (CombatXpListener.isSpawnerOrigin(victim, spawnerTagKey) || mobs.isSplitOffspring(victim)
                || mobs.isTestInstance(victim)) {
            return;
        }
        String variant = mobs.specialMobId(victim).map(NamespacedKey::toString).orElse(null);
        record(new ActivityLogRepository.Activity("kill:" + victim.getUniqueId(), killer.getUniqueId(),
                StatMetric.ENTITY_KILL, victim.getType().name(), variant, 1, clock.instant()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)
                || !eligible(event.getPlayer())) {
            return;
        }
        record(new ActivityLogRepository.Activity("fish:" + event.getHook().getUniqueId(),
                event.getPlayer().getUniqueId(), StatMetric.FISH_CAUGHT, caught.getItemStack().getType().name(),
                null, 1, clock.instant()));
    }

    /** Compétition publique : survie ou aventure, sans mode bypass administrateur. */
    boolean eligible(Player player) {
        GameMode mode = player.getGameMode();
        return (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !adminBypass.isActive(player);
    }

    private void record(ActivityLogRepository.Activity activity) {
        journal.record(activity).exceptionally(error -> {
            logger.warn("Événements : action {} non journalisée.", activity.action(), error);
            return false;
        });
    }
}
