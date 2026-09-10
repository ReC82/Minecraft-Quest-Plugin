package com.lodygames.rpgquest.panel.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import org.junit.jupiter.api.Test;

/**
 * Matrice de rôles PlugAdmin (issue #50) : vérifie que chaque rôle a exactement les capacités
 * attendues et, surtout, <strong>pas</strong> les capacités interdites. La source de vérité des
 * mutations est {@link AgentActionCatalog} : {@code READ_ONLY} ne doit détenir aucune permission
 * qui y déclenche une action de mutation.
 */
class RolePermissionMatrixTest {

    private final PermissionService authz = new PermissionService();

    private boolean can(Role role, Permission p) {
        return authz.can(role.name(), p);
    }

    // ---- OWNER ----------------------------------------------------------------------------

    @Test
    void ownerHasEveryPermissionAutomatically() {
        for (Permission p : Permission.values()) {
            assertTrue(Role.OWNER.has(p), "OWNER doit avoir " + p);
        }
        assertTrue(can(Role.OWNER, Permission.USER_MANAGE));
    }

    @Test
    void onlyOwnerManagesUsers() {
        assertTrue(Role.OWNER.has(Permission.USER_MANAGE));
        for (Role r : new Role[] {Role.ADMIN, Role.TESTER, Role.BUILDER, Role.CONTENT_EDITOR, Role.READ_ONLY}) {
            assertFalse(r.has(Permission.USER_MANAGE), r + " ne doit pas gérer les utilisateurs");
        }
    }

    @Test
    void onlyOwnerSeesDevModule() {
        assertTrue(Role.OWNER.has(Permission.DEV_MODULE));
        for (Role r : new Role[] {Role.ADMIN, Role.TESTER, Role.BUILDER, Role.CONTENT_EDITOR, Role.READ_ONLY}) {
            assertFalse(r.has(Permission.DEV_MODULE), r + " ne doit pas voir le module dev/déploiement");
        }
    }

    // ---- ADMIN -------------------------------------------------------------------------

    @Test
    void adminRunsServerOperationsButNotUserManagement() {
        assertTrue(can(Role.ADMIN, Permission.PLAYERS_READ));
        assertTrue(can(Role.ADMIN, Permission.PLAYER_MODERATE));
        assertTrue(can(Role.ADMIN, Permission.ACTION_QUEST));
        assertTrue(can(Role.ADMIN, Permission.ACTION_PLAYER_RESET));
        assertTrue(can(Role.ADMIN, Permission.NPC_SPAWN_WRITE));
        assertTrue(can(Role.ADMIN, Permission.DIAGNOSTICS_READ));
        assertFalse(can(Role.ADMIN, Permission.USER_MANAGE));
        assertFalse(can(Role.ADMIN, Permission.DEV_MODULE));
    }

    // ---- TESTER ----------------------------------------------------------------------

    @Test
    void testerPreparesTestsButCannotDeployOrModerateOrManageUsers() {
        assertTrue(can(Role.TESTER, Permission.ACTION_QUEST));
        assertTrue(can(Role.TESTER, Permission.ACTION_STORY));
        assertTrue(can(Role.TESTER, Permission.ACTION_VARIABLE_GET));
        assertTrue(can(Role.TESTER, Permission.DIAGNOSTICS_READ));
        // « pas de déploiement » : aucune écriture de contenu publiable
        assertFalse(can(Role.TESTER, Permission.QUEST_CONTENT_WRITE));
        assertFalse(can(Role.TESTER, Permission.STORY_CONTENT_WRITE));
        assertFalse(can(Role.TESTER, Permission.ACTION_VARIABLE_SET));
        assertFalse(can(Role.TESTER, Permission.ACTION_PLAYER_RESET));
        assertFalse(can(Role.TESTER, Permission.PLAYER_MODERATE));
        assertFalse(can(Role.TESTER, Permission.USER_MANAGE));
    }

    // ---- BUILDER ---------------------------------------------------------------------

    @Test
    void builderGetsDocsAndNpcInfoButNoPlayerDataNoServerAdmin() {
        assertTrue(can(Role.BUILDER, Permission.DOCS_READ));
        assertTrue(can(Role.BUILDER, Permission.NPC_READ));
        assertTrue(can(Role.BUILDER, Permission.CONTENT_READ));
        // pas de données joueurs sensibles
        assertFalse(can(Role.BUILDER, Permission.PLAYERS_READ));
        assertFalse(can(Role.BUILDER, Permission.PLAYER_MODERATE));
        // pas d'administration serveur
        assertFalse(can(Role.BUILDER, Permission.ACTION_QUEST));
        assertFalse(can(Role.BUILDER, Permission.NPC_WRITE));
        assertFalse(can(Role.BUILDER, Permission.USER_MANAGE));
    }

    // ---- CONTENT_EDITOR ------------------------------------------------------------

    @Test
    void contentEditorEditsContentButCannotModerateOrManageUsers() {
        assertTrue(can(Role.CONTENT_EDITOR, Permission.QUEST_CONTENT_WRITE));
        assertTrue(can(Role.CONTENT_EDITOR, Permission.STORY_CONTENT_WRITE));
        assertTrue(can(Role.CONTENT_EDITOR, Permission.DIALOGUE_WRITE));
        assertTrue(can(Role.CONTENT_EDITOR, Permission.NPC_WRITE));
        assertTrue(can(Role.CONTENT_EDITOR, Permission.CONTENT_READ));
        assertFalse(can(Role.CONTENT_EDITOR, Permission.PLAYER_MODERATE));
        assertFalse(can(Role.CONTENT_EDITOR, Permission.NPC_SPAWN_WRITE));
        assertFalse(can(Role.CONTENT_EDITOR, Permission.ACTION_PLAYER_RESET));
        assertFalse(can(Role.CONTENT_EDITOR, Permission.USER_MANAGE));
    }

    // ---- READ_ONLY -----------------------------------------------------------------

    @Test
    void readOnlyHoldsNoMutationPermissionAtAll() {
        for (Permission p : Permission.values()) {
            if (!Role.READ_ONLY.has(p)) {
                continue;
            }
            boolean drivesMutation = AgentActionCatalog.all().stream()
                    .anyMatch(spec -> spec.permission() == p && spec.mutation());
            assertFalse(drivesMutation, "READ_ONLY détient " + p + " qui pilote une mutation");
        }
        assertFalse(Role.READ_ONLY.has(Permission.QUEST_CONTENT_WRITE));
        assertFalse(Role.READ_ONLY.has(Permission.STORY_CONTENT_WRITE));
        assertFalse(Role.READ_ONLY.has(Permission.DIALOGUE_WRITE));
        assertFalse(Role.READ_ONLY.has(Permission.NPC_WRITE));
        assertFalse(Role.READ_ONLY.has(Permission.PLAYER_MODERATE));
        assertFalse(Role.READ_ONLY.has(Permission.USER_MANAGE));
    }

    @Test
    void everyNonOwnerRoleIsASubsetOfOwner() {
        for (Role r : Role.values()) {
            for (Permission p : r.permissions()) {
                assertTrue(Role.OWNER.has(p), "OWNER doit couvrir " + p + " (détenu par " + r + ")");
            }
        }
    }

    @Test
    void unknownOrNullRoleGrantsNothing() {
        assertFalse(authz.can(null, Permission.DASHBOARD_VIEW));
        assertFalse(authz.can("SUPERADMIN", Permission.DASHBOARD_VIEW));
        assertFalse(authz.can("owner", Permission.DASHBOARD_VIEW), "sensible à la casse : le nom exact est requis");
    }
}
