package com.lodygames.rpgquest.mob.ability;

/** Issue #542 — occasions auxquelles {@link AbilityEngine} présente une capacité à son exécuteur. */
public enum AbilityTrigger {
    /** {@code EntityDamageEvent} non annulé subi par le mob. */
    DAMAGED,
    /** {@code ExplosionPrimeEvent} du mob (creeper qui va exploser). */
    EXPLOSION_PRIME,
    /** Tâche partagée d'une seconde, sur la seule population connue des profils concernés. */
    TICK,
    /** Mort du mob (nettoyage, renforts à retirer). */
    DEATH
}
