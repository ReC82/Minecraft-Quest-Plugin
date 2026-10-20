package com.lodygames.rpgquest.world.create;

import java.util.Optional;
import java.util.Set;

/**
 * Accès au serveur pour créer un monde (issue #562). Toutes les méthodes s'appellent sur le
 * <strong>thread principal</strong>. L'implémentation réelle est {@link BukkitWorldCreationGateway} ;
 * les tests fournissent un faux serveur et ne créent jamais de monde.
 */
public interface WorldCreationGateway {

    /**
     * État du serveur pour la validation. {@code folders} est relevé par l'appelant hors du thread
     * principal (liste des dossiers du répertoire des mondes).
     */
    WorldCreationPlanner.Context context(Set<String> folders);

    /** Identifiants des biomes du registre du serveur ({@code minecraft:plains}…), triés. */
    java.util.List<String> biomes();

    /** Identifiants des blocs utilisables comme couche de monde plat, triés. */
    java.util.List<String> blocks();

    /**
     * Crée le monde décrit par l'aperçu validé, via Multiverse-Core uniquement.
     *
     * @return {@code null} si Multiverse a créé et chargé le monde, sinon la raison en français
     */
    String create(WorldCreationPlanner.Plan plan);

    /**
     * Réglages appliqués juste après la création : zone d'apparition non gardée en mémoire, puis
     * enregistrement du fichier de Multiverse-Core.
     *
     * @return les remarques (échecs non bloquants), vide si tout a réussi
     */
    java.util.List<String> configureCreated(String world);

    /** Relecture du monde créé ; vide s'il n'est pas chargé. */
    Optional<Created> describe(String world);

    /**
     * Ce que le serveur relit après création. {@code generatorSettings} et {@code keepSpawnLoaded}
     * viennent de Multiverse ({@code null} = non lisible).
     */
    record Created(String name, String environment, String worldType, long seed, boolean structures,
                   String generatorSettings, Boolean keepSpawnLoaded, boolean multiverseKnown, boolean autoLoad) {
    }
}
