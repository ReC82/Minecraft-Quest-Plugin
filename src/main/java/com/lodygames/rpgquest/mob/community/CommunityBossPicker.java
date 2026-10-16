package com.lodygames.rpgquest.mob.community;

import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Issue #175 — tirage pondéré explicite parmi les profils communautaires éligibles (poids =
 * {@link SpecialMobDefinition#communityWeight()}), jamais « le premier trouvé dans le registre ».
 */
public final class CommunityBossPicker {

    private CommunityBossPicker() {
    }

    public static Optional<SpecialMobDefinition> pick(List<SpecialMobDefinition> eligible, RandomGenerator random) {
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        long total = 0;
        for (SpecialMobDefinition def : eligible) {
            total += def.communityWeight();
        }
        long roll = random.nextLong(total);
        long cumulative = 0;
        for (SpecialMobDefinition def : eligible) {
            cumulative += def.communityWeight();
            if (roll < cumulative) {
                return Optional.of(def);
            }
        }
        return Optional.of(eligible.get(eligible.size() - 1));
    }
}
