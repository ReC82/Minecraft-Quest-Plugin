package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import java.util.function.LongSupplier;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Issue #173 : quand le mob subit des dégâts <strong>effectifs</strong> (non annulés,
 * {@code finalDamage > 0}), délai et chance communs passés, invoque des renforts dans la limite de
 * {@code maxAlive} vivants pour ce mob.
 *
 * <p>Aucune cascade par construction : les renforts sont des entités vanilla, jamais passées par
 * {@code SpecialMobService#apply}, donc jamais reconnues comme mobs spéciaux. Ils sont seulement tagués
 * en PDC (clé {@value #OWNER_PDC_KEY_NAME}, inchangée depuis #173) avec l'UUID de l'invocateur.</p>
 *
 * <p><strong>Issue #542</strong> : apparition refusée dans le Hub, un claim ou une zone qui interdit
 * les hostiles ; {@value AbilityLimits#MAX_REINFORCEMENTS_PER_SECOND_PER_WORLD} renforts par seconde et
 * par monde au plus ; délai tenu par le moteur et oublié à la mort (fuite mémoire corrigée) ;
 * {@code despawn-with-owner} (Q7, défaut non) retire les renforts à la mort de l'invocateur.</p>
 */
final class SummonOnDamageExecutor implements AbilityExecutor<SummonOnDamageAbility> {

    static final String OWNER_PDC_KEY_NAME = "special-mob-summon-owner";
    private static final double REINFORCEMENT_SEARCH_RADIUS = 48.0;

    private final AbilityWorldPolicy policy;
    private final AbilityLimits limits;
    private final LongSupplier clock;
    private final NamespacedKey ownerKey;

    SummonOnDamageExecutor(Plugin plugin, AbilityWorldPolicy policy, AbilityLimits limits, LongSupplier clock) {
        this.policy = policy;
        this.limits = limits;
        this.clock = clock;
        this.ownerKey = new NamespacedKey(plugin, OWNER_PDC_KEY_NAME);
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.SUMMON_ON_DAMAGE;
    }

    @Override
    public Class<SummonOnDamageAbility> abilityClass() {
        return SummonOnDamageAbility.class;
    }

    @Override
    public void execute(AbilityContext<SummonOnDamageAbility> context) {
        switch (context.trigger()) {
            case DAMAGED -> onDamaged(context);
            case DEATH -> onOwnerDeath(context);
            default -> {
            }
        }
    }

    private void onDamaged(AbilityContext<SummonOnDamageAbility> context) {
        if (context.event(EntityDamageEvent.class).getFinalDamage() <= 0 || !context.passesGate()) {
            return;
        }
        LivingEntity parent = context.mob();
        SummonOnDamageAbility ability = context.ability();
        int alreadyAlive = countAliveReinforcements(parent);
        if (alreadyAlive >= ability.maxAlive() || !policy.allowsSpawnAt(parent.getLocation())) {
            return;
        }
        int toSpawn = Math.min(ability.amount(), ability.maxAlive() - alreadyAlive);
        String world = parent.getWorld().getName();
        int spawned = 0;
        for (int i = 0; i < toSpawn; i++) {
            if (!limits.tryReserveReinforcement(world, clock.getAsLong())) {
                break;
            }
            LivingEntity reinforcement = (LivingEntity) parent.getWorld().spawnEntity(parent.getLocation(), ability.summonedEntityType());
            reinforcement.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, parent.getUniqueId().toString());
            spawned++;
        }
        if (spawned > 0) {
            context.markActivated();
        }
    }

    private void onOwnerDeath(AbilityContext<SummonOnDamageAbility> context) {
        if (!context.ability().despawnWithOwner()) {
            return; // défaut (Q7) : les renforts restent, comme avant #542.
        }
        for (LivingEntity reinforcement : aliveReinforcements(context.mob())) {
            reinforcement.remove();
        }
    }

    private int countAliveReinforcements(LivingEntity parent) {
        return aliveReinforcements(parent).size();
    }

    /** Borné à un rayon fixe autour de l'invocateur (jamais un scan global). */
    private java.util.List<LivingEntity> aliveReinforcements(LivingEntity parent) {
        String ownerId = parent.getUniqueId().toString();
        java.util.List<LivingEntity> out = new java.util.ArrayList<>();
        for (Entity nearby : parent.getNearbyEntities(
                REINFORCEMENT_SEARCH_RADIUS, REINFORCEMENT_SEARCH_RADIUS, REINFORCEMENT_SEARCH_RADIUS)) {
            if (nearby instanceof LivingEntity livingNearby && !livingNearby.isDead()
                    && ownerId.equals(nearby.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) {
                out.add(livingNearby);
            }
        }
        return out;
    }
}
