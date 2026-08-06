package be.lloyd.rpgquest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class RPGQuestPluginTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginLoadsSuccessfully() {
        assertNotNull(plugin);
        assertTrue(plugin.isEnabled());
    }

    @Test
    void rpgquestCommandIsRegistered() {
        assertNotNull(server.getPluginManager().getPlugin("RPGQuest"));
        assertNotNull(plugin.getCommand("rpgquest"));
    }
}
