package com.lodygames.rpgquest.web.agent;

import java.util.Optional;
import java.util.Set;
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
 *   <li>{@code VAULT_UNLOCK_<ID>} : booléen ; {@code VAULT_SLOTS_<ID>} : 9 à 54 par rangée de 9
 *       (coffres personnels, #106).</li>
 *   <li>Toute autre variable garde son type libre.</li>
 * </ul>
 *
 * <p>Le passage de {@code CLAIM_TIER_<n>} à un entier unique {@code CLAIM_TIER} est préparé dans
 * {@code docs/audits/claim-tier-entier.md} ; il n'est pas appliqué.</p>
 */
public final class VariableTypes {

    private static final Pattern BOOLEAN_KEYS = Pattern.compile(
            "CLAIM_TIER_\\d+|WILD_ENTRY_WARNING_HIDDEN|BETA_WARNING_HIDDEN|RUNE_RAPPEL_GRANTED|VAULT_UNLOCK_[A-Z0-9_]{1,32}");
    private static final Pattern INTEGER = Pattern.compile("[1-9]\\d?");
    /** Issue #106 — taille d'un coffre personnel : une à six rangées de 9 cases. */
    private static final Pattern VAULT_SLOTS_KEY = Pattern.compile("VAULT_SLOTS_[A-Z0-9_]{1,32}");
    private static final Set<String> VAULT_SLOTS = Set.of("9", "18", "27", "36", "45", "54");

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
        if (VAULT_SLOTS_KEY.matcher(key).matches()) {
            return "9, 18, 27, 36, 45 ou 54";
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
        if (key != null && VAULT_SLOTS_KEY.matcher(key).matches() && !VAULT_SLOTS.contains(v)) {
            return Optional.of("« " + v + " » n'est pas valide pour " + key + " : 9, 18, 27, 36, 45 ou 54.");
        }
        return Optional.empty();
    }
}
