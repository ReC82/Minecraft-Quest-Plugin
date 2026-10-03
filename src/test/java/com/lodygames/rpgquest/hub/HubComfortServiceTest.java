package com.lodygames.rpgquest.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.HubConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #33 : le Hub n'est pas un monde de survie — courir/sauter/explorer n'y diminue jamais la
 * faim ni la saturation, et un joueur revenu blessé/affamé depuis le Wild récupère sans commande.
 * Les dégâts eux-mêmes sont déjà entièrement annulés par {@code HubWorldProtectionListener}
 * (testé séparément) ; ce test couvre uniquement {@link HubComfortService}.
 */
class HubComfortServiceTest {

    private static final HubConfig HUB_CONFIG = new HubConfig("world_hub");

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private World hub;
    private World wild;
    private HubComfortService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        hub = server.addSimpleWorld("world_hub");
        wild = server.addSimpleWorld("wild");

        service = new HubComfortService(plugin, () -> HUB_CONFIG, plugin.getSLF4JLogger());
        server.getPluginManager().registerEvents(service.listener(), plugin);
    }

    @AfterEach
    void tearDown() {
        service.stop();
        MockBukkit.unmock();
    }

    private PlayerMock addPlayerIn(World world) {
        PlayerMock player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 64, 0.5));
        return player;
    }

    // ---- Épuisement : jamais une perte de faim dans le Hub ------------------------------------

    @Test
    void foodLevelDecreaseInTheHubIsCancelled() {
        PlayerMock player = addPlayerIn(hub);
        player.setFoodLevel(20);
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);

        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "sprint/sauts ne doivent jamais faire baisser la faim dans le Hub");
    }

    @Test
    void foodLevelIncreaseInTheHubIsNeverCancelled() {
        PlayerMock player = addPlayerIn(hub);
        player.setFoodLevel(15);
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 20);

        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "une augmentation (ex. consommation) n'a aucune raison d'être bloquée");
    }

    @Test
    void foodLevelDecreaseInTheWildIsNeverCancelled() {
        PlayerMock player = addPlayerIn(wild);
        player.setFoodLevel(20);
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);

        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "le Wild garde son épuisement normal");
    }

    // ---- Restauration à l'arrivée (Wild -> Hub, reconnexion, réapparition) ---------------------

    @Test
    void joiningDirectlyInTheHubRestoresHealthFoodAndSaturation() {
        PlayerMock player = addPlayerIn(hub);
        player.setFoodLevel(5);
        player.setSaturation(0f);
        player.setHealth(3.0);

        server.getPluginManager().callEvent(new PlayerJoinEvent(player, "join"));

        assertEquals(20, player.getFoodLevel());
        assertEquals(20f, player.getSaturation());
        assertEquals(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(), player.getHealth());
    }

    @Test
    void changingWorldIntoTheHubRestoresHealthFoodAndSaturation() {
        PlayerMock player = addPlayerIn(wild);
        player.setFoodLevel(2);
        player.setSaturation(0f);
        player.setHealth(1.0);
        player.teleport(new Location(hub, 0.5, 64, 0.5));

        server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, wild));

        assertEquals(20, player.getFoodLevel());
        assertTrue(player.getHealth() > 1.0, "la vie doit être restaurée en arrivant dans le Hub");
    }

    @Test
    void changingWorldIntoTheWildNeverRestoresAnything() {
        PlayerMock player = addPlayerIn(hub);
        player.teleport(new Location(wild, 0.5, 64, 0.5));
        player.setFoodLevel(7);
        player.setSaturation(0f);

        server.getPluginManager().callEvent(new PlayerChangedWorldEvent(player, hub));

        assertEquals(7, player.getFoodLevel(), "le Wild ne restaure jamais la faim automatiquement");
    }

    @Test
    void respawningIntoTheHubRestoresHealthFoodAndSaturation() {
        PlayerMock player = addPlayerIn(wild);
        player.setFoodLevel(1);
        player.setSaturation(0f);
        Location hubRespawn = new Location(hub, 0.5, 64, 0.5);

        server.getPluginManager().callEvent(new PlayerRespawnEvent(player, hubRespawn, false));
        server.getScheduler().performTicks(2); // la restauration au respawn est différée d'un tick.

        assertEquals(20, player.getFoodLevel());
    }

    // ---- Garde périodique anti-épuisement silencieux -------------------------------------------

    @Test
    void periodicSweepRestoresFoodSaturationAndExhaustionForPlayersInHub() {
        service.start();
        PlayerMock player = addPlayerIn(hub);
        player.setFoodLevel(12);
        player.setSaturation(2f);
        player.setExhaustion(3f);

        server.getScheduler().performTicks(25); // période de garde = 20 ticks.

        assertEquals(20, player.getFoodLevel());
        assertEquals(20f, player.getSaturation());
        assertEquals(0f, player.getExhaustion());
    }

    @Test
    void periodicSweepNeverTouchesPlayersInTheWild() {
        service.start();
        PlayerMock player = addPlayerIn(wild);
        player.setFoodLevel(12);
        player.setSaturation(2f);

        server.getScheduler().performTicks(25);

        assertEquals(12, player.getFoodLevel(), "le Wild garde sa faim normale");
    }
}
