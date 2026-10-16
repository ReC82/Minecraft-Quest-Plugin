package com.lodygames.rpgquest.entitlement.reward;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Contenu validé de {@code entitlements.yml} (issue #422) : l'interrupteur général et les
 * récompenses par clé.
 */
public record RewardCatalog(boolean enabled, Map<String, RewardDefinition> rewards) {

    /** Fichier absent ou invalide : fonction désactivée, aucune récompense. */
    public static final RewardCatalog DISABLED = new RewardCatalog(false, Map.of());

    public RewardCatalog {
        rewards = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
    }

    public Optional<RewardDefinition> find(String key) {
        return Optional.ofNullable(rewards.get(key));
    }
}
