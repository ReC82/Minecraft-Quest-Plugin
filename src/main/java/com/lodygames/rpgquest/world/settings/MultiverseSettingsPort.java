package com.lodygames.rpgquest.world.settings;

import java.util.Map;
import java.util.Optional;

/**
 * Réglages persistés par Multiverse-Core pour un monde qu'il connaît (issue #561). Multiverse est
 * facultatif : l'implémentation réelle ({@code world.multiverse.MultiverseWorldSettings}) vérifie sa
 * présence et rend « inconnu » s'il est absent, désactivé ou incompatible.
 *
 * <p>Clés lues : {@code alias} (#81, {@code -} = aucun), {@code difficulty}, {@code pvp}, {@code monsters}, {@code animals},
 * {@code gamemode}, {@code spawn} (forme canonique de {@link WorldSettingsPlanner#spawnValue}) et
 * {@code allow_weather} (information seulement). Appels sur le thread principal.</p>
 */
public interface MultiverseSettingsPort {

    /**
     * Clé présente seule dans le relevé quand Multiverse est installé mais illisible : les réglages
     * qu'il porte deviennent alors indisponibles (jamais écrits par un autre chemin).
     */
    String ERROR_KEY = "error";

    /** Valeurs Multiverse du monde, vide si Multiverse ne le connaît pas ou n'est pas lisible. */
    Optional<Map<String, String>> read(String world);

    /** Écrit un réglage dans Multiverse (appliqué au monde chargé) ; {@code null} = réussi, sinon la raison. */
    String write(String world, String key, String value);

    /** Enregistre le fichier {@code worlds.yml} de Multiverse ; {@code null} = réussi, sinon la raison. */
    String save();

    /** Aucun Multiverse : tous les mondes passent par l'API Bukkit. */
    static MultiverseSettingsPort none() {
        return new MultiverseSettingsPort() {
            @Override
            public Optional<Map<String, String>> read(String world) {
                return Optional.empty();
            }

            @Override
            public String write(String world, String key, String value) {
                return "Multiverse-Core indisponible";
            }

            @Override
            public String save() {
                return null;
            }
        };
    }
}
