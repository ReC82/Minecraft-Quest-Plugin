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
     * Jeton <strong>stable</strong> de CETTE complétion, racine de l'identité de paiement des
     * récompenses monétaires (issue #16). Généré à la première demande puis conservé.
     *
     * <p>Il n'est pas lui-même l'identifiant de paiement : chaque récompense monétaire de la quête
     * obtient {@code <jeton>#<index>}, parce qu'une même complétion peut en porter plusieurs et
     * qu'elles doivent toutes être payées — avec une identité partagée, toutes sauf la première
     * seraient avalées comme « déjà payées ».</p>
     *
     * <p>Ce jeton n'a pas besoin de survivre à un redémarrage : dès qu'il a servi, il est écrit
     * <strong>en base</strong> dans les lignes de dette, et c'est de là que la reprise relit
     * l'identité de paiement initiale. L'objet en mémoire, lui, ne sert qu'à la remise en cours.</p>
     *
     * <p>Une quête répétable repart d'une <em>nouvelle</em> instance de cette classe à chaque
     * acceptation : elle obtient donc un nouveau jeton et sa complétion suivante est légitimement
     * payée.</p>
     */
    String rewardGrantId() {
        if (rewardGrantId == null) {
            rewardGrantId = UUID.randomUUID().toString();
        }
        return rewardGrantId;
    }
}
