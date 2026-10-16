package com.lodygames.rpgquest.entitlement.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Lit et valide {@code entitlements.yml} (issue #422). Comme {@code statistics.yml}, un fichier
 * invalide n'est jamais appliqué à moitié : la moindre erreur rend {@link RewardCatalog#DISABLED} et
 * la liste des erreurs, le plugin démarre normalement.
 */
public final class RewardCatalogLoader {

    /** Même forme que la clé {@code entitlement:} d'une récompense d'événement (statistics.yml). */
    static final Pattern REWARD_KEY = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern MATERIAL = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    private static final Pattern PERK_PART = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    /** Avantages gérés par un service dédié, jamais accordés directement par une récompense. */
    static final java.util.Set<String> RESERVED_PERKS =
            java.util.Set.of(com.lodygames.rpgquest.backpack.BackpackService.ENTITLEMENT_KEY);
    static final int LABEL_MAX = 120;
    static final int MAX_ITEMS = 9;
    static final int MAX_AMOUNT = 64;

    private RewardCatalogLoader() {
    }

    public record Result(RewardCatalog catalog, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result load(ConfigurationSection root) {
        List<String> errors = new ArrayList<>();
        boolean enabled = root.getBoolean("enabled", false);
        Map<String, RewardDefinition> rewards = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("rewards");
        if (root.contains("rewards") && section == null) {
            errors.add("« rewards » doit être une liste de récompenses par clé.");
        }
        if (section != null) {
            for (String key : section.getKeys(false)) {
                RewardDefinition reward = readReward(key, section.getConfigurationSection(key), errors);
                if (reward != null) {
                    rewards.put(key, reward);
                }
            }
        }
        if (!errors.isEmpty()) {
            return new Result(RewardCatalog.DISABLED, List.copyOf(errors));
        }
        return new Result(new RewardCatalog(enabled, rewards), List.of());
    }

    private static RewardDefinition readReward(String key, ConfigurationSection entry, List<String> errors) {
        String where = "rewards « " + key + " »";
        if (!REWARD_KEY.matcher(key).matches()) {
            errors.add(where + " : clé en minuscules, chiffres, _ . - (64 caractères au plus).");
            return null;
        }
        if (entry == null) {
            errors.add(where + " : doit contenir « label » et « items » ou « perk ».");
            return null;
        }
        String label = entry.getString("label");
        if (label == null || label.isBlank() || label.length() > LABEL_MAX) {
            errors.add(where + " : « label » obligatoire (" + LABEL_MAX + " caractères au plus).");
            return null;
        }
        int errorsBefore = errors.size();
        List<RewardDefinition.Item> items = readItems(where, entry.getMapList("items"), errors);
        if (entry.contains("items") && !entry.isList("items")) {
            errors.add(where + " : « items » doit être une liste.");
        }
        RewardDefinition.Perk perk = readPerk(where, entry, errors);
        if (errors.size() > errorsBefore) {
            return null;
        }
        if (items.isEmpty() && perk == null) {
            errors.add(where + " : au moins un objet (« items ») ou un avantage (« perk »).");
            return null;
        }
        return new RewardDefinition(key, label.trim(), items, perk);
    }

    private static List<RewardDefinition.Item> readItems(String where, List<Map<?, ?>> raw, List<String> errors) {
        List<RewardDefinition.Item> items = new ArrayList<>();
        if (raw.size() > MAX_ITEMS) {
            errors.add(where + " : " + MAX_ITEMS + " lignes d'objets au plus.");
            return items;
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> line = raw.get(i);
            String at = where + ".items[" + i + "]";
            Object id = line.get("item");
            String itemId = id == null ? null : String.valueOf(id).trim();
            if (itemId == null || !(isMaterial(itemId) || NAMESPACED.matcher(itemId).matches())) {
                errors.add(at + " : « item » = matériau Paper existant (DIAMOND) ou id custom (rpgquest:…).");
                continue;
            }
            Object amountRaw = line.containsKey("amount") ? line.get("amount") : 1;
            if (!(amountRaw instanceof Number number) || number.intValue() != number.doubleValue()
                    || number.intValue() < 1 || number.intValue() > MAX_AMOUNT) {
                errors.add(at + " : « amount » entier entre 1 et " + MAX_AMOUNT + ".");
                continue;
            }
            items.add(new RewardDefinition.Item(itemId, number.intValue()));
        }
        return items;
    }

    private static boolean isMaterial(String raw) {
        if (!MATERIAL.matcher(raw).matches()) {
            return false;
        }
        Material material = Material.matchMaterial(raw);
        return material != null && !material.isAir() && !material.isLegacy();
    }

    private static RewardDefinition.Perk readPerk(String where, ConfigurationSection entry, List<String> errors) {
        if (!entry.contains("perk")) {
            return null;
        }
        ConfigurationSection perk = entry.getConfigurationSection("perk");
        String key = perk == null ? null : perk.getString("key");
        String tier = perk == null ? null : perk.getString("tier");
        if (key == null || tier == null || !PERK_PART.matcher(key).matches() || !PERK_PART.matcher(tier).matches()) {
            errors.add(where + " : « perk » = {key: …, tier: …} (lettres, chiffres, _ . -).");
            return null;
        }
        if (RESERVED_PERKS.contains(key)) {
            // Le palier de sac se change avec BackpackService (redimensionnement, débordement) :
            // l'écrire seul dans player_entitlements désynchroniserait le sac du joueur.
            errors.add(where + " : l'avantage « " + key + " » est réservé et ne peut pas être accordé ici.");
            return null;
        }
        return new RewardDefinition.Perk(key, tier);
    }
}
