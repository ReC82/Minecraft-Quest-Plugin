package com.lodygames.rpgquest.world.settings;

import java.util.Optional;

/**
 * Accès au serveur pour les réglages d'un monde (issue #561). Toutes les méthodes s'appellent sur
 * le <strong>thread principal</strong>. L'implémentation réelle est {@link BukkitWorldSettingsGateway} ;
 * les tests fournissent un faux serveur.
 */
public interface WorldSettingsGateway {

    /** Relit l'état réel d'un monde <strong>chargé</strong> ; vide s'il n'est pas chargé. */
    Optional<WorldSettingsSnapshot> read(String world);

    /**
     * Écrit UN réglage par son unique chemin ({@code change.writer()}).
     *
     * @return {@code null} si l'appel a réussi, sinon la raison de l'échec en français
     */
    String write(String world, WorldSettingsPlanner.Change change);

    /**
     * Enregistre durablement ce qui vient d'être écrit (fichier de Multiverse-Core). Les écritures
     * Bukkit sont conservées par le monde lui-même.
     *
     * @return {@code null} si l'enregistrement a réussi ou n'était pas nécessaire, sinon la raison
     */
    String persist(String world);

    /**
     * Position actuelle d'un joueur connecté <strong>dans ce monde</strong>, sous forme canonique de
     * point d'arrivée ({@code x,y,z,orientation}).
     *
     * @return la position, ou {@code Optional.empty()} si le joueur est absent de ce monde
     */
    Optional<String> playerPosition(String world, String player);
}
