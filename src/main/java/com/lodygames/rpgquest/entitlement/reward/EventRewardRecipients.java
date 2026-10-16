package com.lodygames.rpgquest.entitlement.reward;

import com.lodygames.rpgquest.database.StatisticsRepository.ArchivedValue;
import com.lodygames.rpgquest.stats.EventReward;
import com.lodygames.rpgquest.stats.StatMetric;
import com.lodygames.rpgquest.stats.StatPeriod;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Calcule, depuis l'archive <strong>figée</strong> d'un événement (#57), les droits à récompense qu'il
 * ouvre (issue #422, docs/REWARD_ENTITLEMENTS.md §6.1). Fonction pure : aucune base, aucun Bukkit.
 *
 * <ul>
 *   <li>{@code rank: N} : rang « compétition » ≤ N (ex-aequo inclus), dans le sens de la règle de
 *       classement ;</li>
 *   <li>{@code threshold: N} : valeur figée ≥ N ;</li>
 *   <li>{@code community: true} : chaque contributeur, si la somme atteint {@code goal}.</li>
 * </ul>
 *
 * <p>Les joueurs exclus par la modération ne gagnent rien et ne comptent ni dans les rangs ni dans
 * la somme commune ; une valeur nulle ne gagne jamais.</p>
 */
public final class EventRewardRecipients {

    private EventRewardRecipients() {
    }

    /**
     * @param values valeurs figées de la <em>première</em> métrique de l'événement
     */
    public static List<RewardEntitlement.Declaration> declarations(StatPeriod event, List<ArchivedValue> values,
                                                                   Set<java.util.UUID> excluded) {
        List<ArchivedValue> eligible = values.stream()
                .filter(value -> value.value() > 0 && !excluded.contains(value.player()))
                .toList();
        List<RewardEntitlement.Declaration> declarations = new ArrayList<>();
        for (EventReward reward : event.rules().rewards()) {
            for (ArchivedValue winner : winners(event, reward, eligible)) {
                declarations.add(new RewardEntitlement.Declaration(idempotencyKey(event, reward, winner.player()),
                        winner.player(), RewardSource.EVENT, event.id(), reward.entitlement(), reward.label()));
            }
        }
        return declarations;
    }

    private static List<ArchivedValue> winners(StatPeriod event, EventReward reward, List<ArchivedValue> eligible) {
        return switch (reward.kind()) {
            case THRESHOLD -> eligible.stream().filter(value -> value.value() >= reward.value()).toList();
            case COMMUNITY -> {
                long goal = event.rules().goal();
                long total = eligible.stream().mapToLong(ArchivedValue::value).sum();
                yield goal > 0 && total >= goal ? eligible : List.of();
            }
            case RANK -> {
                boolean ascending = event.rules().ranking().order() == StatMetric.SortOrder.ASC;
                yield eligible.stream().filter(value -> rank(value, eligible, ascending) <= reward.value()).toList();
            }
        };
    }

    /** Rang « compétition » : 1 + le nombre de joueurs strictement mieux classés. */
    private static long rank(ArchivedValue candidate, List<ArchivedValue> all, boolean ascending) {
        return 1 + all.stream()
                .filter(other -> ascending ? other.value() < candidate.value() : other.value() > candidate.value())
                .count();
    }

    /**
     * {@code event:<id>:<rank|threshold>:<N>:<récompense>:<uuid>} ou {@code event:<id>:community:<récompense>:<uuid>}.
     * Stable si le fichier est réordonné, distincte si un même critère donne deux récompenses. Au-delà
     * de {@link RewardEntitlement#MAX_KEY_LENGTH}, le préfixe est remplacé par son empreinte SHA-256,
     * toujours aussi stable.
     */
    static String idempotencyKey(StatPeriod event, EventReward reward, java.util.UUID player) {
        String criterion = reward.kind().name().toLowerCase(Locale.ROOT)
                + (reward.kind() == EventReward.Kind.COMMUNITY ? "" : ":" + reward.value());
        String prefix = "event:" + event.id() + ":" + criterion + ":" + reward.entitlement();
        String key = prefix + ":" + player;
        if (key.length() <= RewardEntitlement.MAX_KEY_LENGTH) {
            return key;
        }
        return "event:h" + sha256(prefix).substring(0, 40) + ":" + player;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
