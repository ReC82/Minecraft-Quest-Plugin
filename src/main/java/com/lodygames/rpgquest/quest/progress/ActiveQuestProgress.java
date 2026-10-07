package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.quest.model.QuestState;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * {@link java.util.concurrent.ConcurrentHashMap} et non {@code HashMap} : ces compteurs sont
     * écrits sur le thread principal mais <strong>lus</strong> aussi depuis un thread de base de
     * données — l'évaluation des conditions de dialogue se poursuit sur le thread qui termine la
     * lecture précédente (voir {@code npc.hint.NpcHintService}, qui chaîne {@code reachableNodes}
     * après une requête), et la condition {@code HAS_PENDING_DELIVERY} (issue #123) lit ces
     * compteurs. Une lecture concurrente d'un {@code HashMap} en cours d'écriture n'a aucune
     * garantie ; ici la valeur lue peut être d'un tick en retard, jamais incohérente.
     */
    private final Map<Integer, Integer> objectiveCounters = new ConcurrentHashMap<>();
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
