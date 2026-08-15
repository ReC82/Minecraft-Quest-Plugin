package com.lodygames.rpgquest.spawn;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Position persistée du spawn du village central RPGQuest — monde, coordonnées et orientation
 * exacts capturés par {@code /rpgadmin spawn set}. Aucune valeur par défaut n'existe : tant
 * qu'un administrateur n'a pas défini de spawn, {@link SpawnService#current()} reste vide et
 * le comportement vanilla (spawn du monde, ou lit/ancre pour la réapparition) s'applique.
 */
public record SpawnPoint(String world, double x, double y, double z, float yaw, float pitch) {

    public SpawnPoint {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world est obligatoire.");
        }
    }

    /** Capture exacte de la position actuelle de {@code location}, monde compris. */
    public static SpawnPoint of(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("La position n'a pas de monde associé.");
        }
        return new SpawnPoint(world.getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }
}
