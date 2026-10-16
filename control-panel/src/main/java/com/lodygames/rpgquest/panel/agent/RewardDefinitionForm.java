package com.lodygames.rpgquest.panel.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formulaires « catalogue des récompenses » de PlugAdmin (issue #474) → paramètres des actions
 * {@code reward.catalog.*} et {@code reward.definition.*}. Une récompense arrive en lignes
 * {@code item_id_<i>} / {@code item_amount_<i>} / {@code item_ench_<i>} / {@code item_potion_<i>},
 * normalisées en {@code items = ITEM@quantité[+ench=clé:niveau,…][+potion=type];…}. Le plugin
 * revalide tout, vérifie les références (objet custom, enchantement, potion) et le vrai chargeur
 * tranche avant toute écriture.
 */
public final class RewardDefinitionForm {

    /** Clé d'une nouvelle récompense : sans point (YAML le lirait comme deux niveaux). */
    public static final Pattern NEW_KEY = Pattern.compile("[a-z0-9_-]{1,64}");
    static final Pattern KEY = Pattern.compile("[a-z0-9_.-]{1,64}");
    static final Pattern ITEM = Pattern.compile("[A-Z0-9_]{1,64}|[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    static final Pattern MC_KEY = Pattern.compile("[a-z0-9_]{1,64}");
    static final Pattern PERK_PART = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    /** « sharpness 3 », « sharpness:3 », « minecraft:sharpness=3 ». */
    private static final Pattern ENCHANT = Pattern.compile("(?:minecraft:)?([a-z0-9_]{1,64})\\s*[ :=]\\s*(\\d{1,2})");
    public static final int MAX_ITEMS = 9;
    static final int LABEL_MAX = 120;

    private RewardDefinitionForm() {
    }

    /** {@code reward.definition.save} ; renvoie le message d'erreur à afficher, ou {@code null}. */
    static String fill(Map<String, String> form, Map<String, String> params) {
        boolean create = "true".equals(trim(form.get("create")));
        String label = trim(form.get("label"));
        if (label.isEmpty() || label.length() > LABEL_MAX || label.chars().anyMatch(Character::isISOControl)) {
            return "Nom de la récompense obligatoire, sur une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        String key = trim(form.get("reward_key")).toLowerCase(Locale.ROOT);
        if (create && key.isEmpty()) {
            key = RewardTableForm.slug(label); // identifiant facultatif à la création : tiré du nom
        }
        if (!(create ? NEW_KEY : KEY).matcher(key).matches()) {
            return "Identifiant de la récompense : minuscules, chiffres, _ ou - (64 caractères au plus), ex. "
                    + "epee_du_heros.";
        }
        if (create && RewardTableForm.RESERVED_IDS.contains(key)) {
            return "« " + key + " » ne peut pas servir d'identifiant (YAML le lirait comme oui/non) : choisir par "
                    + "exemple « " + key + "_recompense ».";
        }
        String expected = trim(form.get("expected_version"));
        if (!expected.isEmpty() && !RewardTableForm.VERSION.matcher(expected).matches()) {
            return "Version du formulaire invalide : recharger la page.";
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < MAX_ITEMS; i++) {
            String error = itemLine(form, i, items);
            if (error != null) {
                return error;
            }
        }
        String perkKey = trim(form.get("perk_key"));
        String perkTier = trim(form.get("perk_tier"));
        if (perkKey.isEmpty() != perkTier.isEmpty()) {
            return "Avantage : remplir la clé ET le palier, ou laisser les deux vides.";
        }
        if (!perkKey.isEmpty() && (!PERK_PART.matcher(perkKey).matches() || !PERK_PART.matcher(perkTier).matches())) {
            return "Avantage : clé et palier en lettres, chiffres, _ . - (64 caractères au plus).";
        }
        if (items.isEmpty() && perkKey.isEmpty()) {
            return "Ajouter au moins un objet à la récompense.";
        }
        params.put("reward_key", key);
        params.put("label", label);
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        params.put("create", create ? "true" : "false");
        if (!items.isEmpty()) {
            params.put("items", String.join(";", items));
        }
        if (!perkKey.isEmpty()) {
            params.put("perk_key", perkKey);
            params.put("perk_tier", perkTier);
        }
        if (!expected.isEmpty()) {
            params.put("expected_version", expected);
        }
        return null;
    }

    /** Une ligne d'objet ; vide = ignorée. */
    private static String itemLine(Map<String, String> form, int i, List<String> out) {
        String raw = trim(form.get("item_id_" + i));
        String amountRaw = trim(form.get("item_amount_" + i));
        String enchRaw = trim(form.get("item_ench_" + i)).toLowerCase(Locale.ROOT);
        String potion = trim(form.get("item_potion_" + i)).toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        if (raw.isEmpty()) {
            return enchRaw.isEmpty() && potion.isEmpty() ? null
                    : "Objet " + (i + 1) + " : choisir l'objet (ou vider aussi ses enchantements et sa potion).";
        }
        String item = raw.indexOf(':') >= 0 ? raw.toLowerCase(Locale.ROOT) : raw.toUpperCase(Locale.ROOT);
        if (item.startsWith("minecraft:")) {
            item = item.substring("minecraft:".length()).toUpperCase(Locale.ROOT);
        }
        if (!ITEM.matcher(item).matches()) {
            return "Objet " + (i + 1) + " : « " + raw + " » n'est pas un objet Minecraft (DIAMOND) ni un objet "
                    + "RPGQuest (rpgquest:…). Choisir dans la liste.";
        }
        int amount;
        try {
            amount = amountRaw.isEmpty() ? 1 : Integer.parseInt(amountRaw);
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount < 1 || amount > 64) {
            return "Objet " + (i + 1) + " (" + item + ") : quantité entre 1 et 64.";
        }
        StringBuilder line = new StringBuilder(item).append('@').append(amount);
        if (!enchRaw.isEmpty()) {
            Map<String, Integer> enchantments = new LinkedHashMap<>();
            for (String part : enchRaw.split("[,;]")) {
                Matcher m = ENCHANT.matcher(part.trim());
                if (!m.matches() || Integer.parseInt(m.group(2)) < 1 || Integer.parseInt(m.group(2)) > 10
                        || enchantments.put(m.group(1), Integer.parseInt(m.group(2))) != null) {
                    return "Objet " + (i + 1) + " (" + item + ") : enchantement « " + part.trim() + " » à écrire "
                            + "« nom niveau », niveau 1 à 10, séparés par des virgules (ex. sharpness 3, unbreaking 2).";
                }
            }
            line.append("+ench=");
            line.append(String.join(",", enchantments.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue()).toList()));
        }
        if (!potion.isEmpty()) {
            if (!MC_KEY.matcher(potion).matches()) {
                return "Objet " + (i + 1) + " (" + item + ") : type de potion inconnu, choisir dans la liste.";
            }
            line.append("+potion=").append(potion);
        }
        out.add(line.toString());
        return null;
    }

    /** {@code reward.definition.duplicate}. */
    static String fillDuplicate(Map<String, String> form, Map<String, String> params) {
        String source = trim(form.get("reward_key")).toLowerCase(Locale.ROOT);
        String target = trim(form.get("new_key")).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(source).matches()) {
            return "Récompense à copier invalide : recharger la page.";
        }
        if (!NEW_KEY.matcher(target).matches() || RewardTableForm.RESERVED_IDS.contains(target)) {
            return "Identifiant de la copie : minuscules, chiffres, _ ou - (64 caractères au plus), ex. " + source
                    .replace('.', '_') + "_v2.";
        }
        String label = trim(form.get("new_label"));
        if (label.length() > LABEL_MAX || label.chars().anyMatch(Character::isISOControl)) {
            return "Nom de la copie : une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        params.put("reward_key", source);
        params.put("new_key", target);
        if (!label.isEmpty()) {
            params.put("new_label", label);
        }
        return null;
    }

    /** {@code reward.definition.toggle}. */
    static String fillToggle(Map<String, String> form, Map<String, String> params) {
        String key = trim(form.get("reward_key")).toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) {
            return "Récompense invalide : recharger la page.";
        }
        params.put("reward_key", key);
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        return null;
    }

    /** {@code reward.catalog.init} : confirmation explicite obligatoire. */
    static String fillInit(Map<String, String> form, Map<String, String> params) {
        if (!"yes".equals(trim(form.get("confirm")))) {
            return "Cocher la confirmation pour créer le catalogue (il sera créé désactivé et vide).";
        }
        return null;
    }

    /** {@code reward.catalog.toggle} : l'interrupteur général change ce que vivent tous les joueurs. */
    static String fillCatalogToggle(Map<String, String> form, Map<String, String> params) {
        if (!"yes".equals(trim(form.get("confirm")))) {
            return "Cocher la confirmation : activer ou désactiver les récompenses concerne toutes les quêtes et "
                    + "tous les boss.";
        }
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
