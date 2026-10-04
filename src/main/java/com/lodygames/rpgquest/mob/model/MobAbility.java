package com.lodygames.rpgquest.mob.model;

/**
 * Interface scellée (même discipline que {@code QuestReward}/{@code
 * DialogueAction}) : un `switch` exhaustif sur les capacités est
 * vérifié par le compilateur.
 */
public sealed interface MobAbility permits StrongerExplosionAbility, ExplosiveOnAttackAbility, SplitOnHitAbility,
        EnragedAbility, SummonOnDamageAbility {

    MobAbilityType type();
}
