package be.lloyd.rpgquest.mob.ability;

import be.lloyd.rpgquest.mob.SpecialMobService;
import be.lloyd.rpgquest.mob.model.MobAbility;
import be.lloyd.rpgquest.mob.model.MobAbilityType;
import be.lloyd.rpgquest.mob.model.SpecialMobDefinition;
import be.lloyd.rpgquest.mob.model.SplitOnHitAbility;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
 * maxChildrenPerHit} borne le nombre d'enfants créés par coup, ce qui
 * garantit ensemble qu'aucune chaîne de divisions n'est infinie (mission
 * point 7). Le nombre d'enfants est en plus borné par {@code max-population}
 * de la définition, si présent.
 */
public final class SplitOnHitAbilityListener implements Listener {

    private final SpecialMobService service;
    private final NamespacedKey depthKey;

    public SplitOnHitAbilityListener(SpecialMobService service) {
        this.service = service;
        this.depthKey = service.splitDepthKey();
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
        World world = parent.getWorld();
        int spawned = 0;
        for (int i = 0; i < ability.maxChildrenPerHit(); i++) {
            if (service.atPopulationLimit(def)) {
                break;
            }
            LivingEntity child = (LivingEntity) world.spawnEntity(parent.getLocation(), def.entityType());
            service.apply(child, def);
            child.getPersistentDataContainer().set(depthKey, PersistentDataType.INTEGER, depth + 1);
            spawned++;
        }
        if (spawned > 0) {
            service.recordAbilityTrigger(MobAbilityType.SPLIT_ON_HIT.name());
        }
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
