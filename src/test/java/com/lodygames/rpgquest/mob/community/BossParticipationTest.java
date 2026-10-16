package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import com.lodygames.rpgquest.mob.community.BossParticipation.PlayerSituation;
import com.lodygames.rpgquest.mob.community.BossParticipation.Verdict;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #176 — règles pures d'éligibilité, de clé d'idempotence et de mémorisation. */
class BossParticipationTest {

    private static final String WORLD = "wild";
    private static final String BIOME = "minecraft:plains";
    private static final UUID BOSS = UUID.fromString("00000000-0000-0000-0000-00000000b055");

    private static CommunityBossInstance instance() {
        return new CommunityBossInstance(BOSS, "rpgquest:ogre", "Ogre", WORLD, 0, 64, 0, "minecraft:forest",
                null, null, 0, null, 0L, false);
    }

    @Test
    void verdictFollowsTheCumulativeConditionsInOrder() {
        assertEquals(Verdict.ELIGIBLE, BossParticipation.verdict(new PlayerSituation(true, false, WORLD, BIOME), WORLD, BIOME));
        assertEquals(Verdict.OFFLINE, BossParticipation.verdict(PlayerSituation.OFFLINE, WORLD, BIOME));
        assertEquals(Verdict.OFFLINE, BossParticipation.verdict(null, WORLD, BIOME));
        assertEquals(Verdict.DEAD, BossParticipation.verdict(new PlayerSituation(true, true, WORLD, BIOME), WORLD, BIOME));
        assertEquals(Verdict.OTHER_WORLD, BossParticipation.verdict(new PlayerSituation(true, false, "hub", BIOME), WORLD, BIOME));
        assertEquals(Verdict.OTHER_BIOME,
                BossParticipation.verdict(new PlayerSituation(true, false, WORLD, "minecraft:forest"), WORLD, BIOME));
    }

    @Test
    void theBiomeComparedIsTheBossBiomeAtDeathNotTheSpawnBiome() {
        // L'instance est apparue en forêt ; le boss meurt en plaine : c'est la plaine qui compte.
        UUID alice = UUID.randomUUID();
        List<BossParticipation.Evaluated> evaluated = BossParticipation.evaluate(Set.of(alice), WORLD, BIOME,
                id -> new PlayerSituation(true, false, WORLD, BIOME), id -> "Alice");
        assertEquals(Verdict.ELIGIBLE, evaluated.get(0).verdict());
        assertEquals(1, BossParticipation.declarations(instance(), evaluated, List.of("boss_ogre"), k -> k).size());
    }

    @Test
    void everyEligibleParticipantGetsOneDeclarationPerRewardAndOthersNone() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        Set<UUID> participants = new LinkedHashSet<>(List.of(alice, bob, carol));
        Map<UUID, PlayerSituation> where = Map.of(
                alice, new PlayerSituation(true, false, WORLD, BIOME),
                bob, new PlayerSituation(true, false, WORLD, BIOME),
                carol, new PlayerSituation(true, true, WORLD, BIOME));
        List<BossParticipation.Evaluated> evaluated = BossParticipation.evaluate(participants, WORLD, BIOME,
                where::get, id -> id.toString().substring(0, 4));

        List<RewardEntitlement.Declaration> declarations = BossParticipation.declarations(instance(), evaluated,
                List.of("boss_ogre", "titre.ogre"), key -> "Lot " + key);

        assertEquals(4, declarations.size());
        assertTrue(declarations.stream().noneMatch(d -> d.playerId().equals(carol)));
        RewardEntitlement.Declaration first = declarations.get(0);
        assertEquals(RewardSource.BOSS, first.source());
        assertEquals("boss:" + BOSS + ":boss_ogre:" + alice, first.idempotencyKey());
        assertEquals("rpgquest:ogre@" + BOSS, first.sourceRef());
        assertEquals("Lot boss_ogre — Ogre", first.label());
    }

    @Test
    void idempotencyKeysAreStableAndFitTheColumn() {
        UUID player = UUID.randomUUID();
        String reward = "a".repeat(64);
        String key = BossParticipation.idempotencyKey(BOSS, reward, player);
        assertEquals(key, BossParticipation.idempotencyKey(BOSS, reward, player));
        assertTrue(key.length() <= RewardEntitlement.MAX_KEY_LENGTH, key.length() + " caractères");
        assertTrue(RewardEntitlement.KEY_PATTERN.matcher(key).matches());
    }

    @Test
    void participantsRoundTripThroughThePdcTextAndTolerateGarbage() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Set<UUID> ids = new LinkedHashSet<>(List.of(a, b));
        assertEquals(ids, BossParticipation.decode(BossParticipation.encode(ids)));
        assertEquals(Set.of(a), BossParticipation.decode(a + ",pas-un-uuid,"));
        assertTrue(BossParticipation.decode(null).isEmpty());
    }
}
