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
    static final int MAX_ENCHANTMENTS = 8;
    static final int MAX_ENCHANT_LEVEL = 10;
    static final int MAX_TABLES = 100;
    static final int MAX_TABLE_ENTRIES = 20;
    private static final Pattern MINECRAFT_KEY = Pattern.compile("[a-z0-9_]{1,64}");
    /** Identifiant d'une table (issue #177) : court, pour tenir dans la clé d'idempotence d'un gain. */
    public static final Pattern TABLE_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    static final Pattern ENTRY_ID = Pattern.compile("[a-z0-9_-]{1,24}");
    static final java.util.Set<String> POTION_ITEMS =
            java.util.Set.of("POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW");

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
        Map<String, RewardTable> tables = errors.isEmpty() ? readTables(root, rewards, errors) : Map.of();
        if (!errors.isEmpty()) {
            return new Result(RewardCatalog.DISABLED, List.copyOf(errors));
        }
        return new Result(new RewardCatalog(enabled, rewards, tables), List.of());
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
            Map<String, Integer> enchantments = readEnchantments(at, itemId, line.get("enchantments"), errors);
            String potion = readPotion(at, itemId, line.get("potion"), errors);
            if (enchantments == null || (line.containsKey("potion") && potion == null)) {
                continue;
            }
            items.add(new RewardDefinition.Item(itemId, number.intValue(), enchantments, potion));
        }
        return items;
    }

    /**
     * {@code enchantments: {sharpness: 3}} (issue #177). Forme vérifiée ici ; la clé Minecraft est
     * résolue à la remise (une clé inconnue laisse le droit en attente avec l'erreur). Sur un
     * {@code ENCHANTED_BOOK}, les enchantements sont stockés dans le livre — jamais appliqués à
     * l'équipement du joueur.
     */
    private static Map<String, Integer> readEnchantments(String at, String itemId, Object raw, List<String> errors) {
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        if (raw == null) {
            return enchantments;
        }
        Map<?, ?> map = raw instanceof ConfigurationSection section ? section.getValues(false)
                : raw instanceof Map<?, ?> m ? m : null;
        if (map == null || map.isEmpty() || map.size() > MAX_ENCHANTMENTS) {
            errors.add(at + " : « enchantments » = {clé: niveau}, " + MAX_ENCHANTMENTS + " au plus.");
            return null;
        }
        if (itemId.indexOf(':') >= 0) {
            errors.add(at + " : « enchantments » ne s'applique pas à un objet custom.");
            return null;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey()).trim().toLowerCase(java.util.Locale.ROOT);
            if (key.startsWith("minecraft:")) {
                key = key.substring("minecraft:".length());
            }
            if (!MINECRAFT_KEY.matcher(key).matches() || !(e.getValue() instanceof Number level)
                    || level.intValue() != level.doubleValue() || level.intValue() < 1
                    || level.intValue() > MAX_ENCHANT_LEVEL) {
                errors.add(at + " : enchantement « " + e.getKey() + " » = clé Minecraft (sharpness) et niveau 1–"
                        + MAX_ENCHANT_LEVEL + ".");
                return null;
            }
            enchantments.put(key, level.intValue());
        }
        return enchantments;
    }

    /** {@code potion: strength} sur une potion ou une flèche à effet (issue #177). */
    private static String readPotion(String at, String itemId, Object raw, List<String> errors) {
        if (raw == null) {
            return null;
        }
        String potion = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (potion.startsWith("minecraft:")) {
            potion = potion.substring("minecraft:".length());
        }
        if (!POTION_ITEMS.contains(itemId) || !MINECRAFT_KEY.matcher(potion).matches()) {
            errors.add(at + " : « potion » = type Minecraft (strength, long_swiftness…) sur " + POTION_ITEMS + ".");
            return null;
        }
        return potion;
    }

    private static Map<String, RewardTable> readTables(ConfigurationSection root, Map<String, RewardDefinition> rewards,
                                                       List<String> errors) {
        Map<String, RewardTable> tables = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("tables");
        if (root.contains("tables") && section == null) {
            errors.add("« tables » doit être une liste de tables par identifiant.");
            return tables;
        }
        if (section == null) {
            return tables;
        }
        if (section.getKeys(false).size() > MAX_TABLES) {
            errors.add("« tables » : " + MAX_TABLES + " tables au plus.");
            return tables;
        }
        for (String id : section.getKeys(false)) {
            RewardTable table = readTable(id, section.getConfigurationSection(id), rewards, errors);
            if (table != null) {
                tables.put(id, table);
            }
        }
        return tables;
    }

    private static RewardTable readTable(String id, ConfigurationSection entry, Map<String, RewardDefinition> rewards,
                                         List<String> errors) {
        String where = "tables « " + id + " »";
        if (!TABLE_ID.matcher(id).matches()) {
            errors.add(where + " : identifiant en minuscules, chiffres, _ - (32 caractères au plus).");
            return null;
        }
        if (entry == null) {
            errors.add(where + " : doit contenir « label », « mode » et « entries ».");
            return null;
        }
        String label = entry.getString("label");
        if (label == null || label.isBlank() || label.length() > LABEL_MAX) {
            errors.add(where + " : « label » obligatoire (" + LABEL_MAX + " caractères au plus).");
            return null;
        }
        RewardTable.Mode mode = RewardTable.Mode.fromYaml(entry.getString("mode", ""));
        if (mode == null) {
            // Jamais de mode par défaut : « indépendant » et « une seule » ne donnent pas les mêmes gains.
            errors.add(where + " : « mode » obligatoire, « independent » (chances indépendantes) ou « single » "
                    + "(une récompense maximum).");
            return null;
        }
        List<Map<?, ?>> raw = entry.getMapList("entries");
        if (!entry.isList("entries") || raw.isEmpty() || raw.size() > MAX_TABLE_ENTRIES) {
            errors.add(where + " : « entries » = liste de 1 à " + MAX_TABLE_ENTRIES + " entrées.");
            return null;
        }
        int errorsBefore = errors.size();
        List<RewardTable.Entry> entries = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            RewardTable.Entry line = readTableEntry(where + ".entries[" + i + "]", raw.get(i), rewards, errors);
            if (line != null && !ids.add(line.id())) {
                errors.add(where + " : identifiant d'entrée « " + line.id() + " » en double.");
            } else if (line != null) {
                entries.add(line);
            }
        }
        if (errors.size() > errorsBefore) {
            return null;
        }
        RewardTable table = new RewardTable(id, label.trim(), mode, entry.getBoolean("enabled", true), entries);
        if (mode == RewardTable.Mode.SINGLE && table.totalChance() > RewardTable.FULL) {
            // Jamais de normalisation silencieuse en poids relatifs : la somme affichée est la vraie.
            errors.add(where + " : en mode « single », la somme des chances (" + RewardTable.percent(table.totalChance())
                    + ") ne peut pas dépasser 100 %.");
            return null;
        }
        return table;
    }

    private static RewardTable.Entry readTableEntry(String at, Map<?, ?> line, Map<String, RewardDefinition> rewards,
                                                    List<String> errors) {
        String entryId = line.get("id") == null ? null : String.valueOf(line.get("id")).trim();
        if (entryId == null || !ENTRY_ID.matcher(entryId).matches()) {
            errors.add(at + " : « id » obligatoire, stable (minuscules, chiffres, _ -, 24 caractères au plus).");
            return null;
        }
        String reward = line.get("reward") == null ? null : String.valueOf(line.get("reward")).trim();
        if (reward == null || !rewards.containsKey(reward)) {
            errors.add(at + " : « reward » doit être une clé de « rewards » de ce fichier.");
            return null;
        }
        Integer chance = chance(line.get("chance"));
        if (chance == null) {
            errors.add(at + " : « chance » = pourcentage strictement positif, ≤ 100, deux décimales au plus (12.5).");
            return null;
        }
        return new RewardTable.Entry(entryId, reward, chance);
    }

    /** Pourcentage → centièmes de pour cent, sans arrondi : 12.345 est refusé plutôt qu'arrondi. */
    static Integer chance(Object raw) {
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            return null;
        }
        try {
            java.math.BigDecimal percent = new java.math.BigDecimal(String.valueOf(raw).trim().replace(',', '.'));
            java.math.BigDecimal hundredths = percent.movePointRight(2);
            if (hundredths.stripTrailingZeros().scale() > 0) {
                return null;
            }
            int value = hundredths.intValueExact();
            return value >= 1 && value <= RewardTable.FULL ? value : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
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
