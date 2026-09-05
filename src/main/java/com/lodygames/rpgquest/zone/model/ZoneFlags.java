package com.lodygames.rpgquest.zone.model;

/**
 * Permissions d'une zone protégée. Trois groupes de champs :
 * <ul>
 * <li>Les onze premiers sont « bloqués par défaut » (une zone toute neuve les a tous à
 * {@code false}) : les six historiques de la mission (pvp, casse/pose de bloc, explosions, feu,
 * lave, pistons traversant la frontière), plus {@code allowHostileSpawn}, et trois protections
 * "sécurité du joueur" ajoutées pour le village central : {@code allowHostileDamage} (dégâts
 * infligés par un mob hostile), {@code allowEnvironmentalDamage} (chute, noyade, faim, lave,
 * feu... — voir {@code ZoneProtectionListener#ENVIRONMENTAL_CAUSES}) et {@code allowNpcDamage}
 * (dégâts subis par un PNJ Citizens présent dans la zone).</li>
 * <li>Les cinq suivants sont « autorisés par config » (une zone toute neuve les a à {@code
 * true}, sauf les conteneurs publics — risque de vol dans une zone partagée, désactivés par
 * défaut, décision documentée dans {@code docs/SAFE_ZONE.md}).</li>
 * <li>{@code forceDay} (dernier champ, {@code false} par défaut) est un réglage cosmétique
 * indépendant, pas une protection : temps figé à midi côté client uniquement pour un joueur
 * présent dans la zone (voir {@code ZoneProtectionListener#applyDayOverride}), jamais l'horloge
 * du monde entier.</li>
 * </ul>
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
        boolean allowHostileDamage,
        boolean allowEnvironmentalDamage,
        boolean allowNpcDamage,
        boolean allowDoors,
        boolean allowButtons,
        boolean allowLevers,
        boolean allowNpcInteract,
        boolean allowPublicContainers,
        boolean forceDay
) {

    public static ZoneFlags defaults() {
        return new ZoneFlags(
                false, false, false, false, false, false, false, false, false, false, false,
                true, true, true, true, false,
                false);
    }
}
