package com.lodygames.rpgquest.quest.progress;

import java.util.List;
import org.bukkit.NamespacedKey;

public record AcceptOutcome(Result result, List<NamespacedKey> missingPrerequisites) {

    public enum Result {
        ACCEPTED, UNKNOWN_QUEST, ALREADY_ACTIVE, NOT_REPEATABLE, MISSING_PREREQUISITES
    }

    public static AcceptOutcome accepted() {
        return new AcceptOutcome(Result.ACCEPTED, List.of());
    }

    public static AcceptOutcome unknown() {
        return new AcceptOutcome(Result.UNKNOWN_QUEST, List.of());
    }

    public static AcceptOutcome alreadyActive() {
        return new AcceptOutcome(Result.ALREADY_ACTIVE, List.of());
    }

    public static AcceptOutcome notRepeatable() {
        return new AcceptOutcome(Result.NOT_REPEATABLE, List.of());
    }

    public static AcceptOutcome missingPrerequisites(List<NamespacedKey> missing) {
        return new AcceptOutcome(Result.MISSING_PREREQUISITES, missing);
    }
}
