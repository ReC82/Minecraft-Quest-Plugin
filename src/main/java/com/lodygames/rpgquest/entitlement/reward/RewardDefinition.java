package com.lodygames.rpgquest.entitlement.reward;

import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Une récompense du catalogue {@code entitlements.yml} (issue #422, docs/REWARD_ENTITLEMENTS.md §3).
 *
 * @param items objets remis ensemble, tout ou rien (peut être vide si {@code perk} est présent)
 * @param perk  avantage persistant accordé via {@code EntitlementService}, facultatif
 */
public record RewardDefinition(String key, String label, List<Item> items, @Nullable Perk perk) {

    public RewardDefinition {
        items = List.copyOf(items);
    }

    /**
     * @param itemId nom de matériau Paper ({@code DIAMOND}) ou id custom namespacé
     *               ({@code rpgquest:return_stone}), résolu à la remise
     */
    public record Item(String itemId, int amount) {

        public boolean custom() {
            return itemId.indexOf(':') >= 0;
        }
    }

    public record Perk(String key, String tier) {
    }
}
