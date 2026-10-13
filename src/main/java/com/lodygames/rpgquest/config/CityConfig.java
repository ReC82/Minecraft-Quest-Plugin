package com.lodygames.rpgquest.config;

/**
 * Section {@code cities:} de {@code config.yml} (issue #178) — annonce de la découverte d'une ville.
 *
 * <p>Les durées sont en <strong>ticks</strong> (20 = 1 s), comme le titre natif de Minecraft. Elles
 * sont bornées par {@link #bounded()} : un titre de dix minutes ou d'un tick ne serait pas
 * lisible. Les textes du titre vivent dans {@code messages.yml} ({@code city.welcome-title},
 * {@code city.welcome-subtitle}).</p>
 *
 * @param discoveryEnabled annoncer et enregistrer les découvertes (désactiver coupe les deux)
 * @param fadeInTicks      apparition du titre
 * @param stayTicks        durée d'affichage plein
 * @param fadeOutTicks     disparition
 */
public record CityConfig(boolean discoveryEnabled, int fadeInTicks, int stayTicks, int fadeOutTicks) {

    public static CityConfig defaults() {
        return new CityConfig(true, 10, 70, 20);
    }

    /** Ramène chaque durée dans une plage lisible. */
    public CityConfig bounded() {
        return new CityConfig(discoveryEnabled, clamp(fadeInTicks, 0, 100), clamp(stayTicks, 20, 200),
                clamp(fadeOutTicks, 0, 100));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
