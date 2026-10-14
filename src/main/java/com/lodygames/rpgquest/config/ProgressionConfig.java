package com.lodygames.rpgquest.config;

/**
 * Section {@code progression:} — courbe de niveaux, mirroir vers la piste
 * {@code GLOBAL}, affichage, garde-fou anti-farm par répétition, et bascule
 * de l'XP vanilla (mission étape 19, points 3/9/10).
 */
public record ProgressionConfig(
        long baseXp,
        double growthFactor,
        int maxLevel,
        double globalMirrorRatio,
        int maxGrantsPerMinute,
        DisplayMode displayMode,
        boolean keepVanillaXp,
        int questCompletionXp,
        int combatKillXp,
        int miningBlockXp,
        int farmingHarvestXp,
        int fishingCatchXp,
        int explorationZoneXp,
        /**
         * Plafond gameplay des cœurs supplémentaires gagnés par récompense de quête (issue #166) ;
         * {@code 0} = aucun plafond. Les droits au-delà restent enregistrés : relever le plafond
         * les rend effectifs, sans rien rejouer.
         */
        int maxBonusHearts
) {
}
