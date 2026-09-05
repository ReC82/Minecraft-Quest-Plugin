package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import org.bukkit.entity.Player;

/**
 * Décision, prise juste avant la téléportation d'un portail simple, d'autoriser ou non le passage
 * (mission « avertissement avant entrée dans le Wild »). Injecté dans {@link
 * WorldPortalTeleportListener} plutôt que codé dedans : le portail simple reste générique, la
 * politique (« demander confirmation si le joueur n'a pas de Rune de rappel ») vit à part et ne
 * concerne qu'un sous-ensemble des portails.
 */
@FunctionalInterface
public interface WorldPortalEntryGuard {

    /**
     * @return {@code true} pour laisser {@link WorldPortalTeleportListener} téléporter normalement ;
     *         {@code false} pour bloquer ce passage-ci (à charge de l'implémentation d'expliquer au
     *         joueur et, le cas échéant, de relancer elle-même la téléportation plus tard).
     */
    boolean allowEntry(Player player, WorldPortalDefinition portal);
}
