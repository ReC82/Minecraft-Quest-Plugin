package com.lodygames.rpgquest.quest.progress;

import java.time.Duration;
import java.util.List;
import org.bukkit.NamespacedKey;

/**
 * Résultat d'une acceptation. {@code retryAfter} n'est renseigné (non nul) que pour
 * {@link Result#ON_COOLDOWN} : temps restant avant de pouvoir reprendre la quête (issue #13).
 */
public record AcceptOutcome(Result result, List<NamespacedKey> missingPrerequisites, Duration retryAfter) {

    public enum Result {
        ACCEPTED, UNKNOWN_QUEST, ALREADY_ACTIVE, NOT_REPEATABLE, MISSING_PREREQUISITES, ON_COOLDOWN,
        /** Issue #15 : modèle de quête périodique — seul le service des quêtes périodiques l'attribue. */
        NOT_ASSIGNED
    }

    public AcceptOutcome(Result result, List<NamespacedKey> missingPrerequisites) {
        this(result, missingPrerequisites, null);
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

    public static AcceptOutcome notAssigned() {
        return new AcceptOutcome(Result.NOT_ASSIGNED, List.of());
    }

    public static AcceptOutcome onCooldown(Duration retryAfter) {
        return new AcceptOutcome(Result.ON_COOLDOWN, List.of(), retryAfter);
    }
}
