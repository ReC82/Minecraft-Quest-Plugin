package be.lloyd.rpgquest.economy.merchant;

import be.lloyd.rpgquest.economy.merchant.model.MerchantDefinition;
import java.util.List;

/** Résultat d'un chargement : les marchands valides, et un problème par fichier/marchand rejeté. */
public record MerchantLoadReport(List<MerchantDefinition> loaded, List<MerchantLoadIssue> issues) {

    public MerchantLoadReport {
        loaded = List.copyOf(loaded);
        issues = List.copyOf(issues);
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
