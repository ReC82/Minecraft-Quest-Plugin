package com.lodygames.rpgquest.entitlement.reward;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce dont une source de tirages (fin de quête, #177) a besoin du catalogue de récompenses : la table,
 * le libellé d'une récompense, et la remise immédiate des droits créés. Testable sans base.
 */
public interface RewardTables {

    Optional<RewardTable> table(String id);

    /** Libellé du catalogue, ou la clé elle-même si elle y est inconnue. */
    String label(String rewardKey);

    void deliverSoon(List<UUID> players);
}
