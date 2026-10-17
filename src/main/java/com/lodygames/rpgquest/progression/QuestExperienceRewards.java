package com.lodygames.rpgquest.progression;

import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.progression.model.SkillType;
import com.lodygames.rpgquest.quest.progress.ExperienceRewardSink;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Issue #183 — récompense de quête {@code EXPERIENCE} : XP RPG (piste {@code GLOBAL}) quand la
 * progression vanilla est supprimée ({@code progression.vanilla-xp-progression: false}), XP vanilla
 * sinon. L'octroi RPG passe par {@link ProgressionService#awardXp} avec le jeton de complétion
 * comme id d'événement : une remise rejouée ne paie jamais deux fois.
 */
public final class QuestExperienceRewards implements ExperienceRewardSink {

    private final ProgressionService progression;
    private final Supplier<ProgressionConfig> config;

    public QuestExperienceRewards(ProgressionService progression, Supplier<ProgressionConfig> config) {
        this.progression = progression;
        this.config = config;
    }

    @Override
    public boolean grant(Player player, int amount, String grantId) {
        if (config.get().keepVanillaXp()) {
            return VANILLA.grant(player, amount, grantId);
        }
        progression.awardXp(player.getUniqueId(), SkillType.GLOBAL, amount, "quest_reward", "quest_reward:" + grantId);
        return true;
    }
}
