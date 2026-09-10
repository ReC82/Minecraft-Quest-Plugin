package com.lodygames.rpgquest.panel.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lodygames.rpgquest.panel.authz.Role;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteUserRepositoryTest {

    @TempDir
    Path tmp;

    private String db() {
        return tmp.resolve("cp.db").toString();
    }

    private static PanelUser sample(String username, Role role) {
        return new PanelUser(UUID.randomUUID().toString(), username, "pbkdf2_sha256$1$x$y",
                role, true, Instant.parse("2026-09-10T10:00:00Z"), null);
    }

    @Test
    void migrationIsIdempotent() {
        String path = db();
        SqliteUserRepository first = new SqliteUserRepository(path);
        first.insert(sample("alice", Role.ADMIN));
        // reconstruire sur la même base ne doit rien casser ni rien perdre
        SqliteUserRepository second = new SqliteUserRepository(path);
        assertTrue(second.findByUsername("alice").isPresent());
        // une troisième fois encore
        new SqliteUserRepository(path);
        assertEquals(1, second.all().size());
    }

    @Test
    void insertRoundTripsAllFields() {
        SqliteUserRepository repo = new SqliteUserRepository(db());
        PanelUser u = sample("Bob", Role.CONTENT_EDITOR);
        repo.insert(u);
        PanelUser back = repo.findById(u.id()).orElseThrow();
        assertEquals("Bob", back.username());
        assertEquals(Role.CONTENT_EDITOR, back.role());
        assertTrue(back.active());
        assertEquals(u.createdAt(), back.createdAt());
        // recherche insensible à la casse
        assertTrue(repo.findByUsername("bOb").isPresent());
    }

    @Test
    void duplicateUsernameIsRejected() {
        SqliteUserRepository repo = new SqliteUserRepository(db());
        repo.insert(sample("carol", Role.READ_ONLY));
        assertThrows(UserRepository.DuplicateUsernameException.class,
                () -> repo.insert(sample("CAROL", Role.ADMIN)));
    }

    @Test
    void updatesAndActiveOwnerCount() {
        SqliteUserRepository repo = new SqliteUserRepository(db());
        PanelUser o1 = sample("owner1", Role.OWNER);
        PanelUser o2 = sample("owner2", Role.OWNER);
        repo.insert(o1);
        repo.insert(o2);
        assertEquals(2, repo.countActiveOwners());

        repo.updateActive(o2.id(), false);
        assertEquals(1, repo.countActiveOwners());
        assertFalse(repo.findById(o2.id()).orElseThrow().active());

        repo.updateRole(o1.id(), Role.ADMIN);
        assertEquals(0, repo.countActiveOwners());

        Instant when = Instant.parse("2026-09-10T12:34:56Z");
        repo.recordLogin(o1.id(), when);
        assertEquals(when, repo.findById(o1.id()).orElseThrow().lastLoginAt());

        repo.updatePasswordHash(o1.id(), "pbkdf2_sha256$2$a$b");
        assertEquals("pbkdf2_sha256$2$a$b", repo.findById(o1.id()).orElseThrow().passwordHash());
    }

    @Test
    void allIsOrderedByCreationThenUsername() {
        SqliteUserRepository repo = new SqliteUserRepository(db());
        repo.insert(new PanelUser(UUID.randomUUID().toString(), "later", "h", Role.READ_ONLY, true,
                Instant.parse("2026-09-10T11:00:00Z"), null));
        repo.insert(new PanelUser(UUID.randomUUID().toString(), "earlier", "h", Role.READ_ONLY, true,
                Instant.parse("2026-09-10T09:00:00Z"), null));
        assertEquals("earlier", repo.all().get(0).username());
    }
}
