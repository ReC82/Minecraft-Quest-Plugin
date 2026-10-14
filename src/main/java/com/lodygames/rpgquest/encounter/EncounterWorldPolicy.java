package com.lodygames.rpgquest.encounter;

import java.util.List;
import java.util.Set;

/**
 * Mondes où un PNJ récurrent peut apparaître (issue #13). Sans état, sans Bukkit.
 *
 * <p>{@code encounters.worlds} vide = le seul monde d'exploration ({@code travel.wild-world}). Le
 * Hub et le monde des claims sont <strong>toujours</strong> exclus, même s'ils figurent dans la
 * liste : une faute de configuration ne doit jamais faire apparaître un brigand au spawn ou sur un
 * terrain de joueur.</p>
 */
public final class EncounterWorldPolicy {

    private EncounterWorldPolicy() {
    }

    public static boolean allowed(String world, List<String> configured, String wildWorld, Set<String> forbidden) {
        if (world == null || world.isBlank() || forbidden.contains(world)) {
            return false;
        }
        return configured.isEmpty() ? world.equals(wildWorld) : configured.contains(world);
    }
}
