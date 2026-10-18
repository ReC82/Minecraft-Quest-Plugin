package com.lodygames.rpgquest.progression;

import com.lodygames.rpgquest.config.ProgressionConfig;
import com.lodygames.rpgquest.progression.model.SkillType;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Issue #532 — règle unique des conditions {@code required-level} (portails, offres marchandes).
 * Depuis #183, le niveau vanilla ne progresse plus quand {@code progression.vanilla-xp-progression}
 * vaut {@code false} (défaut) : la condition lit alors le niveau RPG {@code GLOBAL}, tenu en cache
 * mémoire par {@link ProgressionService} (aucun accès SQL). Avec la clé à {@code true}, le niveau
 * vanilla reste la référence. Sans branchement (tests), niveau vanilla.
 */
@FunctionalInterface
public interface RequiredLevelRule {

    /** Niveau vanilla, l'ancien comportement. */
    RequiredLevelRule VANILLA = Player::getLevel;

    /** Niveau comparé à {@code required-level} pour ce joueur. */
    int levelOf(Player player);

    /** {@code true} si aucune condition de niveau ({@code null}) ou si le joueur l'atteint. */
    default boolean meets(Player player, Integer requiredLevel) {
        return requiredLevel == null || levelOf(player) >= requiredLevel;
    }

    /** Niveau RPG {@code GLOBAL}, ou vanilla quand {@code progression.vanilla-xp-progression} est actif. */
    static RequiredLevelRule configured(ProgressionService progression, Supplier<ProgressionConfig> config) {
        return player -> config.get().keepVanillaXp()
                ? player.getLevel()
                : progression.level(player.getUniqueId(), SkillType.GLOBAL);
    }
}
