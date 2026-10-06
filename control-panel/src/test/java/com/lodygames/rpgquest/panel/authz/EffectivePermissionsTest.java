package com.lodygames.rpgquest.panel.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Combinaison des droits et provenance (issue #199).
 *
 * <p>La propriété figée ici est celle qui décide de tout le reste : <strong>l'union</strong>. Un
 * compte a un droit s'il le tient de son rôle ou d'au moins un groupe, et un groupe ne peut donc
 * jamais <em>réduire</em> ce que le rôle accorde. Les tests de « conflit » vérifient précisément
 * cela : il n'existe aucun refus capable d'écraser une autorisation, donc aucune priorité à
 * arbitrer.</p>
 */
class EffectivePermissionsTest {

    private static PanelGroup group(String name, Permission... permissions) {
        Set<Permission> set = permissions.length == 0
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.of(permissions[0], permissions);
        return new PanelGroup(name + "-id", name, "", set, Instant.now());
    }

    @Test
    void withoutAnyGroupTheEffectiveRightsAreExactlyTheRoleRights() {
        EffectivePermissions effective = EffectivePermissions.ofRoleOnly(Role.READ_ONLY);

        assertEquals(Role.READ_ONLY.permissions(), effective.granted());
        // C'est ce qui garantit la migration : une base sans groupe ne change aucun droit.
        for (Permission permission : Permission.values()) {
            assertEquals(Role.READ_ONLY.has(permission), effective.has(permission), permission.name());
        }
    }

    @Test
    void groupsAddToTheRoleAndNeverRemoveFromIt() {
        EffectivePermissions effective = new EffectivePermissions(Role.READ_ONLY,
                List.of(group("Éditeurs", Permission.QUEST_CONTENT_WRITE)));

        assertTrue(effective.has(Permission.QUEST_CONTENT_WRITE), "ajouté par le groupe");
        // Tout ce que le rôle donnait est toujours là : un groupe n'enlève rien.
        for (Permission permission : Role.READ_ONLY.permissions()) {
            assertTrue(effective.has(permission), permission.name());
        }
    }

    @Test
    void severalGroupsAreUnioned() {
        EffectivePermissions effective = new EffectivePermissions(Role.BUILDER, List.of(
                group("A", Permission.QUEST_CONTENT_WRITE),
                group("B", Permission.STORY_CONTENT_WRITE),
                group("C", Permission.OPS_LOGS)));

        assertTrue(effective.has(Permission.QUEST_CONTENT_WRITE));
        assertTrue(effective.has(Permission.STORY_CONTENT_WRITE));
        assertTrue(effective.has(Permission.OPS_LOGS));
    }

    @Test
    void anEmptyGroupChangesNothing() {
        EffectivePermissions withEmpty = new EffectivePermissions(Role.TESTER, List.of(group("Vide")));

        assertEquals(Role.TESTER.permissions(), withEmpty.granted());
    }

    @Test
    void aGroupCannotTakeAwayWhatTheRoleGrants() {
        // Le « conflit » annoncé par le ticket : un groupe qui n'accorde PAS un droit que le rôle
        // accorde. Avec l'union, le droit reste — et c'est la règle documentée, pas un oubli.
        EffectivePermissions effective = new EffectivePermissions(Role.OWNER,
                List.of(group("Restreint", Permission.DASHBOARD_VIEW)));

        assertTrue(effective.has(Permission.PLAYER_OP_WRITE),
                "aucun groupe ne peut retirer un droit du rôle : il n'existe pas de refus explicite");
        assertEquals(EnumSet.allOf(Permission.class), EnumSet.copyOf(effective.granted()));
    }

    @Test
    void theSameRightComingFromTwoPlacesListsBothOrigins() {
        EffectivePermissions effective = new EffectivePermissions(Role.CONTENT_EDITOR, List.of(
                group("Rédaction", Permission.QUEST_CONTENT_WRITE),
                group("Relecture", Permission.QUEST_CONTENT_WRITE)));

        List<EffectivePermissions.Source> sources = effective.sourcesOf(Permission.QUEST_CONTENT_WRITE);
        // Trois origines : le rôle + les deux groupes. Savoir qu'un droit arrive par PLUSIEURS
        // chemins est exactement l'information utile quand on cherche à le retirer.
        assertEquals(3, sources.size(), () -> sources.toString());
        assertEquals(EffectivePermissions.Source.Kind.ROLE, sources.get(0).kind());
        assertEquals("CONTENT_EDITOR", sources.get(0).name());
        assertTrue(sources.stream().anyMatch(s -> "Rédaction".equals(s.name())));
        assertTrue(sources.stream().anyMatch(s -> "Relecture".equals(s.name())));
    }

    @Test
    void aRightNotGrantedHasNoOrigin() {
        EffectivePermissions effective = EffectivePermissions.ofRoleOnly(Role.BUILDER);

        assertFalse(effective.has(Permission.PLAYER_OP_WRITE));
        assertTrue(effective.sourcesOf(Permission.PLAYER_OP_WRITE).isEmpty());
    }

    @Test
    void theOriginLabelSaysWhereTheRightComesFrom() {
        EffectivePermissions effective = new EffectivePermissions(Role.READ_ONLY,
                List.of(group("Testeurs avancés", Permission.OPS_LOGS)));

        assertEquals("groupe « Testeurs avancés »",
                effective.sourcesOf(Permission.OPS_LOGS).get(0).label());
        assertEquals("rôle READ_ONLY",
                effective.sourcesOf(Permission.DASHBOARD_VIEW).get(0).label());
    }

    @Test
    void anAccountWithoutRoleHasNothingEvenWithGroups() {
        // Montage dégradé (rôle inconnu en base) : ne rien accorder par le rôle, mais ne pas
        // perdre les groupes non plus — et surtout ne pas lever d'exception.
        EffectivePermissions effective = new EffectivePermissions(null,
                List.of(group("A", Permission.DOCS_READ)));

        assertTrue(effective.has(Permission.DOCS_READ));
        assertFalse(effective.has(Permission.USER_MANAGE));
    }
}
