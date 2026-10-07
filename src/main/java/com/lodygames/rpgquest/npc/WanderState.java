package com.lodygames.rpgquest.npc;

/**
 * État de la <strong>promenade</strong> (Wander) d'un PNJ Citizens, relu tel qu'il est enregistré.
 *
 * <p>Issue #165. Citizens n'a pas de « trait Wander » : la promenade est un
 * {@code WaypointProvider} du trait {@code waypoints}, au même titre que {@code linear} (patrouille
 * par points) et {@code guided}. C'est pourquoi cet état porte {@link #provider} : savoir que la
 * promenade est inactive ne suffit pas, il faut savoir <em>ce qui occupe la place</em> avant de
 * proposer de la remplacer.</p>
 *
 * <p>Aucune référence à Citizens ici : c'est la vue transportable de ce que
 * {@link CitizensBehaviourBridge} a lu.</p>
 *
 * @param provider      nom du fournisseur courant : {@code linear} (défaut Citizens),
 *                      {@code wander}, {@code guided}, ou un autre enregistré par un plugin tiers.
 * @param waypointCount nombre de points du fournisseur courant quand il en expose
 *                      ({@code linear} et {@code guided} implémentent
 *                      {@code EnumerableWaypointProvider}) ; {@code 0} sinon. C'est ce compte qui
 *                      distingue une patrouille réelle d'un {@code linear} vide — l'état neutre
 *                      dans lequel Citizens laisse tout PNJ jamais configuré.
 * @param anchorWorld   monde de l'ancre de promenade, ou {@code null} si aucune ancre n'est
 *                      enregistrée. <strong>Sans ancre, Citizens ne borne pas la zone</strong> :
 *                      {@code WanderWaypointProvider} ne transmet son arbre de régions que si
 *                      {@code regionCentres} est non vide.
 * @param xRange        demi-côté horizontal de la zone, en blocs ({@code getXRange}). Défaut
 *                      Citizens : 25.
 * @param yRange        demi-hauteur verticale de la zone, en blocs ({@code getYRange}). Défaut
 *                      Citizens : 3.
 * @param delayTicks    pause entre deux déplacements, en ticks (20 ticks = 1 s). Défaut de
 *                      {@code WanderWaypointProvider} : {@code -1}, c'est-à-dire aucune pause
 *                      ajoutée.
 * @param pathfind      le PNJ calcule-t-il un chemin ({@code true}) ou se contente-t-il de pas d'un
 *                      bloc ({@code false}). Défaut Citizens : vrai.
 */
public record WanderState(boolean enabled, String provider, int waypointCount,
                          String anchorWorld, double anchorX, double anchorY, double anchorZ,
                          int xRange, int yRange, int delayTicks, boolean pathfind) {

    /** Vrai si une ancre est enregistrée, donc si la zone de promenade est effectivement bornée. */
    public boolean hasAnchor() {
        return anchorWorld != null && !anchorWorld.isBlank();
    }
}
