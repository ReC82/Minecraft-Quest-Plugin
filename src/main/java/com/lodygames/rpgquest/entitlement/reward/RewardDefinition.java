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
    /** Résumé lisible pour PlugAdmin : « 2 × DIAMOND, ENCHANTED_BOOK (sharpness 3) + avantage title:GOLD ». */
    public String summary() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Item item : items) {
            StringBuilder part = new StringBuilder();
            if (item.amount() > 1) {
                part.append(item.amount()).append(" × ");
            }
            part.append(item.itemId());
            java.util.List<String> extras = new java.util.ArrayList<>();
            item.enchantments().forEach((key, level) -> extras.add(key + " " + level));
            if (item.potion() != null) {
                extras.add("potion " + item.potion());
            }
            if (!extras.isEmpty()) {
                part.append(" (").append(String.join(", ", extras)).append(')');
            }
            parts.add(part.toString());
        }
        String text = String.join(", ", parts);
        if (perk != null) {
            text = (text.isEmpty() ? "" : text + " + ") + "avantage " + perk.key() + ":" + perk.tier();
        }
        return text;
    }

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
