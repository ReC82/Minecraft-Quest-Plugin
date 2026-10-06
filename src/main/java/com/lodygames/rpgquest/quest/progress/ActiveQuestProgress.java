package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.quest.model.QuestState;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
    private String rewardGrantId;

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

    /**
     * Identifiant <strong>stable</strong> de l'occasion de paiement d'une récompense monétaire
     * (issue #16). Généré à la première demande puis conservé : si la remise de cette même
     * progression était rejouée, le crédit se présenterait en base avec le même identifiant et
     * serait donc refusé comme déjà payé, au lieu de doubler la récompense.
     *
     * <p>Une quête répétable repart d'une <em>nouvelle</em> instance de cette classe à chaque
     * acceptation : elle obtient donc un nouvel identifiant et sa complétion suivante est
     * légitimement payée.</p>
     */
    String rewardGrantId() {
        if (rewardGrantId == null) {
            rewardGrantId = UUID.randomUUID().toString();
        }
        return rewardGrantId;
    }
}
