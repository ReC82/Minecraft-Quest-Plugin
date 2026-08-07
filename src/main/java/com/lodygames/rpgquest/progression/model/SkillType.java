package com.lodygames.rpgquest.progression.model;

/**
 * Les pistes de progression RPG, indépendantes de l'XP vanilla. {@link
 * #GLOBAL} agrège toutes les sources (mission point 1) : chaque octroi d'XP
 * sur une piste spécifique en mirrore automatiquement une partie sur {@code
 * GLOBAL} (voir {@code ProgressionService#awardXp}), un appelant n'a jamais
 * besoin d'accorder les deux explicitement.
 */
public enum SkillType {
    GLOBAL,
    COMBAT,
    MINING,
    FARMING,
    FISHING,
    EXPLORATION
}
