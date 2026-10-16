package com.lodygames.rpgquest.entitlement.reward;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Un droit à récompense tel qu'enregistré dans {@code reward_entitlements} (issue #422).
 *
 * @param idempotencyKey clé stable et unique : deux déclarations de la même clé = un seul droit
 * @param rewardKey      clé du catalogue {@code entitlements.yml}, résolue à la remise
 * @param label          libellé figé à la déclaration (traçabilité)
 */
public record RewardEntitlement(String idempotencyKey, UUID playerId, RewardSource source, String sourceRef,
                                String rewardKey, @Nullable String label, RewardEntitlementStatus status,
                                int attempts, @Nullable String lastError, @Nullable String revokeReason,
                                Instant createdAt, Instant updatedAt, @Nullable Instant deliveredAt,
                                @Nullable Instant revokedAt) {

    /** Longueur maximale d'une clé d'idempotence (colonne clé VARCHAR(191) sur MariaDB). */
    public static final int MAX_KEY_LENGTH = 160;
    /** Caractères admis dans une clé d'idempotence ou une référence de source. */
    public static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_:.\\-]{1," + MAX_KEY_LENGTH + "}");

    /** Ce qu'une source déclare : le statut initial est toujours {@link RewardEntitlementStatus#PENDING}. */
    public record Declaration(String idempotencyKey, UUID playerId, RewardSource source, String sourceRef,
                              String rewardKey, @Nullable String label) {

        public Declaration {
            if (!KEY_PATTERN.matcher(idempotencyKey).matches()) {
                throw new IllegalArgumentException("clé d'idempotence invalide : " + idempotencyKey);
            }
        }
    }
}
