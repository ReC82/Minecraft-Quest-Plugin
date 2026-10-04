package com.lodygames.rpgquest.mob.model;

/**
 * Catégorie d'un profil de mob spécial (issue #172, lot 1 de l'EPIC #169).
 *
 * <ul>
 *   <li>{@code SPECIAL} : éligible au tirage aléatoire dans le Wild ({@code SpecialMobService}) --
 *       nom coloré.</li>
 *   <li>{@code BOSS} : jamais tiré au hasard (exclu explicitement du tirage) -- nom coloré,
 *       particules colorées en continu, barre de vie. N'apparaît que par action admin de test ou,
 *       plus tard, par objectif de quête (#171, non implémenté dans ce lot).</li>
 * </ul>
 */
public enum MobCategory {
    SPECIAL,
    BOSS
}
