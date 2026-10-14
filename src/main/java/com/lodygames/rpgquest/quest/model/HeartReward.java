package com.lodygames.rpgquest.quest.model;

import java.util.regex.Pattern;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;

/**
 * Récompense « Cœur supplémentaire » (issue #166) : augmente <strong>durablement</strong> la vie
 * maximale du joueur de {@code hearts} cœurs, soit {@code 2 × hearts} points de vie.
 *
 * <p>Ce n'est ni une absorption temporaire ni une guérison : le droit est écrit en base, par UUID,
 * sous la clé {@link #grantKey()}, et le bonus est recalculé depuis cette table à chaque connexion
 * et réapparition. Un droit ne s'obtient <strong>qu'une fois</strong> par joueur et par clé : une
 * quête répétable, une remise rejouée ou un PNJ renommé ne peuvent pas l'accorder une seconde
 * fois.</p>
 *
 * @param hearts   cœurs accordés, de 1 à {@link #MAX_HEARTS_PER_REWARD}
 * @param grantKey clé stable du droit ({@code null} = l'identifiant de la quête). Partager une clé
 *                 entre plusieurs quêtes (ex. {@code city:central_city}) rend leur cœur commun :
 *                 le premier remis l'emporte, les suivants n'accordent rien.
 */
public record HeartReward(int hearts, @Nullable String grantKey) implements QuestReward {

    /**
     * Borne technique d'une seule récompense : 512 cœurs = 1024 PV, plafond par défaut de
     * l'attribut {@code max_health} côté serveur ({@code attribute.maxHealth.max} de spigot.yml).
     * Au-delà, la valeur ne pourrait de toute façon jamais être appliquée. Ce n'est pas un
     * plafond de gameplay — celui-ci se règle par {@code progression.max-bonus-hearts}.
     */
    public static final int MAX_HEARTS_PER_REWARD = 512;

    /** Format d'une clé de droit : identifiant technique, jamais un nom affiché. */
    public static final Pattern GRANT_KEY = Pattern.compile("[a-z0-9_.:\\-]{1,128}");

    public HeartReward {
        if (hearts <= 0 || hearts > MAX_HEARTS_PER_REWARD) {
            throw new IllegalArgumentException(
                    "hearts doit être compris entre 1 et " + MAX_HEARTS_PER_REWARD + " : " + hearts);
        }
        if (grantKey != null && !GRANT_KEY.matcher(grantKey).matches()) {
            throw new IllegalArgumentException("clé de droit invalide : " + grantKey);
        }
    }

    /** Clé effective du droit pour {@code questId} : la clé explicite, sinon {@code quest:<id>}. */
    public String effectiveGrantKey(NamespacedKey questId) {
        return grantKey != null ? grantKey : "quest:" + questId;
    }

    @Override
    public RewardType type() {
        return RewardType.HEART;
    }
}
