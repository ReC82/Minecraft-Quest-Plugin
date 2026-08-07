package com.lodygames.rpgquest.quest.model;

/**
 * {@code world} est un simple nom de monde (pas un {@code org.bukkit.World}
 * live) : sa résolution se fait au moment de l'évaluation par le futur
 * moteur de progression, pas au chargement de la définition.
 */
public record ReachLocationObjective(String world, double x, double y, double z, double radius) implements QuestObjective {

    public ReachLocationObjective {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world ne peut pas être vide.");
        }
        if (radius <= 0) {
            throw new IllegalArgumentException("radius doit être strictement positif : " + radius);
        }
    }

    @Override
    public ObjectiveType type() {
        return ObjectiveType.REACH_LOCATION;
    }
}
