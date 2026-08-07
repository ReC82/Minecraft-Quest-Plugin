package com.lodygames.rpgquest.config;

import com.lodygames.rpgquest.backpack.model.BackpackSize;
import java.util.Set;
import org.bukkit.Material;

/**
 * Section {@code backpacks:} — nombre de lignes par palier (mission étape
 * 20, point 2), objets interdits (point 7), et palier accordé par la
 * permission de secours (point 5).
 */
public record BackpackConfig(
        int smallRows,
        int mediumRows,
        int largeRows,
        Set<Material> forbiddenMaterials,
        BackpackSize fallbackSize,
        Material openItemMaterial
) {

    public int rowsFor(BackpackSize size) {
        return switch (size) {
            case SMALL -> smallRows;
            case MEDIUM -> mediumRows;
            case LARGE -> largeRows;
        };
    }
}
