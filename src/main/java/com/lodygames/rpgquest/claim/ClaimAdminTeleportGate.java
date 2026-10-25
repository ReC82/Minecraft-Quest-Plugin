package com.lodygames.rpgquest.claim;

import com.lodygames.rpgquest.permission.AdminBypassMode;
import com.lodygames.rpgquest.permission.RpgPermissions;
import org.bukkit.entity.Player;

/**
 * Issue #829, décision D1 du propriétaire (25/10/2026) : les commandes d'administration qui
 * téléportent vers le monde des claims ({@code /rpgadmin world tp <monde des claims>}, {@code
 * /claim admin tp <joueur>}) exigent le mode bypass explicite (#35) — même pour un OP. Sans session
 * {@code /rpgadmin bypass on} active portant {@link RpgPermissions#BYPASS_CLAIM_WORLD}, elles ne
 * téléportent pas et l'expliquent ; aucune autre protection n'est assouplie.
 */
public final class ClaimAdminTeleportGate {

    /** Message de refus commun aux deux commandes (MiniMessage). */
    public static final String REFUSAL = "<red>Téléportation administrative vers le monde des claims refusée :</red> "
            + "<gray>active d'abord le mode administrateur avec</gray> <white>/rpgadmin bypass on</white><gray>.</gray>";

    private ClaimAdminTeleportGate() {
    }

    /** {@code true} seulement avec une session bypass active et le nœud du monde des claims (ou l'ombrelle). */
    public static boolean permits(AdminBypassMode bypass, Player player) {
        return bypass != null && bypass.allows(player, RpgPermissions.BYPASS_CLAIM_WORLD);
    }
}
