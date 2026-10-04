package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Issue #173 (premier lot de capacités) : quand une entité {@link SummonOnDamageAbility} subit des
 * dégâts <strong>effectifs</strong> (événement non annulé, {@code finalDamage > 0}), tire sa chance
 * d'invoquer des renforts, sous réserve du cooldown et du plafond de renforts vivants.
 *
 * <p>Aucune cascade possible par construction : les renforts invoqués sont de simples entités
 * vanilla, jamais upgradées via {@link SpecialMobService#apply}, donc jamais reconnues par
 * {@link SpecialMobService#specialMobDefinition} -- elles ne peuvent donc jamais déclencher cette
 * même capacité à leur tour. Elles sont uniquement taguées en PDC (clé {@link #OWNER_PDC_KEY_NAME})
 * avec l'UUID du mob invocateur, ce qui permet de borner leur nombre vivant sans registre séparé.</p>
 */
public final class SummonOnDamageAbilityListener implements Listener {

    private static final String OWNER_PDC_KEY_NAME = "special-mob-summon-owner";
    private static final double REINFORCEMENT_SEARCH_RADIUS = 48.0;

    private final SpecialMobService service;
    private final NamespacedKey ownerKey;
    private final RandomGenerator random;
    private final Map<java.util.UUID, Long> lastSummonAtMillis = new ConcurrentHashMap<>();

    public SummonOnDamageAbilityListener(Plugin plugin, SpecialMobService service) {
        this(plugin, service, ThreadLocalRandom.current());
    }

    SummonOnDamageAbilityListener(Plugin plugin, SpecialMobService service, RandomGenerator random) {
        this.service = service;
        this.random = random;
        this.ownerKey = new NamespacedKey(plugin, OWNER_PDC_KEY_NAME);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled() || event.getFinalDamage() <= 0) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        service.specialMobDefinition(living).ifPresent(def -> findAbility(def).ifPresent(ability -> maybeSummon(living, ability)));
    }

    private void maybeSummon(LivingEntity parent, SummonOnDamageAbility ability) {
        long now = System.currentTimeMillis();
        long cooldownMillis = ability.cooldownSeconds() * 1000L;
        Long last = lastSummonAtMillis.get(parent.getUniqueId());
        if (last != null && now - last < cooldownMillis) {
            return;
        }
        if (random.nextDouble() >= ability.chance()) {
            return;
        }
        int alreadyAlive = countAliveReinforcements(parent);
        if (alreadyAlive >= ability.maxAlive()) {
            return;
        }

        lastSummonAtMillis.put(parent.getUniqueId(), now);
        int spawned = 0;
        int toSpawn = Math.min(ability.amount(), ability.maxAlive() - alreadyAlive);
        for (int i = 0; i < toSpawn; i++) {
            LivingEntity reinforcement = (LivingEntity) parent.getWorld().spawnEntity(parent.getLocation(), ability.summonedEntityType());
            reinforcement.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, parent.getUniqueId().toString());
            spawned++;
        }
        if (spawned > 0) {
            service.recordAbilityTrigger(MobAbilityType.SUMMON_ON_DAMAGE.name());
        }
    }

    private int countAliveReinforcements(LivingEntity parent) {
        String ownerId = parent.getUniqueId().toString();
        int count = 0;
        for (Entity nearby : parent.getNearbyEntities(
                REINFORCEMENT_SEARCH_RADIUS, REINFORCEMENT_SEARCH_RADIUS, REINFORCEMENT_SEARCH_RADIUS)) {
            if (nearby instanceof LivingEntity livingNearby && !livingNearby.isDead()
                    && ownerId.equals(nearby.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) {
                count++;
            }
        }
        return count;
    }

    private Optional<SummonOnDamageAbility> findAbility(SpecialMobDefinition def) {
        for (MobAbility ability : def.abilities()) {
            if (ability instanceof SummonOnDamageAbility summonAbility) {
                return Optional.of(summonAbility);
            }
        }
        return Optional.empty();
    }
}
