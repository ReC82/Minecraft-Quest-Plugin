package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * Rend agressives des entités normalement passives (ex. {@code creeper_pig}) : aucun goal d'IA
 * « attaque » n'est disponible via l'API publique pour ces types, donc, à chaque seconde
 * ({@link AbilityTrigger#TICK}), l'entité se met en chemin vers le joueur valide le plus proche et
 * explose à portée. L'explosion est réelle ({@code World#createExplosion}) : son
 * {@code EntityExplodeEvent} passe par les protections de zone/claim existantes.
 *
 * <p><strong>Issue #190</strong> : la poursuite d'une base passive passe par
 * {@link Mob#getPathfinder()} (API publique Paper, sans NMS), recalculée à chaque seconde tant qu'un
 * joueur est dans {@link #SENSE_RANGE_BLOCKS} mais hors de {@code triggerRangeBlocks}.</p>
 *
 * <p><strong>Issue #542</strong> (décision Q9) : la cible passe par
 * {@link AbilityWorldPolicy#isValidTarget} — ni PNJ, ni joueur en créatif ou spectateur.</p>
 */
final class ExplosiveOnAttackExecutor implements AbilityExecutor<ExplosiveOnAttackAbility> {

    /** Rayon (blocs) au-delà de {@code triggerRangeBlocks} dans lequel l'entité se met en chemin vers le joueur. */
    static final double SENSE_RANGE_BLOCKS = 16.0;

    private final AbilityWorldPolicy policy;

    ExplosiveOnAttackExecutor(AbilityWorldPolicy policy) {
        this.policy = policy;
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.EXPLOSIVE_ON_ATTACK;
    }

    @Override
    public Class<ExplosiveOnAttackAbility> abilityClass() {
        return ExplosiveOnAttackAbility.class;
    }

    @Override
    public void execute(AbilityContext<ExplosiveOnAttackAbility> context) {
        LivingEntity entity = context.mob();
        ExplosiveOnAttackAbility ability = context.ability();
        Player nearest = nearestValidPlayer(entity);
        if (nearest == null) {
            return;
        }
        double distanceSquared = nearest.getLocation().distanceSquared(entity.getLocation());
        if (distanceSquared <= ability.triggerRangeBlocks() * ability.triggerRangeBlocks()) {
            if (!context.passesGate()) {
                return;
            }
            entity.getWorld().createExplosion(entity, entity.getLocation(), ability.power(), ability.setFire(), true);
            entity.damage(entity.getHealth() + 1.0);
            context.markActivated();
            return;
        }
        if (distanceSquared <= SENSE_RANGE_BLOCKS * SENSE_RANGE_BLOCKS && entity instanceof Mob mob) {
            AttributeInstance speedAttr = entity.getAttribute(Attribute.MOVEMENT_SPEED);
            double speed = speedAttr != null ? speedAttr.getValue() : 0.25;
            mob.getPathfinder().moveTo(nearest, speed);
        }
    }

    private Player nearestValidPlayer(LivingEntity entity) {
        Player nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Player p : entity.getWorld().getPlayers()) {
            if (!policy.isValidTarget(p)) {
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
}
