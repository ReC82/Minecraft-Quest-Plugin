package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Rend agressives des entités normalement passives (ex. {@code creeper_pig}) :
 * pas de goal d'IA "attaque" disponible via l'API publique pour ces types
 * d'entité, donc on balaye périodiquement les entités spéciales connues
 * ({@link SpecialMobService#aliveEntityIds}, pas {@code World#getLivingEntities()}
 * — borné à la population réelle des variantes, pas à tous les mobs du monde)
 * et on déclenche l'explosion à portée. Une explosion réelle est créée via
 * {@code World#createExplosion}, ce qui déclenche un {@code EntityExplodeEvent}
 * normal — les listeners de protection de zone/claim s'appliquent donc sans
 * modification (mission point 6).
 *
 * <p><strong>Issue #190</strong> : changer les statistiques d'une base passive (ex. {@code PIG})
 * ne lui donne aucune IA de poursuite — une créature vanilla passive n'a tout simplement pas de
 * goal de combat/poursuite dans son sélecteur d'IA. Solution 100% API publique Paper, sans NMS :
 * {@link org.bukkit.entity.Mob#getPathfinder()} ({@code com.destroystokyo.paper.entity.Pathfinder})
 * permet de commander un déplacement vers une cible indépendamment des goals d'IA de l'entité.
 * Tant qu'un joueur éligible est à portée de {@link #SENSE_RANGE_BLOCKS} mais hors de
 * {@code triggerRangeBlocks}, un nouveau chemin vers lui est recalculé à chaque balayage (1 s,
 * même cadence que le reste de cette classe) ; inchangé pour toute entité sans cette capacité --
 * les animaux ordinaires ne sont jamais rendus agressifs.</p>
 */
public final class ExplosiveOnAttackAbilityService implements PluginService {

    private static final long SWEEP_PERIOD_TICKS = 20L; // 1 s : assez réactif pour une capacité "aggro à portée".
    /** Rayon (blocs) au-delà de {@code triggerRangeBlocks} dans lequel l'entité se met en chemin vers le joueur. */
    private static final double SENSE_RANGE_BLOCKS = 16.0;

    private final Plugin plugin;
    private final SpecialMobRegistry registry;
    private final SpecialMobService service;
    private BukkitTask task;

    public ExplosiveOnAttackAbilityService(Plugin plugin, SpecialMobRegistry registry, SpecialMobService service) {
        this.plugin = plugin;
        this.registry = registry;
        this.service = service;
    }

    @Override
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    void sweep() {
        for (SpecialMobDefinition def : registry.definitions()) {
            findAbility(def).ifPresent(ability -> {
                for (UUID entityId : service.aliveEntityIds(def.id())) {
                    Entity entity = Bukkit.getEntity(entityId);
                    if (entity instanceof LivingEntity living && !living.isDead()) {
                        maybeTrigger(living, ability);
                    }
                }
            });
        }
    }

    private void maybeTrigger(LivingEntity entity, ExplosiveOnAttackAbility ability) {
        double triggerRangeSquared = ability.triggerRangeBlocks() * ability.triggerRangeBlocks();
        Player nearest = nearestEligiblePlayer(entity);
        if (nearest == null) {
            return;
        }
        double distanceSquared = nearest.getLocation().distanceSquared(entity.getLocation());
        if (distanceSquared <= triggerRangeSquared) {
            entity.getWorld().createExplosion(entity, entity.getLocation(), ability.power(), ability.setFire(), true);
            entity.damage(entity.getHealth() + 1.0);
            service.recordAbilityTrigger(MobAbilityType.EXPLOSIVE_ON_ATTACK.name());
            return;
        }

        double senseRangeSquared = SENSE_RANGE_BLOCKS * SENSE_RANGE_BLOCKS;
        if (distanceSquared <= senseRangeSquared && entity instanceof Mob mob) {
            AttributeInstance speedAttr = entity.getAttribute(Attribute.MOVEMENT_SPEED);
            double speed = speedAttr != null ? speedAttr.getValue() : 0.25;
            mob.getPathfinder().moveTo(nearest, speed);
        }
    }

    private Player nearestEligiblePlayer(LivingEntity entity) {
        Player nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Player p : entity.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double d = p.getLocation().distanceSquared(entity.getLocation());
            if (d < nearestDistanceSquared) {
                nearestDistanceSquared = d;
                nearest = p;
            }
        }
        return nearest;
    }

    private Optional<ExplosiveOnAttackAbility> findAbility(SpecialMobDefinition def) {
        for (MobAbility ability : def.abilities()) {
            if (ability instanceof ExplosiveOnAttackAbility explosive) {
                return Optional.of(explosive);
            }
        }
        return Optional.empty();
    }
}
