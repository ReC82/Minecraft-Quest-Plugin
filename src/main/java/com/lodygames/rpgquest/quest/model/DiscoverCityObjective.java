package com.lodygames.rpgquest.quest.model;

/**
 * Découvrir des villes (issue #178), sur le modèle de {@link DiscoverWaypointObjective}.
 *
 * <p>Deux formes, une seule classe :</p>
 * <ul>
 *   <li><strong>{@code city} vide</strong> : découvrir {@code amount} villes <em>distinctes</em> ;</li>
 *   <li><strong>{@code city} renseigné</strong> : découvrir cette ville précise ({@code amount} vaut
 *       alors toujours 1).</li>
 * </ul>
 *
 * <p>Seule une <strong>première découverte réelle</strong> compte : elle est unique par couple
 * (joueur, ville) au niveau de la base. Sortir puis revenir, se téléporter dans une ville déjà connue,
 * renommer une ville ou redémarrer le serveur ne font donc jamais monter le compteur. L'identité
 * comptée est l'identifiant stable de la ville, jamais son nom.</p>
 *
 * <p>La règle de prise en compte des découvertes antérieures à l'acceptation est la même que pour
 * les waypoints, et elle est toujours explicite : {@link DiscoverWaypointObjective.CountMode}.</p>
 */
public record DiscoverCityObjective(int amount, String city, DiscoverWaypointObjective.CountMode countMode)
        implements QuestObjective {

    public DiscoverCityObjective {
        city = city == null || city.isBlank() ? null : city.trim();
        if (city != null) {
            amount = 1;
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
        countMode = countMode == null ? DiscoverWaypointObjective.CountMode.NEW_ONLY : countMode;
    }

    /** {@code true} pour la forme « une ville précise ». */
    public boolean specific() {
        return city != null;
    }

    /** {@code true} si la découverte de {@code cityId} compte pour cet objectif. */
    public boolean matches(String cityId) {
        return city == null || city.equals(cityId);
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.DISCOVER_CITY;
    }
}
