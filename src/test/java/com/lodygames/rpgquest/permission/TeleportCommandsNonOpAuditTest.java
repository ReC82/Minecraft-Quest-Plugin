package com.lodygames.rpgquest.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandException;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.exception.UnimplementedOperationException;

/**
 * Issue #829 (clarification propriétaire du 25/10) — un joueur ordinaire non-OP ne dispose d'aucune
 * commande de téléportation RPGQuest : il voyage par les mécaniques en jeu (portails, bornes,
 * Pierre de retour). Profil reproduit : celui du groupe LuckPerms {@code default} de DEV, vide, donc
 * les seuls défauts Bukkit du {@code plugin.yml} ; puis le paquet testeur ({@code rpgquest.admin.testing},
 * seul groupe réellement attribué sur DEV). Chaque commande est exécutée de bout en bout par le plugin
 * chargé : le joueur, comme la cible d'un {@code sendhome}, ne doit pas bouger.
 */
class TeleportCommandsNonOpAuditTest {

    private static final String CLAIMS_WORLD = "claims";
    private static final String OTHER_WORLD = "audit_target";
    private static final String START_WORLD = "world_hub";

    /** Toutes les commandes RPGQuest qui déplacent un joueur, telles qu'un joueur les taperait. */
    private static final List<String> TELEPORT_COMMANDS = List.of(
            "rpgadmin world tp " + CLAIMS_WORLD,
            "rpgadmin world tp " + OTHER_WORLD,
            "rpgadmin spawn tp",
            "rpgadmin waystone tp audit_waystone",
            "claim admin tp %s",
            "claim admin sendhome %s");

    private ServerMock server;
    private RPGQuestPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld(START_WORLD); // premier monde = monde d'apparition des joueurs
        server.addSimpleWorld(CLAIMS_WORLD);
        server.addSimpleWorld(OTHER_WORLD);
        plugin = MockBukkit.load(RPGQuestPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void anOrdinaryNonOpPlayerCannotTeleportAnyoneByCommand() {
        assertNoCommandMovesAnyone(nonOp());
    }

    @Test
    void theTesterBundleStillCannotTeleportAnyoneByCommand() {
        PlayerMock tester = nonOp();
        tester.addAttachment(plugin, "rpgquest.admin.testing", true);

        assertNoCommandMovesAnyone(tester);
    }

    @Test
    void theAuditCanSeeATeleport() {
        // Témoin : la commande d'un OP atteint bien la téléportation. MockBukkit n'implémente pas
        // teleportAsync : l'atteindre lève une exception, qui ferait aussi échouer les audits ci-dessus.
        PlayerMock op = server.addPlayer();
        op.setOp(true);

        CommandException reached = assertThrows(CommandException.class,
                () -> op.performCommand("rpgadmin world tp " + OTHER_WORLD));
        assertTrue(reached.getCause() instanceof UnimplementedOperationException, String.valueOf(reached.getCause()));
    }

    @Test
    void noTeleportOrAdminNodeIsGrantedByDefault() {
        // Le profil d'un joueur ordinaire = les nœuds « default: true » du plugin.yml.
        for (Permission permission : plugin.getPluginMeta().getPermissions()) {
            if (permission.getDefault() != PermissionDefault.TRUE) {
                continue;
            }
            String node = permission.getName();
            assertFalse(node.startsWith("rpgquest.admin") || node.startsWith("rpgquest.bypass"),
                    node + " ne doit jamais être accordé par défaut");
            for (Map.Entry<String, Boolean> child : permission.getChildren().entrySet()) {
                assertFalse(child.getValue() && (child.getKey().startsWith("rpgquest.admin")
                                || child.getKey().startsWith("rpgquest.bypass")),
                        node + " accorde par défaut " + child.getKey());
            }
        }
        PlayerMock player = nonOp();
        assertFalse(player.hasPermission("rpgquest.admin.world"));
        assertFalse(player.hasPermission(RpgPermissions.BYPASS_CLAIM_WORLD));
        assertFalse(player.hasPermission(AdminBypassMode.PERMISSION));
    }

    private PlayerMock nonOp() {
        PlayerMock player = server.addPlayer();
        player.setOp(false);
        return player;
    }

    private void assertNoCommandMovesAnyone(PlayerMock player) {
        PlayerMock other = nonOp();
        World start = player.getWorld();
        assertNotEquals(CLAIMS_WORLD, start.getName());
        Location playerBefore = player.getLocation().clone();
        Location otherBefore = other.getLocation().clone();

        for (String template : TELEPORT_COMMANDS) {
            String command = template.formatted(other.getName());
            drain(player);
            player.performCommand(command);
            server.getScheduler().performTicks(5);

            assertEquals(playerBefore, player.getLocation(), "/" + command + " a déplacé " + player.getName());
            assertEquals(otherBefore, other.getLocation(), "/" + command + " a déplacé " + other.getName());
            List<String> messages = drain(player);
            assertTrue(messages.stream().anyMatch(m -> m.contains("Permission manquante")),
                    "/" + command + " doit être refusé explicitement, reçu : " + messages);
        }
    }

    private static List<String> drain(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            messages.add(message);
        }
        return messages;
    }
}
