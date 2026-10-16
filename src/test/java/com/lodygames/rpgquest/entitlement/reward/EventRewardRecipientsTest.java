package com.lodygames.rpgquest.entitlement.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.database.StatisticsRepository.ArchivedValue;
import com.lodygames.rpgquest.stats.EventRanking;
import com.lodygames.rpgquest.stats.EventReward;
import com.lodygames.rpgquest.stats.EventRules;
import com.lodygames.rpgquest.stats.StatPeriod;
import com.lodygames.rpgquest.stats.StatScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #422 — droits ouverts par l'archive figée d'un événement (§6.1). */
class EventRewardRecipientsTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private static StatPeriod event(String id, EventRanking ranking, long goal, EventReward... rewards) {
        return new StatPeriod(id, StatScope.EVENT, id, Instant.parse("2026-11-07T18:00:00Z"),
                Instant.parse("2026-11-09T22:00:00Z"), List.of("ENTITY_KILL"), StatPeriod.State.PLANNED,
                new EventRules(ranking, Map.of(), goal, List.of(rewards)));
    }

    private static List<ArchivedValue> values() {
        return List.of(new ArchivedValue(A, 30), new ArchivedValue(B, 50), new ArchivedValue(C, 50),
                new ArchivedValue(D, 0));
    }

    private static Set<UUID> winners(List<RewardEntitlement.Declaration> declarations, String rewardKey) {
        return declarations.stream().filter(d -> d.rewardKey().equals(rewardKey))
                .map(RewardEntitlement.Declaration::playerId).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void rankUsesCompetitionRankingWithTies() {
        StatPeriod event = event("zombie_weekend", EventRanking.HIGHEST_TOTAL, 0,
                new EventReward(EventReward.Kind.RANK, 1, "title", "Titre"),
                new EventReward(EventReward.Kind.RANK, 2, "top2", "Top 2"),
                new EventReward(EventReward.Kind.RANK, 3, "top3", "Top 3"));
        List<RewardEntitlement.Declaration> declarations = EventRewardRecipients.declarations(event, values(), Set.of());
        assertEquals(Set.of(B, C), winners(declarations, "title"), "ex-aequo au rang 1");
        assertEquals(Set.of(B, C), winners(declarations, "top2"), "le suivant est 3e, pas 2e");
        assertEquals(Set.of(A, B, C), winners(declarations, "top3"));
        assertTrue(declarations.stream().allMatch(d -> d.source() == RewardSource.EVENT
                && d.sourceRef().equals("zombie_weekend")));
    }

    @Test
    void thresholdAndExclusionsAndZeroValues() {
        StatPeriod event = event("zombie_weekend", EventRanking.HIGHEST_TOTAL, 0,
                new EventReward(EventReward.Kind.THRESHOLD, 40, "hunter", "Chasseur"),
                new EventReward(EventReward.Kind.RANK, 1, "title", "Titre"));
        List<RewardEntitlement.Declaration> declarations =
                EventRewardRecipients.declarations(event, values(), Set.of(C));
        assertEquals(Set.of(B), winners(declarations, "hunter"));
        assertEquals(Set.of(B), winners(declarations, "title"), "un exclu ne compte pas dans les rangs");
    }

    @Test
    void lowestTotalRanksAscending() {
        StatPeriod event = event("chrono", EventRanking.LOWEST_TOTAL, 0,
                new EventReward(EventReward.Kind.RANK, 1, "fastest", "Le plus rapide"));
        assertEquals(Set.of(A), winners(EventRewardRecipients.declarations(event, values(), Set.of()), "fastest"));
    }

    @Test
    void communityRewardsEveryContributorOnlyWhenTheGoalIsReached() {
        EventReward hat = new EventReward(EventReward.Kind.COMMUNITY, 0, "hat", "Chapeau");
        assertEquals(Set.of(A, B, C), winners(EventRewardRecipients.declarations(
                event("peche", EventRanking.COMMUNITY_GOAL, 130, hat), values(), Set.of()), "hat"));
        assertEquals(Set.of(), winners(EventRewardRecipients.declarations(
                event("peche", EventRanking.COMMUNITY_GOAL, 131, hat), values(), Set.of()), "hat"));
        assertEquals(Set.of(), winners(EventRewardRecipients.declarations(
                event("peche", EventRanking.COMMUNITY_GOAL, 130, hat), values(), Set.of(C)), "hat"),
                "un exclu ne compte pas dans la somme");
    }

    @Test
    void idempotencyKeysAreStableDistinctAndBounded() {
        EventReward rank = new EventReward(EventReward.Kind.RANK, 1, "title", "Titre");
        EventReward coins = new EventReward(EventReward.Kind.RANK, 1, "coins", "Pièces");
        StatPeriod event = event("zombie_weekend", EventRanking.HIGHEST_TOTAL, 0, rank, coins);
        assertEquals("event:zombie_weekend:rank:1:title:" + B, EventRewardRecipients.idempotencyKey(event, rank, B));
        assertTrue(!EventRewardRecipients.idempotencyKey(event, rank, B)
                .equals(EventRewardRecipients.idempotencyKey(event, coins, B)));
        assertEquals("event:peche:community:hat:" + A, EventRewardRecipients.idempotencyKey(event("peche",
                EventRanking.COMMUNITY_GOAL, 1, rank), new EventReward(EventReward.Kind.COMMUNITY, 0, "hat", "C"), A));

        StatPeriod longEvent = event("e".repeat(64), EventRanking.HIGHEST_TOTAL, 0, rank);
        EventReward longReward = new EventReward(EventReward.Kind.THRESHOLD, 1_000_000, "r".repeat(64), "L");
        String key = EventRewardRecipients.idempotencyKey(longEvent, longReward, A);
        assertTrue(key.length() <= RewardEntitlement.MAX_KEY_LENGTH, key);
        assertTrue(RewardEntitlement.KEY_PATTERN.matcher(key).matches());
        assertEquals(key, EventRewardRecipients.idempotencyKey(longEvent, longReward, A), "stable");
    }
}
