package com.lodygames.rpgquest.quest.model;

/**
 * Cycle de vie de la progression d'un joueur sur une quête. {@code
 * NOT_STARTED} n'est jamais persisté : l'absence de ligne dans {@code
 * quest_progress} pour un joueur+quête donné en tient lieu.
 */
public enum QuestState {
    NOT_STARTED,
    ACTIVE,
    READY_TO_TURN_IN,
    COMPLETED,
    FAILED,
    ABANDONED
}
