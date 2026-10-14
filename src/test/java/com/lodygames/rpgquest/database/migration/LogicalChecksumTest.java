package com.lodygames.rpgquest.database.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Empreinte logique indépendante du moteur (issue #42). */
class LogicalChecksumTest {

    @TempDir
    Path dir;

    private LogicalChecksum.Result checksum(String... inserts) throws SQLException {
        Path file = dir.resolve("t" + System.nanoTime() + ".db");
        try (Connection connection = MigrationFixtures.open(file); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE t (a TEXT, b INTEGER, c REAL, d BLOB)");
            for (String insert : inserts) {
                statement.execute("INSERT INTO t VALUES " + insert);
            }
            return LogicalChecksum.of(connection, "t", List.of("a", "b", "c", "d"));
        }
    }

    @Test
    void orderOfRowsDoesNotMatter() throws SQLException {
        assertEquals(checksum("('x', 1, 0.5, NULL)", "('y', 2, 1.5, x'00')"),
                checksum("('y', 2, 1.5, x'00')", "('x', 1, 0.5, NULL)"));
    }

    @Test
    void anyChangedValueChangesTheChecksum() throws SQLException {
        LogicalChecksum.Result reference = checksum("('x', 1, 0.5, x'01')");
        assertNotEquals(reference.checksum(), checksum("('x', 2, 0.5, x'01')").checksum());
        assertNotEquals(reference.checksum(), checksum("('X', 1, 0.5, x'01')").checksum());
        assertNotEquals(reference.checksum(), checksum("('x ', 1, 0.5, x'01')").checksum());
        assertNotEquals(reference.checksum(), checksum("('x', 1, 0.5, x'02')").checksum());
    }

    @Test
    void nullIsDistinctFromEmptyAndDuplicatesCount() throws SQLException {
        assertNotEquals(checksum("(NULL, 1, 0, NULL)").checksum(), checksum("('', 1, 0, NULL)").checksum());
        LogicalChecksum.Result once = checksum("('x', 1, 0, NULL)");
        LogicalChecksum.Result twice = checksum("('x', 1, 0, NULL)", "('x', 1, 0, NULL)");
        assertEquals(2, twice.rows());
        assertNotEquals(once.checksum(), twice.checksum());
        assertNotEquals("0".repeat(64), twice.checksum(), "un XOR annulerait les doublons, pas une somme");
    }

    @Test
    void numbersAreComparedByValueNotByDriverType() {
        assertEquals("1", LogicalChecksum.canonicalNumber(1));
        assertEquals("1", LogicalChecksum.canonicalNumber(1L));
        assertEquals("1", LogicalChecksum.canonicalNumber(1.0d));
        assertEquals("0", LogicalChecksum.canonicalNumber(-0.0d));
        assertEquals("0.1", LogicalChecksum.canonicalNumber(0.1d));
        assertEquals("9000000000", LogicalChecksum.canonicalNumber(9_000_000_000L));
        assertEquals("NaN", LogicalChecksum.canonicalNumber(Double.NaN));
    }
}
