package be.lloyd.rpgquest.zone.model;

/**
 * Permissions d'une zone protégée. Les six premiers champs sont ceux
 * « bloqués par défaut » de la mission (une zone toute neuve les a tous à
 * {@code false}) ; les cinq derniers sont ceux « autorisés par config »
 * (une zone toute neuve les a à {@code true}, sauf les conteneurs publics —
 * risque de vol dans une zone partagée, désactivés par défaut, décision
 * documentée dans {@code docs/SAFE_ZONE.md}).
 */
public record ZoneFlags(
        boolean allowPvp,
        boolean allowBlockBreak,
        boolean allowBlockPlace,
        boolean allowExplosions,
        boolean allowFire,
        boolean allowLava,
        boolean allowPistonsAcrossBorder,
        boolean allowHostileSpawn,
        boolean allowDoors,
        boolean allowButtons,
        boolean allowLevers,
        boolean allowNpcInteract,
        boolean allowPublicContainers
) {

    public static ZoneFlags defaults() {
        return new ZoneFlags(
                false, false, false, false, false, false, false, false,
                true, true, true, true, false);
    }
}
