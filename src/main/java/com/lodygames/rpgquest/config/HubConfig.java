package com.lodygames.rpgquest.config;

/**
 * Nom du monde Hub, unique source de vérité pour {@code hub.HubWorldRulesService}/{@code
 * hub.HubWorldProtectionListener}/{@code claim.ClaimService} — jamais de comparaison à une chaîne
 * codée en dur ailleurs dans le plugin.
 */
public record HubConfig(String world) {
}
