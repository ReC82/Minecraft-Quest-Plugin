package be.lloyd.rpgquest.mob.ability;

import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.mob.SpecialMobRegistry;
import be.lloyd.rpgquest.mob.SpecialMobService;
import be.lloyd.rpgquest.mob.model.ExplosiveOnAttackAbility;
import be.lloyd.rpgquest.mob.model.MobAbility;
import be.lloyd.rpgquest.mob.model.MobAbilityType;
import be.lloyd.rpgquest.mob.model.SpecialMobDefinition;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
 */
public final class ExplosiveOnAttackAbilityService implements PluginService {

    private static final long SWEEP_PERIOD_TICKS = 20L; // 1 s : assez réactif pour une capacité "aggro à portée".

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
        double rangeSquared = ability.triggerRangeBlocks() * ability.triggerRangeBlocks();
        boolean playerNearby = entity.getWorld().getPlayers().stream()
                .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                .anyMatch(p -> p.getLocation().distanceSquared(entity.getLocation()) <= rangeSquared);
        if (!playerNearby) {
            return;
        }

        entity.getWorld().createExplosion(entity, entity.getLocation(), ability.power(), ability.setFire(), true);
        entity.damage(entity.getHealth() + 1.0);
        service.recordAbilityTrigger(MobAbilityType.EXPLOSIVE_ON_ATTACK.name());
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
