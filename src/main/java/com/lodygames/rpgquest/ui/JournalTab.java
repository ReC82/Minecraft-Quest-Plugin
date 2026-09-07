package com.lodygames.rpgquest.ui;

/**
 * Les deux onglets du journal de quêtes (item {@code rpgquest:journal_quetes} remis par le Libraire,
 * ou {@code /quests}). Le journal ne liste <strong>que</strong> les quêtes déjà connues du joueur :
 * il n'existe pas d'onglet « catalogue » des quêtes disponibles — celles-ci s'obtiennent uniquement
 * auprès des PNJ.
 */
enum JournalTab {
    /** Quêtes acceptées et encore actives ({@code ACTIVE} / {@code READY_TO_TURN_IN}). */
    IN_PROGRESS,
    /** Quêtes terminées ({@code COMPLETED}). */
    COMPLETED
}
