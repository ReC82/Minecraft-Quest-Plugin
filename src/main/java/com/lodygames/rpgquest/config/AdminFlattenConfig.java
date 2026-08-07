package com.lodygames.rpgquest.config;

import java.util.List;
import org.bukkit.Material;

/** Configuration de {@code /rpgadmin flatten} (voir {@code docs/ADMIN_FLATTEN.md}). */
public record AdminFlattenConfig(
        int maxRadius,
        FlattenShape defaultShape,
        Material topLayerMaterial,
        Material subLayerMaterial,
        int subLayerDepth,
        int clearAboveHeight,
        int confirmationTimeoutSeconds,
        int blocksPerTick,
        List<String> forbiddenWorlds
) {

    public AdminFlattenConfig {
        forbiddenWorlds = List.copyOf(forbiddenWorlds);
    }
}
