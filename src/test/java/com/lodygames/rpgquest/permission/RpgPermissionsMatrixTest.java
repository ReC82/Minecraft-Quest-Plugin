package com.lodygames.rpgquest.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.RPGQuestPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Issue #27 — la matrice des droits fins, telle que les protections la consultent : chaque nœud
 * accorde ce qu'il dit, et rien d'autre. Voir {@code docs/PERMISSIONS.md}.
 */
class RpgPermissionsMatrixTest {

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

    private PlayerMock with(String... nodes) {
        PlayerMock p = server.addPlayer();
        p.setOp(false);
        for (String n : nodes) {
            p.addAttachment(plugin, n, true);
        }
        return p;
    }

    @Test
    void aPlayerWithoutPermissionBuildsNowhereAndBypassesNothing() {
        PlayerMock p = with();
        assertFalse(RpgPermissions.canBuildInHub(p, "world_hub"));
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM));
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM_WORLD));
        assertFalse(RpgPermissions.canEnterAdminCommand(p));
    }

    @Test
    void aHubBuilderBuildsInThatHubOnlyAndNeverTouchesClaims() {
        PlayerMock p = with(RpgPermissions.buildHub("world_hub"));
        assertTrue(RpgPermissions.canBuildInHub(p, "world_hub"));
        assertFalse(RpgPermissions.canBuildInHub(p, "world_hub_2"), "un autre Hub reste fermé");
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM), "aucun droit sur les claims joueurs");
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM_WORLD), "ni sur l'accès au monde Claims");
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_ZONE));
        assertFalse(RpgPermissions.canEnterAdminCommand(p));
    }

    @Test
    void allHubsIsStillNotAClaimBypass() {
        PlayerMock p = with(RpgPermissions.BUILD_HUB_ALL);
        assertTrue(RpgPermissions.canBuildInHub(p, "world_hub"));
        assertTrue(RpgPermissions.canBuildInHub(p, "world_hub_2"));
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM));
    }

    @Test
    void theWildPermissionGrantsNoClaimBypass() {
        PlayerMock p = with(RpgPermissions.BUILD_WILD);
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM));
        assertFalse(RpgPermissions.canBuildInHub(p, "world_hub"));
    }

    @Test
    void anNpcEditorRunsNpcActionsWithoutTheOtherAdminBranches() {
        PlayerMock p = with(RpgPermissions.ADMIN_COMMAND, RpgPermissions.ADMIN_NPC_TAG);
        assertTrue(RpgPermissions.canEnterAdminCommand(p));
        assertTrue(RpgPermissions.canRunNpcAction(p, RpgPermissions.ADMIN_NPC_TAG));
        assertFalse(RpgPermissions.canRunNpcAction(p, RpgPermissions.ADMIN_NPC_UNTAG), "seulement l'action donnée");
        assertFalse(RpgPermissions.canRunLegacyAdminBranch(p), "pas les autres branches de /rpgadmin");
        assertFalse(RpgPermissions.canManageCities(p));
        assertFalse(RpgPermissions.canBuildInHub(p, "world_hub"));
    }

    @Test
    void theLegacyAdminWorldNodeStillGrantsEverythingAndIsTheOpsImplicitBypass() {
        // Constat d'audit (#27, #35, #321) : c'est le seul nœud qui ouvre tout, et plugin.yml le donne
        // aux OP par défaut. Ce test documente l'état actuel ; le mode bypass explicite (#35) le remplacera.
        PlayerMock p = with(RpgPermissions.LEGACY_ADMIN_WORLD);
        assertTrue(RpgPermissions.canBuildInHub(p, "world_hub"));
        assertTrue(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM_WORLD));
        assertTrue(RpgPermissions.canRunLegacyAdminBranch(p));
    }
}
