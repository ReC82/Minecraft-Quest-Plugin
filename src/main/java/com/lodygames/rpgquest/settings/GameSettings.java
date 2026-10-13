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
 */
public record GameSettings(String currencyName, boolean betaWarningEnabled, String betaWarningText) {

    public static final int MAX_CURRENCY = 24;
    public static final int MAX_WARNING = 500;

    public static final String DEFAULT_WARNING = "Attention : le serveur est en version bêta. Des bugs sont "
            + "possibles, et des données ou la progression peuvent être perdues ou remises à zéro à tout moment.";

    public static final GameSettings DEFAULTS = new GameSettings("LodyCoins", true, DEFAULT_WARNING);

    /** Clés acceptées par {@code settings.set}, dans l'ordre affiché. */
    public static final List<String> KEYS = List.of("currency_name", "beta_warning_enabled", "beta_warning_text");

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
                default -> errors.add("Paramètre inconnu : " + e.getKey());
            }
        }
        return new Change(errors.isEmpty() ? new GameSettings(currency, enabled, text) : this, List.copyOf(errors));
    }
}
