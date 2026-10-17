package com.lodygames.rpgquest.economy.merchant;

import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;

/**
 * Résultat d'un chargement : les marchands valides, et un problème par fichier/marchand rejeté.
 * {@code files} : nom du fichier de chaque marchand chargé (issue #17, édition depuis PlugAdmin).
 */
public record MerchantLoadReport(List<MerchantDefinition> loaded, List<MerchantLoadIssue> issues,
                                 Map<NamespacedKey, String> files) {

    public MerchantLoadReport(List<MerchantDefinition> loaded, List<MerchantLoadIssue> issues) {
        this(loaded, issues, Map.of());
    }

    public MerchantLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
        files = Map.copyOf(files);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
