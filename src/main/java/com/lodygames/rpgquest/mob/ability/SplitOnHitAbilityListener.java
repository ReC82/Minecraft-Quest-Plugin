package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SplitOnHitAbility;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Fait apparaître des enfants à chaque coup non mortel reçu par une entité
 * portant {@link SplitOnHitAbility}. La profondeur de génération est stockée
 * en PDC (clé {@link SpecialMobService#splitDepthKey()}, jamais dans le nom
 * affiché — mission point 10 ; exposée publiquement pour que d'autres
 * systèmes, ex. la progression RPG de l'étape 19, puissent détecter un
 * descendant de division via {@link SpecialMobService#isSplitOffspring}) :
 * un enfant né à la profondeur maximale ne peut plus se diviser, et {@code
 * maxChildrenPerHit} borne le nombre d'enfants créés par déclenchement.
 *
 * <p><strong>Issue #190</strong> : ces deux bornes seules ne protégeaient pas contre des coups
 * <em>répétés</em> sur la même entité avant sa mort (le cas normal au combat) -- chaque coup non
 * mortel relançait une division complète, produisant potentiellement bien plus que
 * {@code maxChildrenPerHit} descendants directs pour un seul parent. {@code maxAlivePerParent}
 * (clé PDC {@link #PARENT_KEY_NAME} sur chaque enfant, comptage par balayage borné des entités
 * proches -- même patron que {@code SummonOnDamageAbilityListener}) plafonne désormais le nombre
 * d'enfants <strong>directs et vivants</strong> d'une même entité, indépendamment du nombre de
 * coups reçus.</p>
 */
public final class SplitOnHitAbilityListener implements Listener {

    private static final double PARENT_SEARCH_RADIUS = 48.0;

    private final SpecialMobService service;
    private final NamespacedKey depthKey;
    private final NamespacedKey parentKey;

    public SplitOnHitAbilityListener(SpecialMobService service) {
        this.service = service;
        this.depthKey = service.splitDepthKey();
        this.parentKey = service.splitParentKey();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (living.getHealth() - event.getFinalDamage() <= 0) {
            return; // coup mortel : la mort prime, pas de division (évite un doublon avec le décès).
        }

        service.specialMobDefinition(living).ifPresent(def -> findAbility(def).ifPresent(ability -> {
            int depth = currentDepth(living);
            if (depth >= ability.maxDepth()) {
                return;
            }
            split(living, def, ability, depth);
        }));
    }

    private void split(LivingEntity parent, SpecialMobDefinition def, SplitOnHitAbility ability, int depth) {
        int alreadyAlive = countAliveChildren(parent);
        if (alreadyAlive >= ability.maxAlivePerParent()) {
            return; // #190 : ce parent a déjà atteint son plafond d'enfants vivants, pas de nouveau coup = nouvelle portée.
        }
        World world = parent.getWorld();
        int toSpawn = Math.min(ability.maxChildrenPerHit(), ability.maxAlivePerParent() - alreadyAlive);
        int spawned = 0;
        for (int i = 0; i < toSpawn; i++) {
            if (service.atPopulationLimit(def)) {
                break;
            }
            LivingEntity child = (LivingEntity) world.spawnEntity(parent.getLocation(), def.entityType());
            service.apply(child, def);
            child.getPersistentDataContainer().set(depthKey, PersistentDataType.INTEGER, depth + 1);
            child.getPersistentDataContainer().set(parentKey, PersistentDataType.STRING, parent.getUniqueId().toString());
            spawned++;
        }
        if (spawned > 0) {
            service.recordAbilityTrigger(MobAbilityType.SPLIT_ON_HIT.name());
        }
    }

    /** Borné à un rayon fixe autour du parent (jamais un scan global) -- même discipline que les autres capacités. */
    private int countAliveChildren(LivingEntity parent) {
        String parentId = parent.getUniqueId().toString();
        int count = 0;
        for (Entity nearby : parent.getNearbyEntities(PARENT_SEARCH_RADIUS, PARENT_SEARCH_RADIUS, PARENT_SEARCH_RADIUS)) {
            if (nearby instanceof LivingEntity livingNearby && !livingNearby.isDead()
                    && parentId.equals(nearby.getPersistentDataContainer().get(parentKey, PersistentDataType.STRING))) {
                count++;
            }
        }
        return count;
    }

    private int currentDepth(LivingEntity entity) {
        Integer depth = entity.getPersistentDataContainer().get(depthKey, PersistentDataType.INTEGER);
        return depth == null ? 0 : depth;
    }

    private Optional<SplitOnHitAbility> findAbility(SpecialMobDefinition def) {
        for (MobAbility ability : def.abilities()) {
            if (ability instanceof SplitOnHitAbility splitAbility) {
                return Optional.of(splitAbility);
            }
        }
        return Optional.empty();
    }
}
