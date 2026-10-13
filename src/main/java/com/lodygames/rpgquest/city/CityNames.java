package com.lodygames.rpgquest.city;

import java.util.function.Function;

/**
 * Nom affiché actuel d'une ville à partir de son identifiant stable (issue #178), pour les libellés
 * d'objectif (journal, action bar). Les libellés de quête sont produits par du code statique : ce
 * relais leur donne accès au registre sans le leur injecter partout. Sans registre branché (tests,
 * démarrage), l'identifiant est rendu tel quel.
 */
public final class CityNames {

    private static volatile Function<String, String> resolver = id -> id;

    private CityNames() {
    }

    public static void use(CityRegistry registry) {
        resolver = id -> registry.find(id).map(c -> c.name()).orElse(id);
    }

    public static String of(String cityId) {
        if (cityId == null) {
            return "";
        }
        String name = resolver.apply(cityId);
        return name == null ? cityId : name;
    }
}
