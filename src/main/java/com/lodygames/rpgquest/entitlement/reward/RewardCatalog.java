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

    /**
     * La récompense peut-elle encore être promise (issue #474) ? Inconnue = oui : le droit reste en
     * attente avec l'erreur visible, comme avant ; désactivée = non.
     */
    public boolean promisable(String key) {
        RewardDefinition reward = rewards.get(key);
        return reward == null || reward.enabled();
    }

    public Optional<RewardDefinition> find(String key) {
        return Optional.ofNullable(rewards.get(key));
    }
}
