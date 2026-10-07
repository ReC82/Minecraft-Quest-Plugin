package com.lodygames.rpgquest.travel.model;

/**
 * Météo <strong>globale</strong> du monde d'exploration, telle que Minecraft la gère réellement
 * (issue #24) : la pluie et l'orage sont des états du <em>monde</em>, jamais du biome — certains
 * biomes rendent simplement la précipitation différemment (neige, ciel sec en désert). Ce type ne
 * prétend donc jamais décrire la météo du point d'arrivée, seulement celle du monde entier.
 *
 * <p>Trois états seulement, dans l'ordre de gravité croissante, exactement ceux que l'API publique
 * expose ({@code World#hasStorm()} / {@code World#isThundering()}).</p>
 */
public enum WildWeather {

    CLEAR("Le temps est clair."),
    RAIN("Il pleut."),
    THUNDER("Un orage est en cours.");

    private final String sentence;

    WildWeather(String sentence) {
        this.sentence = sentence;
    }

    /** Phrase prête à être lue par un PNJ (français, sans balise MiniMessage). */
    public String sentence() {
        return sentence;
    }
}
