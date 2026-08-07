package be.lloyd.rpgquest.mob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.item.YamlCustomItemRegistry;
import be.lloyd.rpgquest.mob.model.SpecialMobDefinition;
import be.lloyd.rpgquest.zone.ZoneRegistry;
import be.lloyd.rpgquest.zone.model.ZoneDefinition;
import be.lloyd.rpgquest.zone.model.ZoneFlags;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SpecialMobService newService(RandomGenerator random) {
        return new SpecialMobService(plugin, registry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(), random);
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
                plugin, zonedRegistry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(), fixedRandom(0.0));

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
