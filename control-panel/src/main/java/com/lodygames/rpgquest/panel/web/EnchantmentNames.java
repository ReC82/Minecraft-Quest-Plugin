package com.lodygames.rpgquest.panel.web;

import java.util.Locale;
import java.util.Map;

/**
 * Noms français des enchantements Minecraft, pour l'éditeur de récompenses (issue #474, retour du
 * 18/10 : « ne pas imposer de connaître les identifiants anglais »).
 *
 * <p>Même contrainte que {@link MaterialNames} : le Control Panel n'a aucune dépendance Paper et ne
 * lit pas les fichiers de langue du jeu. Les enchantements vanilla sont une quarantaine : la table
 * nominative ci-dessous reprend les libellés du jeu en français. Un enchantement absent (datapack,
 * version plus récente) tombe sur l'identifiant embelli ({@code lunge} → « Lunge ») plutôt que sur
 * une traduction inventée ; la recherche filtre aussi sur l'identifiant, donc rien n'est
 * introuvable.</p>
 */
public final class EnchantmentNames {

    private EnchantmentNames() {
    }

    private static final Map<String, String> FRENCH = Map.ofEntries(
            Map.entry("aqua_affinity", "Affinité aquatique"),
            Map.entry("bane_of_arthropods", "Fléau des arthropodes"),
            Map.entry("binding_curse", "Malédiction du lien éternel"),
            Map.entry("blast_protection", "Protection contre les explosions"),
            Map.entry("breach", "Brèche"),
            Map.entry("channeling", "Canalisation"),
            Map.entry("density", "Densité"),
            Map.entry("depth_strider", "Agilité aquatique"),
            Map.entry("efficiency", "Efficacité"),
            Map.entry("feather_falling", "Chute amortie"),
            Map.entry("fire_aspect", "Aura de feu"),
            Map.entry("fire_protection", "Protection contre le feu"),
            Map.entry("flame", "Flamme"),
            Map.entry("fortune", "Fortune"),
            Map.entry("frost_walker", "Semelles givrantes"),
            Map.entry("impaling", "Empalement"),
            Map.entry("infinity", "Infinité"),
            Map.entry("knockback", "Recul"),
            Map.entry("looting", "Butin"),
            Map.entry("loyalty", "Loyauté"),
            Map.entry("luck_of_the_sea", "Chance de la mer"),
            Map.entry("lure", "Appât"),
            Map.entry("mending", "Raccommodage"),
            Map.entry("multishot", "Tir multiple"),
            Map.entry("piercing", "Perforation"),
            Map.entry("power", "Puissance"),
            Map.entry("projectile_protection", "Protection contre les projectiles"),
            Map.entry("protection", "Protection"),
            Map.entry("punch", "Frappe"),
            Map.entry("quick_charge", "Charge rapide"),
            Map.entry("respiration", "Apnée"),
            Map.entry("riptide", "Impulsion"),
            Map.entry("sharpness", "Tranchant"),
            Map.entry("silk_touch", "Toucher de soie"),
            Map.entry("smite", "Châtiment"),
            Map.entry("soul_speed", "Agilité des âmes"),
            Map.entry("sweeping_edge", "Affilage"),
            Map.entry("swift_sneak", "Furtivité rapide"),
            Map.entry("thorns", "Épines"),
            Map.entry("unbreaking", "Solidité"),
            Map.entry("vanishing_curse", "Malédiction de disparition"),
            Map.entry("wind_burst", "Rafale de vent"));

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /** « sharpness » → « Tranchant » ; inconnu → identifiant embelli. */
    public static String french(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        String known = FRENCH.get(k);
        if (known != null) {
            return known;
        }
        StringBuilder sb = new StringBuilder();
        for (String word : k.split("_")) {
            if (!word.isEmpty()) {
                sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1));
            }
        }
        return sb.toString();
    }

    /** Niveau en chiffres romains comme dans le jeu (1 à 10), en chiffres au-delà. */
    public static String level(int level) {
        return level >= 1 && level < ROMAN.length ? ROMAN[level] : Integer.toString(level);
    }

    /** « sharpness », 3 → « Tranchant III ». */
    public static String label(String key, int level) {
        return french(key) + " " + level(level);
    }
}
