package com.lodygames.rpgquest.world.state;

import java.util.Map;

/**
 * Chargement, déchargement et import par Multiverse-Core (issue #563). Multiverse est facultatif :
 * l'implémentation réelle ({@code world.multiverse.MultiverseWorldState}) vérifie sa présence ;
 * absent, toutes les opérations sont refusées avec explication — jamais de repli sur l'API Bukkit,
 * pour qu'un monde déchargé reste connu d'un gestionnaire qui sait le recharger.
 */
public interface MultiverseStatePort {

    /** {@code null} si Multiverse est utilisable, sinon la raison en français. */
    String unavailableReason();

    /** Mondes connus de Multiverse (chargés ou non), clé = nom exact. */
    Map<String, WorldStatePlanner.Known> knownWorlds();

    /** {@code null} = réussi, sinon la raison donnée par Multiverse. */
    String load(String world);

    /** Enregistre puis décharge ; le monde reste dans le fichier de Multiverse. {@code null} = réussi. */
    String unload(String world);

    /** {@code null} = réussi. Vérification de dossier Multiverse toujours active. */
    String importFolder(String world, String environment);

    /** {@code null} = réussi. */
    String setAutoLoad(String world, boolean autoLoad);

    static MultiverseStatePort none() {
        return new MultiverseStatePort() {
            @Override
            public String unavailableReason() {
                return "Multiverse-Core n'est pas installé";
            }

            @Override
            public Map<String, WorldStatePlanner.Known> knownWorlds() {
                return Map.of();
            }

            @Override
            public String load(String world) {
                return unavailableReason();
            }

            @Override
            public String unload(String world) {
                return unavailableReason();
            }

            @Override
            public String importFolder(String world, String environment) {
                return unavailableReason();
            }

            @Override
            public String setAutoLoad(String world, boolean autoLoad) {
                return unavailableReason();
            }
        };
    }
}
