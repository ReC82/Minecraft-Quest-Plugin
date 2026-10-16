package com.lodygames.rpgquest.mob.community;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Issue #175 — point d'extension du cycle de vie d'un boss communautaire ; la participation et les
 * récompenses (#176) s'y branchent ({@link CommunityBossRewards}). Appelé sur le thread
 * principal ; une implémentation ne doit ni bloquer ni lever d'exception (elle est isolée par
 * {@link CommunityBossService}).
 */
public interface CommunityBossHooks {

    /** Le boss vient d'apparaître et d'être annoncé. */
    default void onSpawned(CommunityBossInstance instance) {
    }

    /**
     * Le boss est mort (cause de mort Bukkit) ; {@code entity} est l'entité à l'instant de sa mort
     * (position et PDC encore lisibles), {@code killer} peut être {@code null}.
     */
    default void onDefeated(CommunityBossInstance instance, LivingEntity entity, Player killer) {
    }

    /** Le verrou est libéré sans mort constatée (retrait admin, entité disparue). Jamais de récompense. */
    default void onReleased(CommunityBossInstance instance, String reason) {
    }
}
