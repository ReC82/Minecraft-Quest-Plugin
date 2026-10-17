package com.lodygames.rpgquest.economy.price;

import com.lodygames.rpgquest.economy.price.PriceDefinition.PriceItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Lit et valide {@code prices.yml} (issue #336). Comme {@code entitlements.yml}, un fichier invalide
 * n'est jamais appliqué à moitié : la moindre erreur rend {@link PriceCatalog#EMPTY} — aucune offre
 * qui cite un prix ne s'échange — et la liste des erreurs ; le plugin démarre normalement.
 *
 * <p>Refusé : montant absent ou négatif (aucun prix implicite), quantité hors 1–64, objet inconnu,
 * fabrication sans ingrédient, ingrédients sur une vente, et toute <strong>boucle d'enrichissement</strong>
 * entre prix actifs ({@link PriceArbitrage}). Avertissements (le fichier reste appliqué) : objet
 * donné ou racheté pour 0 pièce, valeurs de démonstration.</p>
 */
public final class PriceCatalogLoader {

    /** Clé d'un prix : sans point (YAML lirait {@code pain.x4} comme deux niveaux). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_-]{1,64}");
    /** Mots que YAML lit comme oui / non : jamais une clé. */
    public static final Set<String> RESERVED_KEYS =
            Set.of("y", "n", "yes", "no", "on", "off", "true", "false", "null");
    public static final Pattern ITEM = Pattern.compile("[A-Z0-9_]{1,64}|[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    public static final int LABEL_MAX = 120;
    public static final int MAX_AMOUNT = 64;
    public static final int MAX_INGREDIENTS = 9;
    /** Garde-fou contre une faute de frappe (un zéro de trop) ; pas une règle économique. */
    public static final long MAX_COINS = 1_000_000_000L;
    public static final int MAX_PRICES = 500;

    private PriceCatalogLoader() {
    }

    public record Result(PriceCatalog catalog, List<String> errors, List<String> warnings) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result load(ConfigurationSection root) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, PriceDefinition> prices = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("prices");
        if (root.contains("prices") && section == null && !isEmptyMap(root.get("prices"))) {
            errors.add("« prices » doit être une liste de prix par clé.");
        }
        if (section != null) {
            if (section.getKeys(false).size() > MAX_PRICES) {
                errors.add("Trop de prix (" + section.getKeys(false).size() + ", " + MAX_PRICES + " au plus).");
            }
            for (String key : section.getKeys(false)) {
                PriceDefinition price = readPrice(key, section.getConfigurationSection(key), errors, warnings);
                if (price != null) {
                    prices.put(key, price);
                }
            }
        }
        if (errors.isEmpty()) {
            errors.addAll(PriceArbitrage.loops(prices.values()));
        }
        if (!errors.isEmpty()) {
            return new Result(PriceCatalog.EMPTY, List.copyOf(errors), List.copyOf(warnings));
        }
        long demo = prices.values().stream().filter(PriceDefinition::demo).count();
        if (demo > 0) {
            warnings.add(demo + " prix marqué(s) « démonstration » : valeurs d'essai à revoir avant d'ouvrir "
                    + "l'économie aux joueurs.");
        }
        return new Result(new PriceCatalog(prices), List.of(), List.copyOf(warnings));
    }

    private static boolean isEmptyMap(Object value) {
        return value instanceof Map<?, ?> map && map.isEmpty();
    }

    private static PriceDefinition readPrice(String key, ConfigurationSection entry, List<String> errors,
                                             List<String> warnings) {
        String where = "prix « " + key + " »";
        if (!KEY.matcher(key).matches() || RESERVED_KEYS.contains(key)) {
            errors.add(where + " : clé en minuscules, chiffres, _ ou - (64 caractères au plus), pas oui/non.");
            return null;
        }
        if (entry == null) {
            errors.add(where + " : doit contenir « label », « type », « item », « amount » et « coins ».");
            return null;
        }
        int before = errors.size();
        String label = entry.getString("label", "").trim();
        if (label.isEmpty() || label.length() > LABEL_MAX || label.chars().anyMatch(Character::isISOControl)) {
            errors.add(where + " : « label » obligatoire, sur une ligne, " + LABEL_MAX + " caractères au plus.");
        }
        PriceType type = readType(entry.getString("type"), where, errors);
        PriceItem item = readItem(entry.get("item"), entry.get("amount"), where + ", objet", errors);
        Long coins = readCoins(entry, where, errors);
        List<PriceItem> ingredients = readIngredients(entry, type, item, where, errors);
        boolean demo = readBoolean(entry, "demo", false, where, errors);
        boolean enabled = readBoolean(entry, "enabled", true, where, errors);
        if (errors.size() > before || type == null || item == null || coins == null) {
            return null;
        }
        if (coins == 0 && type == PriceType.SELL_TO_PLAYER) {
            warnings.add(where + " : objet donné gratuitement (0 pièce).");
        } else if (coins == 0 && type == PriceType.BUY_FROM_PLAYER) {
            warnings.add(where + " : objet repris sans rien payer (0 pièce).");
        }
        return new PriceDefinition(key, label, type, item, coins, ingredients, demo, enabled);
    }

    private static PriceType readType(String raw, String where, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.add(where + " : « type » obligatoire (SELL_TO_PLAYER, BUY_FROM_PLAYER ou CRAFT).");
            return null;
        }
        try {
            return PriceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(where + " : type « " + raw + " » inconnu (SELL_TO_PLAYER, BUY_FROM_PLAYER ou CRAFT).");
            return null;
        }
    }

    private static Long readCoins(ConfigurationSection entry, String where, List<String> errors) {
        Object raw = entry.get("coins");
        if (raw == null) {
            errors.add(where + " : « coins » obligatoire — aucun prix n'est jamais déduit ni mis par défaut.");
            return null;
        }
        if (!(raw instanceof Integer || raw instanceof Long)) {
            errors.add(where + " : « coins » doit être un nombre entier de pièces, valeur trouvée : " + raw + ".");
            return null;
        }
        long coins = ((Number) raw).longValue();
        if (coins < 0 || coins > MAX_COINS) {
            errors.add(where + " : « coins » entre 0 et " + MAX_COINS + ", valeur trouvée : " + coins + ".");
            return null;
        }
        return coins;
    }

    private static List<PriceItem> readIngredients(ConfigurationSection entry, PriceType type, PriceItem result,
                                                   String where, List<String> errors) {
        boolean present = entry.contains("ingredients");
        if (type != PriceType.CRAFT) {
            if (present && !entry.getList("ingredients", List.of()).isEmpty()) {
                errors.add(where + " : « ingredients » n'a de sens que pour une fabrication (CRAFT).");
            }
            return List.of();
        }
        List<?> raw = entry.getList("ingredients");
        if (raw == null || raw.isEmpty()) {
            errors.add(where + " : une fabrication demande au moins un ingrédient (sinon, c'est une vente).");
            return List.of();
        }
        if (raw.size() > MAX_INGREDIENTS) {
            errors.add(where + " : " + MAX_INGREDIENTS + " ingrédients au plus.");
            return List.of();
        }
        List<PriceItem> ingredients = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String at = where + ", ingrédient " + (i + 1);
            if (!(raw.get(i) instanceof Map<?, ?> line)) {
                errors.add(at + " : attendu « item » et « amount ».");
                continue;
            }
            PriceItem ingredient = readItem(line.get("item"), line.get("amount"), at, errors);
            if (ingredient == null) {
                continue;
            }
            if (!seen.add(ingredient.itemId())) {
                errors.add(at + " : « " + ingredient.itemId() + " » apparaît deux fois — réunir les quantités.");
            } else if (result != null && ingredient.itemId().equals(result.itemId())) {
                errors.add(at + " : l'objet fabriqué ne peut pas être son propre ingrédient.");
            } else {
                ingredients.add(ingredient);
            }
        }
        return ingredients;
    }

    private static PriceItem readItem(Object rawId, Object rawAmount, String where, List<String> errors) {
        String id = rawId == null ? "" : rawId.toString().trim();
        if (!ITEM.matcher(id).matches()) {
            errors.add(where + " : objet « " + id + " » invalide — matériau Minecraft (BREAD) ou objet RPGQuest "
                    + "(rpgquest:…).");
            return null;
        }
        if (id.indexOf(':') < 0) {
            Material material = Material.matchMaterial(id);
            if (material == null || material.isAir() || material.isLegacy()) {
                errors.add(where + " : « " + id + " » n'est pas un objet Minecraft.");
                return null;
            }
        }
        if (!(rawAmount instanceof Integer amount) || amount < 1 || amount > MAX_AMOUNT) {
            errors.add(where + " : quantité entière entre 1 et " + MAX_AMOUNT + " obligatoire, valeur trouvée : "
                    + rawAmount + ".");
            return null;
        }
        return new PriceItem(id, amount);
    }

    private static boolean readBoolean(ConfigurationSection entry, String key, boolean fallback, String where,
                                       List<String> errors) {
        Object raw = entry.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Boolean value)) {
            errors.add(where + " : « " + key + " » doit valoir true ou false.");
            return fallback;
        }
        return value;
    }
}
