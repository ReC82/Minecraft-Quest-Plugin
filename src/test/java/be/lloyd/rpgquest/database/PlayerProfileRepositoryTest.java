package be.lloyd.rpgquest.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerProfileRepositoryTest {

    private static final long TIMEOUT_SECONDS = 5;

    @TempDir
    Path tempDir;

    private DatabaseManager database;
    private PlayerProfileRepository profiles;
    private PlayerVariableRepository variables;

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseManager(tempDir.resolve("data.db"));
        database.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        profiles = new PlayerProfileRepository(database);
        variables = new PlayerVariableRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void createsSchemaOnTemporaryDatabase() {
        assertTrue(Files.exists(tempDir.resolve("data.db")));
    }

    @Test
    void insertsReadsAndUpdatesProfile() throws Exception {
        UUID uuid = UUID.randomUUID();

        PlayerProfile created = profiles.findOrCreate(uuid, "Steve").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(uuid, created.uuid());
        assertEquals("Steve", created.lastName());

        Optional<PlayerProfile> fetched = profiles.find(uuid).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(fetched.isPresent());
        assertEquals("Steve", fetched.get().lastName());

        PlayerProfile renamed = profiles.findOrCreate(uuid, "Alex").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("Alex", renamed.lastName());
        assertEquals(created.createdAt(), renamed.createdAt());
        assertFalse(renamed.updatedAt().isBefore(created.updatedAt()));
    }

    @Test
    void handlesApostropheAndUnicodeValues() throws Exception {
        UUID uuid = UUID.randomUUID();
        profiles.findOrCreate(uuid, "Init").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        String tricky = "L'épée du héros — 龍 🐉 O'Brien's \"quote\"";
        variables.set(uuid, "nickname", tricky).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Optional<String> stored = variables.get(uuid, "nickname").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(tricky, stored.orElseThrow());

        // Le nom du joueur lui-même peut contenir une apostrophe (via findOrCreate).
        PlayerProfile renamed = profiles.findOrCreate(uuid, "O'Brien").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("O'Brien", renamed.lastName());
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        // initialize() a déjà exécuté migrate() une première fois : le rejouer ne doit rien casser.
        database.<Void>execute(connection -> {
            SchemaMigrator.migrate(connection);
            return null;
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        UUID uuid = UUID.randomUUID();
        assertDoesNotThrow(() ->
                profiles.findOrCreate(uuid, "Idempotent").get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void closesConnectionCleanly() throws Exception {
        DatabaseManager other = new DatabaseManager(tempDir.resolve("data2.db"));
        other.initialize().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertFalse(other.isClosed());
        other.shutdown();
        assertTrue(other.isClosed());
    }
}
