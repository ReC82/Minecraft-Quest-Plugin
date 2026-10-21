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
    void theTesterBundleRunsTheTestBranchesAndBypassesNothing() {
        // Issue #64 : un testeur non-OP utilise player/quest/story et reste un joueur normal pour
        // toutes les protections (Hub, claims, monde des claims, zones) et les autres branches.
        PlayerMock p = with(RpgPermissions.ADMIN_TESTING);
        assertFalse(p.isOp());
        assertTrue(RpgPermissions.canEnterAdminCommand(p));
        assertTrue(RpgPermissions.canRunTestingBranch(p, RpgPermissions.ADMIN_PLAYER));
        assertTrue(RpgPermissions.canRunTestingBranch(p, RpgPermissions.ADMIN_QUEST));
        assertTrue(RpgPermissions.canRunTestingBranch(p, RpgPermissions.ADMIN_STORY));
        assertFalse(RpgPermissions.canBuildInHub(p, "world_hub"), "aucune construction dans le Hub");
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM));
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM_WORLD));
        assertFalse(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_ZONE));
        assertFalse(RpgPermissions.canRunLegacyAdminBranch(p), "pas les autres branches de /rpgadmin");
        assertFalse(RpgPermissions.canRunNpcAction(p, RpgPermissions.ADMIN_NPC_TAG));
        assertFalse(RpgPermissions.canManageCities(p));
        assertFalse(RpgPermissions.canManageBuildingSites(p));
        assertFalse(p.hasPermission("rpgquest.admin.bypass"), "aucune session /rpgadmin bypass");
        assertFalse(p.hasPermission("rpgquest.admin.debug"), "variable set reste hors du paquet");
        assertFalse(p.hasPermission("rpgquest.admin.hub.combat"));
        assertFalse(p.hasPermission("rpgquest.admin.hub.resources"), "#675 : coffres et commerce du Hub fermés");
    }

    @Test
    void anOrdinaryNonOpPlayerRunsNoTestBranch() {
        PlayerMock p = with();
        assertFalse(RpgPermissions.canRunTestingBranch(p, RpgPermissions.ADMIN_PLAYER));
        assertFalse(RpgPermissions.canRunTestingBranch(p, RpgPermissions.ADMIN_QUEST));
        assertFalse(RpgPermissions.canRunTestingBranch(p, RpgPermissions.ADMIN_STORY));
    }

    @Test
    void onlyPlayerQuestAndStoryAreTestBranches() {
        assertTrue(RpgPermissions.ADMIN_PLAYER.equals(RpgPermissions.testingBranchNode("PLAYER")));
        assertTrue(RpgPermissions.ADMIN_QUEST.equals(RpgPermissions.testingBranchNode("quest")));
        assertTrue(RpgPermissions.ADMIN_STORY.equals(RpgPermissions.testingBranchNode("story")));
        for (String other : new String[] {"zone", "flatten", "claim", "kit", "bypass", "npc", "world", "vault"}) {
            assertTrue(RpgPermissions.testingBranchNode(other) == null, other + " n'est pas une branche de test");
        }
    }

    @Test
    void theLegacyAdminWorldNodeStillGrantsEverythingAndIsTheOpsImplicitBypass() {
        // Constat d'audit (#27, #35, #321) : c'est le seul nœud qui ouvre tout, et plugin.yml le donne
        // aux OP par défaut. Depuis #35, ses bypass de règles de jeu ne comptent QUE pendant une session
        // /rpgadmin bypass on (voir AdminBypassModeTest) ; ses droits d'administration restent entiers.
        PlayerMock p = with(RpgPermissions.LEGACY_ADMIN_WORLD);
        assertTrue(RpgPermissions.canBuildInHub(p, "world_hub"));
        assertTrue(RpgPermissions.hasBypass(p, RpgPermissions.BYPASS_CLAIM_WORLD));
        assertTrue(RpgPermissions.canRunLegacyAdminBranch(p));
    }
}
