package com.lodygames.rpgquest.world.create;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Création de monde par Multiverse-Core (issue #562, décision Q1 de l'étude #331). Multiverse est
 * facultatif : l'implémentation réelle ({@code world.multiverse.MultiverseWorldCreator}) vérifie sa
 * présence ; absent, la création est désactivée avec explication — jamais de repli sur
 * {@code WorldCreator}, pour garder une seule source de vérité des nouveaux mondes.
 */
public interface MultiverseCreationPort {

    /** {@code null} si Multiverse est utilisable, sinon la raison en français. */
    String unavailableReason();

    /** Noms des mondes que Multiverse connaît (chargés ou non). */
    Set<String> knownWorlds();

    /** Crée et charge le monde ; {@code null} = réussi, sinon la raison donnée par Multiverse. */
    String create(WorldCreationPlanner.Plan plan);

    /** Zone d'apparition non gardée en mémoire puis enregistrement ; {@code null} = réussi. */
    String disableKeepSpawnAndSave(String world);

    /** Relevé Multiverse du monde : {@code generatorSettings}, {@code keepSpawnLoaded}, {@code autoLoad}. */
    Optional<Map<String, String>> describe(String world);

    static MultiverseCreationPort none() {
        return new MultiverseCreationPort() {
            @Override
            public String unavailableReason() {
                return "Multiverse-Core n'est pas installé";
            }

            @Override
            public Set<String> knownWorlds() {
                return Set.of();
            }

            @Override
            public String create(WorldCreationPlanner.Plan plan) {
                return unavailableReason();
            }

            @Override
            public String disableKeepSpawnAndSave(String world) {
                return unavailableReason();
            }

            @Override
            public Optional<Map<String, String>> describe(String world) {
                return Optional.empty();
            }
        };
    }
}
