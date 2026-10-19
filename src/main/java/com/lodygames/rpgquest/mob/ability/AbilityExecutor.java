package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;

/**
 * Issue #542 — le code Paper d'un type de capacité. {@link AbilityEngine} lui présente une instance
 * <em>active</em> (activée, première de son type) aux seuls déclencheurs de son descripteur ; l'exécuteur
 * vérifie ses conditions propres, appelle {@link AbilityContext#passesGate()} au moment de décider
 * (délai + chance communs), agit, puis {@link AbilityContext#markActivated()}.
 *
 * <p>Un exécuteur ne garde aucun état par entité : délais et combat vivent dans le moteur, qui les
 * nettoie ; un état durable éventuel va en PDC sur l'entité elle-même.</p>
 */
public interface AbilityExecutor<A extends MobAbility> {

    MobAbilityType type();

    Class<A> abilityClass();

    void execute(AbilityContext<A> context);
}
