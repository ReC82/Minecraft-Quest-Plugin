package com.lodygames.rpgquest.item.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

// EntityDamageByEntityEvent(..., DamageSource, double) est marqué "for removal" au profit d'un
// constructeur prenant des Map<DamageModifier, ...> internes au moteur vanilla, disproportionné
// pour construire un événement de test ; le constructeur utilisé ici reste fonctionnel en 1.21.11.
@SuppressWarnings("removal")
class WeaponBehaviorListenerTest {

    private static final NamespacedKey PLAIN_SWORD = new NamespacedKey("rpgquest", "plain_sword");
    private static final NamespacedKey CRIT_SWORD = new NamespacedKey("rpgquest", "crit_sword");
    private static final NamespacedKey EFFECT_SWORD = new NamespacedKey("rpgquest", "effect_sword");
    private static final NamespacedKey NEGATIVE_SWORD = new NamespacedKey("rpgquest", "negative_sword");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private YamlCustomItemRegistry registry;
    private AtomicLong clock;
    private CooldownManager cooldowns;
    private WeaponBehaviorListener listener;
    private Path itemsDir;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        itemsDir = tempDir.resolve("items");
        Files.createDirectories(itemsDir);

        writeWeapon("plain_sword.yml", "plain_sword", """
                combat:
                  base-damage: 2.0
                """);
        writeWeapon("crit_sword.yml", "crit_sword", """
                combat:
                  base-damage: 0.0
                  critical-chance: 1.0
                  critical-multiplier: 2.0
                  hit-message: "<red>Crit! <damage></red>"
                  particle: CRIT
                  particle-count: 3
                """);
        writeWeapon("effect_sword.yml", "effect_sword", """
                combat:
                  critical-chance: 0.0
                  effect:
                    ability-id: "slow"
                    type: SLOWNESS
                    duration-ticks: 20
                    amplifier: 0
                    chance: 1.0
                    cooldown-seconds: 5
                """);
        writeWeapon("negative_sword.yml", "negative_sword", """
                combat:
                  base-damage: -100.0
                  critical-chance: 0.0
                """);

        registry = new YamlCustomItemRegistry(itemsDir, NOPLogger.NOP_LOGGER);
        registry.start();

        clock = new AtomicLong(0L);
        cooldowns = new CooldownManager(clock::get);
        listener = new WeaponBehaviorListener(registry, cooldowns, () -> false, NOPLogger.NOP_LOGGER);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void normalAttackAddsBaseDamageBonus() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        attacker.getInventory().setItemInMainHand(registry.create(PLAIN_SWORD, 1).orElseThrow());

        EntityDamageByEntityEvent event = attack(attacker, victim, 1.0);
        listener.onMeleeAttack(event);

        assertEquals(3.0, event.getDamage(), 0.0001, "1.0 de base + 2.0 de bonus configuré, une seule fois");
    }

    @Test
    void criticalHitMultipliesDamageAndNotifies() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        attacker.getInventory().setItemInMainHand(registry.create(CRIT_SWORD, 1).orElseThrow());

        EntityDamageByEntityEvent event = attack(attacker, victim, 4.0);
        listener.onMeleeAttack(event);

        assertEquals(8.0, event.getDamage(), 0.0001, "4.0 * multiplicateur critique 2.0");
        String message = attacker.nextMessage();
        assertTrue(message != null && message.contains("Crit"), "un message doit être envoyé sur coup critique");
    }

    @Test
    void conditionalEffectHasACooldown() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        attacker.getInventory().setItemInMainHand(registry.create(EFFECT_SWORD, 1).orElseThrow());
        String abilityKey = EFFECT_SWORD + ":slow";

        listener.onMeleeAttack(attack(attacker, victim, 1.0));
        assertFalse(cooldowns.isReady(attacker.getUniqueId(), abilityKey), "le cooldown doit démarrer après un déclenchement");

        // Toujours en cooldown : un second coup immédiat ne doit pas redémarrer/prolonger le cooldown.
        long remainingBefore = cooldowns.remainingMillis(attacker.getUniqueId(), abilityKey);
        listener.onMeleeAttack(attack(attacker, victim, 1.0));
        assertEquals(remainingBefore, cooldowns.remainingMillis(attacker.getUniqueId(), abilityKey),
                "un coup pendant le cooldown ne doit pas le réinitialiser");

        clock.set(5000L);
        assertTrue(cooldowns.isReady(attacker.getUniqueId(), abilityKey), "le cooldown doit expirer après 5 secondes");
    }

    @Test
    void cancelledEventIsIgnored() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        attacker.getInventory().setItemInMainHand(registry.create(PLAIN_SWORD, 1).orElseThrow());

        EntityDamageByEntityEvent event = attack(attacker, victim, 1.0);
        event.setCancelled(true);
        listener.onMeleeAttack(event);

        assertEquals(1.0, event.getDamage(), 0.0001, "un événement déjà annulé par un autre plugin ne doit jamais être modifié");
    }

    @Test
    void offHandWeaponIsIgnored() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        attacker.getInventory().setItemInOffHand(registry.create(PLAIN_SWORD, 1).orElseThrow());
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.STICK));

        EntityDamageByEntityEvent event = attack(attacker, victim, 1.0);
        listener.onMeleeAttack(event);

        assertEquals(1.0, event.getDamage(), 0.0001, "une arme en main secondaire ne doit jamais déclencher son comportement");
    }

    @Test
    void armorStandTargetIsIgnored() {
        PlayerMock attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(registry.create(PLAIN_SWORD, 1).orElseThrow());
        World world = server.addSimpleWorld("world");
        ArmorStand armorStand = world.spawn(new Location(world, 0, 64, 0), ArmorStand.class);

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, armorStand,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, damageSource(attacker, DamageType.PLAYER_ATTACK), 1.0);
        listener.onMeleeAttack(event);

        assertEquals(1.0, event.getDamage(), 0.0001, "frapper un armor stand ne doit jamais déclencher le comportement de l'arme");
    }

    @Test
    void projectileDamagerIsIgnored() {
        PlayerMock victim = server.addPlayer();
        World world = server.addSimpleWorld("world");
        Arrow arrow = world.spawn(new Location(world, 0, 64, 0), Arrow.class);

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(arrow, victim,
                EntityDamageEvent.DamageCause.PROJECTILE, damageSource(arrow, DamageType.ARROW), 3.0);
        listener.onMeleeAttack(event);

        assertEquals(3.0, event.getDamage(), 0.0001, "un dégât de projectile ne doit jamais déclencher un comportement d'arme de mêlée");
    }

    @Test
    void forgedItemIsIgnored() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();

        ItemStack fake = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = fake.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize("Test"));
        fake.setItemMeta(meta);
        attacker.getInventory().setItemInMainHand(fake);

        EntityDamageByEntityEvent event = attack(attacker, victim, 1.0);
        listener.onMeleeAttack(event);

        assertEquals(1.0, event.getDamage(), 0.0001, "un objet vanilla renommé pour imiter l'arme ne doit jamais être reconnu");
    }

    @Test
    void reloadingConfigurationChangesAppliedDamage() throws Exception {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        attacker.getInventory().setItemInMainHand(registry.create(PLAIN_SWORD, 1).orElseThrow());

        listener.onMeleeAttack(attack(attacker, victim, 1.0));

        writeWeapon("plain_sword.yml", "plain_sword", """
                combat:
                  base-damage: 10.0
                """);
        registry.reload();
        attacker.getInventory().setItemInMainHand(registry.create(PLAIN_SWORD, 1).orElseThrow());

        EntityDamageByEntityEvent event = attack(attacker, victim, 1.0);
        listener.onMeleeAttack(event);

        assertEquals(11.0, event.getDamage(), 0.0001, "le nouveau bonus doit s'appliquer sans recréer le listener");
    }

    @Test
    void negativeBonusNeverProducesNegativeDamage() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        attacker.getInventory().setItemInMainHand(registry.create(NEGATIVE_SWORD, 1).orElseThrow());

        EntityDamageByEntityEvent event = attack(attacker, victim, 1.0);
        listener.onMeleeAttack(event);

        assertFalse(event.getDamage() < 0, "les dégâts finaux ne doivent jamais être négatifs");
        assertEquals(0.0, event.getDamage(), 0.0001);
    }

    @Test
    void unknownIdDoesNothing() {
        PlayerMock attacker = server.addPlayer();
        PlayerMock victim = server.addPlayer();
        assertNull(registry.find(new NamespacedKey("rpgquest", "does_not_exist")).orElse(null));

        EntityDamageByEntityEvent event = attack(attacker, victim, 1.0);
        listener.onMeleeAttack(event);

        assertEquals(1.0, event.getDamage(), 0.0001);
    }

    private EntityDamageByEntityEvent attack(PlayerMock attacker, PlayerMock victim, double baseDamage) {
        return new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                damageSource(attacker, DamageType.PLAYER_ATTACK), baseDamage);
    }

    private DamageSource damageSource(Entity causer, DamageType type) {
        return DamageSource.builder(type).withCausingEntity(causer).withDirectEntity(causer).build();
    }

    private void writeWeapon(String fileName, String id, String combatYaml) throws Exception {
        String yaml = """
                id: rpgquest:%s
                type: WEAPON
                material: DIAMOND_SWORD
                name: "Test"
                """.formatted(id) + combatYaml;
        Files.writeString(itemsDir.resolve(fileName), yaml);
    }
}
