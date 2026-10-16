package com.lodygames.rpgquest.mob.community;

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
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
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
 * Issue #175 — scheduler, plafond, verrou persistant, annonces et rappel de connexion, contre un
 * serveur MockBukkit. L'horloge, le hasard, l'écriture disque (synchrone ici) et la recherche
 * d'emplacement sont injectés : aucun test ne dépend du temps réel ni du terrain généré.
 */
class CommunityBossServiceTest {

    private static final long INTERVAL = 15 * 60_000L;

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World wild;
    private World hub;
    private SpecialMobRegistry registry;
    private SpecialMobService mobService;
    private CommunityBossSettingsStore settingsStore;
    private ZoneRegistry zoneRegistry;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Waypoint> waypoints = new ArrayList<>();
    private final List<CommunityBossService> started = new ArrayList<>();
    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        wild = server.addSimpleWorld("wild");
        hub = server.addSimpleWorld("hub");

        Path itemsDir = Files.createDirectories(tempDir.resolve("items"));
        YamlCustomItemRegistry items = new YamlCustomItemRegistry(itemsDir, plugin.getSLF4JLogger());
        items.start();
        zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());

        Path mobsDir = Files.createDirectories(tempDir.resolve("mobs"));
        Files.writeString(mobsDir.resolve("ogre.yml"), """
                id: rpgquest:ogre
                category: BOSS
                boss-mode: COMMUNITY
                entity-type: ZOMBIE
                name: "<red>Ogre des collines</red>"
                spawn-chance: 0.0
                health: 200
                """);
        Files.writeString(mobsDir.resolve("quest_boss.yml"), """
                id: rpgquest:quest_boss
                category: BOSS
                entity-type: ZOMBIE
                name: "Boss de quête"
                spawn-chance: 0.0
                """);
        registry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        registry.reload();
        mobService = new SpecialMobService(plugin, registry, zoneRegistry, items, plugin.getSLF4JLogger(),
                new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger()));
        settingsStore = new CommunityBossSettingsStore(mobsDir, plugin.getSLF4JLogger());
        settingsStore.save(new CommunityBossSettings(true, 15, 1));

        waypoints.add(new Waypoint("wp-1", "Pierre aux Corbeaux", "wild", "plains@0,0", "minecraft:plains", 0, 0,
                0, 64, 0, "NORTH", 1, true, Instant.EPOCH));
        player = server.addPlayer("Alice");
        player.teleport(new Location(wild, 0, 64, 0));
    }

    @AfterEach
    void tearDown() {
        for (CommunityBossService service : started) {
            service.stop();
        }
        MockBukkit.unmock();
    }

    /** Emplacement fixe à 50 blocs à l'est du centre, soumis aux règles réelles d'acceptation. */
    private static CommunityBossService.SpotFinder fixedSpot() {
        return (world, center, accept, random) -> {
            Location spot = new Location(world, center.getBlockX() + 50.5, 64, center.getBlockZ() + 0.5);
            return accept.test(spot) ? Optional.of(spot) : Optional.empty();
        };
    }

    private CommunityBossService newService() {
        CommunityBossService service = new CommunityBossService(plugin, plugin.getSLF4JLogger(), registry, mobService,
                settingsStore, new CommunityBossStateStore(tempDir.resolve("state"), plugin.getSLF4JLogger()),
                w -> true, () -> Set.of("hub"), zoneRegistry, () -> null, () -> waypoints,
                new SplittableRandom(42), clock::get, Runnable::run, fixedSpot());
        service.applyProfilesWith((entity, def, test) -> tagAsProfile(entity, def.id().asString()));
        service.start();
        started.add(service);
        return service;
    }

    /**
     * Identité PDC posée comme {@link SpecialMobService#apply} le ferait, sans l'appeler :
     * {@code LivingEntityMock#setRemoveWhenFarAway} n'est pas implémenté par MockBukkit (même
     * contournement que {@code SplitOnHitAbilityListenerTest}).
     */
    private void tagAsProfile(LivingEntity entity, String profileId) {
        entity.getPersistentDataContainer().set(mobService.pdcKey(), PersistentDataType.STRING, profileId);
    }

    private void passDeadline() {
        clock.addAndGet(INTERVAL + 1);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private List<String> drainMessages(PlayerMock p) {
        List<String> out = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            out.add(plain(c));
        }
        return out;
    }

    // ---- Scheduler / plafond -------------------------------------------------------------------

    @Test
    void nothingSpawnsBeforeTheFirstDeadline() {
        CommunityBossService service = newService();
        service.tick();
        assertTrue(service.snapshot().instances().isEmpty());
    }

    @Test
    void dueTickSpawnsOneMarkedBossAndAnnouncesItWithTheRealNearestWaypoint() {
        CommunityBossService service = newService();
        drainMessages(player);
        passDeadline();
        service.tick();

        assertEquals(1, service.snapshot().instances().size());
        CommunityBossInstance instance = service.snapshot().instances().get(0);
        assertEquals("rpgquest:ogre", instance.profileId());
        assertEquals("Pierre aux Corbeaux", instance.waypointName());
        assertEquals("est", instance.waypointDirection());
        assertFalse(instance.test());

        Entity entity = server.getEntity(instance.entityId());
        assertNotNull(entity);
        assertEquals(instance.entityId().toString(), entity.getPersistentDataContainer()
                .get(service.instanceKey(), PersistentDataType.STRING));
        assertTrue(mobService.specialMobId(entity).isPresent(), "le moteur de mobs spéciaux applique le profil");

        String announce = String.join("\n", drainMessages(player));
        assertTrue(announce.contains("Ogre des collines"), announce);
        assertTrue(announce.contains("Pierre aux Corbeaux"), announce);
        assertTrue(announce.contains("à environ 50 blocs au est"), announce);
        assertTrue(service.snapshot().lastSpawnMillis() > 0);
    }

    @Test
    void capIsNeverExceededByLaterDeadlines() {
        CommunityBossService service = newService();
        passDeadline();
        service.tick();
        passDeadline();
        service.tick();

        assertEquals(1, service.snapshot().instances().size());
        assertTrue(service.snapshot().lastOutcome().contains("Plafond"), service.snapshot().lastOutcome());
    }

    @Test
    void pausedSchedulerNeverSpawns() throws Exception {
        settingsStore.save(new CommunityBossSettings(false, 15, 1));
        CommunityBossService service = newService();
        passDeadline();
        service.tick();
        assertTrue(service.snapshot().instances().isEmpty());
    }

    @Test
    void noPlayerInTheWildMeansNoSpawnAndNoAnnouncement() {
        player.teleport(new Location(hub, 0, 64, 0)); // Hub déclaré Wild par erreur : exclu quand même.
        CommunityBossService service = newService();
        drainMessages(player);
        passDeadline();
        service.tick();

        assertTrue(service.snapshot().instances().isEmpty());
        assertTrue(drainMessages(player).isEmpty());
        assertTrue(service.snapshot().lastOutcome().contains("Aucun joueur"), service.snapshot().lastOutcome());
    }

    @Test
    void aMissedDeadlineIsTriedOnceThenTheNormalCadenceResumes() {
        CommunityBossService service = newService();
        clock.addAndGet(10 * INTERVAL); // dix échéances « manquées »
        service.tick();
        assertEquals(1, service.snapshot().instances().size());
        assertEquals(clock.get() + INTERVAL, service.snapshot().nextDueMillis());
    }

    // ---- Fin de vie / verrou --------------------------------------------------------------------

    @Test
    void deathReleasesTheLockAnnouncesItAndTheNextDeadlineCanSpawnAgain() {
        CommunityBossService service = newService();
        passDeadline();
        service.tick();
        LivingEntity boss = (LivingEntity) server.getEntity(service.snapshot().instances().get(0).entityId());
        drainMessages(player);

        service.onDeath(new EntityDeathEvent(boss, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>()));
        assertTrue(service.snapshot().instances().isEmpty());
        assertTrue(String.join("\n", drainMessages(player)).contains("a été vaincu"));

        boss.remove();
        passDeadline();
        service.tick();
        assertEquals(1, service.snapshot().instances().size());
    }

    @Test
    void restartRestoresTheLockSoTheCapStillHolds() {
        CommunityBossService first = newService();
        passDeadline();
        first.tick();
        first.stop();
        started.remove(first);

        CommunityBossService second = newService();
        assertEquals(1, second.snapshot().instances().size(), "verrou restauré depuis le fichier d'état");
        passDeadline();
        second.tick();
        assertEquals(1, second.snapshot().instances().size(), "aucun doublon après redémarrage");
    }

    @Test
    void entityRemovedOutOfBandFromALoadedChunkReleasesItsLock() {
        CommunityBossService service = newService();
        passDeadline();
        service.tick();
        CommunityBossInstance instance = service.snapshot().instances().get(0);
        server.getEntity(instance.entityId()).remove();

        // Chunk non chargé : absence non prouvée, le verrou reste (jamais de faux-mort).
        if (wild.isChunkLoaded(instance.chunkX(), instance.chunkZ())) {
            wild.unloadChunk(instance.chunkX(), instance.chunkZ());
        }
        service.reconcileLoaded();
        assertEquals(1, service.snapshot().instances().size());

        wild.getChunkAt(instance.chunkX(), instance.chunkZ()); // chargé, entités comprises (MockBukkit)
        service.reconcileLoaded();
        assertTrue(service.snapshot().instances().isEmpty());
    }

    @Test
    void orphanFoundOnEntityLoadIsAdoptedUnderTheCapAndRemovedAboveIt() {
        CommunityBossService service = newService();
        LivingEntity orphan = (LivingEntity) wild.spawnEntity(new Location(wild, 60, 64, 0), EntityType.ZOMBIE);
        tagAsProfile(orphan, "rpgquest:ogre");
        orphan.getPersistentDataContainer().set(service.instanceKey(), PersistentDataType.STRING,
                orphan.getUniqueId().toString());

        service.onEntitiesLoad(new EntitiesLoadEvent(wild.getChunkAt(orphan.getLocation()), List.of(orphan)));
        assertEquals(1, service.snapshot().instances().size(), "réadopté sous le plafond");

        LivingEntity second = (LivingEntity) wild.spawnEntity(new Location(wild, 70, 64, 0), EntityType.ZOMBIE);
        tagAsProfile(second, "rpgquest:ogre");
        second.getPersistentDataContainer().set(service.instanceKey(), PersistentDataType.STRING,
                second.getUniqueId().toString());
        service.onEntitiesLoad(new EntitiesLoadEvent(wild.getChunkAt(second.getLocation()), List.of(orphan, second)));

        assertEquals(1, service.snapshot().instances().size(), "jamais au-delà du plafond");
        assertFalse(second.isValid(), "doublon retiré");
    }

    // ---- Test admin / retrait --------------------------------------------------------------------

    @Test
    void testSpawnIsImmediateIgnoresTheCapAndNeverMovesTheDeadline() {
        CommunityBossService service = newService();
        passDeadline();
        service.tick();
        Long deadline = service.snapshot().nextDueMillis();
        long lastSpawn = service.snapshot().lastSpawnMillis();
        drainMessages(player);

        CommunityBossService.Outcome outcome = service.testSpawn(null, "Alice");
        assertTrue(outcome.ok(), outcome.message());
        assertEquals(2, service.snapshot().instances().size());
        assertEquals(deadline, service.snapshot().nextDueMillis());
        assertEquals(lastSpawn, service.snapshot().lastSpawnMillis());
        assertTrue(String.join("\n", drainMessages(player)).contains("apparition de test"));
    }

    @Test
    void testSpawnRefusesAQuestBossAndAnOfflinePlayer() {
        CommunityBossService service = newService();
        assertEquals("NOT_COMMUNITY", service.testSpawn("rpgquest:quest_boss", null).code());
        assertEquals("NOT_COMMUNITY", service.testSpawn("quest_boss", null).code(), "forme courte = rpgquest:");
        assertEquals("OFFLINE", service.testSpawn(null, "Bob").code());
        assertTrue(service.snapshot().instances().isEmpty());
    }

    @Test
    void removingTestInstancesRemovesOnlyThemAndNeverRewards() {
        CommunityBossService service = newService();
        passDeadline();
        service.tick();
        service.testSpawn(null, "Alice");
        CommunityBossInstance test = service.snapshot().instances().stream()
                .filter(CommunityBossInstance::test).findFirst().orElseThrow();

        CommunityBossService.Outcome outcome = service.removeTestInstances();
        assertTrue(outcome.ok());
        assertEquals(1, service.snapshot().instances().size());
        assertFalse(service.snapshot().instances().get(0).test());
        assertNull(server.getEntity(test.entityId()));
    }

    // ---- Rappel de connexion ---------------------------------------------------------------------

    @Test
    void joinReminderListsActiveBossesToThatPlayerOnly() {
        CommunityBossService service = newService();
        PlayerMock late = server.addPlayer("Bob");
        drainMessages(late);
        service.sendReminder(late);
        assertTrue(drainMessages(late).isEmpty(), "rien d'actif : aucun message");

        passDeadline();
        service.tick();
        drainMessages(late);
        drainMessages(player);
        service.sendReminder(late);

        String reminder = String.join("\n", drainMessages(late));
        assertTrue(reminder.contains("Un boss communautaire rôde"), reminder);
        assertTrue(reminder.contains("Ogre des collines"), reminder);
        assertTrue(drainMessages(player).isEmpty(), "pas de rediffusion globale");
    }

    @Test
    void stateFileIsWrittenWithTheLock() throws Exception {
        CommunityBossService service = newService();
        passDeadline();
        service.tick();
        String yaml = Files.readString(tempDir.resolve("state").resolve("community-bosses.yml"));
        assertTrue(yaml.contains(service.snapshot().instances().get(0).entityId().toString()), yaml);
    }
}
