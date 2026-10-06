package com.lodygames.rpgquest.panel.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.EffectivePermissions;
import com.lodygames.rpgquest.panel.authz.PanelGroup;
import com.lodygames.rpgquest.panel.authz.Permission;
import com.lodygames.rpgquest.panel.authz.Role;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Règles métier des groupes (issue #199).
 *
 * <p>La propriété la plus importante de ce fichier est l'<strong>anti-élévation de privilège</strong> :
 * on ne peut jamais accorder — ni retirer — un droit qu'on ne détient pas soi-même. C'est elle qui
 * empêche un administrateur de se fabriquer indirectement {@code PLAYER_OP_WRITE} ou
 * {@code ECONOMY_WRITE} en passant par un groupe.</p>
 */
class GroupDirectoryTest {

    private GroupDirectory groups;
    private InMemoryGroupRepository repo;

    private static final EffectivePermissions OWNER = EffectivePermissions.ofRoleOnly(Role.OWNER);
    private static final EffectivePermissions ADMIN = EffectivePermissions.ofRoleOnly(Role.ADMIN);

    @BeforeEach
    void setUp() {
        repo = new InMemoryGroupRepository();
        groups = new GroupDirectory(repo);
    }

    private static PanelUser user(String name, Role role) {
        return new PanelUser(name + "-id", name, "hash", role, true, Instant.now(), null);
    }

    private static Set<Permission> perms(Permission... permissions) {
        return permissions.length == 0 ? Set.of() : EnumSet.of(permissions[0], permissions);
    }

    // ---- Création / modification / suppression -------------------------------------------------

    @Test
    void anOwnerCreatesAGroupWithItsPermissions() {
        GroupDirectory.Outcome o = groups.create(OWNER, "Testeurs avancés", "Accès console",
                perms(Permission.OPS_LOGS, Permission.OPS_VIEW));

        assertTrue(o.ok(), o.error());
        assertEquals("Testeurs avancés", o.after().name());
        assertEquals(perms(Permission.OPS_LOGS, Permission.OPS_VIEW), o.after().permissions());
        assertEquals(1, groups.list().size());
    }

    @Test
    void aGroupNameMustBeValidAndUnique() {
        assertFalse(groups.create(OWNER, " ", "", perms()).ok());
        assertFalse(groups.create(OWNER, "x", "", perms()).ok(), "trop court");
        assertFalse(groups.create(OWNER, "a".repeat(49), "", perms()).ok(), "trop long");
        assertFalse(groups.create(OWNER, "Testeurs", "x".repeat(201), perms()).ok(), "description trop longue");

        assertTrue(groups.create(OWNER, "Testeurs", "", perms()).ok());
        assertFalse(groups.create(OWNER, "testeurs", "", perms()).ok(), "unicité insensible à la casse");
    }

    @Test
    void renamingKeepsThePermissions() {
        String id = groups.create(OWNER, "Avant", "", perms(Permission.DOCS_READ)).after().id();

        assertTrue(groups.rename(OWNER, id, "Après", "nouvelle description").ok());

        PanelGroup after = groups.byId(id).orElseThrow();
        assertEquals("Après", after.name());
        assertEquals("nouvelle description", after.description());
        assertEquals(perms(Permission.DOCS_READ), after.permissions());
    }

    @Test
    void deletingAGroupAlsoRemovesItsMemberships() {
        String id = groups.create(OWNER, "Temporaire", "", perms(Permission.DOCS_READ)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);
        assertTrue(groups.setMemberships(OWNER, steve, Set.of(id)).ok());
        assertEquals(1, groups.groupsOf(steve.id()).size());

        assertTrue(groups.delete(OWNER, id).ok());

        assertTrue(groups.byId(id).isEmpty());
        assertTrue(groups.groupsOf(steve.id()).isEmpty(),
                "une appartenance orpheline accorderait un droit fantôme");
    }

    @Test
    void anUnknownGroupIsRefusedWithoutSideEffects() {
        assertFalse(groups.rename(OWNER, "inconnu", "X", "").ok());
        assertFalse(groups.setPermissions(OWNER, "inconnu", perms(Permission.DOCS_READ)).ok());
        assertFalse(groups.delete(OWNER, "inconnu").ok());
        assertTrue(groups.list().isEmpty());
    }

    // ---- Appartenances multiples ---------------------------------------------------------------

    @Test
    void aUserBelongsToSeveralGroupsAndGetsTheUnion() {
        String a = groups.create(OWNER, "Rédaction", "", perms(Permission.QUEST_CONTENT_WRITE)).after().id();
        String b = groups.create(OWNER, "Console", "", perms(Permission.OPS_LOGS)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);

        assertTrue(groups.setMemberships(OWNER, steve, Set.of(a, b)).ok());

        EffectivePermissions effective = groups.effectiveFor(steve);
        assertTrue(effective.has(Permission.QUEST_CONTENT_WRITE));
        assertTrue(effective.has(Permission.OPS_LOGS));
        assertTrue(effective.has(Permission.DASHBOARD_VIEW), "le rôle reste la base");
        assertEquals(2, effective.groups().size());
    }

    @Test
    void removingAGroupRemovesExactlyItsRightsAndNothingElse() {
        String a = groups.create(OWNER, "Rédaction", "", perms(Permission.QUEST_CONTENT_WRITE)).after().id();
        String b = groups.create(OWNER, "Console", "", perms(Permission.OPS_LOGS)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);
        groups.setMemberships(OWNER, steve, Set.of(a, b));

        assertTrue(groups.setMemberships(OWNER, steve, Set.of(b)).ok());

        EffectivePermissions effective = groups.effectiveFor(steve);
        assertFalse(effective.has(Permission.QUEST_CONTENT_WRITE), "retiré avec le groupe");
        assertTrue(effective.has(Permission.OPS_LOGS), "l'autre groupe est intact");
        assertTrue(effective.has(Permission.DASHBOARD_VIEW), "le rôle est intact");
    }

    @Test
    void membershipOnAnUnknownGroupIsRefused() {
        PanelUser steve = user("steve", Role.READ_ONLY);

        GroupDirectory.MembershipOutcome o = groups.setMemberships(OWNER, steve, Set.of("inconnu"));

        assertFalse(o.ok());
        assertTrue(groups.groupsOf(steve.id()).isEmpty());
    }

    @Test
    void removingAPermissionFromAGroupAffectsAllItsMembersAtOnce() {
        String id = groups.create(OWNER, "Console", "", perms(Permission.OPS_LOGS)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);
        PanelUser alex = user("alex", Role.READ_ONLY);
        groups.setMemberships(OWNER, steve, Set.of(id));
        groups.setMemberships(OWNER, alex, Set.of(id));

        assertTrue(groups.setPermissions(OWNER, id, perms()).ok());

        assertFalse(groups.effectiveFor(steve).has(Permission.OPS_LOGS));
        assertFalse(groups.effectiveFor(alex).has(Permission.OPS_LOGS));
    }

    @Test
    void membersOfListsExactlyTheMembers() {
        String id = groups.create(OWNER, "Console", "", perms(Permission.OPS_LOGS)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);
        groups.setMemberships(OWNER, steve, Set.of(id));

        assertEquals(List.of(steve.id()), groups.membersOf(id));
    }

    // ---- Anti-élévation de privilège : le cœur du lot ------------------------------------------

    @Test
    void anAdminCannotCreateAGroupGrantingAnOwnerOnlyRight() {
        // Le scénario exact que le ticket interdit : se fabriquer PLAYER_OP_WRITE par un groupe.
        GroupDirectory.Outcome o = groups.create(ADMIN, "Pouvoirs", "",
                perms(Permission.PLAYER_OP_WRITE));

        assertFalse(o.ok());
        assertTrue(o.error().contains("PLAYER_OP_WRITE"), o.error());
        assertTrue(groups.list().isEmpty(), "rien n'est créé partiellement");
    }

    @Test
    void anAdminCannotAddAnOwnerOnlyRightToAnExistingGroup() {
        String id = groups.create(OWNER, "Exploitation", "", perms(Permission.OPS_LOGS)).after().id();

        GroupDirectory.Outcome o = groups.setPermissions(ADMIN, id,
                perms(Permission.OPS_LOGS, Permission.PLAYER_OP_WRITE));

        assertFalse(o.ok());
        assertEquals(perms(Permission.OPS_LOGS), groups.byId(id).orElseThrow().permissions(),
                "le groupe n'a pas bougé");
    }

    @Test
    void anAdminCannotRemoveARightItDoesNotHoldEither() {
        // Retirer serait un moyen détourné de modifier un groupe réservé au propriétaire.
        String id = groups.create(OWNER, "Pouvoirs", "", perms(Permission.PLAYER_OP_WRITE)).after().id();

        GroupDirectory.Outcome o = groups.setPermissions(ADMIN, id, perms());

        assertFalse(o.ok());
        assertEquals(perms(Permission.PLAYER_OP_WRITE), groups.byId(id).orElseThrow().permissions());
    }

    @Test
    void anAdminCannotRenameOrDeleteAGroupItDoesNotFullyHold() {
        String id = groups.create(OWNER, "Pouvoirs", "", perms(Permission.PLAYER_OP_WRITE)).after().id();

        assertFalse(groups.rename(ADMIN, id, "Autre nom", "").ok());
        assertFalse(groups.delete(ADMIN, id).ok());
        assertTrue(groups.byId(id).isPresent());
    }

    @Test
    void anAdminCannotAssignAGroupThatGrantsAnOwnerOnlyRight() {
        String id = groups.create(OWNER, "Pouvoirs", "", perms(Permission.PLAYER_OP_WRITE)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);

        GroupDirectory.MembershipOutcome o = groups.setMemberships(ADMIN, steve, Set.of(id));

        assertFalse(o.ok());
        assertTrue(groups.groupsOf(steve.id()).isEmpty());
    }

    @Test
    void anAdminCannotUndoAnOwnerDecisionByRemovingAReservedGroup() {
        String id = groups.create(OWNER, "Pouvoirs", "", perms(Permission.PLAYER_OP_WRITE)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);
        groups.setMemberships(OWNER, steve, Set.of(id));

        GroupDirectory.MembershipOutcome o = groups.setMemberships(ADMIN, steve, Set.of());

        assertFalse(o.ok());
        assertEquals(1, groups.groupsOf(steve.id()).size());
    }

    @Test
    void anAdminCanStillManageGroupsWithinItsOwnRights() {
        // L'anti-élévation ne doit pas tout bloquer : ADMIN détient ECONOMY_WRITE, donc il peut
        // en faire un groupe. C'est le pendant indispensable des tests précédents.
        GroupDirectory.Outcome o = groups.create(ADMIN, "Économie", "", perms(Permission.ECONOMY_WRITE));

        assertTrue(o.ok(), o.error());
        PanelUser steve = user("steve", Role.READ_ONLY);
        assertTrue(groups.setMemberships(ADMIN, steve, Set.of(o.after().id())).ok());
        assertTrue(groups.effectiveFor(steve).has(Permission.ECONOMY_WRITE));
    }

    @Test
    void anActorWithoutAnyRightCanGrantNothing() {
        assertFalse(groups.create(null, "Tout", "", perms(Permission.DOCS_READ)).ok(),
                "sans acteur résolu, refuser est le choix sûr");
        assertFalse(groups.create(EffectivePermissions.ofRoleOnly(null), "Tout", "",
                perms(Permission.DOCS_READ)).ok());
    }

    // ---- Propriétaire : aucun verrouillage possible par les groupes ----------------------------

    @Test
    void groupsCanNeverLockOutAnOwner() {
        PanelUser owner = user("owner", Role.OWNER);
        String id = groups.create(OWNER, "Restreint", "", perms(Permission.DASHBOARD_VIEW)).after().id();
        groups.setMemberships(OWNER, owner, Set.of(id));

        // Même membre d'un groupe minimal, un OWNER garde TOUT : l'union n'enlève rien, donc il
        // n'existe aucun moyen de l'enfermer en jouant sur les groupes.
        assertTrue(groups.effectiveFor(owner).has(Permission.USER_MANAGE));
        assertTrue(groups.effectiveFor(owner).has(Permission.PLAYER_OP_WRITE));

        groups.delete(OWNER, id);
        assertTrue(groups.effectiveFor(owner).has(Permission.USER_MANAGE));
    }

    @Test
    void forgettingAUserRemovesItsMembershipsOnly() {
        String id = groups.create(OWNER, "Console", "", perms(Permission.OPS_LOGS)).after().id();
        PanelUser steve = user("steve", Role.READ_ONLY);
        groups.setMemberships(OWNER, steve, Set.of(id));

        groups.forgetUser(steve.id());

        assertTrue(groups.groupsOf(steve.id()).isEmpty());
        assertTrue(groups.byId(id).isPresent(), "le groupe lui-même survit");
    }
}
