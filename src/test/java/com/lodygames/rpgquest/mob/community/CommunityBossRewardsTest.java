package com.lodygames.rpgquest.mob.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.mob.model.BossMode;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #176 — participation (coups effectifs mémorisés sur l'entité) et déclaration des droits à la
 * mort, contre MockBukkit. Le service de droits à récompense est remplacé par un puits en mémoire :
 * la remise elle-même (au plus une fois, inventaire plein) est couverte par les tests de #422.
 */
class CommunityBossRewardsTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World wild;
    private World hub;
    private NamespacedKey instanceKey;
    private FakeSink sink;
    private List<String> profileRewards;
    private CommunityBossRewards rewards;
    private Zombie boss;
    private int radius;
    private long now;
    private final List<UUID> activeBosses = new ArrayList<>();

    /** Puits en mémoire : déclarations reçues et joueurs dont la remise a été demandée. */
    private static final class FakeSink implements CommunityBossRewards.RewardSink {
        boolean enabled = true;
        int failuresBeforeSuccess;
        final List<RewardEntitlement.Declaration> declared = new ArrayList<>();
        final List<UUID> delivered = new ArrayList<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String label(String rewardKey) {
            return "boss_ogre".equals(rewardKey) ? "Coffre de l'ogre" : null;
        }

        @Override
        public CompletableFuture<Integer> declare(List<RewardEntitlement.Declaration> declarations) {
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--;
                return CompletableFuture.failedFuture(new IllegalStateException("base indisponible"));
            }
            declared.addAll(declarations);
            return CompletableFuture.completedFuture(declarations.size());
        }

        @Override
        public void deliverSoon(List<UUID> players) {
            delivered.addAll(players);
        }

        @Override
        public Optional<com.lodygames.rpgquest.entitlement.reward.RewardTable> table(String id) {
            return Optional.ofNullable(tables.get(id));
        }

        final java.util.Map<String, com.lodygames.rpgquest.entitlement.reward.RewardTable> tables =
                new java.util.HashMap<>();
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        wild = server.addSimpleWorld("wild");
        hub = server.addSimpleWorld("hub");
        instanceKey = new NamespacedKey(plugin, CommunityBossService.INSTANCE_KEY_NAME);
        sink = new FakeSink();
        profileRewards = new ArrayList<>(List.of("boss_ogre"));
        radius = 100;
        now = 1_000L;
        rewards = new CommunityBossRewards(plugin, plugin.getSLF4JLogger(), instanceKey,
                id -> Optional.of(profile()), sink, () -> radius, () -> List.copyOf(activeBosses), () -> now);
        server.getPluginManager().registerEvents(rewards, plugin);
        boss = communityBoss(0.5);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Zombie communityBoss(double x) {
        Zombie zombie = wild.spawn(new Location(wild, x, 64, 0.5), Zombie.class);
        zombie.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, zombie.getUniqueId().toString());
        activeBosses.add(zombie.getUniqueId());
        return zombie;
    }

    private SpecialMobDefinition profile() {
        return new SpecialMobDefinition(new NamespacedKey("rpgquest", "ogre"), MobCategory.BOSS, true,
                EntityType.ZOMBIE, "Ogre", 0.0, Set.of(), Set.of(), Set.of(), 200.0, null, null, null, null, null,
                null, null, null, List.of(), List.of(), null, null, BossMode.COMMUNITY, 1, profileRewards);
    }

    private CommunityBossInstance instance() {
        return new CommunityBossInstance(boss.getUniqueId(), "rpgquest:ogre", "Ogre", "wild", 0, 64, 0,
                "minecraft:plains", null, null, 0, null, 0L, false);
    }

    private PlayerMock fighter(String name) {
        PlayerMock player = server.addPlayer(name);
        player.teleport(new Location(wild, 3.5, 64, 0.5));
        return player;
    }

    private EntityDamageByEntityEvent hit(Entity damager, LivingEntity victim, double damage) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(damager, victim,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).withCausingEntity(damager).withDirectEntity(damager).build(),
                damage);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private void defeat() {
        rewards.onDefeated(instance(), boss, null);
        server.getScheduler().performTicks(1);
    }

    private List<BossParticipation.Verdict> verdicts() {
        return rewards.recentDefeats().get(0).participants().stream().map(BossParticipation.Evaluated::verdict)
                .toList();
    }

    /** Mort réelle (PlayerDeathEvent), le temps passe, puis réapparition près du boss. */
    private void dieAndRespawn(PlayerMock player) {
        now += 1_000;
        player.setHealth(0);
        now += 1_000;
        player.respawn();
        player.teleport(new Location(wild, 3.5, 64, 0.5));
    }

    private static String messages(PlayerMock p) {
        StringBuilder out = new StringBuilder();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            out.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
        }
        return out.toString();
    }

    @Test
    void oneEffectiveHitIsEnoughAndTheFinalBlowIsNotRequired() {
        PlayerMock alice = fighter("Alice");
        PlayerMock bob = fighter("Bob");
        hit(alice, boss, 1.0);
        hit(alice, boss, 4.0); // un second coup n'inscrit pas deux fois
        hit(bob, boss, 0.5);

        rewards.onDefeated(instance(), boss, bob); // Bob porte le coup final, Alice non
        server.getScheduler().performTicks(1);

        assertEquals(Set.of(alice.getUniqueId(), bob.getUniqueId()),
                Set.copyOf(sink.declared.stream().map(RewardEntitlement.Declaration::playerId).toList()));
        assertEquals(2, sink.declared.size());
        assertEquals(Set.of(alice.getUniqueId(), bob.getUniqueId()), Set.copyOf(sink.delivered));
        assertTrue(messages(alice).contains("Participation reconnue"));
        assertEquals(2, rewards.recentDefeats().get(0).participants().size());
    }

    @Test
    void projectilesCountForTheirShooter() {
        PlayerMock archer = fighter("Archer");
        Arrow arrow = wild.spawn(new Location(wild, 2.5, 65, 0.5), Arrow.class);
        arrow.setShooter(archer);
        hit(arrow, boss, 3.0);

        assertEquals(Set.of(archer.getUniqueId()), rewards.participants(boss));
    }

    @Test
    void cancelledOrZeroDamageHitsDoNotCount() {
        PlayerMock alice = fighter("Alice");
        EntityDamageByEntityEvent cancelled = new EntityDamageByEntityEvent(alice, boss,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).withCausingEntity(alice).withDirectEntity(alice).build(), 5.0);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        hit(alice, boss, 0.0);

        assertTrue(rewards.participants(boss).isEmpty());
        defeat();
        assertTrue(sink.declared.isEmpty());
    }

    @Test
    void hitsOnAnOrdinaryMobAreNeverRecorded() {
        PlayerMock alice = fighter("Alice");
        Zombie ordinary = wild.spawn(new Location(wild, 5.5, 64, 0.5), Zombie.class);
        hit(alice, ordinary, 5.0);
        assertTrue(rewards.participants(ordinary).isEmpty());
    }

    @Test
    void deadOfflineOtherWorldAndTooFarParticipantsAreExcluded() {
        PlayerMock dead = fighter("Mort");
        PlayerMock gone = fighter("Parti");
        PlayerMock away = fighter("Ailleurs");
        PlayerMock far = fighter("Loin");
        PlayerMock winner = fighter("Present");
        for (PlayerMock p : List.of(dead, gone, away, far, winner)) {
            hit(p, boss, 2.0);
        }
        dead.setHealth(0);
        gone.disconnect();
        away.teleport(new Location(hub, 0.5, 64, 0.5));
        far.teleport(new Location(wild, 150.5, 64, 0.5));
        messages(far);

        defeat();

        assertEquals(List.of(winner.getUniqueId()), sink.declared.stream().map(RewardEntitlement.Declaration::playerId).toList());
        // Le mort a perdu ses coups (#176, 16/10) : il n'est plus participant du tout.
        assertTrue(rewards.recentDefeats().get(0).participants().stream()
                .noneMatch(e -> e.playerId().equals(dead.getUniqueId())));
        assertEquals(List.of(BossParticipation.Verdict.OFFLINE, BossParticipation.Verdict.OTHER_WORLD,
                BossParticipation.Verdict.TOO_FAR, BossParticipation.Verdict.ELIGIBLE), verdicts());
        assertTrue(messages(far).contains("trop loin du boss (150 blocs, 100 au plus)"));
        assertEquals(100, rewards.recentDefeats().get(0).radius());
    }

    @Test
    void theBiomeNoLongerMattersOnlyTheConfiguredDistance() {
        // À 3 blocs, mais de l'autre côté d'une frontière de biome : éligible.
        wild.setBiome(3, 64, 0, Biome.FOREST);
        PlayerMock border = fighter("Frontiere");
        PlayerMock edge = fighter("Limite");
        PlayerMock beyond = fighter("Dehors");
        for (PlayerMock p : List.of(border, edge, beyond)) {
            hit(p, boss, 2.0);
        }
        edge.teleport(new Location(wild, 100.5, 64, 0.5)); // exactement 100 blocs
        beyond.teleport(new Location(wild, 101.5, 64, 0.5)); // 101 blocs

        defeat();

        assertEquals(List.of(BossParticipation.Verdict.ELIGIBLE, BossParticipation.Verdict.ELIGIBLE,
                BossParticipation.Verdict.TOO_FAR), verdicts());
    }

    @Test
    void theRadiusIsReadFromTheSettingsAtEachDefeat() {
        PlayerMock far = fighter("Loin");
        hit(far, boss, 2.0);
        far.teleport(new Location(wild, 150.5, 64, 0.5));
        radius = 200;

        defeat();

        assertEquals(List.of(BossParticipation.Verdict.ELIGIBLE), verdicts());
        assertEquals(200, rewards.recentDefeats().get(0).radius());
    }

    @Test
    void aPlayerWhoDiedAndCameBackWithoutHittingIsExcluded() {
        PlayerMock alice = fighter("Alice");
        PlayerMock bob = fighter("Bob");
        hit(alice, boss, 2.0);
        hit(bob, boss, 2.0);
        dieAndRespawn(alice);

        assertEquals(Set.of(bob.getUniqueId()), rewards.participants(boss), "coups effacés du boss chargé");
        defeat();

        assertEquals(List.of(bob.getUniqueId()), sink.declared.stream().map(RewardEntitlement.Declaration::playerId)
                .toList());
    }

    @Test
    void aPlayerWhoDiedThenHitAgainIsEligible() {
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);
        dieAndRespawn(alice);
        hit(alice, boss, 2.0);

        defeat();

        assertEquals(List.of(BossParticipation.Verdict.ELIGIBLE), verdicts());
        assertEquals(1, sink.declared.size());
    }

    @Test
    void aDeathWhileTheBossIsUnloadedStillCancelsOlderHits() {
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);
        activeBosses.clear(); // boss déchargé : seule l'heure de mort inscrite sur le joueur témoigne
        dieAndRespawn(alice);
        assertEquals(Set.of(alice.getUniqueId()), rewards.participants(boss));

        defeat();
        assertEquals(List.of(BossParticipation.Verdict.NO_HIT_SINCE_DEATH), verdicts());
        assertTrue(sink.declared.isEmpty());
        assertTrue(messages(alice).contains("mort pendant le combat, sans nouveau coup ensuite"));

        now += 1_000;
        hit(alice, boss, 2.0); // nouveau coup : la participation revient
        defeat();
        assertEquals(List.of(BossParticipation.Verdict.ELIGIBLE), verdicts());
    }

    @Test
    void reconnectingOrChangingWorldDoesNotEraseTheParticipation() {
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);
        alice.teleport(new Location(hub, 0.5, 64, 0.5));
        alice.teleport(new Location(wild, 3.5, 64, 0.5));
        alice.disconnect();
        alice.reconnect();
        alice.teleport(new Location(wild, 3.5, 64, 0.5));

        defeat();

        assertEquals(List.of(BossParticipation.Verdict.ELIGIBLE), verdicts());
    }

    @Test
    void aDeathClearsTheParticipationOnEveryActiveBossOnly() {
        PlayerMock alice = fighter("Alice");
        PlayerMock bob = fighter("Bob");
        Zombie second = communityBoss(10.5);
        hit(alice, boss, 2.0);
        hit(alice, second, 2.0);
        hit(bob, second, 2.0);
        dieAndRespawn(alice);

        assertTrue(rewards.participants(boss).isEmpty());
        assertEquals(Set.of(bob.getUniqueId()), rewards.participants(second));
    }

    @Test
    void participationSurvivesOnTheEntityAndIsNeverSharedWithAnotherBoss() {
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);
        Zombie other = wild.spawn(new Location(wild, 6.5, 64, 0.5), Zombie.class);
        other.getPersistentDataContainer().set(instanceKey, PersistentDataType.STRING, other.getUniqueId().toString());

        assertEquals(Set.of(alice.getUniqueId()), rewards.participants(boss));
        assertTrue(rewards.participants(other).isEmpty());
    }

    @Test
    void nothingIsDeclaredWithoutRewardsOrWhenEntitlementsAreDisabled() {
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);
        sink.enabled = false;
        defeat();
        assertTrue(sink.declared.isEmpty());
        assertTrue(rewards.recentDefeats().get(0).rewardStatus().contains("désactivés"));

        sink.enabled = true;
        profileRewards.clear();
        defeat();
        assertTrue(sink.declared.isEmpty());
        assertTrue(messages(alice).isEmpty(), "aucune récompense configurée : rien n'est promis");
    }

    @Test
    void aFailedDeclarationIsRetriedWithTheSameKeys() {
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);
        sink.failuresBeforeSuccess = 2;

        defeat();
        assertTrue(sink.declared.isEmpty());
        server.getScheduler().performTicks(20L * 5 + 2);
        server.getScheduler().performTicks(20L * 5 + 2);

        assertEquals(1, sink.declared.size());
        assertTrue(rewards.recentDefeats().get(0).rewardStatus().contains("1 droit(s) déclaré(s)"));
    }

    @Test
    void anUnknownCatalogKeyIsStillDeclaredSoItIsNeverLost() {
        PlayerMock alice = fighter("Alice");
        profileRewards.add("pas_au_catalogue");
        hit(alice, boss, 2.0);
        defeat();
        assertEquals(List.of("boss_ogre", "pas_au_catalogue"),
                sink.declared.stream().map(RewardEntitlement.Declaration::rewardKey).toList());
    }

    // ---- issue #177 : tirages par participant ------------------------------------------------

    private static com.lodygames.rpgquest.entitlement.reward.RewardTable certain(String id, boolean enabled) {
        return new com.lodygames.rpgquest.entitlement.reward.RewardTable(id, "Butin de l'ogre",
                com.lodygames.rpgquest.entitlement.reward.RewardTable.Mode.SINGLE, enabled,
                List.of(new com.lodygames.rpgquest.entitlement.reward.RewardTable.Entry("gem", "boss_ogre",
                        com.lodygames.rpgquest.entitlement.reward.RewardTable.FULL)));
    }

    @Test
    void aTableIsDrawnOncePerEligibleParticipantWithPersonalKeys() {
        sink.tables.put("ogre_loot", certain("ogre_loot", true));
        profileRewards.clear();
        profileRewards.add("table:ogre_loot");
        PlayerMock alice = fighter("Alice");
        PlayerMock bob = fighter("Bob");
        hit(alice, boss, 2.0);
        hit(bob, boss, 2.0);

        defeat();

        assertEquals(2, sink.declared.size());
        for (RewardEntitlement.Declaration d : sink.declared) {
            assertEquals("boss_ogre", d.rewardKey());
            assertEquals("boss:" + boss.getUniqueId() + ":ogre_loot.gem:" + d.playerId(), d.idempotencyKey());
            assertTrue(d.label().contains("table ogre_loot v"), d.label());
        }
        assertTrue(messages(alice).contains("Coffre de l'ogre"));
        assertEquals(2, rewards.recentDefeats().get(0).draws().size());
    }

    @Test
    void guaranteedAndRandomRewardsCombineAndARetryRedrawsTheSame() {
        sink.tables.put("ogre_loot", certain("ogre_loot", true));
        profileRewards.add("table:ogre_loot");
        sink.failuresBeforeSuccess = 1;
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);

        defeat();
        server.getScheduler().performTicks(20L * 5 + 2);

        assertEquals(List.of("boss:" + boss.getUniqueId() + ":boss_ogre:" + alice.getUniqueId(),
                        "boss:" + boss.getUniqueId() + ":ogre_loot.gem:" + alice.getUniqueId()),
                sink.declared.stream().map(RewardEntitlement.Declaration::idempotencyKey).toList());
    }

    @Test
    void anUnknownOrDisabledTableDrawsNothingAndIsTraced() {
        sink.tables.put("off", certain("off", false));
        profileRewards.clear();
        profileRewards.add("table:off");
        profileRewards.add("table:missing");
        PlayerMock alice = fighter("Alice");
        hit(alice, boss, 2.0);

        defeat();

        assertTrue(sink.declared.isEmpty());
        List<String> draws = rewards.recentDefeats().get(0).draws();
        assertTrue(draws.get(0).contains("désactivée"), draws.toString());
        assertTrue(draws.get(1).contains("inconnue"), draws.toString());
    }
}
