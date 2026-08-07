package be.lloyd.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.lloyd.rpgquest.claim.model.Claim;
import be.lloyd.rpgquest.claim.model.ClaimFlags;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaimRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PlayerProfileRepository profiles;
    private ClaimRepository claims;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profiles = new PlayerProfileRepository(database);
        claims = new ClaimRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private Claim claim(String id, UUID owner, Set<UUID> members) {
        return new Claim(id, owner, "world", 0, 0, 0, 10, 255, 10, members, ClaimFlags.defaults());
    }

    @Test
    void createPersistsAClaimAndItsMembers() throws Exception {
        UUID owner = createPlayer("Steve");
        UUID member = createPlayer("Alex");

        assertTrue(claims.create(claim("home", owner, Set.of(member))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        List<Claim> all = claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, all.size());
        Claim loaded = all.get(0);
        assertEquals(owner, loaded.owner());
        assertEquals(Set.of(member), loaded.members());
        assertFalse(loaded.flags().allowPublicRedstone());
    }

    @Test
    void createRejectsDuplicateId() throws Exception {
        UUID owner = createPlayer("Steve");
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void deleteByOwnerSucceeds() throws Exception {
        UUID owner = createPlayer("Steve");
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClaimActionOutcome.SUCCESS, claims.delete("home", owner).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(0, claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void deleteByNonOwnerFails() throws Exception {
        UUID owner = createPlayer("Steve");
        UUID stranger = createPlayer("Alex");
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClaimActionOutcome.NOT_OWNER, claims.delete("home", stranger).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size());
    }

    @Test
    void deleteOfUnknownClaimFails() throws Exception {
        UUID owner = createPlayer("Steve");
        assertEquals(ClaimActionOutcome.CLAIM_NOT_FOUND, claims.delete("missing", owner).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void addMemberByOwnerSucceedsAndIsIdempotent() throws Exception {
        UUID owner = createPlayer("Steve");
        UUID member = createPlayer("Alex");
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClaimActionOutcome.SUCCESS, claims.addMember("home", owner, member).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(ClaimActionOutcome.SUCCESS, claims.addMember("home", owner, member).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        Claim loaded = claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(0);
        assertEquals(Set.of(member), loaded.members());
    }

    @Test
    void addMemberByNonOwnerFails() throws Exception {
        UUID owner = createPlayer("Steve");
        UUID stranger = createPlayer("Alex");
        UUID target = createPlayer("Notch");
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClaimActionOutcome.NOT_OWNER, claims.addMember("home", stranger, target).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void removeMemberByOwnerSucceeds() throws Exception {
        UUID owner = createPlayer("Steve");
        UUID member = createPlayer("Alex");
        claims.create(claim("home", owner, Set.of(member))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClaimActionOutcome.SUCCESS, claims.removeMember("home", owner, member).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(0).members().isEmpty());
    }

    @Test
    void setAllowPublicRedstoneByOwnerSucceeds() throws Exception {
        UUID owner = createPlayer("Steve");
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClaimActionOutcome.SUCCESS,
                claims.setAllowPublicRedstone("home", owner, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(0).flags().allowPublicRedstone());
    }

    @Test
    void setAllowPublicRedstoneByNonOwnerFails() throws Exception {
        UUID owner = createPlayer("Steve");
        UUID stranger = createPlayer("Alex");
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClaimActionOutcome.NOT_OWNER,
                claims.setAllowPublicRedstone("home", stranger, true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void deletingAClaimCascadesItsMembers() throws Exception {
        UUID owner = createPlayer("Steve");
        UUID member = createPlayer("Alex");
        claims.create(claim("home", owner, Set.of(member))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        claims.delete("home", owner).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // Recréer un claim avec le même id et vérifier qu'aucun membre fantôme ne réapparaît :
        // preuve que claim_members a bien été nettoyé par la cascade FK.
        claims.create(claim("home", owner, Set.of())).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(claims.allClaims().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(0).members().isEmpty());
    }

    private UUID createPlayer(String name) throws Exception {
        UUID uuid = UUID.randomUUID();
        profiles.findOrCreate(uuid, name).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return uuid;
    }
}
