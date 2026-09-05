package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.HubConfig;
import com.lodygames.rpgquest.world.WorldService;
import java.nio.file.Path;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.event.world.WorldLoadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Couvre l'identification du monde Hub et l'application des règles réelles de monde (jour/météo
 * permanents), y compris le chargement tardif (ex. Multiverse-Core chargeant {@code world_hub}
 * après le démarrage de RPGQuest) — voir {@code hub.HubWorldProtectionListener} pour les
 * protections d'événement (dégâts/blocs/mobs), testées séparément.
 */
class HubWorldRulesServiceTest {

    private static final HubConfig HUB_CONFIG = new HubConfig("world_hub");

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World other;
    private WorldService worldService;
    private HubWorldRulesService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        other = server.addSimpleWorld("world");

        worldService = new WorldService(plugin, tempDir.resolve("worlds.yml"), plugin.getSLF4JLogger());
        worldService.start();

        service = new HubWorldRulesService(worldService, () -> HUB_CONFIG, plugin.getSLF4JLogger());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void identifiesAndAppliesRulesToTheHubWorldAlreadyLoadedAtStart() {
        World hub = server.addSimpleWorld("world_hub");

        service.start();

        assertFalse(hub.getGameRuleValue(GameRules.ADVANCE_TIME), "jour permanent : le temps ne doit plus avancer");
        assertEquals(6000L, hub.getTime(), "figé à midi");
        assertFalse(hub.getGameRuleValue(GameRules.ADVANCE_WEATHER), "météo permanente : ne doit plus changer");
        assertFalse(hub.hasStorm());
        assertFalse(hub.isThundering());
    }

    @Test
    void neverTouchesAnotherWorld() {
        server.addSimpleWorld("world_hub");

        service.start();

        assertTrue(other.getGameRuleValue(GameRules.ADVANCE_TIME), "un autre monde ne doit jamais être affecté");
        assertTrue(other.getGameRuleValue(GameRules.ADVANCE_WEATHER));
    }

    @Test
    void startingBeforeTheHubWorldExistsNeverThrows() {
        assertDoesNotThrow(() -> service.start());
    }

    @Test
    void appliesRulesWhenTheHubWorldLoadsAfterRPGQuestHasAlreadyStarted() {
        // Le monde Hub n'existe pas encore au démarrage (ex. Multiverse-Core le charge plus tard).
        service.start();
        server.getPluginManager().registerEvents(service.listener(), plugin);

        World hubLoadedLater = server.addSimpleWorld("world_hub");
        server.getPluginManager().callEvent(new WorldLoadEvent(hubLoadedLater));

        assertFalse(hubLoadedLater.getGameRuleValue(GameRules.ADVANCE_TIME));
        assertFalse(hubLoadedLater.getGameRuleValue(GameRules.ADVANCE_WEATHER));
    }

    @Test
    void loadingAnUnrelatedWorldNeverAppliesHubRules() {
        service.start();
        server.getPluginManager().registerEvents(service.listener(), plugin);

        World somethingElse = server.addSimpleWorld("wild");
        assertDoesNotThrow(() -> server.getPluginManager().callEvent(new WorldLoadEvent(somethingElse)));

        assertTrue(somethingElse.getGameRuleValue(GameRules.ADVANCE_TIME), "monde non-Hub : jamais modifié");
    }
}
