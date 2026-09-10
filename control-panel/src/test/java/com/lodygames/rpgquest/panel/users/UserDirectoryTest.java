package com.lodygames.rpgquest.panel.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.security.PasswordHasher;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserDirectoryTest {

    private final PasswordHasher hasher = new PasswordHasher();
    private InMemoryUserRepository repo;
    private UserDirectory dir;

    @BeforeEach
    void setUp() {
        repo = new InMemoryUserRepository();
        dir = new UserDirectory(repo, hasher);
        dir.ensureBootstrapOwner("owner", hasher.hash("bootstrap-owner-pw"));
    }

    @Test
    void bootstrapOwnerIsCreatedActiveAndOwner() {
        PanelUser owner = dir.byUsername("owner").orElseThrow();
        assertEquals(Role.OWNER, owner.role());
        assertTrue(owner.active());
        assertEquals(1, dir.activeOwnerCount());
    }

    @Test
    void bootstrapOwnerIsReassertedAsActiveOwnerOnRestart() {
        PanelUser owner = dir.byUsername("owner").orElseThrow();
        repo.updateActive(owner.id(), false);
        repo.updateRole(owner.id(), Role.READ_ONLY);

        dir.ensureBootstrapOwner("owner", hasher.hash("bootstrap-owner-pw"));

        PanelUser after = dir.byUsername("owner").orElseThrow();
        assertTrue(after.active(), "le compte de récupération est réactivé au démarrage");
        assertEquals(Role.OWNER, after.role());
    }

    @Test
    void createHashesPasswordAndNeverStoresPlaintext() {
        UserDirectory.Outcome out = dir.create("alice", "correct horse battery", Role.TESTER);
        assertTrue(out.ok());
        PanelUser alice = out.after();
        assertNotNull(alice.passwordHash());
        assertFalse(alice.passwordHash().contains("correct horse battery"));
        assertTrue(alice.passwordHash().startsWith("pbkdf2_sha256$"));
        assertTrue(hasher.verify("correct horse battery", alice.passwordHash()));
        assertTrue(alice.active());
        assertNull(alice.lastLoginAt());
    }

    @Test
    void createRejectsWeakPasswordAndBadUsername() {
        assertFalse(dir.create("bob", "short", Role.READ_ONLY).ok());
        assertFalse(dir.create("x", "correct horse battery", Role.READ_ONLY).ok());
        assertFalse(dir.create("bad name!", "correct horse battery", Role.READ_ONLY).ok());
        assertTrue(dir.list().stream().noneMatch(u -> u.username().startsWith("b") && !u.isOwner()));
    }

    @Test
    void createRejectsDuplicateUsernameCaseInsensitively() {
        assertTrue(dir.create("Carol", "correct horse battery", Role.READ_ONLY).ok());
        assertFalse(dir.create("carol", "correct horse battery staple", Role.ADMIN).ok());
    }

    @Test
    void changeRoleReportsBeforeAndAfter() {
        String id = dir.create("dave", "correct horse battery", Role.READ_ONLY).after().id();
        UserDirectory.Outcome out = dir.changeRole(id, Role.ADMIN);
        assertTrue(out.ok());
        assertEquals(Role.READ_ONLY, out.before().role());
        assertEquals(Role.ADMIN, out.after().role());
    }

    @Test
    void lastActiveOwnerCannotBeDemotedOrDeactivated() {
        String ownerId = dir.byUsername("owner").orElseThrow().id();
        assertFalse(dir.changeRole(ownerId, Role.ADMIN).ok(), "dernier OWNER : rôle verrouillé");
        assertFalse(dir.setActive(ownerId, false, "someone-else").ok(), "dernier OWNER : désactivation refusée");
        assertEquals(Role.OWNER, dir.byId(ownerId).orElseThrow().role());
        assertTrue(dir.byId(ownerId).orElseThrow().active());
    }

    @Test
    void withSecondOwnerTheFirstMayBeDemoted() {
        String firstOwner = dir.byUsername("owner").orElseThrow().id();
        String second = dir.create("owner2", "correct horse battery staple", Role.OWNER).after().id();
        assertEquals(2, dir.activeOwnerCount());
        assertTrue(dir.changeRole(firstOwner, Role.ADMIN).ok());
        assertEquals(1, dir.activeOwnerCount());
        assertEquals(second, dir.byUsername("owner2").orElseThrow().id());
    }

    @Test
    void youCannotDeactivateYourOwnAccount() {
        String id = dir.create("erin", "correct horse battery", Role.ADMIN).after().id();
        assertFalse(dir.setActive(id, false, id).ok());
        assertTrue(dir.byId(id).orElseThrow().active());
        // mais un autre acteur le peut
        assertTrue(dir.setActive(id, false, "another-actor").ok());
        assertFalse(dir.byId(id).orElseThrow().active());
    }

    @Test
    void changeRoleAndSetActiveOnUnknownAccountFail() {
        assertFalse(dir.changeRole("00000000-0000-0000-0000-000000000000", Role.ADMIN).ok());
        assertFalse(dir.setActive("00000000-0000-0000-0000-000000000000", false, "x").ok());
    }

    @Test
    void recordLoginIsStoredByRepository() {
        String id = dir.create("frank", "correct horse battery", Role.TESTER).after().id();
        Instant when = Instant.parse("2026-09-10T12:00:00Z");
        repo.recordLogin(id, when);
        assertEquals(when, dir.byId(id).orElseThrow().lastLoginAt());
    }
}
