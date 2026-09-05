package com.lodygames.rpgquest.progression.model;

/**
 * Courbe de progression configurable et validée (mission point 3) : le coût
 * en XP du niveau {@code n} vers {@code n + 1} suit une croissance
 * géométrique {@code baseXp * growthFactor^(n - 1)}, arrondie à l'entier le
 * plus proche. Correcte par construction, même discipline que {@code
 * ZoneFlags}/{@code SpecialMobDefinition}.
 *
 * <p>Le niveau n'est <b>jamais</b> stocké séparément de l'XP totale : il est
 * toujours recalculé à partir de {@code totalXp} via {@link
 * #levelForTotalXp(long)}, pour qu'aucun état persisté ne puisse diverger
 * (validation étape 19 : « les valeurs ne peuvent ni déborder ni devenir
 * incohérentes »). {@link #maxTotalXp()} borne l'XP totale exploitable :
 * {@code ProgressionService} écrête toujours à cette valeur, jamais au-delà,
 * ce qui élimine tout risque de dépassement de capacité par accumulation.</p>
 */
public record ProgressionCurve(long baseXp, double growthFactor, int maxLevel) {

    public ProgressionCurve {
        if (baseXp <= 0) {
            throw new IllegalArgumentException("baseXp doit être strictement positif : " + baseXp);
        }
        if (growthFactor < 1.0) {
            throw new IllegalArgumentException("growthFactor doit être supérieur ou égal à 1.0 : " + growthFactor);
        }
        if (maxLevel < 1) {
            throw new IllegalArgumentException("maxLevel doit être au moins 1 : " + maxLevel);
        }
    }

    /** Coût en XP pour passer du niveau {@code level} au niveau {@code level + 1}. {@code level} est 1-indexé. */
    public long xpToNextLevel(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("level doit être au moins 1 : " + level);
        }
        if (level >= maxLevel) {
            return 0L; // niveau maximal atteint : plus aucun palier à franchir.
        }
        double raw = baseXp * Math.pow(growthFactor, level - 1);
        return Math.round(raw);
    }

    /** XP totale cumulée nécessaire pour atteindre {@code level} depuis le niveau 1 (0 XP au niveau 1). */
    public long totalXpForLevel(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("level doit être au moins 1 : " + level);
        }
        int cappedLevel = Math.min(level, maxLevel);
        long total = 0L;
        for (int l = 1; l < cappedLevel; l++) {
            total = Math.addExact(total, xpToNextLevel(l));
        }
        return total;
    }

    /** XP totale nécessaire pour atteindre {@link #maxLevel()} — jamais dépassée par {@code ProgressionService}. */
    public long maxTotalXp() {
        return totalXpForLevel(maxLevel);
    }

    /** Niveau correspondant à une XP totale cumulée donnée (jamais négative), plafonné à {@link #maxLevel()}. */
    public int levelForTotalXp(long totalXp) {
        if (totalXp < 0) {
            throw new IllegalArgumentException("totalXp ne peut pas être négative : " + totalXp);
        }
        long remaining = totalXp;
        int level = 1;
        while (level < maxLevel) {
            long cost = xpToNextLevel(level);
            if (remaining < cost) {
                break;
            }
            remaining -= cost;
            level++;
        }
        return level;
    }

    /** XP déjà acquise dans le niveau courant (depuis le dernier palier franchi), pour l'affichage d'une barre de progression. */
    public long xpIntoCurrentLevel(long totalXp) {
        return Math.max(0L, totalXp - totalXpForLevel(levelForTotalXp(totalXp)));
    }
}
