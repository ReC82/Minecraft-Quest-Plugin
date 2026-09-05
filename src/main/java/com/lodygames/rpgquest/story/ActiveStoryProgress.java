package com.lodygames.rpgquest.story;

/**
 * État runtime, mutable, d'une Story {@code ACTIVE} pour un joueur — même conception que {@code
 * quest.progress.ActiveQuestProgress} : représente uniquement la position courante dans {@code
 * StoryDefinition#questIds()} (index, jamais l'id lui-même, pour rester correct même si la
 * définition change entre deux lectures). Toute mutation n'a lieu que sur le thread principal (voir
 * {@code StoryService#onQuestProgressChanged}), donc pas de {@code volatile}/verrou ici — même
 * convention que {@code ActiveQuestProgress}.
 */
final class ActiveStoryProgress {

    private final String storyId;
    private int currentIndex;

    ActiveStoryProgress(String storyId, int currentIndex) {
        this.storyId = storyId;
        this.currentIndex = currentIndex;
    }

    String storyId() {
        return storyId;
    }

    int currentIndex() {
        return currentIndex;
    }

    void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }
}
