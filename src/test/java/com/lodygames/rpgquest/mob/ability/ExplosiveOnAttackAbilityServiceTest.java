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
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Premiers tests de {@link ExplosiveOnAttackAbilityService} (issue #190) : la poursuite d'un
 * joueur par une base passive (type {@code PIG}, ex. le Cochon Creeper) via
 * {@code org.bukkit.entity.Mob#getPathfinder()} (API publique Paper, sans NMS), en plus du
 * déclenchement de l'explosion déjà existant mais jusqu'ici non testé directement.
 *
 * <p>Identité posée directement en PDC puis redécouverte via {@link SpecialMobService#onChunkLoad}
 * (jamais {@link SpecialMobService#apply}) : {@code LivingEntityMock#setRemoveWhenFarAway}, appelé
 * par {@code apply()}, n'est pas implémenté par cette version de MockBukkit (limitation déjà
 * documentée ailleurs dans le projet) et ferait échouer tout test l'utilisant ; {@code
 * onChunkLoad} ne l'appelle pas et suffit pour alimenter le suivi de population dont dépend
 * {@code aliveEntityIds} (donc {@code sweep()}).</p>
 */
class ExplosiveOnAttackAbilityServiceTest {

    private static final NamespacedKey PIG_ID = new NamespacedKey("rpgquest", "test_creeper_pig");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private SpecialMobRegistry registry;
    private SpecialMobService service;
    private ExplosiveOnAttackAbilityService abilityService;
    private SpecialMobDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        world = server.addSimpleWorld("world");

        Path itemsDir = tempDir.resolve("items");
        Files.createDirectories(itemsDir);
        YamlCustomItemRegistry itemRegistry = new YamlCustomItemRegistry(itemsDir, plugin.getSLF4JLogger());
        itemRegistry.start();
        ZoneRegistry zoneRegistry = new ZoneRegistry(tempDir.resolve("zones"), plugin.getSLF4JLogger());

        Path mobsDir = tempDir.resolve("mobs");
        Files.createDirectories(mobsDir);
        Files.writeString(mobsDir.resolve("test_creeper_pig.yml"), """
                id: rpgquest:test_creeper_pig
                entity-type: PIG
                name: "Test Creeper Pig"
                spawn-chance: 1.0
                health: 20
                abilities:
                  - type: EXPLOSIVE_ON_ATTACK
                    power: 3.0
                    set-fire: false
                    trigger-range-blocks: 3.0
                """);
        registry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        registry.reload();
        definition = registry.find(PIG_ID).orElseThrow();

        MobSpawnSettingsStore spawnSettingsStore = new MobSpawnSettingsStore(mobsDir, plugin.getSLF4JLogger());
        service = new SpecialMobService(plugin, registry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger(), spawnSettingsStore);
        abilityService = new ExplosiveOnAttackAbilityService(plugin, registry, service);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Voir le javadoc de la classe : identité PDC + redécouverte, jamais {@code apply()}. */
    private LivingEntity spawnTaggedPig(Location location) {
        LivingEntity pig = (LivingEntity) world.spawnEntity(location, EntityType.PIG);
        if (definition.health() != null) {
            AttributeInstance maxHealth = pig.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                maxHealth.setBaseValue(definition.health());
            }
            pig.setHealth(definition.health());
        }
        pig.getPersistentDataContainer().set(service.pdcKey(), PersistentDataType.STRING, definition.id().asString());
        service.onChunkLoad(new ChunkLoadEvent(pig.getLocation().getChunk(), false));
        return pig;
    }

    @Test
    void playerWithinTriggerRangeCausesAnExplosion() {
        LivingEntity pig = spawnTaggedPig(new Location(world, 0, 64, 0));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 1, 64, 0)); // 1 bloc : dans trigger-range-blocks (3.0)

        abilityService.sweep();

        assertTrue(pig.isDead(), "un joueur à portée de déclenchement doit provoquer l'explosion (donc la mort de l'entité)");
    }

    @Test
    void playerWithinSenseRangeButBeyondTriggerRangeNeverExplodes() {
        // Hors de trigger-range-blocks (3.0) mais dans le rayon de perception (16.0, voir
        // SENSE_RANGE_BLOCKS) : doit se mettre en chemin vers le joueur (Pathfinder, API publique
        // Paper), jamais exploser avant d'être réellement à portée.
        LivingEntity pig = spawnTaggedPig(new Location(world, 0, 64, 0));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 10, 64, 0));

        abilityService.sweep();

        assertFalse(pig.isDead(), "hors de trigger-range-blocks, aucune explosion ne doit se déclencher");
    }

    @Test
    void playerBeyondSenseRangeNeverTriggersNorMoves() {
        LivingEntity pig = spawnTaggedPig(new Location(world, 0, 64, 0));
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 100, 64, 0)); // très loin : hors portée de perception

        abilityService.sweep();

        assertFalse(pig.isDead(), "un joueur hors de portée ne doit déclencher ni poursuite ni explosion");
    }
}
