package com.lodygames.rpgquest.travel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.travel.model.WorldPortalDefinition;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * TODO(debug bug TP hub) : outil de diagnostic ({@code /rpgadmin worldportal debug}) — couvre l'état
 * (visible/masqué), pas le rendu par particules/étiquette lui-même (peu vérifiable de façon
 * significative via MockBukkit ; {@link #renderingTickNeverThrowsWithAVisiblePortalInALoadedWorld}
 * confirme au moins l'absence de crash, dans le même esprit que les autres tests
 * « assertDoesNotThrow » déjà présents dans ce projet).
 */
class WorldPortalDebugServiceTest {

    @TempDir
    Path tempDir;

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private WorldPortalRegistry registry;
    private WorldPortalDebugService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        server.addSimpleWorld("world");

        registry = new WorldPortalRegistry(tempDir.resolve("world-portals"), NOPLogger.NOP_LOGGER);
        registry.start();
        registry.create(new WorldPortalDefinition(
                "hub_to_wild", "world", -5, 60, -5, 5, 70, 5, "wild", true));

        service = new WorldPortalDebugService(plugin, registry, NOPLogger.NOP_LOGGER);
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
        MockBukkit.unmock();
    }

    @Test
    void showingAKnownPortalMarksItVisible() {
        assertEquals(WorldPortalDebugService.ShowOutcome.SHOWN, service.show("hub_to_wild"));

        assertTrue(service.visiblePortalIds().contains("hub_to_wild"));
    }

    @Test
    void showingAnUnknownPortalIsRejected() {
        assertEquals(WorldPortalDebugService.ShowOutcome.UNKNOWN_PORTAL, service.show("does_not_exist"));

        assertTrue(service.visiblePortalIds().isEmpty());
    }

    @Test
    void showingAnAlreadyVisiblePortalIsReportedAsSuch() {
        service.show("hub_to_wild");

        assertEquals(WorldPortalDebugService.ShowOutcome.ALREADY_VISIBLE, service.show("hub_to_wild"));
    }

    @Test
    void hidingAVisiblePortalRemovesIt() {
        service.show("hub_to_wild");

        assertEquals(WorldPortalDebugService.HideOutcome.HIDDEN, service.hide("hub_to_wild"));
        assertTrue(service.visiblePortalIds().isEmpty());
    }

    @Test
    void hidingAPortalThatWasNeverShownIsReportedAsSuch() {
        assertEquals(WorldPortalDebugService.HideOutcome.NOT_VISIBLE, service.hide("hub_to_wild"));
    }

    @Test
    void showAllMakesEveryLoadedPortalVisibleAndReturnsTheCount() {
        registry.create(new WorldPortalDefinition(
                "wild_to_hub", "wild", 0, 60, 0, 10, 70, 10, "world", true));

        int shown = service.showAll();

        assertEquals(2, shown);
        assertEquals(2, service.visiblePortalIds().size());
    }

    @Test
    void showAllOnlyCountsNewlyShownPortals() {
        service.show("hub_to_wild");

        int shown = service.showAll();

        assertEquals(0, shown, "hub_to_wild était déjà visible : ne doit pas être recompté");
    }

    @Test
    void hideAllClearsEveryVisiblePortalAndReturnsTheCount() {
        service.show("hub_to_wild");

        int hidden = service.hideAll();

        assertEquals(1, hidden);
        assertTrue(service.visiblePortalIds().isEmpty());
    }

    @Test
    void stopClearsAllVisiblePortals() {
        service.show("hub_to_wild");

        service.stop();

        assertTrue(service.visiblePortalIds().isEmpty(), "annulation à 100% : rien ne doit rester affiché après stop()");
    }

    @Test
    void renderingTickNeverThrowsWithAVisiblePortalInALoadedWorld() {
        service.show("hub_to_wild");

        assertDoesNotThrow(() -> server.getScheduler().performTicks(15));
    }

    @Test
    void renderingTickNeverThrowsWhenTheDestinationOrSourceWorldIsNotLoaded() {
        registry.create(new WorldPortalDefinition(
                "hub_to_unloaded", "not_loaded_world", 0, 60, 0, 10, 70, 10, "wild", true));
        service.show("hub_to_unloaded");

        assertDoesNotThrow(() -> server.getScheduler().performTicks(15));
    }
}
