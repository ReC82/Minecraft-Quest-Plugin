package com.lodygames.rpgquest.item.model;

import java.util.List;
import org.bukkit.Material;

/**
 * Comportement d'outil d'un objet personnalisé. {@code allowedBlocks} vide
 * signifie « tous les blocs » — les bonus (récolte, capacité spéciale) et
 * la consommation de durabilité personnalisée ne s'appliquent que sur un
 * bloc autorisé quand la liste n'est pas vide ; en dehors, l'outil continue
 * de fonctionner normalement (vanilla), simplement sans le comportement
 * spécial de ce plugin.
 */
public record ToolBehavior(
        Double miningSpeedBonus,
        List<Material> allowedBlocks,
        int durabilityCost,
        double harvestBonusChance,
        int harvestBonusAmount,
        ToolSpecialAbility specialAbility
) {

    public ToolBehavior {
        allowedBlocks = List.copyOf(allowedBlocks);

        if (miningSpeedBonus != null && (Double.isNaN(miningSpeedBonus) || Double.isInfinite(miningSpeedBonus))) {
            throw new IllegalArgumentException("tool.mining-speed-bonus ne peut pas être NaN ou infini.");
        }
        if (durabilityCost < 0) {
            throw new IllegalArgumentException("tool.durability-cost ne peut pas être négatif.");
        }
        if (Double.isNaN(harvestBonusChance) || Double.isInfinite(harvestBonusChance)
                || harvestBonusChance < 0 || harvestBonusChance > 1) {
            throw new IllegalArgumentException("tool.harvest-bonus-chance doit être compris entre 0 et 1.");
        }
        if (harvestBonusAmount < 0) {
            throw new IllegalArgumentException("tool.harvest-bonus-amount ne peut pas être négatif.");
        }
    }

    public boolean appliesTo(Material blockType) {
        return allowedBlocks.isEmpty() || allowedBlocks.contains(blockType);
    }
}
