package com.lodygames.rpgquest.travel;

import org.bukkit.entity.Player;

/**
 * Garde d'entrée des portails historiques ({@link PortalService}, issue #829) : contrôle, juste
 * avant la téléportation et avant tout débit, que le joueur a le droit d'entrer dans le monde de
 * destination. Pendant de {@link WorldPortalEntryGuard} pour les portails simples, mais
 * asynchrone par nature : la décision peut dépendre d'une lecture en base.
 *
 * <p>Contrat : {@code onAllowed} est exécuté <strong>au plus une fois</strong>, sur le thread
 * principal, uniquement si l'entrée est autorisée et que le joueur est toujours en ligne. En cas de
 * refus, le garde informe lui-même le joueur et n'exécute rien.</p>
 */
@FunctionalInterface
public interface PortalDestinationGuard {

    /** Aucun contrôle : comportement historique, utilisé tant qu'aucun garde n'est branché. */
    PortalDestinationGuard ALLOW_ALL = (player, destinationWorld, sourceId, onAllowed) -> onAllowed.run();

    /**
     * @param destinationWorld nom du monde où le joueur serait téléporté
     * @param sourceId identifiant du portail, pour les journaux
     */
    void checkEntry(Player player, String destinationWorld, String sourceId, Runnable onAllowed);
}
