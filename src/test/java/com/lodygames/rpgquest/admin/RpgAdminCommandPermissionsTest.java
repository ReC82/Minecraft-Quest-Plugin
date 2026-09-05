package com.lodygames.rpgquest.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Vérifie la granularité par action de {@code /rpgadmin} (issue #27) : un nœud
 * {@code rpgquest.admin.<action>} ouvre son action et rien d'autre, le parapluie
 * {@code rpgquest.admin.world} (et donc l'OP) garde tout, et un joueur sans permission est refusé
 * avec le nom du nœud manquant.
 */
class RpgAdminCommandPermissionsTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Concatène tous les messages reçus par le joueur depuis le dernier appel. */
    private String drainMessages(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        String line;
        while ((line = player.nextMessage()) != null) {
            all.append(line).append('\n');
        }
        return all.toString();
    }

    @Test
    void aPlayerWithoutAnyPermissionIsDeniedWithTheMissingNode() {
        PlayerMock player = server.addPlayer();

        player.performCommand("rpgadmin zone list");

        String messages = drainMessages(player);
        assertTrue(messages.contains("Permission manquante"), messages);
        assertTrue(messages.contains("rpgquest.admin.zone"), messages);
    }

    @Test
    void anNpcEditorCannotFlatten() {
        PlayerMock npcEditor = server.addPlayer();
        npcEditor.addAttachment(plugin, "rpgquest.admin.npc", true);

        npcEditor.performCommand("rpgadmin flatten");

        String messages = drainMessages(npcEditor);
        assertTrue(messages.contains("Permission manquante"), messages);
        assertTrue(messages.contains("rpgquest.admin.flatten"), messages);
    }

    @Test
    void anNpcEditorReachesTheNpcActionWithoutAPermissionError() {
        PlayerMock npcEditor = server.addPlayer();
        npcEditor.addAttachment(plugin, "rpgquest.admin.npc", true);

        npcEditor.performCommand("rpgadmin npc");

        String messages = drainMessages(npcEditor);
        assertFalse(messages.contains("Permission manquante"),
                "le nœud npc est accordé : l'échec éventuel doit venir de la cible, jamais de la permission — " + messages);
    }

    @Test
    void theAdminWorldUmbrellaStillGrantsEveryAction() {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "rpgquest.admin.world", true);

        admin.performCommand("rpgadmin flatten");
        String flattenMessages = drainMessages(admin);
        assertFalse(flattenMessages.contains("Permission manquante"), flattenMessages);

        admin.performCommand("rpgadmin npc");
        String npcMessages = drainMessages(admin);
        assertFalse(npcMessages.contains("Permission manquante"), npcMessages);
    }

    @Test
    void anOpKeepsFullAdminAccess() {
        PlayerMock op = server.addPlayer();
        op.setOp(true);

        op.performCommand("rpgadmin flatten");

        String messages = drainMessages(op);
        assertFalse(messages.contains("Permission manquante"), messages);
    }

    @Test
    void buildPermissionsAloneGiveNoAdminCommandAccess() {
        PlayerMock builder = server.addPlayer();
        builder.addAttachment(plugin, "rpgquest.build.*", true);
        builder.addAttachment(plugin, "rpgquest.build.hub.0", true);

        builder.performCommand("rpgadmin zone list");

        String messages = drainMessages(builder);
        assertTrue(messages.contains("Permission manquante"), messages);
        assertTrue(messages.contains("rpgquest.admin.zone"), messages);
    }

    @Test
    void theRpgadminCommandIsRegistered() {
        assertNotNull(plugin.getCommand("rpgadmin"));
    }
}
