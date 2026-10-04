package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Issue #173 (premier lot de capacités) : balaye périodiquement les entités spéciales connues
 * ({@link SpecialMobService#aliveEntityIds}, même discipline que {@link ExplosiveOnAttackAbilityService}
 * -- borné à la population réelle des variantes, jamais {@code World#getLivingEntities()}) et bascule
 * en rage toute entité {@link EnragedAbility} dont la vie tombe à/sous {@code healthFraction}.
 *
 * <p>Le passage en rage est <b>binaire et définitif</b> : marqué en PDC (clé {@link #PDC_KEY_NAME}),
 * il n'est jamais réévalué ni réappliqué pour une même entité -- aucun cumul de multiplicateurs à
 * chaque balayage ni à chaque coup reçu.</p>
 */
public final class EnragedAbilityService implements PluginService {

    private static final long SWEEP_PERIOD_TICKS = 20L; // 1 s, même cadence que ExplosiveOnAttackAbilityService.
    private static final String PDC_KEY_NAME = "special-mob-enraged";

    private final Plugin plugin;
    private final SpecialMobRegistry registry;
    private final SpecialMobService service;
    private final NamespacedKey enragedKey;
    private BukkitTask task;

    public EnragedAbilityService(Plugin plugin, SpecialMobRegistry registry, SpecialMobService service) {
        this.plugin = plugin;
        this.registry = registry;
        this.service = service;
        this.enragedKey = new NamespacedKey(plugin, PDC_KEY_NAME);
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
                        maybeEnrage(living, ability);
                    }
                }
            });
        }
    }

    private void maybeEnrage(LivingEntity entity, EnragedAbility ability) {
        if (isAlreadyEnraged(entity)) {
            return;
        }
        AttributeInstance maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : entity.getHealth();
        if (maxHealth <= 0 || entity.getHealth() / maxHealth > ability.healthFraction()) {
            return;
        }

        entity.getPersistentDataContainer().set(enragedKey, PersistentDataType.BYTE, (byte) 1);
        multiplyBaseValue(entity, Attribute.MOVEMENT_SPEED, ability.speedMultiplier());
        multiplyBaseValue(entity, Attribute.ATTACK_DAMAGE, ability.damageMultiplier());

        entity.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, entity.getLocation().add(0, entity.getHeight() + 0.3, 0), 6);
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.0f, 0.8f);
        service.recordAbilityTrigger(MobAbilityType.ENRAGED.name());
    }

    private boolean isAlreadyEnraged(LivingEntity entity) {
        Byte flag = entity.getPersistentDataContainer().get(enragedKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    private void multiplyBaseValue(LivingEntity entity, Attribute attribute, double multiplier) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(instance.getBaseValue() * multiplier);
        }
    }

    private Optional<EnragedAbility> findAbility(SpecialMobDefinition def) {
        for (MobAbility ability : def.abilities()) {
            if (ability instanceof EnragedAbility enragedAbility) {
                return Optional.of(enragedAbility);
            }
        }
        return Optional.empty();
    }
}
