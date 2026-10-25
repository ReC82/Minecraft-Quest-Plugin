package com.lodygames.rpgquest.mob.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.mob.MobSpawnSettingsStore;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.quest.model.KillQuestBossObjective;
import com.lodygames.rpgquest.quest.progress.ObjectiveRef;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #171 — apparition, identité, attribution et cycle de vie des boss de quête, contre un serveur
 * MockBukkit. Le moteur de quêtes est remplacé par une couture ({@link QuestBossService.QuestSide}) ;
 * l'emplacement, l'horloge et l'écriture disque (synchrone) sont injectés.
 */
class QuestBossServiceTest {

    private static final NamespacedKey QUEST = new NamespacedKey("rpgquest", "chasse_ogre");
    private static final NamespacedKey OGRE = new NamespacedKey("rpgquest", "ogre");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World wild;
    private SpecialMobRegistry registry;
    private SpecialMobService mobService;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Waypoint> waypoints = new ArrayList<>();
    private final Set<String> discovered = new HashSet<>();
    private final List<QuestBossService> started = new ArrayList<>();
    private final FakeQuests quests = new FakeQuests();
    private final AtomicInteger searches = new AtomicInteger();
    private CompletableFuture<Optional<Location>> nextSpot;
    private PlayerMock alice;

    /** Objectifs en cours par joueur + crédits reçus, comme les exposerait QuestProgressEngine. */
    private static final class FakeQuests implements QuestBossService.QuestSide {
        final Map<UUID, List<ObjectiveRef>> pending = new HashMap<>();
        final List<String> credits = new ArrayList<>();

        @Override
        public boolean isLoaded(UUID playerId) {
            return pending.containsKey(playerId);
        }

        @Override
        public List<ObjectiveRef> pendingQuestBosses(UUID playerId) {
            return pending.getOrDefault(playerId, List.of());
        }

        @Override
        public boolean creditQuestBoss(Player player, NamespacedKey questId, String stepId, int objectiveIndex) {
            List<ObjectiveRef> mine = pending.getOrDefault(player.getUniqueId(), List.of());
            boolean ok = mine.stream().anyMatch(r -> r.questId().equals(questId) && r.stepId().equals(stepId)
                    && r.objectiveIndex() == objectiveIndex);
            if (ok) {
                credits.add(player.getName() + ":" + questId.asString() + ":" + stepId + ":" + objectiveIndex);
                pending.put(player.getUniqueId(), List.of());
            }
            return ok;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        wild = server.addSimpleWorld("wild");

        Path itemsDir = Files.createDirectories(tempDir.resolve("items"));
        YamlCustomItemRegistry items = new YamlCustomItemRegistry(itemsDir, plugin.getSLF4JLogger());
        items.start();
        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());
        Path mobsDir = Files.createDirectories(tempDir.resolve("mobs"));
        Files.writeString(mobsDir.resolve("ogre.yml"), """
                id: rpgquest:ogre
                category: BOSS
                boss-mode: QUEST
                entity-type: ZOMBIE
                name: "<red>Ogre des collines</red>"
                spawn-chance: 0.0
                """);
        Files.writeString(mobsDir.resolve("roi.yml"), """
                id: rpgquest:roi
                category: BOSS
                boss-mode: COMMUNITY
                entity-type: ZOMBIE
                name: "Roi communautaire"
                spawn-chance: 0.0
                """);
        registry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        registry.reload();
        mobService = new SpecialMobService(plugin, registry, zoneRegistry, items, plugin.getSLF4JLogger(),
                new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger()), world -> true);

        waypoints.add(new Waypoint("wp-1", "Pierre aux Corbeaux", "wild", "plains@0,0", "minecraft:plains", 0, 0,
                0, 64, 0, "NORTH", 1, true, Instant.EPOCH));
        alice = server.addPlayer("Alice");
        alice.teleport(new Location(wild, 0, 64, 0));
    }

    @AfterEach
    void tearDown() {
        for (QuestBossService service : started) {
            service.stop();
        }
        MockBukkit.unmock();
    }

    private QuestBossService newService() {
        QuestBossService service = new QuestBossService(plugin, plugin.getSLF4JLogger(), registry, mobService,
                new QuestBossStateStore(tempDir.resolve("state"), plugin.getSLF4JLogger()), quests,
                (def, owner) -> {
                    searches.incrementAndGet();
                    return nextSpot != null ? nextSpot
                            : CompletableFuture.completedFuture(Optional.of(new Location(wild, 50.5, 64, 0.5)));
                },
                () -> waypoints, (player, wp) -> discovered.contains(player + "|" + wp), clock::get, Runnable::run);
        service.applyProfilesWith((entity, def) -> entity.getPersistentDataContainer()
                .set(mobService.pdcKey(), PersistentDataType.STRING, def.id().asString()));
        service.start();
        started.add(service);
        return service;
    }

    private static ObjectiveRef ref(NamespacedKey quest, String step, int index, NamespacedKey mob) {
        return new ObjectiveRef(quest, 0, step, index, new KillQuestBossObjective(mob));
    }

    private void wants(Player player, ObjectiveRef... refs) {
        quests.pending.put(player.getUniqueId(), List.of(refs));
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private String messages(PlayerMock p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            out.add(plain(c));
        }
        return String.join("\n", out);
    }

    private void kill(LivingEntity boss) {
        mobService.onDeath(new EntityDeathEvent(boss, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>()));
        started.getLast().onDeath(new EntityDeathEvent(boss, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>()));
    }

    // ---- Apparition / identité ------------------------------------------------------------------

    @Test
    void acceptedObjectiveSpawnsOneOwnedBossAndTellsTheOwnerWhereItIs() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        messages(alice);

        service.reconcile(alice.getUniqueId());

        assertEquals(1, service.activeInstances().size());
        QuestBossInstance instance = service.activeInstances().get(0);
        assertEquals(alice.getUniqueId(), instance.owner());
        assertEquals("rpgquest:chasse_ogre", instance.questId());
        assertEquals("Pierre aux Corbeaux", instance.waypointName());
        Entity entity = server.getEntity(instance.entityId());
        assertNotNull(entity);
        assertEquals(instance.key(), entity.getPersistentDataContainer()
                .get(service.instanceKey(), PersistentDataType.STRING));

        String text = messages(alice);
        assertTrue(text.contains("Ogre des collines"), text);
        assertTrue(text.contains("Pierre aux Corbeaux"), text);
        assertTrue(text.contains("pas encore découvert"), text);
        assertTrue(plain(service.journalHint(alice.getUniqueId(), QUEST, "chasse", 0).orElseThrow())
                .contains("Pierre aux Corbeaux"));
    }

    @Test
    void repeatedReconcileRetryAndPendingSearchNeverCreateADuplicate() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        nextSpot = new CompletableFuture<>();

        service.reconcile(alice.getUniqueId());
        service.reconcile(alice.getUniqueId()); // double-clic pendant la recherche
        assertEquals(1, searches.get(), "une seule recherche en vol par objectif");
        assertTrue(plain(service.journalHint(alice.getUniqueId(), QUEST, "chasse", 0).orElseThrow())
                .contains("Recherche"));

        nextSpot.complete(Optional.of(new Location(wild, 50.5, 64, 0.5)));
        service.reconcile(alice.getUniqueId());
        service.tick();
        assertEquals(1, service.activeInstances().size());
        assertEquals(1, searches.get());
    }

    @Test
    void discoveredWaypointIsSaidWithoutUnlockingAnything() {
        discovered.add(alice.getUniqueId() + "|wp-1");
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        messages(alice);
        service.reconcile(alice.getUniqueId());
        String text = messages(alice);
        assertTrue(text.contains("déjà découvert"), text);
    }

    @Test
    void noWaypointInRangeGivesRoundedCoordinatesInstead() {
        waypoints.clear();
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        messages(alice);
        service.reconcile(alice.getUniqueId());
        String text = messages(alice);
        assertTrue(text.contains("X ≈ 50"), text);
        assertFalse(text.contains("waypoint déjà"), text);
    }

    @Test
    void communityProfileIsRefusedClearlyAndOnlyOnce() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, new NamespacedKey("rpgquest", "roi")));
        messages(alice);

        service.reconcile(alice.getUniqueId());
        assertTrue(service.activeInstances().isEmpty());
        assertTrue(messages(alice).contains("indisponible"));
        clock.addAndGet(QuestBossService.RETRY_DELAY_MILLIS + 1);
        service.reconcile(alice.getUniqueId());
        assertEquals("", messages(alice), "même problème : pas de nouveau message à chaque essai");
        assertTrue(plain(service.journalHint(alice.getUniqueId(), QUEST, "chasse", 0).orElseThrow())
                .contains("indisponible"));
    }

    @Test
    void noSafeSpotIsReportedAndRetriedAfterTheDelay() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        nextSpot = CompletableFuture.completedFuture(Optional.empty());
        messages(alice);

        service.reconcile(alice.getUniqueId());
        assertTrue(messages(alice).contains("aucun emplacement sûr"));
        service.reconcile(alice.getUniqueId());
        assertEquals(1, searches.get(), "pas de nouvel essai avant le délai");

        nextSpot = null;
        clock.addAndGet(QuestBossService.RETRY_DELAY_MILLIS + 1);
        service.tick();
        assertEquals(1, service.activeInstances().size());
    }

    @Test
    void offlineOrUnloadedPlayerKeepsHisBossAndGetsNoNewOne() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        service.reconcile(alice.getUniqueId());
        quests.pending.remove(alice.getUniqueId()); // déconnexion : progression déchargée

        service.reconcile(alice.getUniqueId());
        assertEquals(1, service.activeInstances().size(), "un joueur déconnecté garde son boss");
    }

    @Test
    void twoPlayersOnTheSameQuestGetTwoDistinctBosses() {
        PlayerMock bob = server.addPlayer("Bob");
        bob.teleport(new Location(wild, 0, 64, 0));
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        wants(bob, ref(QUEST, "chasse", 0, OGRE));
        nextSpot = null;
        service.reconcile(alice.getUniqueId());
        service.reconcile(bob.getUniqueId());

        assertEquals(2, service.activeInstances().size());
        assertEquals(2, service.activeInstances().stream().map(QuestBossInstance::owner).distinct().count());
    }

    // ---- Attribution ----------------------------------------------------------------------------

    @Test
    void deathNearTheOwnerCreditsOnlyHisObjective() {
        PlayerMock bob = server.addPlayer("Bob");
        bob.teleport(new Location(wild, 0, 64, 0));
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        wants(bob, ref(QUEST, "chasse", 0, OGRE));
        service.reconcile(alice.getUniqueId());
        QuestBossInstance alices = service.activeInstances().get(0);
        service.reconcile(bob.getUniqueId());

        alice.teleport(new Location(wild, 45, 64, 0));
        kill((LivingEntity) server.getEntity(alices.entityId()));

        assertEquals(List.of("Alice:rpgquest:chasse_ogre:chasse:0"), quests.credits,
                "le boss d'Alice ne valide jamais l'objectif de Bob");
        assertEquals(1, service.activeInstances().size());
        assertEquals(bob.getUniqueId(), service.activeInstances().get(0).owner());
        assertTrue(messages(alice).contains("objectif rempli"));
    }

    @Test
    void deathFarFromTheOwnerCreditsNothingAndASingleNewBossFollowsTheDelay() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        service.reconcile(alice.getUniqueId());
        QuestBossInstance first = service.activeInstances().get(0);

        alice.teleport(new Location(wild, 500, 64, 500));
        messages(alice);
        kill((LivingEntity) server.getEntity(first.entityId()));

        assertTrue(quests.credits.isEmpty());
        assertTrue(messages(alice).contains("n'est pas rempli"));
        service.reconcile(alice.getUniqueId());
        assertTrue(service.activeInstances().isEmpty(), "pas de réapparition immédiate");

        clock.addAndGet(QuestBossService.RESPAWN_DELAY_MILLIS + 1);
        service.tick();
        service.tick();
        assertEquals(1, service.activeInstances().size());
    }

    @Test
    void ordinaryMobOfTheSameTypeIsIgnored() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        service.reconcile(alice.getUniqueId());
        LivingEntity zombie = (LivingEntity) wild.spawnEntity(new Location(wild, 5, 64, 0), EntityType.ZOMBIE);

        service.onDeath(new EntityDeathEvent(zombie, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>()));
        assertTrue(quests.credits.isEmpty());
        assertEquals(1, service.activeInstances().size());
    }

    // ---- Cycle de vie ---------------------------------------------------------------------------

    @Test
    void abandoningTheQuestRemovesTheBossWithoutLoot() {
        QuestBossService service = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        service.reconcile(alice.getUniqueId());
        Entity boss = server.getEntity(service.activeInstances().get(0).entityId());

        wants(alice); // abandon : plus d'objectif en cours
        service.reconcile(alice.getUniqueId());

        assertTrue(service.activeInstances().isEmpty());
        assertFalse(boss.isValid());
        assertNull(service.journalHint(alice.getUniqueId(), QUEST, "chasse", 0).orElse(null));
    }

    @Test
    void restartRestoresTheSameBossWithoutDuplicate() {
        QuestBossService first = newService();
        wants(alice, ref(QUEST, "chasse", 0, OGRE));
        first.reconcile(alice.getUniqueId());
        UUID entityId = first.activeInstances().get(0).entityId();
        first.stop();
        started.remove(first);

        QuestBossService second = newService();
        second.reconcile(alice.getUniqueId());
        assertEquals(1, second.activeInstances().size());
        assertEquals(entityId, second.activeInstances().get(0).entityId());
        assertEquals(1, searches.get(), "aucune nouvelle recherche après redémarrage");
    }

    @Test
    void markedEntityWithoutLockIsDiscardedNeverAdopted() {
        QuestBossService service = newService();
        LivingEntity orphan = (LivingEntity) wild.spawnEntity(new Location(wild, 60, 64, 0), EntityType.ZOMBIE);
        orphan.getPersistentDataContainer().set(service.instanceKey(), PersistentDataType.STRING, "x");

        service.onEntitiesLoad(new EntitiesLoadEvent(wild.getChunkAt(orphan.getLocation()), List.of(orphan)));

        assertFalse(orphan.isValid());
        assertTrue(service.activeInstances().isEmpty());
    }

    @Test
    void stateStoreRoundTripsInstances() throws Exception {
        QuestBossStateStore store = new QuestBossStateStore(tempDir.resolve("rt"), plugin.getSLF4JLogger());
        QuestBossInstance i = new QuestBossInstance(UUID.randomUUID(), UUID.randomUUID(), "rpgquest:q", "s", 1,
                "rpgquest:ogre", "Ogre", "wild", 1, 2, 3, "minecraft:plains", "wp-1", "Pierre", 40, "nord", 7L);
        store.save(new QuestBossStateStore.State(List.of(i), Set.of()), 1);
        assertEquals(List.of(i), store.load().instances());
    }
}
