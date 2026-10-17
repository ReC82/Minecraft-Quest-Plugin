package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.CooldownText;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Issue #13 — lignes du journal propres aux quêtes secondaires renouvelables (rencontres de biome) :
 * l'étiquette « Rencontre », la cadence de renouvellement et, pour une quête terminée, le temps
 * restant avant de pouvoir la refaire. Pur (aucun accès Bukkit) : le temps restant est calculé par
 * {@code QuestProgressEngine#remainingCooldowns}, avec les mêmes règles que l'acceptation.
 *
 * <p>Seuls des nombres et des libellés fixes sont insérés dans le MiniMessage produit : aucune
 * donnée éditoriale n'y est concaténée.</p>
 */
final class JournalReplayText {

    /** Catégorie des quêtes de rencontre — même règle que la page Quêtes du panel. */
    static final String ENCOUNTER_CATEGORY = "encounter";
    private static final String ENCOUNTER_GIVER_PREFIX = "encounter_";

    private JournalReplayText() {
    }

    /** Quête proposée par un PNJ récurrent de biome : catégorie {@code encounter} ou donneur {@code encounter_*}. */
    static boolean isEncounter(QuestDefinition quest) {
        String category = quest.category() == null ? "" : quest.category().trim().toLowerCase(Locale.ROOT);
        String giver = quest.giver() == null ? "" : quest.giver().trim().toLowerCase(Locale.ROOT);
        return ENCOUNTER_CATEGORY.equals(category) || giver.startsWith(ENCOUNTER_GIVER_PREFIX);
    }

    /** Étiquette affichée sous l'état d'une quête de rencontre, vide pour toute autre quête. */
    static Optional<String> encounterTag(QuestDefinition quest) {
        return isEncounter(quest)
                ? Optional.of("<dark_aqua>Rencontre</dark_aqua> <gray>— quête secondaire</gray>")
                : Optional.empty();
    }

    /**
     * Pour une quête <strong>terminée et répétable</strong> : quand pourra-t-elle être refaite ?
     * {@code remaining} nul ou nul en durée = tout de suite. Vide pour toute autre quête : une quête
     * non répétable n'annonce rien, une quête en cours non plus.
     */
    static Optional<String> replayLine(QuestDefinition quest, QuestState state, Duration remaining) {
        // Issue #15 : une quête périodique annonce son renouvellement à part (onglet de sa période).
        if (state != QuestState.COMPLETED || !quest.repeatable() || quest.isPeriodic()) {
            return Optional.empty();
        }
        if (remaining != null && !remaining.isZero() && !remaining.isNegative()) {
            return Optional.of("<gray>À refaire dans</gray> <white>" + CooldownText.format(remaining) + "</white>");
        }
        return Optional.of(isEncounter(quest)
                ? "<green>Peut être refaite</green> <gray>auprès d'un PNJ de rencontre</gray>"
                : "<green>Peut être refaite</green>");
    }

    /** Cadence d'une quête répétable avec délai (« toutes les 20 h »), pour la vue détail. */
    static Optional<String> cadenceLine(QuestDefinition quest) {
        if (quest.isPeriodic()) {
            return Optional.of("<white>Renouvelable :</white> <gray>"
                    + (quest.period() == com.lodygames.rpgquest.quest.model.QuestPeriod.DAILY
                    ? "chaque jour" : "chaque semaine") + "</gray>");
        }
        if (!quest.repeatable() || quest.cooldown().isZero()) {
            return Optional.empty();
        }
        return Optional.of("<white>Renouvelable :</white> <gray>toutes les "
                + CooldownText.format(quest.cooldown()) + "</gray>");
    }
}
