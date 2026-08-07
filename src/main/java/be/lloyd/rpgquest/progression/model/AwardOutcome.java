package be.lloyd.rpgquest.progression.model;

/** Résultat d'une tentative d'octroi d'XP — voir {@code ProgressionService#awardXp}. */
public enum AwardOutcome {
    /** XP réellement accordée (et éventuellement niveau(x) franchi(s)). */
    GRANTED,
    /** Même (joueur, compétence, id d'événement) déjà récompensé : ignoré (mission point 5). */
    DUPLICATE,
    /** Refusé : {@code amount} négatif ou nul. */
    REJECTED_INVALID_AMOUNT,
    /** Refusé : trop d'octrois pour cette compétence dans la fenêtre de temps courante (anti-farm point 7). */
    THROTTLED
}
