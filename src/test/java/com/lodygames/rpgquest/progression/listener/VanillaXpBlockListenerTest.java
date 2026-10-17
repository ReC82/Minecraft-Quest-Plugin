package com.lodygames.rpgquest.progression.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lodygames.rpgquest.config.DisplayMode;
import com.lodygames.rpgquest.config.ProgressionConfig;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Issue #183 — aucune progression de niveaux Minecraft quand vanilla-xp-progression vaut false. */
class VanillaXpBlockListenerTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ProgressionConfig config(boolean keepVanillaXp) {
        return new ProgressionConfig(100L, 1.15, 100, 0.0, 1000, DisplayMode.OFF, keepVanillaXp, 50, 15, 5, 4, 10, 100, 0);
    }

    private EntityDeathEvent zombieDeath(int droppedExp) {
        LivingEntity zombie = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);
        return new EntityDeathEvent(zombie, DamageSource.builder(DamageType.GENERIC).build(), List.of(), droppedExp);
    }

    private BlockBreakEvent oreBreak(int exp) {
        Block block = world.getBlockAt(0, 10, 0);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setExpToDrop(exp);
        return event;
    }

    @Test
    void blockedModeRemovesOrbsAndVanillaGains() {
        VanillaXpBlockListener listener = new VanillaXpBlockListener(() -> config(false));

        EntityDeathEvent death = zombieDeath(5);
        listener.onEntityDeath(death);
        assertEquals(0, death.getDroppedExp(), "plus d'orbe à la mort d'un mob, même sans tueur joueur");

        BlockBreakEvent breakEvent = oreBreak(3);
        listener.onBlockBreak(breakEvent);
        assertEquals(0, breakEvent.getExpToDrop(), "plus d'orbe à la casse d'un minerai");

        PlayerExpChangeEvent gain = new PlayerExpChangeEvent(player, 7);
        listener.onExpChange(gain);
        assertEquals(0, gain.getAmount(), "toute orbe restante (villageois, meule, autre plugin) ne donne rien");
    }

    @Test
    void keepModeLeavesVanillaXpUntouched() {
        VanillaXpBlockListener listener = new VanillaXpBlockListener(() -> config(true));

        EntityDeathEvent death = zombieDeath(5);
        listener.onEntityDeath(death);
        assertEquals(5, death.getDroppedExp());

        BlockBreakEvent breakEvent = oreBreak(3);
        listener.onBlockBreak(breakEvent);
        assertEquals(3, breakEvent.getExpToDrop());

        PlayerExpChangeEvent gain = new PlayerExpChangeEvent(player, 7);
        listener.onExpChange(gain);
        assertEquals(7, gain.getAmount());
    }

    @Test
    void existingVanillaLevelsAreNeverTouched() {
        player.setLevel(12);
        VanillaXpBlockListener listener = new VanillaXpBlockListener(() -> config(false));

        listener.onExpChange(new PlayerExpChangeEvent(player, 7));

        assertEquals(12, player.getLevel(), "politique de migration : ni conversion ni effacement des anciens niveaux");
    }
}
