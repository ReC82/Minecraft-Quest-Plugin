package com.lodygames.rpgquest.quest.periodic;

import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository;
import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository.Assignment;
import com.lodygames.rpgquest.quest.model.QuestPeriod;
import com.lodygames.rpgquest.quest.model.QuestState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.NamespacedKey;

/**
 * Issue #15 (retour TC-350) — où en est un joueur dans ses quêtes quotidiennes ou hebdomadaires de
 * la période en cours, calculé à partir de ses <strong>attributions réelles</strong> (jamais du
 * nombre de modèles publiés ni du nombre réglé dans {@code periodic-quests}).
 *
 * <p>Une attribution pas encore démarrée ({@code ASSIGNED} : crash, joueur parti entre le choix et
 * le démarrage) compte comme « pas encore commencée », même si la quête garde l'état
 * {@code COMPLETED} d'une période précédente : seul un {@code STARTED} de la période en cours peut
 * être « terminée ».</p>
 *
 * @param period     période concernée
 * @param toDo       quêtes de la période pas encore terminées, dans l'ordre de l'attribution
 * @param completed  quêtes de la période terminées, dans l'ordre de l'attribution
 * @param states     état <em>effectif</em> de chaque quête attribuée pour la période
 */
public record PeriodicProgress(QuestPeriod period, List<NamespacedKey> toDo, List<NamespacedKey> completed,
                               Map<NamespacedKey, QuestState> states) {

    public PeriodicProgress {
        toDo = List.copyOf(toDo);
        completed = List.copyOf(completed);
        states = Map.copyOf(states);
    }

    public static PeriodicProgress of(QuestPeriod period, List<Assignment> assignments,
                                      Map<NamespacedKey, QuestState> playerStates) {
        List<NamespacedKey> toDo = new ArrayList<>();
        List<NamespacedKey> completed = new ArrayList<>();
        Map<NamespacedKey, QuestState> states = new java.util.LinkedHashMap<>();
        for (Assignment assignment : assignments) {
            NamespacedKey questId = NamespacedKey.fromString(assignment.questId());
            if (questId == null || states.containsKey(questId)) {
                continue;
            }
            QuestState state = PeriodicQuestAssignmentRepository.STARTED.equals(assignment.status())
                    ? Objects.requireNonNullElse(playerStates.get(questId), QuestState.NOT_STARTED)
                    : QuestState.NOT_STARTED;
            states.put(questId, state);
            (state == QuestState.COMPLETED ? completed : toDo).add(questId);
        }
        return new PeriodicProgress(period, toDo, completed, states);
    }

    public int total() {
        return toDo.size() + completed.size();
    }

    public QuestState stateOf(NamespacedKey questId) {
        return states.getOrDefault(questId, QuestState.NOT_STARTED);
    }

    /** Titre court de l'onglet, lisible sans survol : « Quotidiennes : 1/2 terminée ». */
    public String shortSummary() {
        if (total() == 0) {
            return label() + " : aucune quête";
        }
        return label() + " : " + completed.size() + "/" + total() + " " + plural(completed.size(), "terminée");
    }

    /** Résumé complet : « Quotidiennes : 1/2 terminée — 1 restante ». */
    public String summary() {
        if (total() == 0) {
            return shortSummary();
        }
        int remaining = toDo.size();
        String rest = remaining == 0 ? "aucune restante" : remaining + " " + plural(remaining, "restante");
        return shortSummary() + " — " + rest;
    }

    private String label() {
        return period == QuestPeriod.DAILY ? "Quotidiennes" : "Hebdomadaires";
    }

    private static String plural(int count, String word) {
        return count > 1 ? word + "s" : word;
    }
}
