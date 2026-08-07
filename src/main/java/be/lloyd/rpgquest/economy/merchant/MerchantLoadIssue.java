package be.lloyd.rpgquest.economy.merchant;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record MerchantLoadIssue(String file, String message) {
}
