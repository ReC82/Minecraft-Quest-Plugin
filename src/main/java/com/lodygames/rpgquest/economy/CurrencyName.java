package com.lodygames.rpgquest.economy;

/**
 * Nom affiché de la monnaie (issue #335), réglé depuis PlugAdmin > Paramètres généraux.
 *
 * <p>Purement affiché : le portefeuille, les soldes, les transactions et les identifiants internes
 * ne le connaissent pas. Valeur globale tenue à jour par {@code GameSettingsService}, pour que
 * chaque écran (bourse, marchands, marché, journal, messages) affiche le même nom sans dépendre
 * d'un service de plus.</p>
 */
public final class CurrencyName {

    private static volatile String name = "LodyCoins";

    private CurrencyName() {
    }

    public static String get() {
        return name;
    }

    public static void set(String value) {
        if (value != null && !value.isBlank()) {
            name = value.trim();
        }
    }

    /** « 25 LodyCoins ». */
    public static String amount(long amount) {
        return amount + " " + name;
    }
}
