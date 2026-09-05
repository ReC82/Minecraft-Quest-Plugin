package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.StrongerExplosionAbility;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ExplosionPrimeEvent;

/**
 * Multiplie le rayon d'une explosion vanilla (creeper) qui prime, si l'entité
 * porte {@link StrongerExplosionAbility}. {@code ExplosionPrimeEvent} agit
 * avant la création de l'explosion : les listeners de protection de zone/claim
 * réagissent ensuite sur le {@code EntityExplodeEvent} qui en découle, donc
 * cette capacité n'a pas besoin de dupliquer cette logique (mission point 6).
 */
public final class StrongerExplosionAbilityListener implements Listener {

    private final SpecialMobService service;

    public StrongerExplosionAbilityListener(SpecialMobService service) {
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        service.specialMobDefinition(event.getEntity()).ifPresent(def -> {
            for (MobAbility ability : def.abilities()) {
                if (ability instanceof StrongerExplosionAbility stronger) {
                    event.setRadius((float) (event.getRadius() * stronger.radiusMultiplier()));
                    service.recordAbilityTrigger(MobAbilityType.STRONGER_EXPLOSION.name());
                }
            }
        });
    }
}
