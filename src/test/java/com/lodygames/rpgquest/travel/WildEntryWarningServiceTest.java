package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Mission « avertissement avant entrée dans le Wild » : couvre la décision du garde
 * ({@link WildEntryWarningService#allowEntry}) et les branches Continuer/Annuler.
 */
class WildEntryWarningServiceTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private YamlCustomItemRegistry customItemRegistry;
    private WildEntryWarningService guard;
    private final AtomicInteger teleportNowCalls = new AtomicInteger();

    private static final WorldPortalDefinition TO_WILD = new WorldPortalDefinition(
            "hub_to_wild", "world_hub", 0, 0, 0, 4, 4, 4, "wild", true);
    private static final WorldPortalDefinition TO_CLAIMS = new WorldPortalDefinition(
            "hub_to_claims", "world_hub", 0, 0, 0, 4, 4, 4, "claims", true);

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world_hub");
        customItemRegistry = new YamlCustomItemRegistry(tempDir.resolve("items"), plugin.getSLF4JLogger());
        customItemRegistry.start();

        guard = new WildEntryWarningService(plugin, customItemRegistry, () -> "wild",
                (player, portal) -> teleportNowCalls.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock player() {
        PlayerMock p = server.addPlayer();
        p.teleport(new Location(server.getWorld("world_hub"), 2, 2, 2));
        return p;
    }

    private List<String> drainMessages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        String next;
        while ((next = player.nextMessage()) != null) {
            out.add(next);
        }
        return out;
    }

    @Test
    void aPlayerWithoutARuneIsWarnedAndBlockedWithBothButtons() {
        PlayerMock player = player();

        boolean allowed = guard.allowEntry(player, TO_WILD);

        assertFalse(allowed, "sans Rune, le passage vers le Wild doit être bloqué");
        List<String> messages = drainMessages(player);
        assertTrue(messages.stream().anyMatch(m -> m.contains("sans moyen de rappel")), "avertissement attendu");
        assertTrue(messages.stream().anyMatch(m -> m.contains("[Continuer]") && m.contains("[Annuler]")),
                "les deux boutons doivent être proposés");
    }

    @Test
    void aPlayerWithARuneIsNeverWarned() {
        PlayerMock player = player();
        player.getInventory().addItem(customItemRegistry.create(RpgItemKeys.RUNE_RAPPEL, 1).orElseThrow());

        assertTrue(guard.allowEntry(player, TO_WILD), "avec une Rune, le passage est autorisé");
        assertTrue(drainMessages(player).isEmpty(), "aucun avertissement quand le joueur a une Rune");
    }

    @Test
    void portalsThatDoNotLeadToTheWildAreNeverAffected() {
        PlayerMock player = player();
        assertTrue(guard.allowEntry(player, TO_CLAIMS));
        assertTrue(drainMessages(player).isEmpty());
    }

    @Test
    void theWarningIsNotSpammedWithinTheCooldown() {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        drainMessages(player);

        guard.allowEntry(player, TO_WILD);
        assertTrue(drainMessages(player).isEmpty(), "pas de second avertissement dans la fenêtre anti-spam");
    }

    @Test
    void continueGrantsAOneShotBypassThenTeleports() {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD); // bloqué + averti.

        guard.onContinue(player.getUniqueId(), TO_WILD);
        assertTrue(teleportNowCalls.get() >= 1, "« Continuer » doit relancer la téléportation");

        // Le laissez-passer est à usage unique : il autorise le passage une fois...
        assertTrue(guard.allowEntry(player, TO_WILD), "le bypass tout juste accordé autorise ce passage");
        // ...puis est consommé : un passage ultérieur est de nouveau bloqué.
        drainMessages(player);
        assertFalse(guard.allowEntry(player, TO_WILD), "le bypass ne doit servir qu'une fois");
    }

    @Test
    void cancelKeepsThePlayerAtTheHub() {
        PlayerMock player = player();
        guard.allowEntry(player, TO_WILD);
        drainMessages(player);

        guard.onCancel(player.getUniqueId());
        assertTrue(drainMessages(player).stream().anyMatch(m -> m.contains("restes au Hub")));
    }
}
