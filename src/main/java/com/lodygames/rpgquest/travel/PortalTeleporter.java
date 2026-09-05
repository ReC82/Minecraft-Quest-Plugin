package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import org.bukkit.entity.Player;

/**
 * Capacité minimale « téléporter un joueur via un portail simple, sans contrôle d'entrée » —
 * extraite pour que {@link WildEntryWarningService} ne dépende pas de toute la classe
 * {@link WorldPortalTeleportListener} (et reste testable sans monde/registre réels).
 */
@FunctionalInterface
public interface PortalTeleporter {

    void teleportNow(Player player, WorldPortalDefinition portal);
}
