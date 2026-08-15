package com.lodygames.rpgquest.config;

/**
 * Paramètres de {@code travel.RandomSafeLocationFinder} pour
 * {@code travel.model.DestinationStrategy#RANDOM_SAFE} — voir docs-site/worlds.html, section
 * « Portail Hub → wild ».
 */
public record RandomSafeArrivalConfig(int minRadius, int maxRadius, int maxAttempts) {
}
