package com.lodygames.rpgquest.mob.ability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.zone.ZoneRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code max-depth}/{@code max-children-per-hit} doivent ensemble garantir
 * qu'aucune chaîne de division n'est infinie (mission étape 18, point 7,
 * test automatique "profondeur maximale de division").
 */
@SuppressWarnings("removal") // EntityDamageByEntityEvent(Entity, Entity, DamageCause, double) : voir
// ZoneProtectionListenerTest pour la justification de cette dépréciation acceptée en test.
class SplitOnHitAbilityListenerTest {

    private static final NamespacedKey SPLIT_ID = new NamespacedKey("rpgquest", "splitting_test");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World world;
    private SpecialMobRegistry registry;
    private SpecialMobService service;
    private SplitOnHitAbilityListener listener;
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
        Files.writeString(mobsDir.resolve("splitting_test.yml"), """
                id: rpgquest:splitting_test
                entity-type: ZOMBIE
                name: "Splitting Test"
                spawn-chance: 1.0
                health: 30
                abilities:
                  - type: SPLIT_ON_HIT
                    max-depth: 1
                    max-children-per-hit: 2
                max-population: 5
                """);
        registry = new SpecialMobRegistry(mobsDir, plugin.getSLF4JLogger());
        registry.reload();
        definition = registry.find(SPLIT_ID).orElseThrow();

        service = new SpecialMobService(plugin, registry, zoneRegistry, itemRegistry, plugin.getSLF4JLogger());
        listener = new SplitOnHitAbilityListener(service);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private LivingEntity spawnParent() {
        LivingEntity parent = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), org.bukkit.entity.EntityType.ZOMBIE);
        service.apply(parent, definition);
        return parent;
    }

    @Test
    void nonLethalHitSpawnsConfiguredChildrenAtDepthOne() {
        LivingEntity parent = spawnParent();
        PlayerMock attacker = server.addPlayer();
        int before = world.getEntitiesByClass(Zombie.class).size();

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, parent, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onDamage(event);

        int after = world.getEntitiesByClass(Zombie.class).size();
        assertEquals(before + 2, after, "max-children-per-hit: 2 doit créer exactement 2 enfants");
    }

    @Test
    void childAtMaxDepthNeverSplitsAgain() {
        LivingEntity parent = spawnParent();
        PlayerMock attacker = server.addPlayer();

        listener.onDamage(new EntityDamageByEntityEvent(
                attacker, parent, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0));

        LivingEntity child = world.getEntitiesByClass(Zombie.class).stream()
                .filter(z -> !z.getUniqueId().equals(parent.getUniqueId()))
                .findFirst().orElseThrow();
        int beforeChildSplit = world.getEntitiesByClass(Zombie.class).size();

        listener.onDamage(new EntityDamageByEntityEvent(
                attacker, child, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0));

        int afterChildSplit = world.getEntitiesByClass(Zombie.class).size();
        assertEquals(beforeChildSplit, afterChildSplit,
                "un enfant né à la profondeur maximale (max-depth: 1) ne doit plus jamais se diviser");
    }

    @Test
    void lethalHitNeverSplits() {
        LivingEntity parent = spawnParent();
        PlayerMock attacker = server.addPlayer();
        int before = world.getEntitiesByClass(Zombie.class).size();

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, parent, EntityDamageEvent.DamageCause.ENTITY_ATTACK, parent.getHealth() + 1);
        listener.onDamage(event);

        assertEquals(before, world.getEntitiesByClass(Zombie.class).size(), "un coup mortel ne doit jamais déclencher de division");
    }

    @Test
    void cancelledDamageEventNeverSplits() {
        LivingEntity parent = spawnParent();
        PlayerMock attacker = server.addPlayer();
        int before = world.getEntitiesByClass(Zombie.class).size();

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, parent, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        event.setCancelled(true);
        listener.onDamage(event);

        assertEquals(before, world.getEntitiesByClass(Zombie.class).size());
    }

    @Test
    void populationLimitCapsSplitting() {
        // max-population: 5. On amène la population à la limite exacte avec des remplisseurs, le
        // dernier servant de victime : aucun enfant ne doit alors pouvoir être créé.
        LivingEntity parent = null;
        for (int i = 0; i < 5; i++) {
            LivingEntity filler = (LivingEntity) world.spawnEntity(new Location(world, i + 10, 64, 0), org.bukkit.entity.EntityType.ZOMBIE);
            service.apply(filler, definition);
            parent = filler;
        }
        assertTrue(service.atPopulationLimit(definition), "la population doit déjà être à la limite avant le test");

        PlayerMock attacker = server.addPlayer();
        int before = world.getEntitiesByClass(Zombie.class).size();

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, parent, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onDamage(event);

        assertEquals(before, world.getEntitiesByClass(Zombie.class).size(),
                "aucun enfant ne doit être créé une fois max-population atteinte");
    }
}
