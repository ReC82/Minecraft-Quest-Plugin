package com.lodygames.rpgquest.mob.ability;

import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.MobAbilityType;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SplitOnHitAbility;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Fait apparaître des enfants à chaque coup non mortel reçu. La profondeur de génération est stockée
 * en PDC ({@link SpecialMobService#splitDepthKey()}, jamais dans le nom affiché ; lue aussi par la
 * progression RPG via {@link SpecialMobService#isSplitOffspring}) : un enfant né à la profondeur
 * maximale ne se divise plus, et {@code maxChildrenPerHit} borne les enfants d'un déclenchement.
 *
 * <p><strong>Issue #190</strong> : {@code maxAlivePerParent} (clé PDC
 * {@link SpecialMobService#splitParentKey()} sur chaque enfant, comptage borné aux entités proches)
 * plafonne les enfants <strong>directs et vivants</strong> d'un même parent, quel que soit le nombre
 * de coups reçus.</p>
 *
 * <p><strong>Issue #542</strong> : aucune division dans le Hub, un claim ou une zone qui interdit les
 * hostiles ({@link AbilityWorldPolicy#allowsSpawnAt}).</p>
 */
final class SplitOnHitExecutor implements AbilityExecutor<SplitOnHitAbility> {

    private static final double PARENT_SEARCH_RADIUS = 48.0;

    private final SpecialMobService service;
    private final AbilityWorldPolicy policy;
    private final NamespacedKey depthKey;
    private final NamespacedKey parentKey;

    SplitOnHitExecutor(SpecialMobService service, AbilityWorldPolicy policy) {
        this.service = service;
        this.policy = policy;
        this.depthKey = service.splitDepthKey();
        this.parentKey = service.splitParentKey();
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.SPLIT_ON_HIT;
    }

    @Override
    public Class<SplitOnHitAbility> abilityClass() {
        return SplitOnHitAbility.class;
    }

    @Override
    public void execute(AbilityContext<SplitOnHitAbility> context) {
        LivingEntity parent = context.mob();
        EntityDamageEvent event = context.event(EntityDamageEvent.class);
        if (parent.getHealth() - event.getFinalDamage() <= 0) {
            return; // coup mortel : la mort prime, pas de division (évite un doublon avec le décès).
        }
        SplitOnHitAbility ability = context.ability();
        int depth = currentDepth(parent);
        if (depth >= ability.maxDepth()) {
            return;
        }
        int alreadyAlive = countAliveChildren(parent);
        if (alreadyAlive >= ability.maxAlivePerParent()) {
            return; // #190 : plafond d'enfants vivants atteint, un nouveau coup n'est pas une nouvelle portée.
        }
        if (!policy.allowsSpawnAt(parent.getLocation()) || !context.passesGate()) {
            return;
        }
        SpecialMobDefinition def = context.definition();
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
            context.markActivated();
        }
    }

    /** Borné à un rayon fixe autour du parent (jamais un scan global). */
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
}
