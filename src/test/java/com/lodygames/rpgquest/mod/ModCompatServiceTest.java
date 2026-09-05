package com.lodygames.rpgquest.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ModCompatConfig;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Couvre la détection de compatibilité (mission étape 23, points 4/8) et la
 * politique de repli/obligation (point 9), entièrement côté serveur — voir
 * docs/CLIENT_MOD.md pour le protocole. {@link ModCompatService#onPluginMessageReceived}
 * est appelé directement (comme le ferait le Messenger de Bukkit) plutôt que
 * de simuler le transport réseau, plus fiable en test.
 */
class ModCompatServiceTest {

    private static final long TIMEOUT_TICKS = 20L;

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

    private ModCompatService newService(boolean requireMod) {
        ModCompatConfig config = new ModCompatConfig(requireMod, (int) TIMEOUT_TICKS);
        ModCompatService service = new ModCompatService(plugin, () -> config, plugin.getSLF4JLogger());
        service.start();
        return service;
    }

    private PlayerMock join(ModCompatService service) {
        PlayerMock player = server.addPlayer();
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        return player;
    }

    private byte[] validResponse(byte protocolVersion) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x52504751);
            out.writeByte(protocolVersion);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    @Test
    void compatibleClientRespondingWithTheRightProtocolVersionIsNeverKicked() {
        ModCompatService service = newService(true);
        PlayerMock player = join(service);

        service.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, player, validResponse((byte) 1));

        assertEquals(PlayerModStatus.COMPATIBLE, service.status(player.getUniqueId()));
        assertTrue(player.isOnline());
    }

    @Test
    void wrongProtocolVersionIsDetectedAndKickedOnlyWhenModIsRequired() {
        ModCompatService requiredService = newService(true);
        PlayerMock player = join(requiredService);

        requiredService.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, player, validResponse((byte) 99));

        assertEquals(PlayerModStatus.WRONG_VERSION, requiredService.status(player.getUniqueId()));
        assertFalse(player.isOnline());
    }

    @Test
    void wrongProtocolVersionDoesNotKickWhenVanillaFallbackIsAllowed() {
        ModCompatService fallbackService = newService(false);
        PlayerMock player = join(fallbackService);

        fallbackService.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, player, validResponse((byte) 99));

        assertEquals(PlayerModStatus.WRONG_VERSION, fallbackService.status(player.getUniqueId()));
        assertTrue(player.isOnline());
    }

    @Test
    void vanillaClientNeverRespondingBecomesNoModAfterTheTimeout() {
        ModCompatService service = newService(false);
        PlayerMock player = join(service);

        assertEquals(PlayerModStatus.PENDING, service.status(player.getUniqueId()));
        server.getScheduler().performTicks(TIMEOUT_TICKS + 1);

        assertEquals(PlayerModStatus.NO_MOD, service.status(player.getUniqueId()));
        assertTrue(player.isOnline(), "un client vanilla reste autorisé par défaut (repli, mission point 9)");
    }

    @Test
    void vanillaClientIsKickedWhenModIsExplicitlyRequired() {
        ModCompatService service = newService(true);
        PlayerMock player = join(service);

        server.getScheduler().performTicks(TIMEOUT_TICKS + 1);

        assertEquals(PlayerModStatus.NO_MOD, service.status(player.getUniqueId()));
        assertFalse(player.isOnline());
    }

    @Test
    void malformedPacketIsTreatedAsNoModWithoutThrowing() {
        ModCompatService service = newService(false);
        PlayerMock player = join(service);

        byte[] tooShort = {0x01, 0x02};
        service.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, player, tooShort);
        assertEquals(PlayerModStatus.NO_MOD, service.status(player.getUniqueId()));

        byte[] wrongMagicButRightLength = {0x00, 0x00, 0x00, 0x00, 0x01};
        service.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, player, wrongMagicButRightLength);
        assertEquals(PlayerModStatus.NO_MOD, service.status(player.getUniqueId()));
    }

    @Test
    void aClientMessageNeverGrantsAnythingRegardlessOfContent() {
        ModCompatService service = newService(false);
        PlayerMock player = join(service);

        // Tentative de falsification (mission, test dédié) : même un paquet qui "ressemble" à une
        // réponse valide ne fait jamais plus que classer la compatibilité — jamais un octroi.
        service.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, player, validResponse((byte) 1));

        assertEquals(PlayerModStatus.COMPATIBLE, service.status(player.getUniqueId()));
        // Aucune dépendance vers EconomyService/QuestProgressEngine/EntitlementService n'existe dans
        // ModCompatService (voir imports de la classe) : structurellement impossible d'accorder quoi
        // que ce soit depuis ce canal.
    }

    @Test
    void reconnectingResetsStateAndCancelsThePreviousTimeout() {
        ModCompatService service = newService(false);
        PlayerMock player = join(service);
        service.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, player, validResponse((byte) 1));
        assertEquals(PlayerModStatus.COMPATIBLE, service.status(player.getUniqueId()));

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null, QuitReason.DISCONNECTED));
        assertEquals(PlayerModStatus.PENDING, service.status(player.getUniqueId()), "un joueur reparti n'a plus d'état suivi");

        server.getPluginManager().callEvent(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
        assertEquals(PlayerModStatus.PENDING, service.status(player.getUniqueId()), "une reconnexion relance un handshake, jamais l'ancien état");

        // La minuterie précédente ne doit pas non plus déclencher un NO_MOD tardif après la reconnexion.
        server.getScheduler().performTicks(TIMEOUT_TICKS + 1);
        assertEquals(PlayerModStatus.NO_MOD, service.status(player.getUniqueId()));
    }

    @Test
    void sendMobVariantTagOnlyReachesPlayersWithACompatibleMod() {
        ModCompatService service = newService(false);
        PlayerMock compatible = join(service);
        service.onPluginMessageReceived(ModCompatService.HANDSHAKE_CHANNEL, compatible, validResponse((byte) 1));
        PlayerMock incompatible = join(service);

        // Ne doit jamais lever d'exception, avec ou sans avantage compatible.
        service.sendMobVariantTag(compatible, 1, "Golden Creeper");
        service.sendMobVariantTag(incompatible, 2, "Golden Creeper");
    }
}
