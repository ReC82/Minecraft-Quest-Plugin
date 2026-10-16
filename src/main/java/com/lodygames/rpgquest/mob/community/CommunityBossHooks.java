package com.lodygames.rpgquest.mob.community;

import org.bukkit.entity.Player;

/**
 * Issue #175 — point d'extension pour la participation et les récompenses (#176/#177), volontairement
 * vide dans ce lot : aucune mécanique temporaire de récompense n'est imposée. Appelé sur le thread
 * principal ; une implémentation ne doit ni bloquer ni lever d'exception (elle est isolée par
 * {@link CommunityBossService}).
 */
public interface CommunityBossHooks {

    /** Le boss vient d'apparaître et d'être annoncé. */
    default void onSpawned(CommunityBossInstance instance) {
    }

    /** Le boss est mort (cause de mort Bukkit) ; {@code killer} peut être {@code null}. */
    default void onDefeated(CommunityBossInstance instance, Player killer) {
    }

    /** Le verrou est libéré sans mort constatée (retrait admin, entité disparue). Jamais de récompense. */
    default void onReleased(CommunityBossInstance instance, String reason) {
    }
}
