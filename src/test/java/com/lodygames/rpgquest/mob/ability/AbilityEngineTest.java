package com.lodygames.rpgquest.mob.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.mob.MobSpawnSettingsStore;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #542 — {@link AbilityEngine} : champs communs (activation, chance, délai), politique de zones,
 * plafond de renforts, nettoyage à la mort, renforts retirés avec l'invocateur (Q7), état de combat (Q4).
 * Même contournement MockBukkit que {@code SplitOnHitExecutorTest} : identité posée en PDC, jamais
 * {@code SpecialMobService#apply}.
 */
@SuppressWarnings("removal") // EntityDamageByEntityEvent(Entity, Entity, DamageCause, double) : voir ZoneProtectionListenerTest.
class AbilityEngineTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private Path mobsDir;
    private SpecialMobRegistry registry;
    private SpecialMobService service;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private double nextRandom = 0.0;
    private final RandomGenerator random = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public double nextDouble() {
            return nextRandom;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");
        mobsDir = tempDir.resolve("mobs");
        Files.createDirectories(mobsDir);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SpecialMobDefinition load(String abilityYaml) throws Exception {
        Files.writeString(mobsDir.resolve("summoner.yml"), """
                id: rpgquest:summoner
                category: BOSS
                entity-type: ZOMBIE
                name: "Invocateur"
                spawn-chance: 0
                health: 200
                abilities:
                """ + abilityYaml);
        registry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        registry.reload();
        Path itemsDir = tempDir.resolve("items");
        Files.createDirectories(itemsDir);
        YamlCustomItemRegistry items = new YamlCustomItemRegistry(itemsDir, plugin.getSLF4JLogger());
        items.start();
        service = new SpecialMobService(plugin, registry, new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger()),
                items, plugin.getSLF4JLogger(), new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger()), world -> true);
        return registry.find(new NamespacedKey("rpgquest", "summoner")).orElseThrow();
    }

    private static String summon(int amount, double chance, int cooldown, int maxAlive, String extra) {
        return "  - type: SUMMON_ON_DAMAGE\n    summon-entity-type: SKELETON\n    amount: " + amount
                + "\n    chance: " + chance + "\n    cooldown-seconds: " + cooldown + "\n    max-alive: " + maxAlive
                + "\n" + extra;
    }

    private AbilityEngine engine(AbilityWorldPolicy policy) {
        return new AbilityEngine(plugin, registry, service, policy, random, now::get);
    }

    private LivingEntity spawnSummoner(SpecialMobDefinition def) {
        LivingEntity mob = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        mob.getPersistentDataContainer().set(service.pdcKey(), PersistentDataType.STRING, def.id().asString());
        return mob;
    }

    private void hit(AbilityEngine engine, LivingEntity mob, PlayerMock attacker) {
        engine.onDamage(new EntityDamageByEntityEvent(attacker, mob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0));
    }

    private int skeletons() {
        return world.getEntitiesByClass(Skeleton.class).size();
    }

    private void die(AbilityEngine engine, LivingEntity mob) {
        engine.onDeath(new EntityDeathEvent(mob, DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>()));
    }

    @Test
    void cooldownIsHeldByTheEngineAndForgottenAtDeath() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(2, 1.0, 20, 10, "")));
        AbilityEngine engine = engine(AbilityWorldPolicy.unrestricted());
        PlayerMock player = server.addPlayer();

        hit(engine, mob, player);
        assertEquals(2, skeletons());
        hit(engine, mob, player);
        assertEquals(2, skeletons(), "délai de 20 s : pas de nouvelle invocation");
        now.addAndGet(20_000L);
        hit(engine, mob, player);
        assertEquals(4, skeletons(), "délai écoulé : nouvelle invocation");

        assertEquals(1, engine.trackedCooldowns());
        die(engine, mob);
        assertEquals(0, engine.trackedCooldowns(), "plus aucun délai en mémoire pour un mob mort (fuite d'avant #542)");
    }

    @Test
    void failedChanceRollNeverSummons() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(2, 0.25, 0, 10, "")));
        AbilityEngine engine = engine(AbilityWorldPolicy.unrestricted());
        nextRandom = 0.9;
        hit(engine, mob, server.addPlayer());
        assertEquals(0, skeletons());
        nextRandom = 0.1;
        hit(engine, mob, server.addPlayer());
        assertEquals(2, skeletons());
    }

    @Test
    void disabledAbilityKeepsItsValuesButNeverFires() throws Exception {
        SpecialMobDefinition def = load(summon(2, 1.0, 0, 10, "    enabled: false\n"));
        assertEquals(1, def.abilities().size(), "l'instance désactivée reste dans le profil");
        LivingEntity mob = spawnSummoner(def);
        hit(engine(AbilityWorldPolicy.unrestricted()), mob, server.addPlayer());
        assertEquals(0, skeletons());
    }

    @Test
    void neverSummonsInTheHubWorld() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(2, 1.0, 0, 10, "")));
        AbilityEngine engine = engine(new AbilityWorldPolicy(() -> "WORLD", (w, x, y, z) -> false,
                (w, x, y, z) -> false, e -> false));
        hit(engine, mob, server.addPlayer());
        assertEquals(0, skeletons());
    }

    @Test
    void neverSummonsInAZoneThatForbidsHostiles() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(2, 1.0, 0, 10, "")));
        AbilityEngine engine = engine(new AbilityWorldPolicy(() -> "hub", (w, x, y, z) -> false,
                (w, x, y, z) -> "world".equals(w) && x == 0 && z == 0, e -> false));
        hit(engine, mob, server.addPlayer());
        assertEquals(0, skeletons());
    }

    @Test
    void serverCapLimitsReinforcementsPerSecondAndPerWorld() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(5, 1.0, 0, 20, "")));
        AbilityEngine engine = engine(AbilityWorldPolicy.unrestricted());
        hit(engine, mob, server.addPlayer());
        assertEquals(AbilityLimits.MAX_REINFORCEMENTS_PER_SECOND_PER_WORLD, skeletons());
        hit(engine, mob, server.addPlayer());
        assertEquals(AbilityLimits.MAX_REINFORCEMENTS_PER_SECOND_PER_WORLD, skeletons(), "même seconde : plafond atteint");
        now.addAndGet(1_000L);
        hit(engine, mob, server.addPlayer());
        assertEquals(2 * AbilityLimits.MAX_REINFORCEMENTS_PER_SECOND_PER_WORLD, skeletons());
        assertTrue(engine.limits().refusedCount() > 0);
    }

    @Test
    void reinforcementsStayAtOwnerDeathByDefault() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(2, 1.0, 0, 10, "")));
        AbilityEngine engine = engine(AbilityWorldPolicy.unrestricted());
        hit(engine, mob, server.addPlayer());
        List<Skeleton> summoned = List.copyOf(world.getEntitiesByClass(Skeleton.class));
        die(engine, mob);
        assertTrue(summoned.stream().allMatch(Skeleton::isValid), "Q7 : défaut = comportement d'avant, ils restent");
    }

    @Test
    void reinforcementsDespawnWithOwnerWhenAsked() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(2, 1.0, 0, 10, "    despawn-with-owner: true\n")));
        AbilityEngine engine = engine(AbilityWorldPolicy.unrestricted());
        hit(engine, mob, server.addPlayer());
        List<Skeleton> summoned = List.copyOf(world.getEntitiesByClass(Skeleton.class));
        assertEquals(2, summoned.size());
        die(engine, mob);
        assertTrue(summoned.stream().noneMatch(Skeleton::isValid));
    }

    @Test
    void combatNeedsATargetedPlayerAndARecentExchangeWithinRange() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(1, 1.0, 0, 1, "")));
        AbilityEngine engine = engine(AbilityWorldPolicy.unrestricted());
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 3, 64, 0));
        ((Zombie) mob).setTarget(player);
        CombatState combat = engine.combat();
        java.util.function.Predicate<org.bukkit.entity.Player> valid = engine.policy()::isValidTarget;

        assertFalse(combat.isInCombat(mob, 8, now.get(), valid), "aucun coup échangé");
        engine.onCombatHit(new EntityDamageByEntityEvent(player, mob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0));
        assertTrue(combat.isInCombat(mob, 8, now.get(), valid));
        assertFalse(combat.isInCombat(mob, 2, now.get(), valid), "hors de portée");
        player.setGameMode(GameMode.CREATIVE);
        assertFalse(combat.isInCombat(mob, 8, now.get(), valid), "joueur en créatif : jamais une cible");
        player.setGameMode(GameMode.SURVIVAL);
        now.addAndGet(CombatState.COMBAT_TIMEOUT_MILLIS);
        assertFalse(combat.isInCombat(mob, 8, now.get(), valid), "plus de 10 s sans coup");

        engine.onCombatHit(new EntityDamageByEntityEvent(mob, player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0));
        assertEquals(1, combat.size(), "coup donné par le mob : compte aussi");
        die(engine, mob);
        assertEquals(0, combat.size());
    }

    @Test
    void stopForgetsEverything() throws Exception {
        LivingEntity mob = spawnSummoner(load(summon(1, 1.0, 30, 5, "")));
        AbilityEngine engine = engine(AbilityWorldPolicy.unrestricted());
        PlayerMock player = server.addPlayer();
        hit(engine, mob, player);
        engine.onCombatHit(new EntityDamageByEntityEvent(player, mob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0));
        engine.stop();
        assertEquals(0, engine.trackedCooldowns());
        assertEquals(0, engine.combat().size());
    }

    @Test
    void oneActionAtATimePerMobAndAGlobalCap() {
        AbilityLimits limits = new AbilityLimits();
        java.util.UUID first = java.util.UUID.randomUUID();
        assertTrue(limits.tryBeginAction(first));
        assertFalse(limits.tryBeginAction(first), "Q5 : une seule action en cours par mob");
        for (int i = 1; i < AbilityLimits.MAX_ACTIONS_IN_PROGRESS; i++) {
            assertTrue(limits.tryBeginAction(java.util.UUID.randomUUID()));
        }
        assertFalse(limits.tryBeginAction(java.util.UUID.randomUUID()), "plafond global");
        limits.endAction(first);
        assertTrue(limits.tryBeginAction(java.util.UUID.randomUUID()));
    }
}
