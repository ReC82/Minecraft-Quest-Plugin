package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.quest.model.QuestObjective;
import org.bukkit.NamespacedKey;

/**
 * Localise un objectif précis : quelle quête, quelle étape (index — pour
 * comparer vite avec l'étape courante en mémoire — et id, pour les clés de
 * persistance), quel index dans cette étape.
 */
public record ObjectiveRef(NamespacedKey questId, int stepIndex, String stepId, int objectiveIndex, QuestObjective objective) {
}
