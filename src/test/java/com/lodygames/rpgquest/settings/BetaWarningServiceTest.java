package com.lodygames.rpgquest.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.DatabaseManager;
import com.lodygames.rpgquest.database.PlayerProfileRepository;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.travel.WildEntryPrompt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Issue #28 — avertissement bêta : OK, OK et ne plus afficher, désactivation globale, une fois. */
class BetaWarningServiceTest {

    @TempDir
    Path tmp;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private DatabaseManager database;
    private PlayerVariableRepository variables;
    private PlayerProfileRepository profiles;
    private final List<WildEntryPrompt> shown = new ArrayList<>();
    private final AtomicReference<GameSettings> settings = new AtomicReference<>(GameSettings.DEFAULTS);
    private BetaWarningService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        database = new DatabaseManager(tmp.resolve("t.db"));
        database.initialize().get(10, TimeUnit.SECONDS);
        variables = new PlayerVariableRepository(database);
        profiles = new PlayerProfileRepository(database);
        service = new BetaWarningService(plugin, variables, settings::get, (player, prompt) -> shown.add(prompt));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void okShowsItAgainNextTimeAndTheButtonsAreClear() throws Exception {
        PlayerMock p = player();
        join(p);
        assertEquals(1, shown.size());
        List<String> labels = shown.get(0).buttons().stream()
                .map(b -> PlainTextComponentSerializer.plainText().serialize(b.label())).toList();
        assertEquals(List.of("OK", "OK et ne plus afficher"), labels);
        shown.get(0).buttons().get(0).action().run();
        join(p);
        assertEquals(1, shown.size(), "pas deux fois dans la même connexion");
        service.onQuit(new org.bukkit.event.player.PlayerQuitEvent(p, net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        join(p);
        assertEquals(2, shown.size(), "revient à la connexion suivante");
    }

    @Test
    void okAndHideIsRememberedAndTheGlobalSwitchKeepsIt() throws Exception {
        PlayerMock p = player();
        join(p);
        shown.get(0).buttons().get(1).action().run();
        settle();
        assertEquals("true", variables.get(p.getUniqueId(), BetaWarningService.HIDDEN_KEY).get(5, TimeUnit.SECONDS).orElse(""));
        quit(p);
        join(p);
        assertEquals(1, shown.size(), "plus affiché");

        settings.set(GameSettings.DEFAULTS.with(java.util.Map.of("beta_warning_enabled", "false")).settings());
        PlayerMock other = player();
        join(other);
        assertEquals(1, shown.size(), "désactivé pour tous");
        assertEquals("true", variables.get(p.getUniqueId(), BetaWarningService.HIDDEN_KEY).get(5, TimeUnit.SECONDS).orElse(""),
                "le choix du joueur n'est pas effacé");
    }

    @Test
    void theConfiguredTextIsShown() throws Exception {
        settings.set(GameSettings.DEFAULTS.with(java.util.Map.of("beta_warning_text", "Bêta, attention !")).settings());
        PlayerMock p = player();
        join(p);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(shown.get(0).body().get(0)).contains("Bêta, attention !"));
    }

    private void join(PlayerMock p) throws Exception {
        service.onJoin(new PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        settle();
    }

    private void quit(PlayerMock p) {
        service.onQuit(new org.bukkit.event.player.PlayerQuitEvent(p, net.kyori.adventure.text.Component.empty(),
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
    }

    private PlayerMock player() throws Exception {
        PlayerMock p = server.addPlayer();
        profiles.findOrCreate(p.getUniqueId(), p.getName()).get(10, TimeUnit.SECONDS);
        return p;
    }

    private void settle() throws Exception {
        for (int i = 0; i < 50; i++) {
            server.getScheduler().performTicks(2);
            Thread.sleep(10);
        }
    }
}
