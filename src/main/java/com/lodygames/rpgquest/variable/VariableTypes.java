package com.lodygames.rpgquest.variable;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Registre des variables joueur que le jeu lit réellement (issues #319, #320), pour qu'aucune
 * écriture — outil « Écrire une variable » de PlugAdmin, {@code /rpgadmin player variable set},
 * action de dialogue {@code SET_VARIABLE}, récompense de quête {@code VARIABLE} — ne pose une
 * valeur que le jeu interpréterait de travers ({@code CLAIM_TIER_1 = lasagne}).
 *
 * <ul>
 *   <li>Booléens ({@code CLAIM_TIER_<n>}, préférences « ne plus afficher », droits…) : la chaîne
 *       exacte {@code true} ou {@code false}. Le jeu ne reconnaît que {@code true} : toute autre
 *       valeur déjà stockée est lue comme « non débloqué » (fail-closed), jamais convertie.</li>
 *   <li>{@code STARTER_KIT_TIER} : entier de 1 à 99 (palier du kit de départ).</li>
 *   <li>{@code VAULT_SLOTS_<ID>} : 9 à 54 par rangée de 9 (coffres personnels, #106).</li>
 *   <li>Toute autre variable reste <strong>libre</strong> (variables narratives) : aucune
 *       conversion, aucun refus.</li>
 * </ul>
 *
 * <p>Une variable absente n'est jamais l'équivalent de {@code false} ici : la validation porte sur
 * une valeur écrite, l'absence relève de chaque lecteur.</p>
 *
 * <p>Le panel tient une copie de ce registre ({@code panel.agent.KnownVariableTypes}) : les deux
 * doivent évoluer ensemble. Le passage de {@code CLAIM_TIER_<n>} à un entier unique est préparé
 * dans {@code docs/audits/claim-tier-entier.md} ; il n'est pas appliqué.</p>
 */
public final class VariableTypes {

    /** Nature d'une variable connue. */
    public enum Kind { BOOLEAN, INTEGER, CHOICE }

    /**
     * Une famille de clés connues : motif de clé, nature, valeurs acceptées (exactes, sans espaces)
     * en mots, et rôle dans le jeu.
     */
    public record KnownVariable(String label, Pattern keyPattern, Kind kind, Pattern valuePattern,
                                String expected, String description) {

        boolean matches(String key) {
            return keyPattern.matcher(key).matches();
        }

        boolean accepts(String value) {
            return value != null && valuePattern.matcher(value).matches();
        }
    }

    private static final Pattern BOOLEAN_VALUE = Pattern.compile("true|false");
    private static final String BOOLEAN_EXPECTED = "true ou false";

    private static final List<KnownVariable> REGISTRY = List.of(
            bool("CLAIM_TIER_<n>", "CLAIM_TIER_\\d+", "Droit au palier n de terrain (true = débloqué)."),
            bool("WILD_ENTRY_WARNING_HIDDEN", "WILD_ENTRY_WARNING_HIDDEN",
                    "Avertissement d'entrée dans le Wild masqué par le joueur."),
            bool("PORTAL_WARNING_HIDDEN_<ID>", "PORTAL_WARNING_HIDDEN_[A-Z0-9_-]{1,48}",
                    "Avertissement d'un portail masqué par le joueur (issue #569)."),
            bool("BETA_WARNING_HIDDEN", "BETA_WARNING_HIDDEN", "Avertissement bêta masqué par le joueur."),
            bool("RUNE_RAPPEL_GRANTED", "RUNE_RAPPEL_GRANTED", "Pierre de retour déjà remise."),
            bool("STARTER_TOOL_KIT_AVAILABLE", "STARTER_TOOL_KIT_AVAILABLE",
                    "Droit au kit de départ pour la vie en cours (false = déjà reçu, absent = disponible)."),
            bool("WEB_PROFILE_HIDDEN", "WEB_PROFILE_HIDDEN", "Profil web public masqué par le joueur."),
            bool("VAULT_UNLOCK_<ID>", "VAULT_UNLOCK_[A-Z0-9_]{1,32}", "Coffre personnel débloqué."),
            new KnownVariable("STARTER_KIT_TIER", Pattern.compile("STARTER_KIT_TIER"), Kind.INTEGER,
                    Pattern.compile("[1-9]\\d?"), "un nombre entier de 1 à 99",
                    "Meilleur palier du kit de départ (absent = palier 1)."),
            new KnownVariable("VAULT_SLOTS_<ID>", Pattern.compile("VAULT_SLOTS_[A-Z0-9_]{1,32}"), Kind.CHOICE,
                    Pattern.compile("9|18|27|36|45|54"), "9, 18, 27, 36, 45 ou 54",
                    "Capacité d'un coffre personnel, par rangée de 9 cases."));

    private VariableTypes() {
    }

    private static KnownVariable bool(String label, String keyRegex, String description) {
        return new KnownVariable(label, Pattern.compile(keyRegex), Kind.BOOLEAN, BOOLEAN_VALUE, BOOLEAN_EXPECTED,
                description);
    }

    /** Toutes les familles connues, dans l'ordre d'affichage. */
    public static List<KnownVariable> registry() {
        return REGISTRY;
    }

    /** La famille connue de cette clé, ou vide si la variable est libre. */
    public static Optional<KnownVariable> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return REGISTRY.stream().filter(v -> v.matches(key)).findFirst();
    }

    /** Le type attendu, en mots : « true ou false », « entier de 1 à 99 », ou vide (libre). */
    public static String expected(String key) {
        return find(key).map(KnownVariable::expected).orElse("");
    }

    /**
     * Le motif du refus, ou vide si la valeur convient au type de la clé. La comparaison est
     * exacte : {@code " true"} ou {@code TRUE} seraient stockés tels quels et lus comme non
     * débloqués, ils sont donc refusés.
     */
    public static Optional<String> problem(String key, String value) {
        return find(key).filter(v -> !v.accepts(value)).map(v ->
                "« " + (value == null ? "" : value) + " » n'est pas valide pour " + key + " : " + v.expected() + ".");
    }

    /**
     * Diagnostic d'une valeur DÉJÀ stockée (lecture) : vide si elle est conforme ou si la variable
     * est libre ; sinon, comment le jeu la traite. Rien n'est corrigé automatiquement.
     */
    public static Optional<String> storedValueDiagnostic(String key, String storedValue) {
        Optional<KnownVariable> known = find(key);
        if (known.isEmpty() || storedValue == null || known.get().accepts(storedValue)) {
            return Optional.empty();
        }
        String effect = known.get().kind() == Kind.BOOLEAN
                ? "le jeu la lit comme non débloquée (seule la valeur exacte « true » compte)"
                : "le jeu l'ignore et applique sa valeur par défaut";
        return Optional.of("Valeur stockée « " + storedValue + " » hors type pour " + key + " (attendu : "
                + known.get().expected() + ") : " + effect + ". Aucune correction automatique : réécrire la "
                + "valeur explicitement.");
    }
}
