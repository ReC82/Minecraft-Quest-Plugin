package com.lodygames.rpgquest.player;

/**
 * Ce qu'un joueur montre publiquement sur le portail web (issues #55 / #56).
 *
 * <p>Le portail ne lit jamais la base : il lit l'instantané exporté par le plugin. C'est donc
 * <strong>à l'export</strong> que la confidentialité se décide, en un seul endroit : un joueur qui a
 * masqué son profil ({@code /profile web prive}) disparaît des classements et des profils publics
 * — aucune donnée le concernant n'est écrite dans l'instantané. L'UUID n'est jamais exporté.</p>
 *
 * <p>Le choix est une variable joueur ordinaire, persistante par UUID : elle survit aux
 * reconnexions, aux redémarrages et aux changements de pseudo.</p>
 */
public final class PublicProfilePolicy {

    public static final String HIDDEN_KEY = "WEB_PROFILE_HIDDEN";
    public static final String HIDDEN_VALUE = "true";

    private PublicProfilePolicy() {
    }

    /**
     * Fragment SQL qui écarte un joueur masqué. {@code profileAlias} désigne la table
     * {@code player_profiles} de la requête. Aucune donnée utilisateur n'y est concaténée.
     */
    public static String notHidden(String profileAlias) {
        return "NOT EXISTS (SELECT 1 FROM player_variables hv WHERE hv.player_uuid = " + profileAlias
                + ".uuid AND hv.variable_key = '" + HIDDEN_KEY + "' AND hv.variable_value = '"
                + HIDDEN_VALUE + "')";
    }
}
