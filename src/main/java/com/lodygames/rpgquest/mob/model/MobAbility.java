package com.lodygames.rpgquest.mob.model;

/**
 * Interface scellée (même discipline que {@code QuestReward}/{@code
 * DialogueAction}) : un `switch` exhaustif sur les capacités est
 * vérifié par le compilateur.
 *
 * <p>Issue #542 : chaque instance porte ses {@link AbilityOptions} (activation, chance, délai), gérées
 * par le moteur ({@code mob.ability.AbilityEngine}) et jamais par la capacité elle-même. Le contrat
 * des paramètres (bornes, libellés, rendu YAML) est décrit par {@code mob.ability.MobAbilityCatalog}.</p>
 */
public sealed interface MobAbility permits StrongerExplosionAbility, ExplosiveOnAttackAbility, SplitOnHitAbility,
        EnragedAbility, SummonOnDamageAbility {

    MobAbilityType type();

    AbilityOptions options();
}
