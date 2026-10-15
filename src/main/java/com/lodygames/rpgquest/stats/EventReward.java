package com.lodygames.rpgquest.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Récompense <strong>déclarative</strong> d'un événement (issue #57) : à qui elle revient et quel
 * avantage elle désigne. Le moteur ne livre rien — ni le plugin, ni le portail n'injectent d'objet en
 * jeu ; la livraison passera par les entitlements (#52), qui liront l'archive figée.
 *
 * @param kind        {@link Kind#RANK} : rang ≤ {@code value} ; {@link Kind#THRESHOLD} : valeur ≥
 *                    {@code value} ; {@link Kind#COMMUNITY} : objectif commun atteint ({@code value} ignoré)
 * @param entitlement clé d'avantage interne (jamais publiée)
 * @param label       texte montré aux joueurs
 */
public record EventReward(Kind kind, long value, String entitlement, String label) {

    public enum Kind {
        RANK,
        THRESHOLD,
        COMMUNITY
    }

    /** Ce que le portail peut afficher : le type, le seuil et le libellé, jamais la clé interne. */
    public Map<String, Object> describe() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("kind", kind.name());
        if (kind != Kind.COMMUNITY) {
            descriptor.put("value", value);
        }
        descriptor.put("label", label);
        return descriptor;
    }
}
