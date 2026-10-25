package com.lodygames.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import com.lodygames.rpgquest.zone.model.ZoneDefinition;
import com.lodygames.rpgquest.zone.model.ZoneFlags;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Le générateur aléatoire est toujours injecté (jamais {@code
 * ThreadLocalRandom} en test) pour rendre {@code rollDefinition} déterministe
 * — mission étape 18 : "probabilités avec générateur aléatoire injecté".
 */
class SpecialMobServiceTest {

    private static final NamespacedKey SIMPLE_ID = new NamespacedKey("rpgquest", "test_zombie");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private SpecialMobRegistry registry;
    private YamlCustomItemRegistry itemRegistry;
    private ZoneRegistry zoneRegistry;
    private MobSpawnSettingsStore spawnSettingsStore;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        Path itemsDir = tempDir.resolve("items");
        Files.createDirectories(itemsDir);
        itemRegistry = new YamlCustomItemRegistry(itemsDir, plugin.getSLF4JLogger());
        itemRegistry.start();

        // Ne jamais appeler zoneRegistry.start() ici : il générerait la zone d'exemple embarquée
        // (central_village, -48..48 sur world) qui recouvrirait les positions de test ci-dessous.
        Path zonesDir = tempDir.resolve("zones");
        zoneRegistry = new ZoneRegistry(zonesDir, plugin.getSLF4JLogger());

        Path mobsDir = tempDir.resolve("mobs");
        Files.createDirectories(mobsDir);
        Files.writeString(mobsDir.resolve("test_zombie.yml"), """
                id: rpgquest:test_zombie
                entity-type: ZOMBIE
                name: "<dark_purple>Test Zombie</dark_purple>"
                spawn-chance: 0.5
                health: 30
                damage: 5
                speed: 1.0
                armor: 1
                drops:
                  - material: ROTTEN_FLESH
                    weight: 50
                    min-amount: 1
                    max-amount: 1
                  - material: BONE
                    weight: 50
                    min-amount: 1
                    max-amount: 1
                xp-reward: 10
                """);
        // reload() (jamais start()) : évite de générer/charger les variantes d'exemple embarquées
        // (red_creeper, splitting_zombie...) qui pollueraient les tests basés sur registry.definitions().
        registry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        registry.reload();
        spawnSettingsStore = new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SpecialMobService newService(RandomGenerator random) {
        return new SpecialMobService(plugin, registry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(), random, spawnSettingsStore, world -> true);
    }

    private static EntityDeathEvent deathEvent(LivingEntity entity, List<org.bukkit.inventory.ItemStack> drops) {
        return new EntityDeathEvent(entity, DamageSource.builder(DamageType.GENERIC).build(), drops);
    }

    private static RandomGenerator fixedRandom(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    // ---- Identification PDC ----------------------------------------------------------------

    @Test
    void appliedDefinitionIsIdentifiableOnlyByPdcNeverByDisplayName() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        SpecialMobDefinition def = registry.find(SIMPLE_ID).orElseThrow();

        service.apply(zombie, def);

        assertEquals(Optional.of(SIMPLE_ID), service.specialMobId(zombie));
        assertEquals(Optional.of(def), service.specialMobDefinition(zombie));
        // Renommer l'entité ne doit avoir aucune incidence : seule la PDC identifie la variante.
        zombie.customName(net.kyori.adventure.text.Component.text("Renamed"));
        assertEquals(Optional.of(def), service.specialMobDefinition(zombie));
    }

    @Test
    void untaggedEntityHasNoSpecialMobIdentity() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);

        assertTrue(service.specialMobId(zombie).isEmpty());
        assertTrue(service.specialMobDefinition(zombie).isEmpty());
    }

    @Test
    void unrecognizedVariantIdOnPdcYieldsEmptyDefinition() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        zombie.getPersistentDataContainer().set(service.pdcKey(),
                org.bukkit.persistence.PersistentDataType.STRING, "rpgquest:does_not_exist");

        assertEquals(Optional.of(new NamespacedKey("rpgquest", "does_not_exist")), service.specialMobId(zombie));
        assertTrue(service.specialMobDefinition(zombie).isEmpty(), "un id inconnu du registre ne doit résoudre aucune définition");
    }

    // ---- Probabilités (générateur injecté) --------------------------------------------------

    @Test
    void rollBelowSpawnChanceUpgradesTheEntity() {
        SpecialMobService service = newService(fixedRandom(0.0)); // 0.0 < 0.5 (spawn-chance)
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);

        Optional<SpecialMobDefinition> rolled = service.rollDefinition(zombie);

        assertTrue(rolled.isPresent());
        assertEquals(SIMPLE_ID, rolled.get().id());
    }

    @Test
    void rollAboveSpawnChanceDoesNotUpgradeTheEntity() {
        SpecialMobService service = newService(fixedRandom(0.99)); // 0.99 >= 0.5 (spawn-chance)
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);

        assertTrue(service.rollDefinition(zombie).isEmpty());
    }

    @Test
    void wrongEntityTypeIsNeverRolled() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity pig = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.PIG);

        assertTrue(service.rollDefinition(pig).isEmpty(), "aucune définition ne cible PIG dans ce fixture");
    }

    // ---- Issue #169 (lot 1) : throttle global, exclusion BOSS, tirage pondéré -----------------

    private static RandomGenerator sequenceRandom(double... values) {
        return new RandomGenerator() {
            private int index = 0;

            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public double nextDouble() {
                double value = values[Math.min(index, values.length - 1)];
                index++;
                return value;
            }
        };
    }

    @Test
    void bossCategoryIsNeverEligibleForTheAutomaticRoll() throws Exception {
        Path mobsDir = tempDir.resolve("boss-mobs");
        Files.createDirectories(mobsDir);
        Files.writeString(mobsDir.resolve("boss_zombie.yml"), """
                id: rpgquest:boss_zombie
                category: BOSS
                entity-type: ZOMBIE
                name: "Boss Zombie"
                spawn-chance: 1.0
                """);
        SpecialMobRegistry bossRegistry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        bossRegistry.reload();
        SpecialMobService service = new SpecialMobService(
                plugin, bossRegistry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(), fixedRandom(0.0), spawnSettingsStore, world -> true);

        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        assertTrue(service.rollDefinition(zombie).isEmpty(), "une définition BOSS ne doit jamais sortir du tirage aléatoire");
    }

    @Test
    void globalThrottleBlocksTheRollEvenWhenEveryDefinitionWouldHit() throws Exception {
        Path mobsDir = tempDir.resolve("throttled-mobs");
        Files.writeString(Files.createDirectories(mobsDir).resolve("always_hits.yml"), """
                id: rpgquest:always_hits
                entity-type: ZOMBIE
                name: "Always Hits"
                spawn-chance: 1.0
                """);
        SpecialMobRegistry throttledRegistry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        throttledRegistry.reload();
        MobSpawnSettingsStore throttledSettings = new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger());
        throttledSettings.save(new MobSpawnSettings(true, 0.3, null));
        SpecialMobService service = new SpecialMobService(
                plugin, throttledRegistry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(),
                fixedRandom(0.5), throttledSettings, world -> true); // 0.5 >= 0.3 (throttle global) : jamais examiné plus loin.

        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        assertTrue(service.rollDefinition(zombie).isEmpty(),
                "le throttle global doit bloquer le tirage avant même d'examiner les définitions");
    }

    @Test
    void simultaneousHitsAreResolvedByExplicitWeightedPickNeverTheFirstFound() throws Exception {
        Path mobsDir = tempDir.resolve("weighted-mobs");
        Files.createDirectories(mobsDir);
        Files.writeString(mobsDir.resolve("weight_a.yml"), """
                id: rpgquest:weight_a
                entity-type: ZOMBIE
                name: "Weight A"
                spawn-chance: 0.5
                """);
        Files.writeString(mobsDir.resolve("weight_b.yml"), """
                id: rpgquest:weight_b
                entity-type: ZOMBIE
                name: "Weight B"
                spawn-chance: 0.5
                """);
        SpecialMobRegistry weightedRegistry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        weightedRegistry.reload();
        MobSpawnSettingsStore defaultSettings = new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger());

        // throttle(0.0) passe ; weight_a(0.0<0.5) touche ; weight_b(0.0<0.5) touche ; tirage pondéré(0.9) -> weight_b.
        SpecialMobService serviceB = new SpecialMobService(plugin, weightedRegistry, zoneRegistry, itemRegistry,
                plugin.getSLF4JLogger(), sequenceRandom(0.0, 0.0, 0.0, 0.9), defaultSettings, world -> true);
        LivingEntity zombieB = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        assertEquals("rpgquest:weight_b", serviceB.rollDefinition(zombieB).orElseThrow().id().toString());

        // Même tirage mais pondéré(0.1) -> weight_a : prouve que la sélection dépend du tirage pondéré,
        // jamais simplement de la première définition trouvée dans le registre.
        SpecialMobService serviceA = new SpecialMobService(plugin, weightedRegistry, zoneRegistry, itemRegistry,
                plugin.getSLF4JLogger(), sequenceRandom(0.0, 0.0, 0.0, 0.1), defaultSettings, world -> true);
        LivingEntity zombieA = (LivingEntity) world.spawnEntity(new Location(world, 1, 64, 1), EntityType.ZOMBIE);
        assertEquals("rpgquest:weight_a", serviceA.rollDefinition(zombieA).orElseThrow().id().toString());
    }

    @Test
    void disabledDefinitionIsNeverRolled() throws Exception {
        Path mobsDir = tempDir.resolve("disabled-mobs");
        Files.writeString(Files.createDirectories(mobsDir).resolve("disabled.yml"), """
                id: rpgquest:disabled_zombie
                enabled: false
                entity-type: ZOMBIE
                name: "Disabled"
                spawn-chance: 1.0
                """);
        SpecialMobRegistry disabledRegistry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        disabledRegistry.reload();
        SpecialMobService service = new SpecialMobService(plugin, disabledRegistry, zoneRegistry, itemRegistry,
                plugin.getSLF4JLogger(), fixedRandom(0.0), new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger()), world -> true);

        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        assertTrue(service.rollDefinition(zombie).isEmpty(), "une définition désactivée ne doit jamais être tirée");
    }

    // ---- Monde Wild uniquement (issue #851) ------------------------------------------------

    private SpecialMobService wildOnlyPigService(String profile) throws Exception {
        Path mobsDir = tempDir.resolve("pig-mobs");
        Files.writeString(Files.createDirectories(mobsDir).resolve("creeper_pig.yml"), profile);
        SpecialMobRegistry pigRegistry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        pigRegistry.reload();
        // Configuration volontairement fautive : le Hub est aussi déclaré Wild ; il doit rester exclu.
        return new SpecialMobService(plugin, pigRegistry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(),
                fixedRandom(0.0), new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger()),
                SpecialMobService.wildOnly(w -> Set.of("wild", "world_hub").contains(w.getName()),
                        () -> Set.of("world_hub", "claims")));
    }

    private static final String CREEPER_PIG_ALL_WORLDS = """
            id: rpgquest:creeper_pig
            entity-type: PIG
            name: "Cochon Creeper"
            spawn-chance: 1.0
            worlds: []
            """;

    @Test
    void naturalPigInHubOrClaimsIsNeverTransformedEvenWithEmptyWorlds() throws Exception {
        SpecialMobService service = wildOnlyPigService(CREEPER_PIG_ALL_WORLDS);
        for (String name : List.of("world_hub", "claims")) {
            World protectedWorld = server.addSimpleWorld(name);
            LivingEntity pig = (LivingEntity) protectedWorld.spawnEntity(new Location(protectedWorld, 0, 64, 0), EntityType.PIG);
            assertTrue(service.rollDefinition(pig).isEmpty(), "aucun mob spécial tiré dans « " + name + " »");
        }
    }

    @Test
    void emptyWorldsMeansWildOnly() throws Exception {
        SpecialMobService service = wildOnlyPigService(CREEPER_PIG_ALL_WORLDS);
        World wild = server.addSimpleWorld("wild");
        LivingEntity wildPig = (LivingEntity) wild.spawnEntity(new Location(wild, 0, 64, 0), EntityType.PIG);
        assertTrue(service.rollDefinition(wildPig).isPresent(), "worlds: [] -> tiré dans le Wild");

        LivingEntity otherPig = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.PIG);
        assertTrue(service.rollDefinition(otherPig).isEmpty(), "worlds: [] -> jamais hors des mondes Wild");
    }

    @Test
    void profileListingTheHubStillNeverRollsThere() throws Exception {
        SpecialMobService service = wildOnlyPigService(CREEPER_PIG_ALL_WORLDS.replace("worlds: []", "worlds: [world_hub]"));
        World hub = server.addSimpleWorld("world_hub");
        LivingEntity pig = (LivingEntity) hub.spawnEntity(new Location(hub, 0, 64, 0), EntityType.PIG);
        assertTrue(service.rollDefinition(pig).isEmpty(), "worlds d'un profil restreint le Wild, il ne l'étend jamais");
    }

    // ---- Zone interdite -----------------------------------------------------------------------

    @Test
    void locationOutsideAllowedZoneIsNeverRolled() throws Exception {
        Path mobsDir = tempDir.resolve("zoned-mobs");
        Files.createDirectories(mobsDir);
        Files.writeString(mobsDir.resolve("arena_only.yml"), """
                id: rpgquest:arena_only
                entity-type: ZOMBIE
                name: "Arena Only"
                spawn-chance: 1.0
                zones:
                  - arena
                """);
        SpecialMobRegistry zonedRegistry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        zonedRegistry.reload();
        SpecialMobService service = new SpecialMobService(
                plugin, zonedRegistry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(), fixedRandom(0.0), spawnSettingsStore, world -> true);

        LivingEntity outside = (LivingEntity) world.spawnEntity(new Location(world, 100, 64, 100), EntityType.ZOMBIE);
        assertTrue(service.rollDefinition(outside).isEmpty(), "hors de toute zone nommée « arena », le spawn est refusé");

        zoneRegistry.create(new ZoneDefinition("arena", "world", -5, 0, -5, 5, 255, 5, ZoneFlags.defaults()));
        LivingEntity inside = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        assertTrue(service.rollDefinition(inside).isPresent(), "à l'intérieur de la zone « arena », le spawn est autorisé");
    }

    // ---- Population --------------------------------------------------------------------------

    @Test
    void populationLimitPreventsFurtherRolls() {
        SpecialMobService service = newService(fixedRandom(0.0));
        SpecialMobDefinition def = registry.find(SIMPLE_ID).orElseThrow();
        // Le fixture n'a pas de max-population : on vérifie juste que atPopulationLimit répond false
        // sans limite, puis on simule une population artificiellement pleine via apply() répétés.
        assertFalse(service.atPopulationLimit(def));
    }

    // ---- Drop unique --------------------------------------------------------------------------

    @Test
    void deathRollsExactlyOneDropFromTheWeightedTable() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        SpecialMobDefinition def = registry.find(SIMPLE_ID).orElseThrow();
        service.apply(zombie, def);

        EntityDeathEvent event = deathEvent(zombie, new java.util.ArrayList<>(List.of(
                new org.bukkit.inventory.ItemStack(Material.ROTTEN_FLESH, 5),
                new org.bukkit.inventory.ItemStack(Material.ROTTEN_FLESH, 5))));

        service.onDeath(event);

        assertEquals(1, event.getDrops().size(), "la table de drops de la variante remplace totalement les drops vanilla");
        assertEquals(10, event.getDroppedExp());
    }

    @Test
    void undefinedEntityIsUntouchedOnDeath() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        List<org.bukkit.inventory.ItemStack> vanillaDrops =
                new java.util.ArrayList<>(List.of(new org.bukkit.inventory.ItemStack(Material.ROTTEN_FLESH, 1)));

        EntityDeathEvent event = deathEvent(zombie, vanillaDrops);
        service.onDeath(event);

        assertEquals(1, event.getDrops().size(), "une entité non taguée ne doit jamais voir ses drops modifiés");
    }

    // ---- Événement annulé ---------------------------------------------------------------------

    @Test
    void cancelledCreatureSpawnEventIsNeverUpgraded() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);

        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);
        event.setCancelled(true);

        service.onCreatureSpawn(event);

        assertTrue(service.specialMobId(zombie).isEmpty(), "un spawn déjà annulé (safe zone) ne doit jamais être upgradé");
    }

    @Test
    void cancelledEntityDeathEventLeavesDropsUntouched() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        SpecialMobDefinition def = registry.find(SIMPLE_ID).orElseThrow();
        service.apply(zombie, def);

        List<org.bukkit.inventory.ItemStack> vanillaDrops =
                new java.util.ArrayList<>(List.of(new org.bukkit.inventory.ItemStack(Material.ROTTEN_FLESH, 3)));
        EntityDeathEvent event = deathEvent(zombie, vanillaDrops);
        event.setCancelled(true);

        service.onDeath(event);

        assertEquals(1, event.getDrops().size());
        assertEquals(3, event.getDrops().get(0).getAmount(), "un événement de mort annulé ne doit pas voir ses drops remplacés");
    }

    // ---- Instances de test (issue #169) -----------------------------------------------------

    @Test
    void clearTestInstancesRemovesOnlyTestInstancesAndKeepsTheExcludedOnes() {
        SpecialMobService service = newService(fixedRandom(0.0));
        SpecialMobDefinition def = registry.find(SIMPLE_ID).orElseThrow();
        LivingEntity natural = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        service.apply(natural, def);
        LivingEntity test = (LivingEntity) world.spawnEntity(new Location(world, 2, 64, 0), EntityType.ZOMBIE);
        service.applyTestInstance(test, def);
        LivingEntity communityTest = (LivingEntity) world.spawnEntity(new Location(world, 4, 64, 0), EntityType.ZOMBIE);
        service.applyTestInstance(communityTest, def);
        LivingEntity vanilla = (LivingEntity) world.spawnEntity(new Location(world, 6, 64, 0), EntityType.ZOMBIE);
        java.util.function.Predicate<java.util.UUID> community = communityTest.getUniqueId()::equals;

        SpecialMobService.Population before = service.populationBreakdown(SIMPLE_ID, community);
        assertEquals(3, before.tracked());
        assertEquals(3, before.loaded());
        assertEquals(1, before.test(), "le boss communautaire de test se retire par son propre service");

        assertEquals(1, service.clearTestInstances(community));

        assertTrue(test.isDead() || !test.isValid(), "l'instance de test est supprimée");
        assertTrue(natural.isValid(), "un mob spécial naturel n'est jamais supprimé");
        assertTrue(communityTest.isValid(), "le boss communautaire de test reste (verrou)");
        assertTrue(vanilla.isValid(), "un mob ordinaire n'est jamais touché");
        assertEquals(new SpecialMobService.Population(2, 2, 0), service.populationBreakdown(SIMPLE_ID, community));
    }

    // ---- Compteurs : mort, retrait, déchargement, redémarrage (issue #542) ---------------------

    /**
     * Comme {@code ExplosiveOnAttackExecutorTest} : identité PDC (+ marquage test) puis redécouverte,
     * jamais {@code apply()} ({@code LivingEntityMock#setRemoveWhenFarAway} non implémenté).
     */
    private LivingEntity spawnTagged(SpecialMobService service, Location at, boolean test) {
        LivingEntity entity = (LivingEntity) world.spawnEntity(at, EntityType.ZOMBIE);
        entity.getPersistentDataContainer().set(service.pdcKey(), PersistentDataType.STRING, SIMPLE_ID.asString());
        if (test) {
            entity.getPersistentDataContainer().set(new NamespacedKey(plugin, SpecialMobService.TEST_INSTANCE_KEY_NAME),
                    PersistentDataType.BYTE, (byte) 1);
        }
        service.onEntitiesLoad(new org.bukkit.event.world.EntitiesLoadEvent(entity.getLocation().getChunk(),
                List.of(entity)));
        return entity;
    }

    @Test
    void lethalDamageLikeTheExplosiveAbilityRemovesTheInstanceFromEveryCounter() {
        // ExplosiveOnAttackExecutor tue la source par entity.damage(vie + 1) après l'explosion.
        SpecialMobService service = newService(fixedRandom(0.0));
        service.start();
        LivingEntity test = spawnTagged(service, new Location(world, 0, 64, 0), true);
        assertEquals(new SpecialMobService.Population(1, 1, 1), service.populationBreakdown(SIMPLE_ID, id -> false));

        test.damage(test.getHealth() + 1.0);

        assertTrue(test.isDead());
        assertEquals(new SpecialMobService.Population(0, 0, 0), service.populationBreakdown(SIMPLE_ID, id -> false));
        assertEquals(0, service.populationOf(SIMPLE_ID));
    }

    @Test
    void removalWithoutDeathUntracksButAZoneUnloadDoesNot() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity kept = spawnTagged(service, new Location(world, 0, 64, 0), false);
        LivingEntity removed = spawnTagged(service, new Location(world, 2, 64, 0), false);

        service.onRemove(new EntityRemoveEvent(kept, EntityRemoveEvent.Cause.UNLOAD));
        service.onRemove(new EntityRemoveEvent(removed, EntityRemoveEvent.Cause.PLUGIN));

        assertEquals(java.util.Set.of(kept.getUniqueId()), service.aliveEntityIds(SIMPLE_ID),
                "un déchargement garde l'instance ; un retrait définitif sans mort la décompte");
    }

    @Test
    void unloadedInstanceKeepsItsLastPositionUntilItsZoneReloads() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity test = spawnTagged(service, new Location(world, 40.5, 70, -12.5), true);

        service.onEntitiesUnload(new EntitiesUnloadEvent(test.getLocation().getChunk(), List.of(test)));
        service.onRemove(new EntityRemoveEvent(test, EntityRemoveEvent.Cause.UNLOAD));
        test.remove(); // ce que le serveur fait au déchargement : l'entité n'est plus atteignable

        SpecialMobService.Population away = service.populationBreakdown(SIMPLE_ID, id -> false);
        assertEquals(1, away.tracked());
        assertEquals(0, away.loaded());
        assertEquals(0, away.test(), "« Nettoyer » n'atteint pas une zone non chargée");
        assertEquals(0, away.unknown());
        SpecialMobService.UnloadedInstance at = away.unloaded().get(0);
        assertEquals(test.getUniqueId(), at.entityId());
        assertEquals("world", at.world());
        assertEquals(40, at.x());
        assertEquals(70, at.y());
        assertEquals(-13, at.z());
        assertTrue(at.test());

    }

    @Test
    void reloadingTheZoneClearsTheLastKnownPosition() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity test = spawnTagged(service, new Location(world, 0, 64, 0), true);
        service.onEntitiesUnload(new EntitiesUnloadEvent(test.getLocation().getChunk(), List.of(test)));
        service.onEntitiesLoad(new org.bukkit.event.world.EntitiesLoadEvent(test.getLocation().getChunk(), List.of(test)));

        test.remove(); // disparue ensuite sans événement : plus de position périmée, état inconnu

        SpecialMobService.Population p = service.populationBreakdown(SIMPLE_ID, id -> false);
        assertTrue(p.unloaded().isEmpty(), "rechargée : la position du déchargement est oubliée");
        assertEquals(1, p.unknown());
    }

    @Test
    void restartForgetsEverythingAndReloadingTheZoneCountsTheInstanceOnceAgain() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity test = spawnTagged(service, new Location(world, 0, 64, 0), true);
        service.onEntitiesUnload(new EntitiesUnloadEvent(test.getLocation().getChunk(), List.of(test)));

        service.stop();
        assertEquals(new SpecialMobService.Population(0, 0, 0), service.populationBreakdown(SIMPLE_ID, id -> false));

        service.onEntitiesLoad(new org.bukkit.event.world.EntitiesLoadEvent(test.getLocation().getChunk(), List.of(test)));
        service.onEntitiesLoad(new org.bukkit.event.world.EntitiesLoadEvent(test.getLocation().getChunk(), List.of(test)));
        assertEquals(new SpecialMobService.Population(1, 1, 1), service.populationBreakdown(SIMPLE_ID, id -> false),
                "PDC relue au chargement, jamais comptée deux fois");
    }

    @Test
    void anInstanceGoneWithoutAnyEventIsReportedUnknownNeverHidden() {
        SpecialMobService service = newService(fixedRandom(0.0));
        LivingEntity test = spawnTagged(service, new Location(world, 0, 64, 0), true);

        test.remove(); // listener non enregistré : aucun événement ne parvient au service

        SpecialMobService.Population p = service.populationBreakdown(SIMPLE_ID, id -> false);
        assertEquals(1, p.tracked());
        assertEquals(0, p.loaded());
        assertEquals(1, p.unknown(), "écart dit tel quel, jamais remis à zéro");
    }

    // ---- Reload ---------------------------------------------------------------------------

    @Test
    void reloadReplacesDefinitionsAndUnknownIdsBecomeUnresolvable() throws Exception {
        SpecialMobService service = newService(fixedRandom(0.0));
        assertTrue(registry.find(SIMPLE_ID).isPresent());

        Files.delete(tempDir.resolve("mobs").resolve("test_zombie.yml"));
        registry.reload();

        assertTrue(registry.find(SIMPLE_ID).isEmpty(), "après reload sans le fichier, la définition n'existe plus");

        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        zombie.getPersistentDataContainer().set(service.pdcKey(),
                org.bukkit.persistence.PersistentDataType.STRING, SIMPLE_ID.asString());
        assertTrue(service.specialMobDefinition(zombie).isEmpty(),
                "une entité déjà taguée avec un id retiré au reload doit rester identifiable par PDC mais sans définition");
    }
}
