package com.lodygames.rpgquest.mob.model;

/**
 * Fait apparaître des zombies enfants à chaque coup non mortel reçu.
 * {@code maxDepth} borne le nombre de générations (un enfant né à la
 * profondeur maximale ne peut plus se diviser), {@code maxChildrenPerHit}
 * borne le nombre d'enfants créés <strong>par déclenchement</strong> (un seul coup) — les deux
 * ensemble garantissent qu'aucune chaîne de divisions n'est infinie (mission étape 18, point 7).
 *
 * <p>{@code maxAlivePerParent} (issue #190) borne séparément le nombre d'enfants
 * <strong>directs et vivants</strong> d'une même entité à un instant donné : {@code
 * maxChildrenPerHit} à lui seul ne protège pas contre des coups <em>répétés</em> sur la même
 * entité avant sa mort (cas normal au combat) — chacun relançait une division complète, ce qui
 * pouvait produire bien plus que {@code maxChildrenPerHit} descendants directs pour un seul
 * parent. {@code maxAlivePerParent} plafonne ce total, indépendamment du nombre de coups reçus.</p>
 */
public record SplitOnHitAbility(int maxDepth, int maxChildrenPerHit, int maxAlivePerParent) implements MobAbility {

    /** Rétro-compatible : les profils antérieurs à l'issue #190 n'ont pas {@code maxAlivePerParent}. */
    public SplitOnHitAbility(int maxDepth, int maxChildrenPerHit) {
        this(maxDepth, maxChildrenPerHit, 2);
    }

    public SplitOnHitAbility {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth doit être au moins 1 : " + maxDepth);
        }
        if (maxChildrenPerHit < 1) {
            throw new IllegalArgumentException("maxChildrenPerHit doit être au moins 1 : " + maxChildrenPerHit);
        }
        if (maxAlivePerParent < 1) {
            throw new IllegalArgumentException("maxAlivePerParent doit être au moins 1 : " + maxAlivePerParent);
        }
    }

    @Override
    public MobAbilityType type() {
        return MobAbilityType.SPLIT_ON_HIT;
    }
}
