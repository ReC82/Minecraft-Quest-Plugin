package com.lodygames.rpgquest.mob.model;

public enum MobAbilityType {
    STRONGER_EXPLOSION,
    EXPLOSIVE_ON_ATTACK,
    SPLIT_ON_HIT,
    /** Issue #173 (premier lot de capacités) : vitesse/dégâts accrus sous un seuil de vie. */
    ENRAGED,
    /** Issue #173 : invoque des renforts quand le mob subit des dégâts effectifs. */
    SUMMON_ON_DAMAGE
}
