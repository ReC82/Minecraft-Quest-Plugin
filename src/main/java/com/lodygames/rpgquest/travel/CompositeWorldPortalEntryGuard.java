package com.lodygames.rpgquest.travel;

import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * Compose plusieurs {@link WorldPortalEntryGuard} en un seul : le passage n'est autorisé que si
 * <strong>tous</strong> les gardes de la liste l'autorisent, évalués dans l'ordre. Dès qu'un garde
 * refuse, l'évaluation s'arrête et ce garde-là est propriétaire de la suite (message au joueur,
 * relance éventuelle de la téléportation via {@link WorldPortalTeleportListener#teleportNow}) —
 * exactement le contrat d'un garde unique, {@code WorldPortalTeleportListener} ne fait pas la
 * différence.
 *
 * <p>Nécessaire parce que {@code WorldPortalTeleportListener} n'accepte qu'un seul garde et que
 * deux politiques indépendantes coexistent : l'accès au monde des claims réservé au déblocage
 * réel ({@code claim.ClaimWorldAccessGuard}) et l'avertissement avant entrée dans le Wild ({@link
 * WildEntryWarningService}). Elles visent des mondes de destination différents et ne se
 * chevauchent jamais en pratique, mais rien dans le type ne le garantit — les enchaîner ici est
 * plus sûr que de fusionner leurs responsabilités.</p>
 */
public final class CompositeWorldPortalEntryGuard implements WorldPortalEntryGuard {

    private final List<WorldPortalEntryGuard> guards;

    public CompositeWorldPortalEntryGuard(List<WorldPortalEntryGuard> guards) {
        this.guards = List.copyOf(guards);
    }

    @Override
    public boolean allowEntry(Player player, WorldPortalDefinition portal) {
        for (WorldPortalEntryGuard guard : guards) {
            if (!guard.allowEntry(player, portal)) {
                return false; // ce garde a pris la main (message + suite éventuelle).
            }
        }
        return true;
    }
}
