package com.lodygames.rpgquest.mob.ability;

import java.util.Map;
import org.bukkit.entity.EntityType;

/**
 * Issue #542 — valeurs d'une entrée de capacité déjà validées par {@link MobAbilityCatalog} contre
 * leurs {@link AbilityParam} (type et bornes) ; un paramètre facultatif absent porte son défaut.
 */
public record AbilityValues(Map<String, Object> values) {

    public Double decimal(String key) {
        return (Double) values.get(key);
    }

    public Integer integer(String key) {
        return (Integer) values.get(key);
    }

    public boolean bool(String key) {
        return Boolean.TRUE.equals(values.get(key));
    }

    public EntityType entityType(String key) {
        return (EntityType) values.get(key);
    }
}
