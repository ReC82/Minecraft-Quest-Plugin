package com.lodygames.rpgquest.story.model;

/**
 * Cycle de vie de la progression d'un joueur sur une {@link StoryDefinition}. {@code NOT_STARTED}
 * n'est jamais persisté : l'absence de ligne dans {@code story_progress} pour un joueur+story
 * donné en tient lieu — même convention que {@link com.lodygames.rpgquest.quest.model.QuestState}.
 */
public enum StoryState {
    NOT_STARTED,
    ACTIVE,
    COMPLETED
}
