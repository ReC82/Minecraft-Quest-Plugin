package com.lodygames.rpgquest.player;

import java.util.Optional;

/**
 * Ce qu'un joueur montre publiquement sur le portail web (issues #55 / #56).
 *
 * <p>Le portail ne lit jamais la base : il lit l'instantané exporté par le plugin. C'est donc
 * <strong>à l'export</strong> que la confidentialité se décide, en un seul endroit : un joueur dont
 * le profil n'est pas visible n'apparaît dans aucun classement, aucun profil public ni la liste des
 * connectés — aucune donnée le concernant n'est écrite dans l'instantané. L'UUID n'est jamais
 * exporté.</p>
 *
 * <p>Le choix du joueur est une variable ordinaire, persistante par UUID ({@code /profile web
 * prive|public}) : elle survit aux reconnexions, aux redémarrages et aux changements de pseudo. Un
 * joueur qui n'a rien choisi suit {@link Visibility}, réglé par {@code web-export.profile-visibility-default}
 * — {@link Visibility#PRIVATE} par défaut (#55 : rien de public sans accord explicite).</p>
 */
public final class PublicProfilePolicy {

    public static final String HIDDEN_KEY = "WEB_PROFILE_HIDDEN";
    public static final String HIDDEN_VALUE = "true";
    /** Valeur écrite par {@code /profile web public} : un accord explicite, pas une absence de choix. */
    public static final String PUBLIC_VALUE = "false";

    /** Visibilité d'un joueur qui n'a jamais utilisé {@code /profile web}. */
    public enum Visibility {
        /** Seuls les joueurs ayant tapé {@code /profile web public} sont publiés (par défaut). */
        PRIVATE,
        /** Tout le monde est publié, sauf les joueurs ayant tapé {@code /profile web prive}. */
        PUBLIC
    }

    private PublicProfilePolicy() {
    }

    /**
     * Fragment SQL qui ne garde que les joueurs visibles. {@code profileAlias} désigne la table
     * {@code player_profiles} de la requête. Aucune donnée utilisateur n'y est concaténée.
     */
    public static String visible(String profileAlias, Visibility defaultVisibility) {
        return switch (defaultVisibility) {
            case PRIVATE -> "EXISTS (" + choice(profileAlias, PUBLIC_VALUE) + ")";
            case PUBLIC -> "NOT EXISTS (" + choice(profileAlias, HIDDEN_VALUE) + ")";
        };
    }

    /** Même règle que {@link #visible}, pour une valeur déjà lue ({@code empty} = aucun choix). */
    public static boolean isVisible(Optional<String> storedChoice, Visibility defaultVisibility) {
        if (storedChoice.isPresent() && storedChoice.get().equals(HIDDEN_VALUE)) {
            return false;
        }
        if (storedChoice.isPresent() && storedChoice.get().equals(PUBLIC_VALUE)) {
            return true;
        }
        return defaultVisibility == Visibility.PUBLIC;
    }

    private static String choice(String profileAlias, String value) {
        return "SELECT 1 FROM player_variables hv WHERE hv.player_uuid = " + profileAlias
                + ".uuid AND hv.variable_key = '" + HIDDEN_KEY + "' AND hv.variable_value = '" + value + "'";
    }
}
