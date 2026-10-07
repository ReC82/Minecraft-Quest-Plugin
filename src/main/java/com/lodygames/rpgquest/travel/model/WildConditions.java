package com.lodygames.rpgquest.travel.model;

import java.util.Objects;

/**
 * Relevé instantané des conditions du monde d'exploration (« Wild »), tel qu'un PNJ resté au Hub
 * peut le rapporter à un joueur (issue #24). Purement descriptif : construire ou lire cet objet ne
 * modifie jamais l'heure, la météo ni le cycle jour/nuit du monde (voir
 * {@code travel.WildConditionsService}, qui n'appelle que des accesseurs).
 *
 * <p><strong>Extensible volontairement</strong> : enrichir le relevé plus tard (niveau de danger,
 * événement actif, saison...) consiste à ajouter un champ à ce record et une phrase à
 * {@link #description()} — aucune des deux opérations ne touche au dialogue du Garde ni au moteur de
 * dialogue, qui ne connaissent qu'un texte déjà rendu.</p>
 */
public record WildConditions(boolean daytime, WildWeather weather) {

    private static final String DAY = "Il fait jour dans le Wild.";
    private static final String NIGHT = "Il fait nuit dans le Wild.";

    public WildConditions {
        Objects.requireNonNull(weather, "weather est obligatoire.");
    }

    /**
     * Description complète en français, sans balise MiniMessage : « Il fait nuit dans le Wild. Un
     * orage est en cours. » L'heure (jour/nuit) vient de l'horloge du monde, la météo de son état
     * global — les deux sont rapportées séparément, jamais fusionnées en un seul jugement.
     */
    public String description() {
        return (daytime ? DAY : NIGHT) + " " + weather.sentence();
    }
}
