package com.lodygames.rpgquest.config;

import java.util.List;
import org.bukkit.Material;

/**
 * Section {@code starter-tool-kit:} (issue #26, partie A) — kit d'outils en bois demandé
 * explicitement au Guide, un exemplaire de chaque matériau de {@link #items()}. Définition
 * purement déclarative : aucun matériau n'est codé en dur ailleurs que dans les valeurs par défaut
 * ci-dessous (voir {@code player.StarterToolKitService}).
 */
public record StarterToolKitConfig(boolean enabled, List<Material> items) {

    public StarterToolKitConfig {
        items = List.copyOf(items);
    }
}
