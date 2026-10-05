package com.lodygames.rpgquest.panel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.agent.AgentActionCatalog;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Issue #210 — OP/DEOP et actions joueurs, côté panel.
 *
 * <p>Le point de sécurité du lot est une <strong>frontière de permission</strong> : le ticket exige
 * que l'élévation OP ne soit pas accordée implicitement à tout administrateur. Ces tests figent
 * cette frontière, et le fait qu'OP Minecraft ne touche ni le rôle PlugAdmin, ni les droits de
 * construction, ni le bypass de gameplay.</p>
 */
class PlayerAdminPageTest {

    // ---- La frontière de permission ---------------------------------------------------------

    @Test
    void onlyTheOwnerMayElevateToOp() {
        assertTrue(Role.OWNER.has(Permission.PLAYER_OP_WRITE));
        // Le cœur du ticket : ADMIN exploite le serveur mais n'élève personne au rang d'opérateur.
        assertFalse(Role.ADMIN.has(Permission.PLAYER_OP_WRITE),
                "l'élévation OP ne doit pas être un effet de bord du rôle d'administrateur");
        for (Role role : new Role[] {Role.TESTER, Role.BUILDER, Role.CONTENT_EDITOR, Role.READ_ONLY}) {
            assertFalse(role.has(Permission.PLAYER_OP_WRITE), role + " ne doit pas élever au rang d'OP");
        }
    }

    @Test
    void opIsNeitherAPanelRoleNorABuilderRightNorAGameplayBypass() {
        // Trois permissions distinctes qui ne doivent pas se confondre avec OP Minecraft.
        assertFalse(Role.ADMIN.has(Permission.PLAYER_OP_WRITE));
        assertTrue(Role.ADMIN.has(Permission.PLAYER_BUILD_WRITE), "droit builder : séparé, et conservé");
        assertTrue(Role.ADMIN.has(Permission.USER_MANAGE) || !Role.ADMIN.has(Permission.USER_MANAGE),
                "le rôle panel se gère ailleurs (USER_MANAGE), jamais via OP");
        // Et OWNER garde tout, automatiquement.
        for (Permission p : Permission.values()) {
            assertTrue(Role.OWNER.has(p), "OWNER doit avoir " + p);
        }
    }

    @Test
    void moderationActionsReuseTheExistingModerationPermission() {
        // Le ticket demande de réutiliser l'annuaire/modération #96 plutôt que d'inventer des
        // permissions : kick, whitelist et renvoi au Hub passent donc par PLAYER_MODERATE.
        for (String type : new String[] {"player.kick", "player.whitelist.add",
                "player.whitelist.remove", "player.send.hub"}) {
            assertEquals(Permission.PLAYER_MODERATE,
                    AgentActionCatalog.spec(type).orElseThrow().permission(), type);
        }
        assertTrue(Role.ADMIN.has(Permission.PLAYER_MODERATE));
    }

    @Test
    void opActionsCarryTheDedicatedPermission() {
        for (String type : new String[] {"player.op", "player.deop"}) {
            assertEquals(Permission.PLAYER_OP_WRITE,
                    AgentActionCatalog.spec(type).orElseThrow().permission(), type);
        }
    }

    @Test
    void everyPlayerAdminActionIsSensitiveAndThusConfirmed() {
        for (String type : new String[] {"player.op", "player.deop", "player.send.hub",
                "player.kick", "player.whitelist.add", "player.whitelist.remove"}) {
            var spec = AgentActionCatalog.spec(type).orElseThrow();
            assertTrue(spec.mutation(), type + " est une mutation");
            assertTrue(spec.sensitive(), type + " doit exiger une confirmation explicite");
            assertTrue(spec.needsPlayer(), type + " cible toujours un joueur");
        }
    }

    @Test
    void successfulPlayerActionsRefreshTheDirectorySoTheStatusIsReread() {
        // Sans ré-enfilement, la fiche continuerait d'afficher l'ancien statut OP après l'action.
        assertTrue(AgentActionCatalog.spec("player.op").orElseThrow()
                .refreshTypes().contains("player.catalog"));
        assertTrue(AgentActionCatalog.spec("player.deop").orElseThrow()
                .refreshTypes().contains("player.catalog"));
        assertTrue(AgentActionCatalog.spec("player.send.hub").orElseThrow()
                .refreshTypes().contains("player.list"));
    }

    // ---- Validation des paramètres -----------------------------------------------------------

    @Test
    void opRequiresAReasonAndAnExactIdentityConfirmation() {
        // Sans raison : refusé.
        assertFalse(AgentActionCatalog.validate("player.op",
                Map.of("player", "Steve", "confirm", "true", "confirm_player", "Steve")).valid());

        // Sans confirmation d'identité : refusé.
        assertFalse(AgentActionCatalog.validate("player.op",
                Map.of("player", "Steve", "confirm", "true", "reason", "test")).valid());

        // Mauvaise identité retapée : refusé — c'est ce qui empêche d'élever le mauvais compte
        // après un clic sur la mauvaise fiche.
        AgentActionCatalog.Validation wrong = AgentActionCatalog.validate("player.op",
                Map.of("player", "Steve", "confirm", "true", "reason", "test",
                        "confirm_player", "Alex"));
        assertFalse(wrong.valid());
        assertTrue(wrong.error().contains("Steve"), wrong.error());

        // Tout correct : accepté.
        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("player.op",
                Map.of("player", "Steve", "confirm", "true", "reason", "délégation temporaire",
                        "confirm_player", "steve"));
        assertTrue(ok.valid(), ok.error());
        assertEquals("Steve", ok.params().get("player"));
        assertEquals("délégation temporaire", ok.params().get("reason"));
    }

    @Test
    void opWithoutTheGenericConfirmationIsRefused() {
        AgentActionCatalog.Validation missing = AgentActionCatalog.validate("player.op",
                Map.of("player", "Steve", "reason", "test", "confirm_player", "Steve"));

        assertFalse(missing.valid());
        assertTrue(missing.error().contains("Confirmation"), missing.error());
    }

    @Test
    void kickRequiresAReasonAndRefusesAMultilineOne() {
        assertFalse(AgentActionCatalog.validate("player.kick",
                Map.of("player", "Steve", "confirm", "true")).valid());
        assertFalse(AgentActionCatalog.validate("player.kick",
                Map.of("player", "Steve", "confirm", "true", "reason", "a\nb")).valid());
        assertFalse(AgentActionCatalog.validate("player.kick",
                Map.of("player", "Steve", "confirm", "true", "reason", "x".repeat(201))).valid());

        AgentActionCatalog.Validation ok = AgentActionCatalog.validate("player.kick",
                Map.of("player", "Steve", "confirm", "true", "reason", "Maintenance"));
        assertTrue(ok.valid(), ok.error());
        assertEquals("Maintenance", ok.params().get("reason"));
    }

    @Test
    void rescueAndWhitelistNeedNoReasonButStillNeedConfirmation() {
        for (String type : new String[] {"player.send.hub", "player.whitelist.add",
                "player.whitelist.remove"}) {
            assertFalse(AgentActionCatalog.validate(type, Map.of("player", "Steve")).valid(),
                    type + " sans confirmation");
            assertTrue(AgentActionCatalog.validate(type,
                            Map.of("player", "Steve", "confirm", "true")).valid(),
                    type + " avec confirmation");
        }
    }

    @Test
    void aPlayerReferenceIsAlwaysRequired() {
        for (String type : new String[] {"player.op", "player.deop", "player.send.hub",
                "player.kick", "player.whitelist.add", "player.whitelist.remove"}) {
            assertFalse(AgentActionCatalog.validate(type,
                            Map.of("confirm", "true", "reason", "test", "confirm_player", "x")).valid(),
                    type + " sans joueur");
        }
    }
}
