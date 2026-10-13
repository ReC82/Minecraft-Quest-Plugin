package com.lodygames.rpgquest.web.agent;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issue #319 — types des variables que le jeu lit réellement, pour que l'outil de débogage
 * « Écrire une variable » ne puisse plus poser une valeur que le jeu interpréterait de travers
 * ({@code CLAIM_TIER_1 = lasagne}).
 *
 * <ul>
 *   <li>{@code CLAIM_TIER_<n>} et les préférences « ne plus afficher » : {@code true} ou
 *       {@code false} (le jeu ne reconnaît que la chaîne exacte {@code true}).</li>
 *   <li>{@code STARTER_KIT_TIER} : entier de 1 à 99 (palier du kit de départ).</li>
 *   <li>Toute autre variable garde son type libre.</li>
 * </ul>
 *
 * <p>Le passage de {@code CLAIM_TIER_<n>} à un entier unique {@code CLAIM_TIER} est préparé dans
 * {@code docs/audits/claim-tier-entier.md} ; il n'est pas appliqué.</p>
 */
public final class VariableTypes {

    private static final Pattern BOOLEAN_KEYS = Pattern.compile(
            "CLAIM_TIER_\\d+|WILD_ENTRY_WARNING_HIDDEN|BETA_WARNING_HIDDEN|RUNE_RAPPEL_GRANTED");
    private static final Pattern INTEGER = Pattern.compile("[1-9]\\d?");

    private VariableTypes() {
    }

    /** Le type attendu, en mots : « true ou false », « entier de 1 à 99 », ou vide (libre). */
    public static String expected(String key) {
        if (key == null) {
            return "";
        }
        if (BOOLEAN_KEYS.matcher(key).matches()) {
            return "true ou false";
        }
        if ("STARTER_KIT_TIER".equals(key)) {
            return "un nombre entier de 1 à 99";
        }
        return "";
    }

    /** Le motif du refus, ou vide si la valeur convient au type de la clé. */
    public static Optional<String> problem(String key, String value) {
        String v = value == null ? "" : value.trim();
        if (key != null && BOOLEAN_KEYS.matcher(key).matches() && !"true".equals(v) && !"false".equals(v)) {
            return Optional.of("« " + v + " » n'est pas valide pour " + key + " : true ou false.");
        }
        if ("STARTER_KIT_TIER".equals(key) && !INTEGER.matcher(v).matches()) {
            return Optional.of("« " + v + " » n'est pas valide pour STARTER_KIT_TIER : un nombre entier de 1 à 99.");
        }
        return Optional.empty();
    }
}
