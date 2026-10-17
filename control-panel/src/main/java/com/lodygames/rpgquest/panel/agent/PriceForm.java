package com.lodygames.rpgquest.panel.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Formulaires « Prix et coûts » de PlugAdmin (issue #336) → paramètres des actions {@code price.*}.
 * Un prix arrive avec son objet ({@code item}, {@code amount}), son montant ({@code coins},
 * obligatoire : jamais de prix par défaut) et, pour une fabrication, des lignes
 * {@code ing_id_<i>} / {@code ing_amount_<i>} normalisées en {@code ingredients = ITEM@n;…}. Le
 * plugin revalide tout, vérifie les objets RPGQuest et le vrai chargeur (boucles
 * d'enrichissement comprises) tranche avant toute écriture.
 */
public final class PriceForm {

    /** Clé d'un prix : sans point (YAML le lirait comme deux niveaux). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_-]{1,64}");
    static final Pattern ITEM = RewardDefinitionForm.ITEM;
    static final Set<String> TYPES = Set.of("SELL_TO_PLAYER", "BUY_FROM_PLAYER", "CRAFT");
    public static final int MAX_INGREDIENTS = 9;
    static final int LABEL_MAX = 120;
    static final long MAX_COINS = 1_000_000_000L;

    private PriceForm() {
    }

    /** {@code price.save} ; renvoie le message d'erreur à afficher, ou {@code null}. */
    static String fill(Map<String, String> form, Map<String, String> params) {
        boolean create = "true".equals(trim(form.get("create")));
        String label = trim(form.get("label"));
        if (label.isEmpty() || label.length() > LABEL_MAX || label.chars().anyMatch(Character::isISOControl)) {
            return "Nom du prix obligatoire, sur une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        String key = trim(form.get("price_key")).toLowerCase(Locale.ROOT);
        if (create && key.isEmpty()) {
            key = RewardTableForm.slug(label); // identifiant facultatif à la création : tiré du nom
        }
        if (!KEY.matcher(key).matches() || (create && RewardTableForm.RESERVED_IDS.contains(key))) {
            return "Identifiant du prix : minuscules, chiffres, _ ou - (64 caractères au plus), pas oui/non — "
                    + "ex. pain_x4.";
        }
        String type = trim(form.get("price_type")).toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            return "Choisir ce que fait le PNJ : vendre, racheter ou fabriquer.";
        }
        String expected = trim(form.get("expected_version"));
        if (!expected.isEmpty() && !RewardTableForm.VERSION.matcher(expected).matches()) {
            return "Version du formulaire invalide : recharger la page.";
        }
        String item = normalizeItem(trim(form.get("item")));
        if (item == null) {
            return "Objet : choisir un objet Minecraft (BREAD) ou RPGQuest (rpgquest:…) dans la liste.";
        }
        Integer amount = intOrNull(trim(form.get("amount")));
        if (amount == null || amount < 1 || amount > 64) {
            return "Quantité de l'objet : nombre entier entre 1 et 64.";
        }
        String coinsRaw = trim(form.get("coins")).replace(" ", "").replace(" ", "");
        if (coinsRaw.isEmpty()) {
            return "Montant en pièces obligatoire (0 si c'est vraiment gratuit) : aucun prix n'est jamais mis par "
                    + "défaut.";
        }
        Long coins = longOrNull(coinsRaw);
        if (coins == null || coins < 0 || coins > MAX_COINS) {
            return "Montant en pièces : nombre entier entre 0 et " + MAX_COINS + ".";
        }
        List<String> ingredients = new ArrayList<>();
        for (int i = 0; i < MAX_INGREDIENTS; i++) {
            String error = ingredientLine(form, i, ingredients);
            if (error != null) {
                return error;
            }
        }
        if ("CRAFT".equals(type) && ingredients.isEmpty() && coins == 0) {
            return "Une fabrication demande des ingrédients, des pièces, ou les deux : ajouter au moins un "
                    + "ingrédient ou un montant en pièces.";
        }
        if (!"CRAFT".equals(type) && !ingredients.isEmpty()) {
            return "Les ingrédients ne servent qu'à une fabrication : vider les lignes d'ingrédients, ou choisir "
                    + "« fabriquer ».";
        }
        params.put("price_key", key);
        params.put("label", label);
        params.put("type", type);
        params.put("item", item);
        params.put("amount", Integer.toString(amount));
        params.put("coins", Long.toString(coins));
        if (!ingredients.isEmpty()) {
            params.put("ingredients", String.join(";", ingredients));
        }
        params.put("demo", "true".equals(trim(form.get("demo"))) ? "true" : "false");
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        params.put("create", create ? "true" : "false");
        if (!expected.isEmpty()) {
            params.put("expected_version", expected);
        }
        return null;
    }

    private static String ingredientLine(Map<String, String> form, int i, List<String> out) {
        String raw = trim(form.get("ing_id_" + i));
        String amountRaw = trim(form.get("ing_amount_" + i));
        if (raw.isEmpty()) {
            return null;
        }
        String item = normalizeItem(raw);
        if (item == null) {
            return "Ingrédient " + (i + 1) + " : « " + raw + " » n'est pas un objet Minecraft (IRON_INGOT) ni un "
                    + "objet RPGQuest (rpgquest:…). Choisir dans la liste.";
        }
        Integer amount = amountRaw.isEmpty() ? Integer.valueOf(1) : intOrNull(amountRaw);
        if (amount == null || amount < 1 || amount > 64) {
            return "Ingrédient " + (i + 1) + " (" + item + ") : quantité entre 1 et 64.";
        }
        out.add(item + "@" + amount);
        return null;
    }

    /** « bread », « minecraft:bread » → BREAD ; « rpgquest:x » inchangé ; {@code null} si invalide. */
    static String normalizeItem(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String item = raw.indexOf(':') >= 0 ? raw.toLowerCase(Locale.ROOT) : raw.toUpperCase(Locale.ROOT);
        if (item.startsWith("minecraft:")) {
            item = item.substring("minecraft:".length()).toUpperCase(Locale.ROOT);
        }
        return ITEM.matcher(item).matches() ? item : null;
    }

    /** {@code price.duplicate}. */
    static String fillDuplicate(Map<String, String> form, Map<String, String> params) {
        String source = trim(form.get("price_key")).toLowerCase(Locale.ROOT);
        String target = trim(form.get("new_key")).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(source).matches()) {
            return "Prix à copier invalide : recharger la page.";
        }
        if (!KEY.matcher(target).matches() || RewardTableForm.RESERVED_IDS.contains(target)) {
            return "Identifiant de la copie : minuscules, chiffres, _ ou - (64 caractères au plus), ex. " + source
                    + "_hiver.";
        }
        String label = trim(form.get("new_label"));
        if (label.length() > LABEL_MAX || label.chars().anyMatch(Character::isISOControl)) {
            return "Nom de la copie : une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        params.put("price_key", source);
        params.put("new_key", target);
        if (!label.isEmpty()) {
            params.put("new_label", label);
        }
        return null;
    }

    /** {@code price.toggle}. */
    static String fillToggle(Map<String, String> form, Map<String, String> params) {
        String key = trim(form.get("price_key")).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) {
            return "Prix invalide : recharger la page.";
        }
        params.put("price_key", key);
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        return null;
    }

    private static Integer intOrNull(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOrNull(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
