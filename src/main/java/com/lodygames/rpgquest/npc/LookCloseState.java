package com.lodygames.rpgquest.npc;

/**
 * État du trait Citizens {@code lookclose} pour un PNJ, relu tel qu'il est réellement enregistré.
 *
 * <p>Issue #165. Ce type ne contient <strong>aucune</strong> référence à Citizens : c'est la vue
 * transportable (relevé {@code npc.citizens.list}, panel, tests) de ce que
 * {@link CitizensBehaviourBridge} a lu sur le trait. Les champs reprennent un à un des accesseurs
 * publics réels de {@code net.citizensnpcs.trait.LookClose} de la build installée — rien n'est
 * déduit ni supposé.</p>
 *
 * @param range                 portée en <strong>blocs</strong> ({@code LookClose#getRange}).
 *                              Défaut Citizens : {@code npc.default.look-close.range} = 10.
 * @param realisticLooking      le PNJ exige-t-il une ligne de vue dégagée
 *                              ({@code LookClose#useRealisticLooking}). Défaut Citizens : faux.
 * @param disableWhileNavigating le regard est-il suspendu pendant un déplacement
 *                              ({@code LookClose#disableWhileNavigating}). Défaut Citizens : vrai.
 * @param targetNpcs            le PNJ regarde-t-il aussi les autres PNJ
 *                              ({@code LookClose#targetNPCs}). Défaut Citizens : faux.
 */
public record LookCloseState(boolean enabled, double range, boolean realisticLooking,
                             boolean disableWhileNavigating, boolean targetNpcs) {

    /** Défaut Citizens 2.0.43 : désactivé, portée 10 blocs, suspendu en déplacement. */
    public static LookCloseState citizensDefaults() {
        return new LookCloseState(false, 10.0, false, true, false);
    }
}
