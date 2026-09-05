package com.lodygames.rpgquest.crafting.model;

/**
 * Le résultat d'une recette : soit un objet personnalisé (résolu via {@code
 * YamlCustomItemRegistry} au moment de l'enregistrement de la recette Bukkit),
 * soit un matériau vanilla brut. Même discipline que {@code
 * resource.model.ResourceDrop} : interface scellée, un `switch` exhaustif
 * vérifié par le compilateur.
 */
public sealed interface RecipeResult permits CustomItemResult, VanillaResult {

    int amount();
}
