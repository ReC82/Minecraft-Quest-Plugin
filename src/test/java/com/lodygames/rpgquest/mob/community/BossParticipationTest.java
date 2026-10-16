package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import com.lodygames.rpgquest.mob.community.BossParticipation.BossDeath;
import com.lodygames.rpgquest.mob.community.BossParticipation.PlayerSituation;
import com.lodygames.rpgquest.mob.community.BossParticipation.Verdict;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #176 — règles pures d'éligibilité (rayon, mort pendant le combat), de clé d'idempotence et de mémorisation. */
class BossParticipationTest {

    private static final String WORLD = "wild";
    private static final UUID BOSS = UUID.fromString("00000000-0000-0000-0000-00000000b055");
    private static final BossDeath DEATH = new BossDeath(WORLD, 0.5, 64, 0.5, 100);
    private static final long HIT = 1_000L;

    private static CommunityBossInstance instance() {
        return new CommunityBossInstance(BOSS, "rpgquest:ogre", "Ogre", WORLD, 0, 64, 0, "minecraft:forest",
                null, null, 0, null, 0L, false);
    }

    private static PlayerSituation at(double dx, double dy, double dz) {
        return new PlayerSituation(true, false, WORLD, 0.5 + dx, 64 + dy, 0.5 + dz, 0L);
    }

    @Test
    void verdictFollowsTheCumulativeConditionsInOrder() {
        assertEquals(Verdict.ELIGIBLE, BossParticipation.verdict(at(3, 0, 0), HIT, DEATH));
        assertEquals(Verdict.OFFLINE, BossParticipation.verdict(PlayerSituation.OFFLINE, HIT, DEATH));
        assertEquals(Verdict.OFFLINE, BossParticipation.verdict(null, HIT, DEATH));
        assertEquals(Verdict.DEAD, BossParticipation.verdict(
                new PlayerSituation(true, true, WORLD, 0.5, 64, 0.5, 0L), HIT, DEATH));
        assertEquals(Verdict.OTHER_WORLD, BossParticipation.verdict(
                new PlayerSituation(true, false, "hub", 0.5, 64, 0.5, 0L), HIT, DEATH));
        assertEquals(Verdict.TOO_FAR, BossParticipation.verdict(at(150, 0, 0), HIT, DEATH));
    }

    @Test
    void theRadiusIsInclusiveAndThreeDimensional() {
        assertEquals(Verdict.ELIGIBLE, BossParticipation.verdict(at(100, 0, 0), HIT, DEATH));
        assertEquals(Verdict.TOO_FAR, BossParticipation.verdict(at(101, 0, 0), HIT, DEATH));
        assertEquals(Verdict.ELIGIBLE, BossParticipation.verdict(at(0, 0, -100), HIT, DEATH));
        // 80 blocs à l'horizontale et 80 en hauteur : 113 blocs en ligne droite.
        assertEquals(Verdict.TOO_FAR, BossParticipation.verdict(at(80, 80, 0), HIT, DEATH));
        // Le rayon vient du réglage : 150 blocs suffisent alors.
        assertEquals(Verdict.ELIGIBLE, BossParticipation.verdict(at(150, 0, 0), HIT,
                new BossDeath(WORLD, 0.5, 64, 0.5, 150)));
        // L'autre monde l'emporte, même aux mêmes coordonnées.
        assertEquals(Verdict.OTHER_WORLD, BossParticipation.verdict(
                new PlayerSituation(true, false, "wild_nether", 0.5, 64, 0.5, 0L), HIT, DEATH));
    }

    @Test
    void aDeathAfterTheLastHitCancelsTheParticipationUntilANewHit() {
        // Mort après son coup, revenu dans le rayon sans retoucher : exclu.
        PlayerSituation diedAfterHit = new PlayerSituation(true, false, WORLD, 3.5, 64, 0.5, HIT + 500);
        assertEquals(Verdict.NO_HIT_SINCE_DEATH, BossParticipation.verdict(diedAfterHit, HIT, DEATH));
        // Nouveau coup après la réapparition : éligible.
        assertEquals(Verdict.ELIGIBLE, BossParticipation.verdict(diedAfterHit, HIT + 900, DEATH));
        // Mort bien avant le combat : sans effet.
        assertEquals(Verdict.ELIGIBLE, BossParticipation.verdict(
                new PlayerSituation(true, false, WORLD, 3.5, 64, 0.5, HIT - 1), HIT, DEATH));
        // Inscription de l'ancien format (sans heure) et mort enregistrée depuis : exclu.
        assertEquals(Verdict.NO_HIT_SINCE_DEATH,
                BossParticipation.verdict(diedAfterHit, BossParticipation.UNKNOWN_HIT_TIME, DEATH));
    }

    @Test
    void evaluationKeepsTheDistanceAndExplainsATooFarExclusion() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Map<UUID, Long> hits = new LinkedHashMap<>();
        hits.put(alice, HIT);
        hits.put(bob, HIT);
        Map<UUID, PlayerSituation> where = Map.of(alice, at(3, 0, 0), bob, at(143, 0, 0));
        List<BossParticipation.Evaluated> evaluated = BossParticipation.evaluate(hits, DEATH, where::get,
                id -> id.equals(alice) ? "Alice" : "Bob");

        assertEquals(Verdict.ELIGIBLE, evaluated.get(0).verdict());
        assertEquals(3.0, evaluated.get(0).distance(), 1e-9);
        assertEquals("trop loin du boss (143 blocs, 100 au plus)", evaluated.get(1).reason(100));
        assertEquals(1, BossParticipation.declarations(instance(), evaluated, List.of("boss_ogre"), k -> k).size());
    }

    @Test
    void everyEligibleParticipantGetsOneDeclarationPerRewardAndOthersNone() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        Map<UUID, Long> hits = new LinkedHashMap<>();
        for (UUID id : List.of(alice, bob, carol)) {
            hits.put(id, HIT);
        }
        Map<UUID, PlayerSituation> where = Map.of(
                alice, at(1, 0, 0),
                bob, at(2, 0, 0),
                carol, new PlayerSituation(true, true, WORLD, 0.5, 64, 0.5, 0L));
        List<BossParticipation.Evaluated> evaluated = BossParticipation.evaluate(hits, DEATH, where::get,
                id -> id.toString().substring(0, 4));

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
    void participantsRoundTripThroughThePdcTextAndTolerateGarbageAndTheOldFormat() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, Long> hits = new LinkedHashMap<>();
        hits.put(a, 1_700_000_000_000L);
        hits.put(b, 42L);
        assertEquals(hits, BossParticipation.decode(BossParticipation.encode(hits)));
        assertEquals(List.of(a, b), List.copyOf(BossParticipation.decode(BossParticipation.encode(hits)).keySet()));
        assertEquals(Map.of(a, 5L), BossParticipation.decode(a + "@5,pas-un-uuid," + b + "@x,"));
        // Ancien format #458 (UUID seuls) : relu, heure inconnue.
        assertEquals(Map.of(a, BossParticipation.UNKNOWN_HIT_TIME), BossParticipation.decode(a.toString()));
        assertTrue(BossParticipation.decode(null).isEmpty());
    }

    @Test
    void theParticipationRadiusIsBoundedAndDefaultsToOneHundred() {
        assertEquals(100, CommunityBossSettings.defaults().participationRadius());
        assertEquals(100, new CommunityBossSettings(true, 15, 1).participationRadius());
        assertEquals(5, new CommunityBossSettings(true, 15, 1, 5).participationRadius());
        assertEquals(500, new CommunityBossSettings(true, 15, 1, 500).participationRadius());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CommunityBossSettings(true, 15, 1, 4));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CommunityBossSettings(true, 15, 1, 501));
    }
}
