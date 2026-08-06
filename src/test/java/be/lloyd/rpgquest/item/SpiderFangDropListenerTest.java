package be.lloyd.rpgquest.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

class SpiderFangDropListenerTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private SpiderFangDropListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        YamlCustomItemRegistry registry = new YamlCustomItemRegistry(tempDir.resolve("items"), NOPLogger.NOP_LOGGER);
        registry.start();
        listener = new SpiderFangDropListener(registry);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spiderKilledByAPlayerShouldDrop() {
        PlayerMock player = server.addPlayer();
        assertTrue(listener.shouldDrop(EntityType.SPIDER, player));
        assertTrue(listener.shouldDrop(EntityType.CAVE_SPIDER, player));
    }

    @Test
    void spiderNotKilledByAPlayerShouldNotDrop() {
        assertFalse(listener.shouldDrop(EntityType.SPIDER, null), "mort sans joueur (chute, autre mob...) : pas de drop");
    }

    @Test
    void nonSpiderEntityShouldNotDrop() {
        PlayerMock player = server.addPlayer();
        assertFalse(listener.shouldDrop(EntityType.ZOMBIE, player));
    }
}
