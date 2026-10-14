package com.lodygames.rpgquest.database.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ligne de commande de l'outil de migration (issue #42) : garde-fous et absence de secret. */
class MigrationCliTest {

    private static final String SECRET = "s3cr3t-ne-jamais-afficher";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int cli(UnaryOperator<String> env, String... args) {
        return MigrationCli.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int cli(String... args) {
        return cli(name -> null, args);
    }

    @Test
    void migrateWithoutCommitIsRefused() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));

        int exit = cli("migrate", "--sqlite-file", source.toString(), "--target-sqlite", dir.resolve("t.db").toString());

        assertEquals(MigrationCli.EXIT_USAGE, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--commit"));
        assertFalse(Files.exists(dir.resolve("t.db")), "aucune cible ouverte");
    }

    @Test
    void usageErrorsAreRejected() {
        assertEquals(MigrationCli.EXIT_USAGE, cli());
        assertEquals(MigrationCli.EXIT_USAGE, cli("dry-run", "--sqlite-file", "data.db"));
        assertEquals(MigrationCli.EXIT_USAGE, cli("audit", "--sqlite-file", "a.db", "--target-sqlite", "b.db",
                "--target-mysql", "c.properties"));
        assertEquals(MigrationCli.EXIT_USAGE, cli("dry-run", "--sqlite-file", "a.db", "--target-sqlite", "b.db",
                "--commit"));
        assertEquals(MigrationCli.EXIT_USAGE, cli("audit", "--sqlite-file", "a.db", "--inconnue"));
        assertEquals(MigrationCli.EXIT_USAGE, cli("dry-run", "--sqlite-file", dir.resolve("a.db").toString(),
                "--target-sqlite", dir.resolve("a.db").toString()));
    }

    @Test
    void dryRunWritesTextAndJsonReportsAndReturnsSuccess() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path reports = dir.resolve("reports");

        int exit = cli("dry-run", "--sqlite-file", source.toString(), "--target-sqlite", dir.resolve("t.db").toString(),
                "--report-dir", reports.toString());

        assertEquals(MigrationCli.EXIT_SUCCESS, exit, out.toString(StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(reports)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(2, names.size(), names.toString());
            assertTrue(names.get(0).startsWith("migration-dry-run-") && names.get(0).endsWith(".json"));
            assertTrue(names.get(1).endsWith(".txt"));
        }
    }

    @Test
    void failedMigrationReturnsExitCodeTwo() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path target = dir.resolve("t.db");
        String[] args = {"migrate", "--commit", "--sqlite-file", source.toString(), "--target-sqlite",
                target.toString(), "--report-dir", dir.resolve("r").toString()};
        assertEquals(MigrationCli.EXIT_SUCCESS, cli(args));

        assertEquals(MigrationCli.EXIT_FAILED, cli(args), "seconde exécution : cible non vide refusée");
    }

    @Test
    void plaintextPasswordInTargetFileIsRefused() throws IOException {
        Path properties = dir.resolve("cible.properties");
        Files.writeString(properties, "host=db.example\ndatabase=rpgquest\nusername=rpgquest\npassword=" + SECRET + "\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MigrationCli.loadMySqlSettings(properties));
        assertFalse(error.getMessage().contains(SECRET));
    }

    @Test
    void unreachableMariaDbFailsCleanlyWithoutLeakingHostOrSecret() throws Exception {
        Path source = MigrationFixtures.realisticDatabase(dir.resolve("data.db"));
        Path properties = dir.resolve("cible.properties");
        // port 1 : refus immédiat, aucun serveur
        Files.writeString(properties, "host=127.0.0.1\nport=1\ndatabase=rpgquest_cible\nusername=compte_migration\n"
                + "password-env=MIG_PW\nconnection-timeout-ms=1000\n");
        Path reports = dir.resolve("reports");

        int exit = cli(name -> "MIG_PW".equals(name) ? SECRET : null, "dry-run", "--sqlite-file", source.toString(),
                "--target-mysql", properties.toString(), "--report-dir", reports.toString());

        assertEquals(MigrationCli.EXIT_FAILED, exit);
        String printed = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        StringBuilder written = new StringBuilder(printed);
        try (Stream<Path> files = Files.list(reports)) {
            for (Path file : files.toList()) {
                written.append(Files.readString(file));
            }
        }
        assertTrue(written.toString().contains("connexion impossible"), written.toString());
        assertFalse(written.toString().contains(SECRET), "jamais de mot de passe");
        assertFalse(written.toString().contains("127.0.0.1"), "jamais l'hôte de la cible");
        assertFalse(written.toString().contains("compte_migration"), "jamais le compte de la cible");
        assertTrue(written.toString().contains("rpgquest_cible"), "le nom de base reste lisible");
    }
}
