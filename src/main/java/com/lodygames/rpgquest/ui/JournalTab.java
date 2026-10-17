package com.lodygames.rpgquest.ui;

/**
 * Les onglets du journal de quêtes (item {@code rpgquest:journal_quetes} remis par le Libraire,
 * ou {@code /quests}). Le journal ne liste <strong>que</strong> les quêtes déjà connues du joueur :
 * il n'existe pas d'onglet « catalogue » des quêtes disponibles — celles-ci s'obtiennent uniquement
 * auprès des PNJ.
 */
enum JournalTab {
    /** Quêtes acceptées et encore actives ({@code ACTIVE} / {@code READY_TO_TURN_IN}). */
    IN_PROGRESS,
    /** Quêtes terminées ({@code COMPLETED}). */
    COMPLETED,
    /** Issue #15 : quêtes quotidiennes attribuées pour la période en cours. */
    DAILY,
    /** Issue #15 : quêtes hebdomadaires attribuées pour la période en cours. */
    WEEKLY;

    /** Période d'un onglet de quêtes périodiques, {@code null} pour les onglets ordinaires. */
    com.lodygames.rpgquest.quest.model.QuestPeriod period() {
        return switch (this) {
            case DAILY -> com.lodygames.rpgquest.quest.model.QuestPeriod.DAILY;
            case WEEKLY -> com.lodygames.rpgquest.quest.model.QuestPeriod.WEEKLY;
            default -> null;
        };
    }
}
