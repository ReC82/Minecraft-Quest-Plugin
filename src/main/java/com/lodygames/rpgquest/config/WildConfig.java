package com.lodygames.rpgquest.config;

import java.util.List;
import org.bukkit.entity.EntityType;

/**
 * Section {@code wild:} (issue #168) — règles propres aux mondes d'exploration : créatures
 * hostiles présentes <strong>de jour comme de nuit</strong>, insensibles au soleil, araignées
 * agressives en plein jour.
 *
 * <p>Tout est déclaratif et borné, et ne s'applique qu'aux {@link #worlds()} listés : le Hub
 * (aucun mob hostile) et les Claims (protections inchangées) ne sont jamais concernés, et un monde
 * non listé conserve exactement les règles vanilla. Plusieurs mondes Wild sont supportés d'emblée.</p>
 *
 * @param worlds              mondes concernés ; vide = le seul {@code travel.wild-world}
 * @param sunImmunity         annule la combustion <strong>par le soleil</strong> (jamais les dégâts
 *                            de feu, de lave ou de combat, qui restent normaux)
 * @param aggressiveSpiders   les araignées acquièrent une cible même en pleine lumière
 * @param daylightSpawns      apparitions diurnes contrôlées (voir {@link DaylightSpawnConfig})
 */
public record WildConfig(List<String> worlds, boolean sunImmunity, boolean aggressiveSpiders,
                         DaylightSpawnConfig daylightSpawns) {

    public WildConfig {
        worlds = List.copyOf(worlds == null ? List.of() : worlds);
    }

    /**
     * Apparitions diurnes : un complément <strong>borné</strong> au spawn vanilla (qui, lui,
     * continue d'assurer la nuit — on ne double jamais les apparitions nocturnes, le service ne
     * travaille que de jour).
     *
     * @param enabled           active le complément diurne
     * @param periodTicks       période de la tâche (jamais par mob : une passe par joueur)
     * @param attemptsPerPlayer tentatives par passe et par joueur (une tentative peut échouer)
     * @param minDistance       distance minimale au joueur (jamais dans son dos immédiat)
     * @param maxDistance       distance maximale (reste dans les chunks déjà chargés)
     * @param maxPerPlayer      plafond d'hostiles diurnes comptés autour d'un joueur
     * @param maxPerWorld       plafond global par monde
     * @param types             espèces candidates ; les espèces à conditions spéciales
     *                          (ex. {@code PHANTOM}) sont volontairement absentes par défaut
     */
    public record DaylightSpawnConfig(boolean enabled, int periodTicks, int attemptsPerPlayer,
                                      int minDistance, int maxDistance, int maxPerPlayer,
                                      int maxPerWorld, List<EntityType> types) {

        public DaylightSpawnConfig {
            types = List.copyOf(types == null ? List.of() : types);
        }
    }
}
