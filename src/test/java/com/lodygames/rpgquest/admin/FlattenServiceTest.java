package com.lodygames.rpgquest.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.ConfigService;
import com.lodygames.rpgquest.config.FlattenShape;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class FlattenServiceTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private ConfigService configService;
    private World world;
    private PlayerMock player;
    private AtomicLong clock;
    private FlattenService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        configService = plugin.bootstrap().configService();
        world = server.addSimpleWorld("world");
        player = server.addPlayer();
        player.teleport(new Location(world, 0.5, 65.0, 0.5));

        clock = new AtomicLong(0L);
        service = new FlattenService(plugin, configService, plugin.getSLF4JLogger(), clock::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void previewWithValidRadiusReturnsSquareEstimate() {
        FlattenService.PreviewResult result = service.preview(player, 2, null);

        assertEquals(FlattenService.PreviewOutcome.CREATED, result.outcome());
        FlattenEstimate estimate = result.estimate();
        assertEquals(FlattenShape.SQUARE, estimate.shape());
        assertEquals(25, estimate.columnCount(), "carré de rayon 2 = (2*2+1)^2 = 25 colonnes");
        assertEquals(64, estimate.y(), "hauteur par défaut = bloc sous les pieds du joueur (65 - 1)");
        assertTrue(service.hasPendingPreview(player.getUniqueId()));
    }

    @Test
    void previewRejectsNonPositiveRadius() {
        assertEquals(FlattenService.PreviewOutcome.INVALID_RADIUS, service.preview(player, 0, null).outcome());
        assertEquals(FlattenService.PreviewOutcome.INVALID_RADIUS, service.preview(player, -3, null).outcome());
    }

    @Test
    void previewRejectsRadiusAboveConfiguredMaximum() {
        int max = configService.current().adminFlatten().maxRadius();
        assertEquals(FlattenService.PreviewOutcome.INVALID_RADIUS, service.preview(player, max + 1, null).outcome());
    }

    @Test
    void previewRejectsHeightOutOfWorldBounds() {
        FlattenService.PreviewResult tooLow = service.preview(player, 1, world.getMinHeight() - 1);
        assertEquals(FlattenService.PreviewOutcome.INVALID_HEIGHT, tooLow.outcome());

        FlattenService.PreviewResult tooHigh = service.preview(player, 1, world.getMaxHeight());
        assertEquals(FlattenService.PreviewOutcome.INVALID_HEIGHT, tooHigh.outcome());
    }

    @Test
    void previewRejectsForbiddenWorld() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(configFile.toPath(), """
                admin:
                  flatten:
                    forbidden-worlds: ["world"]
                """);
        configService.reload();

        assertEquals(FlattenService.PreviewOutcome.FORBIDDEN_WORLD, service.preview(player, 2, null).outcome());
    }

    @Test
    void circleHasFewerOrEqualColumnsThanSquareOfSameRadius() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(configFile.toPath(), """
                admin:
                  flatten:
                    default-shape: CIRCLE
                """);
        configService.reload();

        FlattenService.PreviewResult circle = service.preview(player, 3, null);
        assertEquals(FlattenShape.CIRCLE, circle.estimate().shape());
        assertTrue(circle.estimate().columnCount() < 49, "un cercle de rayon 3 doit avoir moins de colonnes qu'un carré 7x7 (49)");
        assertTrue(circle.estimate().columnCount() > 0);
    }

    @Test
    void confirmWithoutPendingPreviewIsRejected() {
        assertEquals(FlattenService.ConfirmOutcome.NO_PENDING, service.confirm(player));
    }

    @Test
    void confirmAfterExpiryIsRejected() {
        service.preview(player, 1, null);
        int timeoutSeconds = configService.current().adminFlatten().confirmationTimeoutSeconds();
        clock.set((timeoutSeconds + 1) * 1000L);

        assertEquals(FlattenService.ConfirmOutcome.EXPIRED, service.confirm(player));
        assertFalse(service.hasPendingPreview(player.getUniqueId()), "un aperçu expiré doit être purgé");
    }

    @Test
    void confirmStartsOperationAndFlattensTheArea() {
        service.preview(player, 1, null);
        assertEquals(FlattenService.ConfirmOutcome.STARTED, service.confirm(player));
        assertTrue(service.hasActiveOperation(player.getUniqueId()));

        server.getScheduler().performTicks(2);

        assertFalse(service.hasActiveOperation(player.getUniqueId()), "petite zone : doit finir en un seul tick de budget");
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(0, 64, 0).getType());
        assertEquals(Material.DIRT, world.getBlockAt(0, 63, 0).getType());
    }

    @Test
    void confirmWhileAlreadyActiveIsRejected() {
        service.preview(player, 1, null);
        service.confirm(player);

        assertEquals(FlattenService.ConfirmOutcome.ALREADY_ACTIVE, service.confirm(player));
    }

    @Test
    void cancelPendingPreviewRemovesItWithoutTouchingBlocks() {
        Material before = world.getBlockAt(0, 64, 0).getType();
        service.preview(player, 1, null);

        assertEquals(FlattenService.CancelOutcome.CANCELLED_PENDING, service.cancel(player));
        assertFalse(service.hasPendingPreview(player.getUniqueId()));
        assertEquals(before, world.getBlockAt(0, 64, 0).getType());
    }

    @Test
    void cancelWithNothingPendingOrActiveReportsNothingToCancel() {
        assertEquals(FlattenService.CancelOutcome.NOTHING_TO_CANCEL, service.cancel(player));
    }

    @Test
    void cancelActiveOperationStopsFurtherProcessing() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(configFile.toPath(), """
                admin:
                  flatten:
                    blocks-per-tick: 1
                """);
        configService.reload();

        service.preview(player, 3, null); // 7x7 = 49 colonnes, largement plus qu'un tick à 1 bloc/tick
        service.confirm(player);
        server.getScheduler().performTicks(2);
        assertTrue(service.hasActiveOperation(player.getUniqueId()), "l'opération ne doit pas être finie après 2 ticks à 1 bloc/tick");

        assertEquals(FlattenService.CancelOutcome.CANCELLED_ACTIVE, service.cancel(player));
        assertFalse(service.hasActiveOperation(player.getUniqueId()));
    }

    @Test
    void undoWithoutAnyCompletedFlattenReportsNothingToUndo() {
        assertEquals(FlattenService.UndoOutcome.NOTHING_TO_UNDO, service.undo(player));
    }

    @Test
    void undoRestoresOriginalBlocksAfterACompletedFlatten() {
        world.getBlockAt(0, 64, 0).setType(Material.OAK_LOG);

        service.preview(player, 1, null);
        service.confirm(player);
        server.getScheduler().performTicks(2);
        assertEquals(Material.GRASS_BLOCK, world.getBlockAt(0, 64, 0).getType());

        assertEquals(FlattenService.UndoOutcome.UNDONE, service.undo(player));
        assertEquals(Material.OAK_LOG, world.getBlockAt(0, 64, 0).getType());
    }

    @Test
    void undoIsUnavailableAfterAlreadyBeingUsedOnce() {
        service.preview(player, 1, null);
        service.confirm(player);
        server.getScheduler().performTicks(2);

        assertEquals(FlattenService.UndoOutcome.UNDONE, service.undo(player));
        assertEquals(FlattenService.UndoOutcome.NOTHING_TO_UNDO, service.undo(player),
                "un seul niveau d'annulation : un second undo ne doit rien trouver");
    }

    @Test
    void undoIsRejectedWhileAnOperationIsStillInProgress() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(configFile.toPath(), """
                admin:
                  flatten:
                    blocks-per-tick: 1
                """);
        configService.reload();

        service.preview(player, 3, null);
        service.confirm(player);
        server.getScheduler().performTicks(1);

        assertEquals(FlattenService.UndoOutcome.OPERATION_IN_PROGRESS, service.undo(player));
    }
}
