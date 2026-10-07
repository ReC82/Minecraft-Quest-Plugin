package com.lodygames.rpgquest.quest.model;

import org.bukkit.Material;

/**
 * Faire <strong>cuire</strong> {@code amount} exemplaires de {@code material} dans un four
 * (issue #141). Distinct de {@link CraftItemObjective}, qui suit une recette d'établi, et de
 * {@link CollectItemObjective}, qui suit un ramassage au sol.
 *
 * <p>{@code material} est l'objet <strong>obtenu</strong> après cuisson (ex. {@code GREEN_DYE} pour
 * un cactus cuit), pas la matière première : c'est ce que le joueur voit sortir du four, et c'est la
 * seule des deux valeurs que l'événement de Bukkit expose de façon fiable.</p>
 *
 * <p>La progression n'a lieu que lorsqu'un joueur <strong>retire réellement</strong> un résultat de
 * cuisson d'un four, d'un haut fourneau ou d'un fumoir — voir
 * {@code quest.progress.QuestSmeltListener} pour l'audit complet des cas écartés (objet obtenu
 * autrement, extraction par un entonnoir, four d'un autre joueur).</p>
 */
public record SmeltItemObjective(Material material, int amount) implements QuestObjective {

    public SmeltItemObjective {
        if (material == null) {
            throw new IllegalArgumentException("material est obligatoire.");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount doit être strictement positif : " + amount);
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.SMELT_ITEM;
    }
}
