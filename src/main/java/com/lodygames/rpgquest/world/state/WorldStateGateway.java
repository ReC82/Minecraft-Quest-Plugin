package com.lodygames.rpgquest.world.state;

import java.util.List;
import java.util.Optional;

/**
 * Accès au serveur pour charger, décharger ou importer un monde (issue #563). Toutes les méthodes
 * s'appellent sur le <strong>thread principal</strong>. L'implémentation réelle est
 * {@link BukkitWorldStateGateway} ; les tests fournissent un faux serveur et n'agissent sur aucun
 * monde. Aucune méthode ne supprime, ne régénère ni ne retire un monde de Multiverse-Core.
 */
public interface WorldStateGateway {

    /** État du serveur pour la validation ; {@code folders} est relevé par l'appelant hors du thread principal. */
    WorldStatePlanner.Context context(List<WorldStatePlanner.Folder> folders);

    /** Charge un monde connu de Multiverse-Core ; {@code null} = réussi, sinon la raison en français. */
    String load(String world);

    /** Enregistre puis décharge le monde, en le gardant dans Multiverse-Core ; {@code null} = réussi. */
    String unload(String world);

    /** Importe le dossier dans Multiverse-Core et le charge ; {@code null} = réussi. */
    String importFolder(String world, String environment);

    /** Règle « charger au démarrage » puis enregistre le fichier de Multiverse-Core ; {@code null} = réussi. */
    String setAutoLoad(String world, boolean autoLoad);

    /** Relecture après l'opération : vide si ni Bukkit ni Multiverse ne connaissent le monde. */
    Optional<Reread> describe(String world);

    /**
     * Ce que le serveur relit. {@code autoLoad} et {@code environment} viennent de Multiverse
     * ({@code null} = non lisible).
     */
    record Reread(boolean loaded, boolean multiverseKnown, Boolean autoLoad, String environment) {
    }
}
