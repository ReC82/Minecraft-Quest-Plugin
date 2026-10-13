package com.lodygames.rpgquest.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Issue #316 — le joueur est prévenu en privé, une fois, de l'état réel de ses droits OP. */
class OperatorNoticeServiceTest {

    @TempDir
    Path tmp;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerVariableRepository variables;
    private PlayerProfileRepository profiles;
    private OperatorNoticeService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tmp.resolve("t.db"));
        database.initialize().get(10, TimeUnit.SECONDS);
        variables = new PlayerVariableRepository(database);
        profiles = new PlayerProfileRepository(database);
        QuestMessagesService messages = new QuestMessagesService(plugin);
        messages.reload();
        service = new OperatorNoticeService(plugin, variables, messages);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void anOnlinePlayerIsToldOnceAfterOpThenDeop() throws Exception {
        PlayerMock p = player();
        drain(p);
        assertEquals(OperatorNoticeService.Delivery.DELIVERED, service.changed(p.getUniqueId(), false, true));
        assertEquals(List.of("Tes droits opérateur Minecraft (OP) ont été activés."), drain(p));
        service.changed(p.getUniqueId(), true, false);
        assertEquals(List.of("Tes droits opérateur Minecraft (OP) ont été retirés."), drain(p));
    }

    @Test
    void anAbsentPlayerGetsOneSummaryOfTheFinalStateAtLogin() throws Exception {
        PlayerMock p = player();
        UUID id = p.getUniqueId();
        p.disconnect();
        assertEquals(OperatorNoticeService.Delivery.DEFERRED, service.changed(id, false, true));
        settle();
        p.reconnect();
        p.setOp(true);
        drain(p);
        service.onJoin(new PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        settle();
        assertEquals(List.of("Pendant ton absence, tes droits opérateur Minecraft (OP) ont été activés."), drain(p));
        service.onJoin(new PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        settle();
        assertTrue(drain(p).isEmpty(), "jamais deux fois");
    }

    @Test
    void opThenDeopWhileAwayLeavesNothingToAnnounce() throws Exception {
        PlayerMock p = player();
        UUID id = p.getUniqueId();
        p.disconnect();
        service.changed(id, false, true);
        settle();
        service.changed(id, true, false);
        settle();
        p.reconnect();
        p.setOp(false);
        drain(p);
        service.onJoin(new PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        settle();
        assertTrue(drain(p).isEmpty(), "état final identique à ce qu'il connaissait : rien à dire");
    }

    @Test
    void aPlayerNeverNotifiedHearsNothingAtLogin() throws Exception {
        PlayerMock p = player();
        drain(p);
        service.onJoin(new PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        settle();
        assertTrue(drain(p).isEmpty());
        assertNull(variables.get(p.getUniqueId(), OperatorNoticeService.NOTICE_KEY).get(5, TimeUnit.SECONDS).orElse(null));
    }

    private PlayerMock player() throws Exception {
        PlayerMock p = server.addPlayer();
        profiles.findOrCreate(p.getUniqueId(), p.getName()).get(10, TimeUnit.SECONDS);
        return p;
    }

    private void settle() throws Exception {
        for (int i = 0; i < 40; i++) {
            server.getScheduler().performTicks(1);
            Thread.sleep(10);
        }
    }

    private static List<String> drain(PlayerMock p) {
        List<String> out = new ArrayList<>();
        // Seuls les messages OP comptent : le plugin chargé envoie aussi les siens (rune, bêta…).
        for (String m = p.nextMessage(); m != null; m = p.nextMessage()) {
            String plain = m.replaceAll("§.", "");
            if (plain.contains("droits opérateur")) {
                out.add(plain);
            }
        }
        return out;
    }
}
