package com.lodygames.rpgquest.waypoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #124 : couvre {@link WaypointProtectionListener} — les blocs constitutifs d'un waypoint sont
 * protégés a minima contre casse joueur, explosion, déplacement piston et feu, avec bypass admin.
 * Les mécaniques indirectes que MockBukkit ne simule pas physiquement (fluides réels, poussée de
 * blocs à gravité, propagation de feu) sont vérifiées ici au niveau de l'événement Bukkit et
 * complétées par le plan de test manuel.
 */
class WaypointProtectionListenerTest {

    private ServerMock server;
    private Plugin plugin;
    private World world;

    /** Le bloc protégé de référence pour tous les tests. */
    private static final int PX = 10;
    private static final int PY = 65;
    private static final int PZ = 10;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("WaypointProtTest");
        world = server.addSimpleWorld("wild");
        Set<String> protectedKeys = Set.of("wild:" + PX + "," + PY + "," + PZ);
        WaypointProtectionListener listener = new WaypointProtectionListener(
                (w, x, y, z) -> protectedKeys.contains(w + ":" + x + "," + y + "," + z));
        server.getPluginManager().registerEvents(listener, plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Block protectedBlock() {
        Block block = world.getBlockAt(PX, PY, PZ);
        block.setType(Material.GOLD_BLOCK);
        return block;
    }

    @Test
    void playerCannotBreakAProtectedBlock() {
        PlayerMock player = server.addPlayer();
        BlockBreakEvent event = new BlockBreakEvent(protectedBlock(), player);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "casse joueur d'un bloc de waypoint refusée");
    }

    @Test
    void adminWithBypassPermissionCanBreakAProtectedBlock() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "rpgquest.admin.world", true);
        BlockBreakEvent event = new BlockBreakEvent(protectedBlock(), player);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "bypass rpgquest.admin.world : maintenance possible");
    }

    @Test
    void playerCanBreakANonProtectedBlock() {
        PlayerMock player = server.addPlayer();
        Block other = world.getBlockAt(PX + 5, PY, PZ);
        other.setType(Material.GOLD_BLOCK);
        BlockBreakEvent event = new BlockBreakEvent(other, player);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "les blocs hors waypoint restent cassables");
    }

    @Test
    void explosionsDoNotDestroyProtectedBlocks() {
        Creeper creeper = (Creeper) world.spawnEntity(new Location(world, PX, PY, PZ), EntityType.CREEPER);
        Block protectedBlock = protectedBlock();
        Block collateral = world.getBlockAt(PX + 3, PY, PZ);
        collateral.setType(Material.DIRT);

        List<Block> blocks = new ArrayList<>(List.of(protectedBlock, collateral));
        EntityExplodeEvent event = new EntityExplodeEvent(creeper, creeper.getLocation(), blocks, 1.0f,
                org.bukkit.ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(event);

        assertFalse(event.blockList().contains(protectedBlock), "l'explosion ne détruit pas le waypoint");
        assertTrue(event.blockList().contains(collateral), "les autres blocs explosent normalement");
    }

    @Test
    void pistonsCannotPushBlocksOntoAProtectedBlock() {
        Block pistonBlock = world.getBlockAt(PX - 3, PY, PZ);
        pistonBlock.setType(Material.PISTON);
        Block pushed = world.getBlockAt(PX - 1, PY, PZ);
        pushed.setType(Material.STONE);

        BlockPistonExtendEvent event = new BlockPistonExtendEvent(
                pistonBlock, List.of(pushed), BlockFace.EAST); // EAST pousse (PX-1) vers (PX) = protégé
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "le piston ne peut pas pousser un bloc dans le waypoint");
    }

    @Test
    void fireCannotBurnAProtectedBlock() {
        BlockBurnEvent event = new BlockBurnEvent(protectedBlock(), world.getBlockAt(PX + 1, PY, PZ));
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "le feu ne consume pas un bloc de waypoint");
    }
}
