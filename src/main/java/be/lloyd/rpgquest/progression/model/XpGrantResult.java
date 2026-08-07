package be.lloyd.rpgquest.progression.model;

/**
 * Résultat complet d'un octroi d'XP sur une compétence : permet à l'appelant
 * (affichage action bar/bossbar, mission point 9) de savoir s'il doit
 * afficher un simple gain ou une montée de niveau — potentiellement de
 * plusieurs niveaux d'un coup (mission, test automatique « montée de
 * plusieurs niveaux »).
 */
public record XpGrantResult(
        SkillType skill,
        AwardOutcome outcome,
        long amountGranted,
        long newTotalXp,
        int previousLevel,
        int newLevel
) {
    public boolean leveledUp() {
        return outcome == AwardOutcome.GRANTED && newLevel > previousLevel;
    }

    public int levelsGained() {
        return leveledUp() ? newLevel - previousLevel : 0;
    }
}
