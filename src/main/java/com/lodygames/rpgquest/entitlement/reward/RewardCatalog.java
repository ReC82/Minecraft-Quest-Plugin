package com.lodygames.rpgquest.entitlement.reward;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Contenu validé de {@code entitlements.yml} (issue #422) : l'interrupteur général et les
 * récompenses par clé, et les tables de récompenses aléatoires (issue #177).
 */
public record RewardCatalog(boolean enabled, Map<String, RewardDefinition> rewards,
                            Map<String, RewardTable> tables) {

    /** Fichier absent ou invalide : fonction désactivée, aucune récompense. */
    public static final RewardCatalog DISABLED = new RewardCatalog(false, Map.of());

    public RewardCatalog {
        rewards = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rewards));
        tables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public RewardCatalog(boolean enabled, Map<String, RewardDefinition> rewards) {
        this(enabled, rewards, Map.of());
    }

    /** Table de récompenses aléatoires (issue #177). */
    public Optional<RewardTable> findTable(String id) {
        return Optional.ofNullable(tables.get(id));
    }

    public Optional<RewardDefinition> find(String key) {
        return Optional.ofNullable(rewards.get(key));
    }
}
