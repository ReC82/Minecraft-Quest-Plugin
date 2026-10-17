package com.lodygames.rpgquest.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.economy.TransactionType;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #184 — branchement de {@code /hud} et du HUD dans le plugin réel. MockBukkit n'implémente
 * ni {@code Objective#numberFormat} ni {@code Score#customName} : le rendu du panneau latéral
 * lui-même relève de la validation en jeu (PENDING MANUAL VALIDATION).
 */
class PlayerHudServiceIntegrationTest {

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
    void hudCommandIsRegisteredWithTabCompletion() {
        var hud = plugin.getCommand("hud");
        assertNotNull(hud);
        assertEquals(List.of("sidebar"), hud.tabComplete(server.addPlayer(), "hud", new String[] {"s"}));
    }

    @Test
    void offByDefaultAndOffChoiceIsStoredOnThePlayerWithoutTouchingTheScoreboard() throws Exception {
        PlayerMock player = server.addPlayer();
        var main = server.getScoreboardManager().getMainScoreboard();

        player.performCommand("hud off");
        // Un gain réel avec le panneau masqué ne doit rien casser (retour en action bar seulement).
        plugin.bootstrap().economyService()
                .credit(player.getUniqueId(), 42, TransactionType.ADMIN_GRANT, "test #184")
                .get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 10; i++) {
            Thread.sleep(20);
            server.getScheduler().performOneTick();
        }

        assertSame(main, player.getScoreboard());
        assertEquals("OFF", player.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "hud_mode"), PersistentDataType.STRING));
    }
}
