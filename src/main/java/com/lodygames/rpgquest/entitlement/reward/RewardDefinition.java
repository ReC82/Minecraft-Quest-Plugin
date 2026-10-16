package com.lodygames.rpgquest.entitlement.reward;

import java.util.List;
import java.util.Map;
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
     * @param itemId       nom de matériau Paper ({@code DIAMOND}) ou id custom namespacé
     *                     ({@code rpgquest:return_stone}), résolu à la remise
     * @param enchantments enchantements par clé Minecraft ({@code sharpness} → niveau) : appliqués à
     *                     l'objet, ou <em>stockés</em> dans un livre enchanté (issue #177)
     * @param potion       type de potion Minecraft ({@code strength}) d'une potion ou flèche, facultatif
     */
    public record Item(String itemId, int amount, Map<String, Integer> enchantments, @Nullable String potion) {

        public Item {
            enchantments = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(enchantments));
        }

        public Item(String itemId, int amount) {
            this(itemId, amount, Map.of(), null);
        }

        public boolean custom() {
            return itemId.indexOf(':') >= 0;
        }
    }

    public record Perk(String key, String tier) {
    }
}
