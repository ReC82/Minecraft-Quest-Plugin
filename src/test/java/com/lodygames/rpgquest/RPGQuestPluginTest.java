package com.lodygames.rpgquest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.bootstrap.RPGQuestBootstrap;
import com.lodygames.rpgquest.config.ConfigValidationException;
import com.lodygames.rpgquest.config.PluginConfig;
import com.lodygames.rpgquest.player.PlayerProfileService;
import java.io.File;
import java.nio.file.Files;
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

    @Test
    void pluginStartsCleanlyWithNoResourcePackConfigured() {
        // resource-pack.enabled vaut false par défaut (config.yml embarqué) : le plugin doit démarrer
        // et fonctionner normalement (apparence vanilla) sans jamais exiger de resource pack.
        assertFalse(plugin.bootstrap().configService().current().resourcePack().enabled());
        assertTrue(plugin.isEnabled());
    }

    @Test
    void reloadAppliesNewValidConfigWithoutRecreatingServices() throws Exception {
        RPGQuestBootstrap bootstrap = plugin.bootstrap();
        PlayerProfileService profileServiceBefore = bootstrap.playerProfileService();
        assertFalse(bootstrap.configService().current().debug());

        writeConfig("""
                debug: true
                locale: fr
                database:
                  file: data.db
                """);

        bootstrap.configService().reload();

        assertTrue(bootstrap.configService().current().debug());
        assertSame(profileServiceBefore, bootstrap.playerProfileService(),
                "le rechargement ne doit pas recréer les services");
    }

    @Test
    void reloadRejectsInvalidConfigAndKeepsPreviousOneActive() throws Exception {
        RPGQuestBootstrap bootstrap = plugin.bootstrap();
        PluginConfig before = bootstrap.configService().current();

        writeConfig("locale: not-a-real-language\n");

        assertThrows(ConfigValidationException.class, () -> bootstrap.configService().reload());
        assertEquals(before, bootstrap.configService().current(),
                "la configuration précédente doit rester active après un rechargement refusé");
    }

    private void writeConfig(String yaml) throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(configFile.toPath(), yaml);
    }
}
