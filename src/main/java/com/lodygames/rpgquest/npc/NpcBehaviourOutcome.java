package com.lodygames.rpgquest.npc;

/**
 * Résultat d'une écriture de comportement Citizens (issue #165) : « regarder les joueurs » ou
 * « promenade ».
 *
 * <p>Type public et sans aucune référence à Citizens, pour que la couche agent le transporte sans
 * dépendre du plugin. {@code code} est stable et destiné au panel ; {@code message} est la phrase
 * montrée à l'opérateur.</p>
 *
 * <p>Codes produits : {@code LOOKCLOSE_SET}, {@code WANDER_ENABLED}, {@code WANDER_DISABLED} en
 * succès ; {@code CITIZENS_NOT_FOUND}, {@code CITIZENS_INCOMPATIBLE}, {@code WANDER_CONFLICT},
 * {@code WANDER_NOT_ACTIVE}, {@code WANDER_UNAVAILABLE}, {@code WANDER_NOT_APPLIED},
 * {@code LOOKCLOSE_NOT_APPLIED} en échec.</p>
 */
public record NpcBehaviourOutcome(boolean ok, String code, String message) {

    static NpcBehaviourOutcome ok(String code, String message) {
        return new NpcBehaviourOutcome(true, code, message);
    }

    static NpcBehaviourOutcome fail(String code, String message) {
        return new NpcBehaviourOutcome(false, code, message);
    }
}
