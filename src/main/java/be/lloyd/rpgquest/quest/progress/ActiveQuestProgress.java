package be.lloyd.rpgquest.quest.progress;

import be.lloyd.rpgquest.quest.model.QuestState;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;

/**
 * État runtime, mutable, d'une quête en cours pour un joueur. Contrairement
 * aux modèles de définition ({@code quest.model}, immuables), cet état
 * change à chaque événement de jeu pertinent : le représenter comme mutable
 * est un choix délibéré (voir docs/ARCHITECTURE.md), pas un oubli.
 * Compteurs limités à l'étape courante : une étape terminée n'a plus besoin
 * des siens, {@link #advanceToStep} les réinitialise.
 */
final class ActiveQuestProgress {

    private final NamespacedKey questId;
    private int currentStepIndex;
    private final Map<Integer, Integer> objectiveCounters = new HashMap<>();
    private QuestState state;

    ActiveQuestProgress(NamespacedKey questId, int currentStepIndex, QuestState state) {
        this.questId = questId;
        this.currentStepIndex = currentStepIndex;
        this.state = state;
    }

    NamespacedKey questId() {
        return questId;
    }

    int currentStepIndex() {
        return currentStepIndex;
    }

    QuestState state() {
        return state;
    }

    void setState(QuestState state) {
        this.state = state;
    }

    int counter(int objectiveIndex) {
        return objectiveCounters.getOrDefault(objectiveIndex, 0);
    }

    void setCounter(int objectiveIndex, int value) {
        objectiveCounters.put(objectiveIndex, value);
    }

    /** @return la nouvelle valeur du compteur */
    int increment(int objectiveIndex) {
        int updated = counter(objectiveIndex) + 1;
        objectiveCounters.put(objectiveIndex, updated);
        return updated;
    }

    void advanceToStep(int stepIndex) {
        this.currentStepIndex = stepIndex;
        this.objectiveCounters.clear();
    }
}
