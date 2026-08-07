package com.lodygames.rpgquest.crafting;

/** Un problème rencontré en chargeant {@code file} (nom de fichier, pas chemin complet). */
public record RecipeLoadIssue(String file, String message) {
}
