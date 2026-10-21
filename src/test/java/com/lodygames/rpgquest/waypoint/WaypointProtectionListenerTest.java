package com.lodygames.rpgquest.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
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
    private com.lodygames.rpgquest.travel.TravelMaintenanceMode maintenance;

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
        maintenance = new com.lodygames.rpgquest.travel.TravelMaintenanceMode();
        WaypointProtectionListener listener = new WaypointProtectionListener(
                (w, x, y, z) -> protectedKeys.contains(w + ":" + x + "," + y + "," + z), maintenance);
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

    /**
     * Issue #191 — reproduit exactement le défaut signalé en jeu. {@code rpgquest.admin.world} a
     * {@code default: op} : tout compte opérateur cassait donc bouton puis structure entière sans
     * aucun geste délibéré. Ce test garantit que cette permission large ne suffit plus.
     */
    @Test
    void adminWorldPermissionAloneNoLongerBreaksAProtectedBlock() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "rpgquest.admin.world", true);
        BlockBreakEvent event = new BlockBreakEvent(protectedBlock(), player);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(),
                "le statut OP / rpgquest.admin.world ne doit plus suffire à détruire un waypoint");
    }

    /** La permission dédiée seule ne suffit pas non plus : l'activation doit être volontaire. */
    @Test
    void dedicatedPermissionWithoutExplicitActivationStillProtects() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, com.lodygames.rpgquest.travel.TravelMaintenanceMode.PERMISSION, true);
        BlockBreakEvent event = new BlockBreakEvent(protectedBlock(), player);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "sans « maintenance on », la protection reste active");
    }

    /** Maintenance explicitement activée par un porteur de la permission dédiée : casse autorisée. */
    @Test
    void explicitMaintenanceModeAllowsDeliberateRepair() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, com.lodygames.rpgquest.travel.TravelMaintenanceMode.PERMISSION, true);
        assertTrue(maintenance.enable(player), "activation acceptée avec la permission dédiée");
        BlockBreakEvent event = new BlockBreakEvent(protectedBlock(), player);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "maintenance explicite : la réparation reste possible");
    }

    /** Sans la permission dédiée, l'activation est refusée — on ne peut pas se l'auto-accorder. */
    @Test
    void maintenanceCannotBeEnabledWithoutTheDedicatedPermission() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "rpgquest.admin.world", true);
        assertFalse(maintenance.enable(player), "OP ne doit pas pouvoir activer la maintenance");
        BlockBreakEvent event = new BlockBreakEvent(protectedBlock(), player);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }

    /** Désactivation : la protection revient immédiatement. */
    @Test
    void disablingMaintenanceRestoresProtectionImmediately() {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, com.lodygames.rpgquest.travel.TravelMaintenanceMode.PERMISSION, true);
        maintenance.enable(player);
        maintenance.disable(player);
        BlockBreakEvent event = new BlockBreakEvent(protectedBlock(), player);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "protection rétablie dès la désactivation");
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

    // ---- Panneaux de nom (issue #654) -------------------------------------------------------

    private Block protectedSign() {
        Block block = world.getBlockAt(PX, PY, PZ);
        block.setType(Material.OAK_WALL_SIGN);
        return block;
    }

    private Block ordinarySign() {
        Block block = world.getBlockAt(PX + 5, PY, PZ);
        block.setType(Material.OAK_WALL_SIGN);
        return block;
    }

    private static List<net.kyori.adventure.text.Component> lines(String first) {
        return new ArrayList<>(List.of(net.kyori.adventure.text.Component.text(first),
                net.kyori.adventure.text.Component.empty(), net.kyori.adventure.text.Component.empty(),
                net.kyori.adventure.text.Component.empty()));
    }

    private PlayerInteractEvent rightClick(PlayerMock player, Block block, ItemStack inHand) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, inHand, block,
                BlockFace.NORTH, org.bukkit.inventory.EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** Le clic droit qui ouvrirait l'éditeur vanilla n'atteint plus le panneau (ni GUI, ni teinture/cire). */
    @Test
    void rightClickOnAProtectedSignNeverReachesTheSign() {
        PlayerMock player = server.addPlayer();
        PlayerInteractEvent bare = rightClick(player, protectedSign(), null);
        assertEquals(Event.Result.DENY, bare.useInteractedBlock(), "main vide : pas d'éditeur");
        for (Material tool : List.of(Material.RED_DYE, Material.GLOW_INK_SAC, Material.INK_SAC, Material.HONEYCOMB)) {
            PlayerInteractEvent event = rightClick(player, protectedSign(), new ItemStack(tool));
            assertEquals(Event.Result.DENY, event.useInteractedBlock(), tool + " ne doit pas modifier le panneau");
        }
    }

    /** Ni OP, ni maintenance explicite (#191) n'exposent l'éditeur standard d'un panneau de waypoint. */
    @Test
    void opAndMaintenanceDoNotReopenTheVanillaSignEditor() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        player.addAttachment(plugin, com.lodygames.rpgquest.travel.TravelMaintenanceMode.PERMISSION, true);
        assertTrue(maintenance.enable(player));
        Block sign = protectedSign();

        assertEquals(Event.Result.DENY, rightClick(player, sign, null).useInteractedBlock());
        PlayerOpenSignEvent open = new PlayerOpenSignEvent(player, (Sign) sign.getState(), Side.FRONT,
                PlayerOpenSignEvent.Cause.INTERACT);
        server.getPluginManager().callEvent(open);
        assertTrue(open.isCancelled(), "même en maintenance, l'éditeur ne s'ouvre pas");
    }

    /** Deuxième barrière : l'ouverture de l'éditeur est refusée sur les deux faces, quelle qu'en soit la cause. */
    @Test
    void signEditorCannotOpenOnEitherSideOfAProtectedSign() {
        PlayerMock player = server.addPlayer();
        Sign sign = (Sign) protectedSign().getState();
        for (Side side : Side.values()) {
            for (PlayerOpenSignEvent.Cause cause : PlayerOpenSignEvent.Cause.values()) {
                PlayerOpenSignEvent event = new PlayerOpenSignEvent(player, sign, side, cause);
                server.getPluginManager().callEvent(event);
                assertTrue(event.isCancelled(), "éditeur refusé : " + side + " / " + cause);
            }
        }
    }

    /** Troisième barrière : un texte envoyé malgré tout (client modifié) n'est jamais écrit. */
    @Test
    void signTextChangeIsRefusedOnBothSidesOfAProtectedSign() {
        PlayerMock player = server.addPlayer();
        for (Side side : Side.values()) {
            SignChangeEvent event = new SignChangeEvent(protectedSign(), player,
                    lines("Grief"), side);
            server.getPluginManager().callEvent(event);
            assertTrue(event.isCancelled(), "écriture refusée côté " + side);
        }
    }

    /** Le bouton (bloc protégé mais pas un panneau) garde son interaction : la découverte reste possible. */
    @Test
    void buttonInteractionIsLeftUntouched() {
        PlayerMock player = server.addPlayer();
        Block button = world.getBlockAt(PX, PY, PZ);
        button.setType(Material.STONE_BUTTON);
        PlayerInteractEvent event = rightClick(player, button, null);
        assertNotEquals(Event.Result.DENY, event.useInteractedBlock(), "le bouton reste cliquable");
    }

    /** Les panneaux des joueurs hors structure restent pleinement éditables. */
    @Test
    void ordinarySignsStayEditable() {
        PlayerMock player = server.addPlayer();
        Block sign = ordinarySign();
        assertNotEquals(Event.Result.DENY, rightClick(player, sign, null).useInteractedBlock());

        PlayerOpenSignEvent open = new PlayerOpenSignEvent(player, (Sign) sign.getState(), Side.FRONT,
                PlayerOpenSignEvent.Cause.INTERACT);
        server.getPluginManager().callEvent(open);
        assertFalse(open.isCancelled(), "éditeur d'un panneau ordinaire autorisé");

        SignChangeEvent change = new SignChangeEvent(sign, player, lines("Chez moi"), Side.BACK);
        server.getPluginManager().callEvent(change);
        assertFalse(change.isCancelled(), "écriture d'un panneau ordinaire autorisée");
    }
}
