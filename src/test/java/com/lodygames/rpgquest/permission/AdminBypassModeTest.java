package com.lodygames.rpgquest.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.settings.GameSettings;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #35 — mode bypass administrateur explicite : OP/DEOP × session ON/OFF, réglages PlugAdmin
 * (#335), fin automatique annoncée, déconnexion et redémarrage. Les cas d'accès au monde Claims
 * (#321) sont dans {@code ClaimWorldAccessGuardTest}.
 */
class AdminBypassModeTest {

    private ServerMock server;
    private RPGQuestPlugin plugin;
    private GameSettings settings = GameSettings.DEFAULTS;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private AdminBypassMode mode;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(RPGQuestPlugin.class);
        mode = new AdminBypassMode(() -> settings, now::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock op() {
        PlayerMock p = server.addPlayer();
        p.setOp(true);
        return p;
    }

    private void settings(String key, String value) {
        GameSettings.Change c = settings.with(Map.of(key, value));
        assertTrue(c.ok(), c.errors().toString());
        settings = c.settings();
    }

    @Test
    void anOpIsNeverBypassingByDefault() {
        PlayerMock op = op();
        assertTrue(RpgPermissions.hasBypass(op, RpgPermissions.BYPASS_CLAIM_WORLD), "précondition : OP = ombrelle");
        assertFalse(mode.isActive(op), "OFF par défaut");
        assertFalse(mode.allows(op, RpgPermissions.BYPASS_CLAIM_WORLD));
        assertFalse(mode.allows(op, RpgPermissions.BYPASS_CLAIM));
        assertFalse(mode.allows(op, RpgPermissions.BYPASS_ZONE));
        assertFalse(mode.allowsExact(op, "rpgquest.admin.hub.combat"));
        assertFalse(mode.status(op).active());
    }

    @Test
    void anOpWhoTurnsItOnBypassesUntilTurningItOff() {
        PlayerMock op = op();
        assertEquals(AdminBypassMode.Outcome.ENABLED, mode.enable(op));
        assertTrue(mode.allows(op, RpgPermissions.BYPASS_CLAIM_WORLD));
        assertTrue(mode.allowsExact(op, "rpgquest.admin.hub.combat"));
        assertEquals(-1L, mode.status(op).remainingMillis(), "par défaut : jusqu'à la déconnexion");

        assertTrue(mode.disable(op.getUniqueId()));
        assertFalse(mode.allows(op, RpgPermissions.BYPASS_CLAIM_WORLD), "OFF : règles normales immédiatement");
        assertFalse(mode.disable(op.getUniqueId()), "déjà fermé");
    }

    @Test
    void aPlayerWithoutThePermissionCannotTurnItOn() {
        PlayerMock deop = server.addPlayer();
        deop.setOp(false);
        assertEquals(AdminBypassMode.Outcome.NO_PERMISSION, mode.enable(deop));
        assertFalse(mode.isActive(deop));
    }

    @Test
    void theSessionOnlyArmsTheBypassNodesTheAccountAlreadyHas() {
        PlayerMock p = server.addPlayer();
        p.addAttachment(plugin, AdminBypassMode.PERMISSION, true);
        p.addAttachment(plugin, RpgPermissions.BYPASS_ZONE, true);
        assertEquals(AdminBypassMode.Outcome.ENABLED, mode.enable(p));

        assertTrue(mode.allows(p, RpgPermissions.BYPASS_ZONE));
        assertFalse(mode.allows(p, RpgPermissions.BYPASS_CLAIM), "aucun droit ajouté par le mode");
        assertFalse(mode.allows(p, RpgPermissions.BYPASS_CLAIM_WORLD));
    }

    @Test
    void buildAndAdminRightsDoNotDependOnTheMode() {
        PlayerMock builder = server.addPlayer();
        builder.addAttachment(plugin, RpgPermissions.BUILD_HUB_ALL, true);
        assertTrue(RpgPermissions.canBuildInHub(builder, "world_hub"));
        assertEquals(AdminBypassMode.Outcome.NO_PERMISSION, mode.enable(builder), "un builder n'ouvre pas de bypass");

        PlayerMock op = op();
        assertTrue(RpgPermissions.canRunLegacyAdminBranch(op), "OP : commandes d'administration intactes, mode OFF");
        assertTrue(RpgPermissions.canBuildInHub(op, "world_hub"));
    }

    @Test
    void theModeCanBeWithdrawnInPlugAdmin() {
        PlayerMock op = op();
        mode.enable(op);
        settings("admin_bypass_available", "false");

        assertFalse(mode.isActive(op), "plus aucun effet dès le retrait");
        assertEquals(AdminBypassMode.EndReason.UNAVAILABLE, mode.endReason(op));
        assertTrue(mode.activePlayers().isEmpty(), "session close");
        assertEquals(AdminBypassMode.Outcome.UNAVAILABLE, mode.enable(op));
    }

    @Test
    void aMaximumDurationEndsTheSession() {
        settings("admin_bypass_max_minutes", "30");
        PlayerMock op = op();
        mode.enable(op);
        assertEquals(30 * 60_000L, mode.status(op).remainingMillis());

        now.addAndGet(30 * 60_000L);
        assertFalse(mode.isActive(op));
        assertEquals(AdminBypassMode.EndReason.EXPIRED, mode.endReason(op));
        assertNull(mode.endReason(op), "déjà close");
    }

    @Test
    void losingThePermissionEndsTheSession() {
        PlayerMock p = server.addPlayer();
        PermissionAttachment grant = p.addAttachment(plugin, AdminBypassMode.PERMISSION, true);
        p.addAttachment(plugin, RpgPermissions.BYPASS_CLAIM, true);
        mode.enable(p);
        assertTrue(mode.allows(p, RpgPermissions.BYPASS_CLAIM));

        grant.unsetPermission(AdminBypassMode.PERMISSION);
        assertFalse(mode.allows(p, RpgPermissions.BYPASS_CLAIM));
        assertEquals(AdminBypassMode.EndReason.PERMISSION_LOST, mode.endReason(p));
    }

    @Test
    void theServiceShowsAnIndicatorOnlyWhileActiveAndAnnouncesTheEnd() {
        AdminBypassService service = new AdminBypassService(plugin, mode);
        PlayerMock op = op();
        PlayerMock other = op();
        mode.enable(op);

        service.tick();
        assertTrue(plainActionBar(op).contains("MODE ADMIN / BYPASS ACTIF"), "indicateur visible");
        assertNull(other.nextActionBar(), "rien pour un OP sans session");

        settings("admin_bypass_available", "false");
        service.tick();
        String msg = nextMessage(op);
        assertTrue(msg.contains("désactivé") && msg.contains("PlugAdmin"), msg);
        assertTrue(mode.activePlayers().isEmpty());
    }

    @Test
    void disconnectingAndRestartingNeverKeepTheSession() {
        AdminBypassService service = new AdminBypassService(plugin, mode);
        PlayerMock op = op();
        mode.enable(op);
        service.onQuit(new PlayerQuitEvent(op, net.kyori.adventure.text.Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(mode.isActive(op), "déconnexion : OFF, rien de conservé à la reconnexion");

        mode.enable(op);
        service.stop();
        assertFalse(mode.isActive(op), "arrêt / rechargement : aucune session ne survit");
        assertTrue(mode.activePlayers().isEmpty());
    }

    private static String plainActionBar(PlayerMock p) {
        var c = p.nextActionBar();
        return c == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String nextMessage(PlayerMock p) {
        String m = p.nextMessage();
        return m == null ? "" : m;
    }
}
