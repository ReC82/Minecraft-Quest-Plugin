package com.lodygames.rpgquest.mob.community;

import com.lodygames.rpgquest.waypoint.model.Waypoint;
import java.util.Collection;
import java.util.Optional;

/**
 * Issue #175 — waypoint <strong>réellement existant</strong> le plus proche d'un boss, pour l'annonce.
 * Lecture seule : ne découvre, ne déverrouille et ne crée jamais rien. Au-delà de
 * {@link #MAX_DISTANCE} blocs, aucun waypoint n'est cité (un repère à 2 000 blocs n'aide personne) —
 * l'annonce retombe alors sur le seul biome, jamais sur un nom inventé.
 *
 * @param distance  distance horizontale arrondie, en blocs
 * @param direction direction du boss <em>vu depuis le waypoint</em> (« nord-est »…), {@code ""} si tout près
 */
public record WaypointHint(String waypointId, String waypointName, int distance, String direction) {

    public static final int MAX_DISTANCE = 600;
    /** En deçà, la direction n'apporte rien : « tout près du waypoint ». */
    public static final int NEAR_DISTANCE = 24;

    private static final String[] DIRECTIONS = {
            "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest", "nord", "nord-est"};

    public static Optional<WaypointHint> nearest(Collection<Waypoint> waypoints, String world, int x, int z) {
        Waypoint best = null;
        long bestSq = Long.MAX_VALUE;
        for (Waypoint w : waypoints) {
            if (!w.active() || !w.world().equals(world)) {
                continue;
            }
            long dx = (long) x - w.x();
            long dz = (long) z - w.z();
            long sq = dx * dx + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = w;
            }
        }
        if (best == null || bestSq > (long) MAX_DISTANCE * MAX_DISTANCE) {
            return Optional.empty();
        }
        int distance = (int) Math.round(Math.sqrt(bestSq));
        String direction = distance < NEAR_DISTANCE ? "" : direction(x - best.x(), z - best.z());
        return Optional.of(new WaypointHint(best.id(), best.displayName(), distance, direction));
    }

    /** Direction cardinale/intercardinale ; en Minecraft, -Z est le nord et +X l'est. */
    static String direction(int dx, int dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx)); // 0 = est, 90 = sud (Z croissant)
        int sector = (int) Math.floorMod(Math.round(angle / 45.0), 8L);
        return DIRECTIONS[sector];
    }
}
