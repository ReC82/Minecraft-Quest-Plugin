package com.lodygames.rpgquest.panel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.Role;
import com.lodygames.rpgquest.panel.users.InMemoryUserRepository;
import com.lodygames.rpgquest.panel.users.PanelUser;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {

    private final PasswordHasher hasher = new PasswordHasher();
    private InMemoryUserRepository repo;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        repo = new InMemoryUserRepository();
        auth = new AuthService(repo, hasher);
    }

    private PanelUser add(String username, String password, Role role, boolean active) {
        PanelUser u = new PanelUser(UUID.randomUUID().toString(), username, hasher.hash(password),
                role, active, Instant.parse("2026-09-01T00:00:00Z"), null);
        repo.insert(u);
        return u;
    }

    @Test
    void validCredentialsReturnUserAndStampLastLogin() {
        PanelUser u = add("alice", "correct horse battery staple", Role.ADMIN, true);
        AuthService.Result r = auth.authenticate("alice", "correct horse battery staple");
        assertTrue(r.ok());
        assertEquals(AuthService.Outcome.OK, r.outcome());
        assertEquals(Role.ADMIN, r.role());
        assertEquals(u.id(), r.userId());
        assertNotNull(repo.findById(u.id()).orElseThrow().lastLoginAt(), "last_login_at posé");
    }

    @Test
    void usernameMatchIsCaseInsensitive() {
        add("Bob", "correct horse battery staple", Role.TESTER, true);
        assertTrue(auth.authenticate("bob", "correct horse battery staple").ok());
    }

    @Test
    void wrongPasswordIsInvalidNotDisabled() {
        add("carol", "correct horse battery staple", Role.READ_ONLY, true);
        AuthService.Result r = auth.authenticate("carol", "nope");
        assertFalse(r.ok());
        assertEquals(AuthService.Outcome.INVALID, r.outcome());
        assertNull(r.role());
    }

    @Test
    void unknownAccountIsInvalid() {
        assertEquals(AuthService.Outcome.INVALID, auth.authenticate("ghost", "whatever at all here").outcome());
    }

    @Test
    void disabledAccountCannotAuthenticateEvenWithRightPassword() {
        PanelUser u = add("dave", "correct horse battery staple", Role.ADMIN, false);
        AuthService.Result r = auth.authenticate("dave", "correct horse battery staple");
        assertFalse(r.ok());
        assertEquals(AuthService.Outcome.DISABLED, r.outcome());
        assertNull(repo.findById(u.id()).orElseThrow().lastLoginAt(), "aucune connexion enregistrée");
    }

    @Test
    void nullInputsAreHandledSafely() {
        assertEquals(AuthService.Outcome.INVALID, auth.authenticate(null, null).outcome());
    }
}
