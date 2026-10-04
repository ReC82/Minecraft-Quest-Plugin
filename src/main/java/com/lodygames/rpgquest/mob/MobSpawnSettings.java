package com.lodygames.rpgquest.mob;

/**
 * Réglage global du tirage aléatoire de mobs spéciaux dans le Wild (issue #174).
 *
 * <p>Résout l'ambiguïté du « pourcentage de chance cumulée » signalée par le porteur de projet :
 * {@code chance} est un <b>throttle global</b>, évalué une seule fois par spawn naturel éligible,
 * <i>avant</i> d'examiner les définitions individuelles ({@code SpecialMobDefinition#spawnChance()},
 * inchangé). Seulement si ce tirage global réussit, chaque définition {@code SPECIAL} compatible
 * tire ensuite sa propre chance ; si plusieurs réussissent simultanément, un tirage pondéré explicite
 * (poids = {@code spawnChance} de chacune) choisit laquelle s'applique -- jamais la première trouvée.</p>
 *
 * <p>{@code maxSimultaneousSpecial} plafonne le nombre total de mobs {@code SPECIAL} vivants, toutes
 * définitions confondues (en plus du {@code max-population} déjà existant par définition). Les BOSS
 * ne sont jamais comptés ici et ne participent jamais à ce tirage (voir {@code MobCategory}).</p>
 */
public record MobSpawnSettings(boolean enabled, double chance, Integer maxSimultaneousSpecial) {

    public MobSpawnSettings {
        if (chance < 0 || chance > 1) {
            throw new IllegalArgumentException("chance doit être compris entre 0 et 1 : " + chance);
        }
        if (maxSimultaneousSpecial != null && maxSimultaneousSpecial < 0) {
            throw new IllegalArgumentException(
                    "maxSimultaneousSpecial ne peut pas être négatif si présent : " + maxSimultaneousSpecial);
        }
    }

    /** Rétro-compatible : avant l'issue #174, chaque spawn naturel éligible était toujours examiné. */
    public static MobSpawnSettings defaults() {
        return new MobSpawnSettings(true, 1.0, null);
    }
}
