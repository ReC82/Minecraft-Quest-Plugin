package com.lodygames.rpgquest.resource.model;

/**
 * Une entrée de la table de drops pondérée d'un {@link ResourceNodeDefinition}.
 * Interface scellée (comme {@code QuestReward}/{@code DialogueAction}) : un
 * drop désigne soit un objet personnalisé (résolu via {@code
 * CustomItemRegistry} au moment de la récolte), soit un matériau vanilla brut
 * — un `switch` exhaustif sur les deux variantes est vérifié par le
 * compilateur.
 */
public sealed interface ResourceDrop permits CustomItemDrop, VanillaItemDrop {

    int weight();

    int minAmount();

    int maxAmount();
}
