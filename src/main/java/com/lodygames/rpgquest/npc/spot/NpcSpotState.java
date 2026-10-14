package com.lodygames.rpgquest.npc.spot;

/**
 * État <strong>constaté</strong> d'un emplacement de PNJ (issue #381) : libre, occupé, inaccessible
 * ou en conflit. Jamais stocké — recalculé à chaque relevé à partir de ce que le serveur voit, pour
 * qu'un PNJ déplacé à la main dans Citizens apparaisse aussitôt en conflit au lieu de mentir.
 *
 * <p>Classe pure : l'appelant fournit les faits (monde accessible, position réelle du PNJ affecté,
 * présence d'un autre PNJ), la décision se teste sans serveur.</p>
 */
public record NpcSpotState(Status status, String message) {

    public enum Status {
        /** Aucun PNJ affecté, personne sur place. */
        FREE,
        /** Le PNJ affecté se tient bien à sa place (dans le rayon de sécurité). */
        OCCUPIED,
        /** Monde non chargé, ou qui n'accepte pas de PNJ : rien ne peut y être placé. */
        INACCESSIBLE,
        /** Ce qui est réservé et ce qui est en jeu divergent. */
        CONFLICT
    }

    /** Position réelle d'un PNJ dans le monde. */
    public record Position(String world, double x, double y, double z) {
    }

    /**
     * @param worldUsable     le monde est chargé et accepte des PNJ
     * @param definitionKnown la définition logique du PNJ affecté existe (ignoré si libre)
     * @param assignedAt      position réelle du PNJ affecté, ou {@code null} s'il n'existe pas en jeu
     * @param otherNpcNearby  un autre PNJ (non affecté ici) se tient dans le rayon de l'emplacement
     */
    public static NpcSpotState classify(NpcSpot spot, boolean worldUsable, boolean definitionKnown,
                                        Position assignedAt, boolean otherNpcNearby) {
        if (!worldUsable) {
            return new NpcSpotState(Status.INACCESSIBLE, "Le monde « " + spot.world()
                    + " » n'est pas chargé ou n'accepte pas de PNJ.");
        }
        if (spot.free()) {
            return otherNpcNearby
                    ? new NpcSpotState(Status.CONFLICT,
                    "Libre, mais un autre PNJ se tient déjà ici : un placement le chevaucherait.")
                    : new NpcSpotState(Status.FREE, "Libre.");
        }
        if (!definitionKnown) {
            return new NpcSpotState(Status.CONFLICT, "Affecté à « " + spot.npcId()
                    + " », dont la définition n'existe plus. Libérez l'emplacement ou recréez le PNJ.");
        }
        if (assignedAt == null) {
            return new NpcSpotState(Status.CONFLICT, "Affecté à « " + spot.npcId()
                    + " », qui n'existe pas en jeu. « Placer » le fera apparaître ici.");
        }
        if (!assignedAt.world().equals(spot.world())
                || spot.distanceTo(assignedAt.x(), assignedAt.y(), assignedAt.z()) > spot.radius()) {
            return new NpcSpotState(Status.CONFLICT, "« " + spot.npcId() + " » n'est plus à sa place ("
                    + assignedAt.world() + " " + Math.round(assignedAt.x()) + " / " + Math.round(assignedAt.y())
                    + " / " + Math.round(assignedAt.z()) + "). « Placer » le ramènera ici.");
        }
        return new NpcSpotState(Status.OCCUPIED, "Occupé par « " + spot.npcId() + " ».");
    }
}
