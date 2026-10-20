package com.lodygames.rpgquest.waypoint;

import com.lodygames.rpgquest.config.TravelConfig;
import java.util.Map;
import org.bukkit.World;

/**
 * Issue #136 — seule règle qui décide si un monde accepte la génération <strong>automatique</strong>
 * de waypoints. Évaluée avant toute recherche d'emplacement ou écriture en base ; ne touche jamais
 * aux waypoints déjà persistés (un monde désactivé garde les siens, découvrables et administrables
 * via #134).
 *
 * <p>Ordre de décision : système coupé ({@code travel.waypoint.enabled}) → monde des claims
 * (toujours interdit) → entrée explicite {@code travel.waypoint.worlds.<nom>.enabled} → rôle Wild
 * ({@code travel.wild-world}) → rôle Hub ({@code hub.world}, si {@code hub-enabled}) → tout autre
 * monde interdit par défaut.</p>
 *
 * <p>La dimension n'est pas déclarée en configuration : elle est lue sur le monde réel
 * ({@link #supportsSurfacePlacement}). La stratégie de surface de #124 ne convient ni au Nether
 * (plafond de bedrock) ni à l'End : un tel monde, même autorisé, est refusé tant qu'aucune
 * stratégie adaptée n'existe.</p>
 */
public final class WaypointWorldEligibility {

    public enum Reason {
        WILD_ROLE, HUB_ROLE, LISTED, SYSTEM_DISABLED, CLAIMS_WORLD, LISTED_DISABLED, HUB_DISABLED, NOT_LISTED
    }

    public record Decision(boolean allowed, Reason reason) {
    }

    private WaypointWorldEligibility() {
    }

    public static Decision evaluate(TravelConfig travel, String hubWorld, String claimsWorld, String world) {
        TravelConfig.WaypointConfig wc = travel.waypoint();
        return evaluate(wc.enabled(), wc.hubEnabled(), wc.worlds(), travel.wildWorld(), hubWorld, claimsWorld, world);
    }

    public static Decision evaluate(boolean enabled, boolean hubEnabled, Map<String, Boolean> worlds,
                                    String wildWorld, String hubWorld, String claimsWorld, String world) {
        if (!enabled) {
            return new Decision(false, Reason.SYSTEM_DISABLED);
        }
        if (world == null) {
            return new Decision(false, Reason.NOT_LISTED);
        }
        if (world.equals(claimsWorld)) {
            return new Decision(false, Reason.CLAIMS_WORLD);
        }
        Boolean listed = worlds.get(world);
        if (listed != null) {
            return new Decision(listed, listed ? Reason.LISTED : Reason.LISTED_DISABLED);
        }
        if (world.equals(wildWorld)) {
            return new Decision(true, Reason.WILD_ROLE);
        }
        if (world.equals(hubWorld)) {
            return new Decision(hubEnabled, hubEnabled ? Reason.HUB_ROLE : Reason.HUB_DISABLED);
        }
        return new Decision(false, Reason.NOT_LISTED);
    }

    /** {@code false} pour le Nether et l'End : la recherche de surface de #124 n'y est pas sûre. */
    public static boolean supportsSurfacePlacement(World.Environment environment) {
        return environment != World.Environment.NETHER && environment != World.Environment.THE_END;
    }
}
