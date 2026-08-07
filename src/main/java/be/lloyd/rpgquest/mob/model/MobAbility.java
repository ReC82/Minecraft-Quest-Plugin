package be.lloyd.rpgquest.mob.model;

/**
 * Interface scellée (même discipline que {@code QuestReward}/{@code
 * DialogueAction}) : un `switch` exhaustif sur les trois capacités est
 * vérifié par le compilateur.
 */
public sealed interface MobAbility permits StrongerExplosionAbility, ExplosiveOnAttackAbility, SplitOnHitAbility {

    MobAbilityType type();
}
