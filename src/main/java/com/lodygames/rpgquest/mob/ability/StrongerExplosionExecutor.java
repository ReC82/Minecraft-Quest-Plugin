package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.StrongerExplosionAbility;
import org.bukkit.event.entity.ExplosionPrimeEvent;

/**
 * Multiplie le rayon d'une explosion vanilla (creeper) qui prime. {@code ExplosionPrimeEvent} agit
 * avant la création de l'explosion : les listeners de protection de zone/claim réagissent ensuite sur
 * le {@code EntityExplodeEvent} qui en découle, sans duplication de cette logique ici.
 *
 * <p>Issue #542 (décision Q3) : une seule instance par profil agit ; avant, deux entrées en double se
 * multipliaient (aucun profil livré n'en avait deux).</p>
 */
final class StrongerExplosionExecutor implements AbilityExecutor<StrongerExplosionAbility> {

    @Override
    public MobAbilityType type() {
        return MobAbilityType.STRONGER_EXPLOSION;
    }

    @Override
    public Class<StrongerExplosionAbility> abilityClass() {
        return StrongerExplosionAbility.class;
    }

    @Override
    public void execute(AbilityContext<StrongerExplosionAbility> context) {
        if (!context.passesGate()) {
            return;
        }
        ExplosionPrimeEvent event = context.event(ExplosionPrimeEvent.class);
        event.setRadius((float) (event.getRadius() * context.ability().radiusMultiplier()));
        context.markActivated();
    }
}
