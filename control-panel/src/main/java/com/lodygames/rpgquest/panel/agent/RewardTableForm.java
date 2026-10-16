package com.lodygames.rpgquest.panel.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Formulaire « table de récompenses aléatoires » de PlugAdmin (issue #177, tranche 2) → paramètres
 * de {@code reward.table.save}. Le formulaire envoie des lignes {@code entry_reward_<i>} /
 * {@code entry_chance_<i>} / {@code entry_id_<i>} ; elles sont normalisées en
 * {@code entries = id=récompense@chance;…}. Le plugin revalide tout et le vrai chargeur tranche
 * (récompense existante, somme ≤ 100 % en « une récompense maximum »).
 */
public final class RewardTableForm {

    public static final Pattern TABLE_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    static final Pattern ENTRY_ID = Pattern.compile("[a-z0-9_-]{1,24}");
    static final Pattern REWARD_KEY = Pattern.compile("[a-z0-9_.-]{1,64}");
    static final Pattern VERSION = Pattern.compile("[0-9a-f]{8}");
    /** Pourcentage saisi : 12, 12.5, 12,25 — jamais plus de deux décimales (aucun arrondi caché). */
    static final Pattern CHANCE = Pattern.compile("\\d{1,3}(?:[.,]\\d{1,2})?");
    /** Mots que YAML lit comme oui/non : refusés comme identifiant (comme le plugin). */
    public static final Set<String> RESERVED_IDS = Set.of("y", "n", "yes", "no", "on", "off", "true", "false", "null");
    public static final int MAX_ENTRIES = 20;
    static final int LABEL_MAX = 120;

    private RewardTableForm() {
    }

    /** Remplit {@code params} ; renvoie le message d'erreur à afficher, ou {@code null}. */
    static String fill(Map<String, String> form, Map<String, String> params) {
        boolean create = "true".equals(trim(form.get("create")));
        String id = trim(form.get("table_id")).toLowerCase(Locale.ROOT);
        if (create && id.isEmpty()) {
            id = slug(trim(form.get("label"))); // identifiant facultatif à la création : tiré du nom
        }
        if (!TABLE_ID.matcher(id).matches()) {
            return "Identifiant de la table : minuscules, chiffres, _ ou - (32 caractères au plus), ex. butin_ogre.";
        }
        if (create && RESERVED_IDS.contains(id)) {
            return "« " + id + " » ne peut pas servir d'identifiant (YAML le lirait comme oui/non) : choisir par "
                    + "exemple « " + id + "_coffre ».";
        }
        String label = trim(form.get("label"));
        if (label.isEmpty() || label.length() > LABEL_MAX || label.chars().anyMatch(Character::isISOControl)) {
            return "Nom de la table obligatoire, sur une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        String mode = trim(form.get("mode"));
        if (!"independent".equals(mode) && !"single".equals(mode)) {
            return "Choisir comment la table est tirée : « Une récompense au plus » ou « Chaque récompense "
                    + "tentée séparément ».";
        }
        String expected = trim(form.get("expected_version"));
        if (!expected.isEmpty() && !VERSION.matcher(expected).matches()) {
            return "Version du formulaire invalide : recharger la page.";
        }
        List<String> entries = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        // Identifiants des lignes existantes d'abord : une nouvelle ligne ne doit jamais prendre celui
        // d'une ligne plus bas (il fait partie de la clé d'idempotence des gains).
        Set<String> taken = new LinkedHashSet<>();
        for (int i = 0; i < MAX_ENTRIES; i++) {
            String existing = trim(form.get("entry_id_" + i)).toLowerCase(Locale.ROOT);
            if (!existing.isEmpty() && !trim(form.get("entry_reward_" + i)).isEmpty()) {
                taken.add(existing);
            }
        }
        int total = 0;
        for (int i = 0; i < MAX_ENTRIES; i++) {
            String reward = trim(form.get("entry_reward_" + i)).toLowerCase(Locale.ROOT);
            String chanceRaw = trim(form.get("entry_chance_" + i));
            if (reward.isEmpty() && chanceRaw.isEmpty()) {
                continue; // ligne vide = rien (ou récompense retirée)
            }
            int line = i + 1;
            if (reward.isEmpty()) {
                return "Ligne " + line + " : choisir une récompense du catalogue (ou vider aussi sa chance pour "
                        + "retirer la ligne).";
            }
            if (!REWARD_KEY.matcher(reward).matches()) {
                return "Ligne " + line + " : « " + reward + " » n'est pas une clé du catalogue (choisir dans la liste).";
            }
            Integer chance = hundredths(chanceRaw);
            if (chance == null) {
                return "Ligne " + line + " (" + reward + ") : chance = un pourcentage entre 0,01 et 100, deux "
                        + "décimales au plus (ex. 12,5).";
            }
            String entryId = trim(form.get("entry_id_" + i)).toLowerCase(Locale.ROOT);
            if (entryId.isEmpty()) {
                Set<String> used = new LinkedHashSet<>(taken);
                used.addAll(ids);
                entryId = entryIdFor(reward, used);
            } else if (!ENTRY_ID.matcher(entryId).matches()) {
                return "Ligne " + line + " : identifiant interne invalide, recharger la page.";
            }
            if (!ids.add(entryId)) {
                return "Ligne " + line + " : identifiant interne « " + entryId + " » en double, recharger la page.";
            }
            total += chance;
            entries.add(entryId + "=" + reward + "@" + BigDecimal.valueOf(chance, 2).stripTrailingZeros().toPlainString());
        }
        if (entries.isEmpty()) {
            return "Ajouter au moins une récompense à la table.";
        }
        if ("single".equals(mode) && total > 10_000) {
            return "« Une récompense au plus » : la somme des chances fait " + percent(total) + ", elle ne peut pas "
                    + "dépasser 100 %. Baisser des chances, ou choisir « Chaque récompense tentée séparément ».";
        }
        params.put("table_id", id);
        params.put("label", label);
        params.put("mode", mode);
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        params.put("entries", String.join(";", entries));
        params.put("create", create ? "true" : "false");
        if (!expected.isEmpty()) {
            params.put("expected_version", expected);
        }
        return null;
    }

    /** {@code reward.table.duplicate} : identifiant source, nouvel identifiant, nom facultatif. */
    static String fillDuplicate(Map<String, String> form, Map<String, String> params) {
        String source = trim(form.get("table_id")).toLowerCase(Locale.ROOT);
        String target = trim(form.get("new_id")).toLowerCase(Locale.ROOT);
        if (!TABLE_ID.matcher(source).matches()) {
            return "Table à copier invalide : recharger la page.";
        }
        if (!TABLE_ID.matcher(target).matches() || RESERVED_IDS.contains(target)) {
            return "Identifiant de la copie : minuscules, chiffres, _ ou - (32 caractères au plus), ex. "
                    + source + "_v2.";
        }
        if (target.equals(source)) {
            return "La copie doit avoir un identifiant différent de l'original.";
        }
        String label = trim(form.get("new_label"));
        if (label.length() > LABEL_MAX || label.chars().anyMatch(Character::isISOControl)) {
            return "Nom de la copie : une ligne, " + LABEL_MAX + " caractères au plus.";
        }
        params.put("table_id", source);
        params.put("new_id", target);
        if (!label.isEmpty()) {
            params.put("new_label", label);
        }
        return null;
    }

    /** {@code reward.table.toggle}. */
    static String fillToggle(Map<String, String> form, Map<String, String> params) {
        String id = trim(form.get("table_id")).toLowerCase(Locale.ROOT);
        if (!TABLE_ID.matcher(id).matches()) {
            return "Table invalide : recharger la page.";
        }
        params.put("table_id", id);
        params.put("enabled", "true".equals(trim(form.get("enabled"))) ? "true" : "false");
        return null;
    }

    /** « 12,5 » → 1250 ; {@code null} si hors bornes ou plus de deux décimales. */
    static Integer hundredths(String raw) {
        if (raw == null || !CHANCE.matcher(raw.trim()).matches()) {
            return null;
        }
        int value = new BigDecimal(raw.trim().replace(',', '.')).movePointRight(2).intValueExact();
        return value >= 1 && value <= 10_000 ? value : null;
    }

    /** Identifiant stable d'une nouvelle ligne, dérivé de la récompense (« gem.rare » → « gem_rare »). */
    static String entryIdFor(String rewardKey, Set<String> taken) {
        String base = rewardKey.replaceAll("[^a-z0-9_-]", "_");
        base = base.length() > 20 ? base.substring(0, 20) : base;
        String candidate = base;
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = base + "_" + n;
        }
        return candidate;
    }

    /** « Butin de l'Ogre ! » → « butin_de_l_ogre » (32 caractères au plus). */
    public static String slug(String label) {
        String plain = java.text.Normalizer.normalize(label == null ? "" : label, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return plain.length() > 32 ? plain.substring(0, 32).replaceAll("_+$", "") : plain;
    }

    static String percent(int hundredths) {
        return percentText(hundredths);
    }

    /** 1250 → « 12,5 % ». */
    public static String percentText(int hundredths) {
        return BigDecimal.valueOf(hundredths, 2).stripTrailingZeros().toPlainString().replace('.', ',') + " %";
    }

    /** Valeur affichée (« 12,5 ») → centièmes, {@code null} si vide ou invalide. */
    public static Integer hundredthsOf(String raw) {
        return hundredths(raw);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
