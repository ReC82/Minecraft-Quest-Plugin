package com.lodygames.rpgquest.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Paramètres généraux du jeu modifiables depuis PlugAdmin (issue #335).
 *
 * <p>Ils vivent dans {@code plugins/RPGQuest/settings.yml}, écrit uniquement par
 * {@link GameSettingsService} : jamais de SQL, jamais d'édition à la main nécessaire. Un fichier
 * absent ou incomplet donne les {@link #DEFAULTS}.</p>
 *
 * @param currencyName     nom affiché de la monnaie (« LodyCoins »). Purement affiché : ni le
 *                         solde, ni les transactions, ni l'identifiant interne ne changent
 * @param betaWarningEnabled avertissement « serveur en version bêta » à la connexion (issue #28)
 * @param betaWarningText  texte de cet avertissement
 * @param adminBypassAvailable mode bypass administrateur (issue #35) proposé sur ce serveur. Ne
 *                         l'active pour personne : il reste à activer par chaque administrateur
 *                         autorisé, pour sa session. {@code false} coupe les sessions en cours
 * @param adminBypassMaxMinutes durée maximale d'une activation, en minutes ; {@code 0} = jusqu'à la
 *                         déconnexion (ou au redémarrage)
 */
public record GameSettings(String currencyName, boolean betaWarningEnabled, String betaWarningText,
                           boolean adminBypassAvailable, int adminBypassMaxMinutes) {

    public static final int MAX_CURRENCY = 24;
    public static final int MAX_WARNING = 500;
    public static final int MAX_BYPASS_MINUTES = 480;

    public static final String DEFAULT_WARNING = "Attention : le serveur est en version bêta. Des bugs sont "
            + "possibles, et des données ou la progression peuvent être perdues ou remises à zéro à tout moment.";

    public static final GameSettings DEFAULTS = new GameSettings("LodyCoins", true, DEFAULT_WARNING, true, 0);

    /** Clés acceptées par {@code settings.set}, dans l'ordre affiché. */
    public static final List<String> KEYS = List.of("currency_name", "beta_warning_enabled", "beta_warning_text",
            "admin_bypass_available", "admin_bypass_max_minutes");

    /** Les paramètres avec les changements demandés appliqués, ou les motifs du refus. */
    public record Change(GameSettings settings, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Applique des changements ({@code clé → valeur texte}) en les validant tous. Rien n'est
     * appliqué si une seule valeur est invalide.
     */
    public Change with(Map<String, String> changes) {
        List<String> errors = new ArrayList<>();
        String currency = currencyName;
        boolean enabled = betaWarningEnabled;
        String text = betaWarningText;
        boolean bypassAvailable = adminBypassAvailable;
        int bypassMinutes = adminBypassMaxMinutes;
        for (Map.Entry<String, String> e : changes.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            switch (e.getKey()) {
                case "currency_name" -> {
                    if (v.isEmpty() || v.length() > MAX_CURRENCY || !v.matches("[\\p{L}\\p{N} '’.-]+")) {
                        errors.add("Nom de la monnaie : 1 à " + MAX_CURRENCY + " lettres, chiffres ou espaces.");
                    } else {
                        currency = v;
                    }
                }
                case "beta_warning_enabled" -> {
                    if (!"true".equals(v) && !"false".equals(v)) {
                        errors.add("Avertissement bêta : « true » ou « false » attendu.");
                    } else {
                        enabled = Boolean.parseBoolean(v);
                    }
                }
                case "beta_warning_text" -> {
                    if (v.isEmpty() || v.length() > MAX_WARNING || v.indexOf('<') >= 0) {
                        errors.add("Texte de l'avertissement : 1 à " + MAX_WARNING + " caractères, sans balise.");
                    } else {
                        text = v;
                    }
                }
                case "admin_bypass_available" -> {
                    if (!"true".equals(v) && !"false".equals(v)) {
                        errors.add("Mode bypass administrateur : « true » ou « false » attendu.");
                    } else {
                        bypassAvailable = Boolean.parseBoolean(v);
                    }
                }
                case "admin_bypass_max_minutes" -> {
                    Integer minutes = v.matches("\\d{1,3}") ? Integer.valueOf(v) : null;
                    if (minutes == null || minutes > MAX_BYPASS_MINUTES) {
                        errors.add("Durée maximale du mode bypass : 0 à " + MAX_BYPASS_MINUTES + " minutes.");
                    } else {
                        bypassMinutes = minutes;
                    }
                }
                default -> errors.add("Paramètre inconnu : " + e.getKey());
            }
        }
        return new Change(errors.isEmpty() ? new GameSettings(currency, enabled, text, bypassAvailable, bypassMinutes) : this, List.copyOf(errors));
    }
}
