package com.lodygames.rpgquest.panel.agent;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Issue #320 — copie côté panel du registre des variables typées du plugin
 * ({@code com.lodygames.rpgquest.variable.VariableTypes}). Les deux doivent évoluer ensemble : le
 * panel refuse tôt (formulaire, validation de contenu), le plugin refuse de toute façon à
 * l'écriture et au chargement. Toute variable absente du registre reste libre.
 */
public final class KnownVariableTypes {

    /** Une famille de clés connues : motif de clé, valeurs exactes acceptées, rôle. */
    public record KnownVariable(String label, Pattern keyPattern, boolean bool, Pattern valuePattern,
                                String expected, String description) {
    }

    private static final Pattern BOOLEAN_VALUE = Pattern.compile("true|false");

    private static final List<KnownVariable> REGISTRY = List.of(
            bool("CLAIM_TIER_<n>", "CLAIM_TIER_\\d+", "Droit au palier n de terrain (true = débloqué)."),
            bool("WILD_ENTRY_WARNING_HIDDEN", "WILD_ENTRY_WARNING_HIDDEN",
                    "Avertissement d'entrée dans le Wild masqué par le joueur."),
            bool("BETA_WARNING_HIDDEN", "BETA_WARNING_HIDDEN", "Avertissement bêta masqué par le joueur."),
            bool("RUNE_RAPPEL_GRANTED", "RUNE_RAPPEL_GRANTED", "Pierre de retour déjà remise."),
            bool("STARTER_TOOL_KIT_AVAILABLE", "STARTER_TOOL_KIT_AVAILABLE",
                    "Droit au kit de départ pour la vie en cours (false = déjà reçu, absent = disponible)."),
            bool("WEB_PROFILE_HIDDEN", "WEB_PROFILE_HIDDEN", "Profil web public masqué par le joueur."),
            bool("VAULT_UNLOCK_<ID>", "VAULT_UNLOCK_[A-Z0-9_]{1,32}", "Coffre personnel débloqué."),
            new KnownVariable("STARTER_KIT_TIER", Pattern.compile("STARTER_KIT_TIER"), false,
                    Pattern.compile("[1-9]\\d?"), "un nombre entier de 1 à 99",
                    "Meilleur palier du kit de départ (absent = palier 1)."),
            new KnownVariable("VAULT_SLOTS_<ID>", Pattern.compile("VAULT_SLOTS_[A-Z0-9_]{1,32}"), false,
                    Pattern.compile("9|18|27|36|45|54"), "9, 18, 27, 36, 45 ou 54",
                    "Capacité d'un coffre personnel, par rangée de 9 cases."));

    /** Clés booléennes proposées par le sélecteur vrai/faux de l'outil « Écrire une variable ». */
    public static final List<String> BOOLEAN_KEYS = List.of(
            "CLAIM_TIER_1", "CLAIM_TIER_2", "CLAIM_TIER_3", "CLAIM_TIER_4", "CLAIM_TIER_5",
            "STARTER_TOOL_KIT_AVAILABLE", "RUNE_RAPPEL_GRANTED", "WILD_ENTRY_WARNING_HIDDEN",
            "BETA_WARNING_HIDDEN", "WEB_PROFILE_HIDDEN");

    private KnownVariableTypes() {
    }

    private static KnownVariable bool(String label, String keyRegex, String description) {
        return new KnownVariable(label, Pattern.compile(keyRegex), true, BOOLEAN_VALUE, "true ou false", description);
    }

    public static List<KnownVariable> registry() {
        return REGISTRY;
    }

    /** La famille connue de cette clé, ou vide si la variable est libre. */
    public static Optional<KnownVariable> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return REGISTRY.stream().filter(v -> v.keyPattern().matcher(key).matches()).findFirst();
    }

    /**
     * Le motif du refus, ou vide si la valeur convient. Comparaison exacte, comme le plugin :
     * « TRUE » ou « true » suivi d'un espace seraient lus comme non débloqués.
     */
    public static Optional<String> problem(String key, String value) {
        String v = value == null ? "" : value;
        return find(key).filter(k -> !k.valuePattern().matcher(v).matches())
                .map(k -> "« " + v + " » n'est pas valide pour " + key + " : " + k.expected() + ".");
    }
}
