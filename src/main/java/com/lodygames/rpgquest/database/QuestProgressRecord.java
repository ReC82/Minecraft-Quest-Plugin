package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.quest.model.QuestState;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.NamespacedKey;

/** Snapshot persisté de la progression d'un joueur sur une quête (sans les compteurs d'objectifs). */
public record QuestProgressRecord(UUID playerUuid, NamespacedKey questId, QuestState state, String currentStepId, Instant updatedAt) {
}
