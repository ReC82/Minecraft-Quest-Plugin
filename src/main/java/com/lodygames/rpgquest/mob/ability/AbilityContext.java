package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;

/**
 * Issue #542 — une occasion de déclenchement présentée à un {@link AbilityExecutor}.
 *
 * @param event l'événement Paper d'origine, {@code null} pour {@link AbilityTrigger#TICK}
 */
public record AbilityContext<A extends MobAbility>(AbilityEngine engine, AbilityTrigger trigger, LivingEntity mob,
                                                   SpecialMobDefinition definition, A ability, Event event) {

    /** Délai écoulé et tirage de chance réussi (champs communs) ; aucun effet de bord. */
    public boolean passesGate() {
        return engine.passesGate(mob, ability);
    }

    /** La capacité a agi : démarre son délai et compte le déclenchement. */
    public void markActivated() {
        engine.markActivated(mob, ability);
    }

    public <E extends Event> E event(Class<E> type) {
        return type.cast(event);
    }
}
