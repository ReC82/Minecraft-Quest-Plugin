package com.lodygames.rpgquest.panel.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Formulaires « Marchands » de PlugAdmin (issue #17) → paramètres des actions {@code merchant.*}.
 * Un catalogue arrive avec son nom ({@code title}), sa nature ({@code catalog_type} : vente, rachat,
 * mixte ou atelier d'artisan #18) et des lignes d'offre {@code of_*_<i>}, normalisées en {@code offers} (une offre par
 * ligne, {@code sens|objet|quantité|prix|prix-ref|quête|état|niveau|permission}). Le plugin
 * revalide tout : objets, prix du catalogue central, cohérence avec la nature du catalogue.
 */
public final class MerchantForm {

    /** Clé d'un nouveau catalogue (→ {@code rpgquest:<clé>}, fichier {@code <clé>.yml}). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9_-]{1,64}");
    /** Id d'un marchand existant : {@code ns:clé} ou clé seule. */
    static final Pattern ID = Pattern.compile("([a-z0-9._-]{1,64}:)?[a-z0-9/._-]{1,64}");
    static final Set<String> TYPES = Set.of("SELL", "BUY", "MIXED", "CRAFT");
    static final Set<String> DIRECTIONS = Set.of("SELL_TO_PLAYER", "BUY_FROM_PLAYER");
    static final Set<String> QUEST_STATES = Set.of("NOT_STARTED", "ACTIVE", "READY_TO_TURN_IN", "COMPLETED",
            "FAILED", "ABANDONED");
    static final Pattern QUEST = ID;
    static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9._*-]{1,100}");
    static final Pattern PRICE_KEY = PriceForm.KEY;
    /** Offres d'une vitrine de six lignes (la dernière est réservée). */
    public static final int MAX_OFFERS = 45;
    static final int TITLE_MAX = 120;
    static final long MAX_COINS = PriceForm.MAX_COINS;

    private MerchantForm() {
    }

    /** {@code merchant.save} ; renvoie le message d'erreur à afficher, ou {@code null}. */
    static String fill(Map<String, String> form, Map<String, String> params) {
        boolean create = "true".equals(trim(form.get("create")));
        String title = trim(form.get("title"));
        if (title.isEmpty() || title.length() > TITLE_MAX || title.chars().anyMatch(Character::isISOControl)) {
            return "Nom du marchand obligatoire, sur une ligne, " + TITLE_MAX + " caractères au plus.";
        }
        String id = trim(form.get("merchant_id")).toLowerCase(Locale.ROOT);
        if (create && id.isEmpty()) {
            id = RewardTableForm.slug(title); // identifiant facultatif à la création : tiré du nom
        }
        if (create ? !KEY.matcher(id).matches() || RewardTableForm.RESERVED_IDS.contains(id)
                : !ID.matcher(id).matches()) {
            return "Identifiant du marchand : minuscules, chiffres, _ ou - (64 caractères au plus), pas oui/non — "
                    + "ex. boutique_village.";
        }
        String type = trim(form.get("catalog_type")).toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            return "Choisir ce que fait ce marchand : vendre, racheter, les deux, ou fabriquer (atelier).";
        }
        String expected = trim(form.get("expected_version"));
        if (!expected.isEmpty() && !RewardTableForm.VERSION.matcher(expected).matches()) {
            return "Version du formulaire invalide : recharger la page.";
        }
        List<String> offers = new ArrayList<>();
        for (int i = 0; i < MAX_OFFERS; i++) {
            String error = offerLine(form, i, type, offers);
            if (error != null) {
                return error;
            }
        }
        if (offers.isEmpty()) {
            return "Au moins une offre est obligatoire : un marchand sans rien à échanger ne sert à rien (le fermer "
                    + "ou le supprimer plutôt).";
        }
        params.put("merchant_id", id);
        params.put("title", title);
        params.put("catalog_type", type);
        params.put("offers", String.join("\n", offers));
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        params.put("create", create ? "true" : "false");
        if (!expected.isEmpty()) {
            params.put("expected_version", expected);
        }
        return null;
    }

    /** Une ligne d'offre ; vide (ni objet ni prix du catalogue) ou cochée « retirer » = ignorée. */
    private static String offerLine(Map<String, String> form, int i, String type, List<String> out) {
        if ("true".equals(trim(form.get("of_del_" + i)))) {
            return null;
        }
        String rawItem = trim(form.get("of_item_" + i));
        String ref = trim(form.get("of_ref_" + i)).toLowerCase(Locale.ROOT);
        if (rawItem.isEmpty() && ref.isEmpty()) {
            return null;
        }
        String n = "Offre " + (i + 1) + " : ";
        String quest = trim(form.get("of_quest_" + i));
        String state = trim(form.get("of_state_" + i)).toUpperCase(Locale.ROOT);
        String level = trim(form.get("of_level_" + i));
        String permission = trim(form.get("of_perm_" + i));
        if (!quest.isEmpty() && !QUEST.matcher(quest.toLowerCase(Locale.ROOT)).matches()) {
            return n + "quête requise « " + quest + " » invalide.";
        }
        quest = quest.toLowerCase(Locale.ROOT);
        if (quest.isEmpty()) {
            state = "";
        } else if (state.isEmpty()) {
            state = "COMPLETED";
        } else if (!QUEST_STATES.contains(state)) {
            return n + "état de quête invalide.";
        }
        if (!level.isEmpty()) {
            Integer lv = intOrNull(level);
            if (lv == null || lv < 0 || lv > 10_000) {
                return n + "niveau RPG requis : nombre entier entre 0 et 10000 (vide = aucun).";
            }
        }
        if (!permission.isEmpty() && !PERMISSION.matcher(permission).matches()) {
            return n + "permission « " + permission + " » invalide (lettres, chiffres, . _ - *).";
        }
        String conditions = quest + "|" + state + "|" + level + "|" + permission;
        if (!ref.isEmpty()) {
            if (!rawItem.isEmpty()) {
                return n + "choisir soit un prix du catalogue, soit un objet avec son prix — pas les deux.";
            }
            if (!PRICE_KEY.matcher(ref).matches()) {
                return n + "prix du catalogue « " + ref + " » invalide : le choisir dans la liste.";
            }
            out.add("||||" + ref + "|" + conditions);
            return null;
        }
        if ("CRAFT".equals(type)) {
            return n + "un atelier d'artisan ne propose que des recettes : choisir une fabrication de la page Prix et "
                    + "coûts dans « Prix du catalogue » (et vider l'objet et le prix).";
        }
        String item = PriceForm.normalizeItem(rawItem);
        if (item == null) {
            return n + "« " + rawItem + " » n'est pas un objet Minecraft (BREAD) ni un objet RPGQuest (rpgquest:…). "
                    + "Choisir dans la liste.";
        }
        String direction = switch (type) {
            case "SELL" -> "SELL_TO_PLAYER";
            case "BUY" -> "BUY_FROM_PLAYER";
            default -> trim(form.get("of_dir_" + i)).toUpperCase(Locale.ROOT);
        };
        if (!DIRECTIONS.contains(direction)) {
            return n + "dans un marchand « vente et rachat », choisir si le PNJ vend ou rachète cet objet.";
        }
        Integer quantity = intOrNull(trim(form.get("of_qty_" + i)));
        if (quantity == null || quantity < 1 || quantity > 64) {
            return n + "quantité entre 1 et 64.";
        }
        String priceRaw = trim(form.get("of_price_" + i)).replace(" ", "").replace(" ", "");
        if (priceRaw.isEmpty()) {
            return n + "prix en pièces obligatoire (0 seulement si c'est vraiment gratuit) : aucun prix n'est jamais "
                    + "mis par défaut.";
        }
        Long price = longOrNull(priceRaw);
        if (price == null || price < 0 || price > MAX_COINS) {
            return n + "prix : nombre entier de pièces entre 0 et " + MAX_COINS + ".";
        }
        out.add(direction + "|" + item + "|" + quantity + "|" + price + "||" + conditions);
        return null;
    }

    /** {@code merchant.duplicate}. */
    static String fillDuplicate(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("merchant_id")).toLowerCase(Locale.ROOT);
        String target = trim(form.get("new_key")).toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            return "Marchand à copier invalide : recharger la page.";
        }
        if (!KEY.matcher(target).matches() || RewardTableForm.RESERVED_IDS.contains(target)) {
            return "Identifiant de la copie : minuscules, chiffres, _ ou - (64 caractères au plus).";
        }
        String title = trim(form.get("new_title"));
        if (title.length() > TITLE_MAX || title.chars().anyMatch(Character::isISOControl)) {
            return "Nom de la copie : une ligne, " + TITLE_MAX + " caractères au plus.";
        }
        params.put("merchant_id", id);
        params.put("new_key", target);
        if (!title.isEmpty()) {
            params.put("new_title", title);
        }
        return null;
    }

    /** {@code merchant.toggle}. */
    static String fillToggle(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("merchant_id")).toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            return "Marchand invalide : recharger la page.";
        }
        params.put("merchant_id", id);
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        return null;
    }

    /** {@code merchant.delete} : la version lue protège d'une suppression d'un fichier modifié entre-temps. */
    static String fillDelete(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("merchant_id")).toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            return "Marchand invalide : recharger la page.";
        }
        String expected = trim(form.get("expected_version"));
        if (!expected.isEmpty() && !RewardTableForm.VERSION.matcher(expected).matches()) {
            return "Version du formulaire invalide : recharger la page.";
        }
        params.put("merchant_id", id);
        if (!expected.isEmpty()) {
            params.put("expected_version", expected);
        }
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
