package com.lodygames.rpgquest.database.migration;

import com.lodygames.rpgquest.database.DatabaseEngine;
import com.lodygames.rpgquest.database.DatabaseSettings;
import com.lodygames.rpgquest.database.MySqlDatabaseEngine;
import com.lodygames.rpgquest.database.SqliteDatabaseEngine;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Point d'entrée en ligne de commande de l'outil de migration SQLite → MariaDB (issue #42).
 * Exécuté <strong>hors serveur Minecraft</strong>, depuis le JAR autonome produit par
 * {@code ./gradlew dbMigrationToolJar}.
 *
 * <pre>
 *   java -jar rpgquest-db-migration.jar audit   --sqlite-file data.db [--target-mysql cible.properties]
 *   java -jar rpgquest-db-migration.jar dry-run --sqlite-file data.db --target-mysql cible.properties
 *   java -jar rpgquest-db-migration.jar dry-run --sqlite-file data.db --target-sqlite repetition.db
 *   java -jar rpgquest-db-migration.jar migrate --sqlite-file data.db --target-mysql cible.properties --commit
 * </pre>
 *
 * <p>Le fichier {@code cible.properties} ne contient <strong>aucun mot de passe</strong> :
 * {@code host}, {@code port}, {@code database}, {@code username}, {@code password-env} (NOM de la
 * variable d'environnement qui porte le mot de passe), {@code ssl-mode}. Une clé {@code password}
 * est refusée.</p>
 *
 * <p>Codes de sortie : {@code 0} SUCCESS, {@code 2} FAILED, {@code 1} usage invalide.</p>
 */
public final class MigrationCli {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_USAGE = 1;
    static final int EXIT_FAILED = 2;

    private static final Set<String> FORBIDDEN_KEYS = Set.of("password", "pass", "passwd", "pwd");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private MigrationCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System::getenv, System.out, System.err));
    }

    static int run(String[] args, UnaryOperator<String> env, PrintStream out, PrintStream err) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Erreur : " + e.getMessage());
            err.println();
            err.println(usage());
            return EXIT_USAGE;
        }

        DatabaseEngine target = null;
        String label = "";
        UnaryOperator<String> redact = UnaryOperator.identity();
        try {
            if (arguments.targetSqlite != null) {
                if (arguments.targetSqlite.toAbsolutePath().normalize()
                        .equals(arguments.sqliteFile.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("la cible SQLite ne peut pas être la source");
                }
                target = new SqliteDatabaseEngine(arguments.targetSqlite);
                label = "sqlite (" + arguments.targetSqlite.getFileName() + ")";
            } else if (arguments.targetMysql != null) {
                DatabaseSettings.MySqlSettings settings = loadMySqlSettings(arguments.targetMysql);
                target = new MySqlDatabaseEngine(settings, env);
                label = "mariadb …/" + settings.database() + " (ssl=" + settings.sslMode() + ")";
                String host = settings.host();
                String user = settings.username();
                redact = message -> message.replace(host, "<hôte>").replace(user, "<compte>");
            }
        } catch (IOException | IllegalArgumentException e) {
            err.println("Erreur : " + e.getMessage());
            return EXIT_USAGE;
        }

        MigrationReport report = new SqliteMigrationTool(Clock.systemUTC(), redact)
                .run(arguments.sqliteFile, target, label, arguments.mode);
        String text = report.toText();
        out.print(text);
        try {
            Files.createDirectories(arguments.reportDir);
            String base = "migration-" + arguments.mode.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                    + "-" + STAMP.format(Clock.systemUTC().instant());
            Path textFile = arguments.reportDir.resolve(base + ".txt");
            Path jsonFile = arguments.reportDir.resolve(base + ".json");
            Files.writeString(textFile, text, StandardCharsets.UTF_8);
            Files.writeString(jsonFile, report.toJson(), StandardCharsets.UTF_8);
            out.println("Rapport : " + textFile + " / " + jsonFile.getFileName());
        } catch (IOException e) {
            err.println("Rapport non écrit (" + e.getMessage() + ") — le résultat ci-dessus fait foi.");
        }
        return report.status() == MigrationReport.Status.SUCCESS ? EXIT_SUCCESS : EXIT_FAILED;
    }

    /** Charge la cible MariaDB depuis un fichier {@code .properties} sans secret. */
    static DatabaseSettings.MySqlSettings loadMySqlSettings(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String key : properties.stringPropertyNames()) {
            if (FORBIDDEN_KEYS.contains(key.trim().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("« " + key + " » est interdit dans " + file.getFileName()
                        + " : le mot de passe vient d'une variable d'environnement (password-env)");
            }
        }
        String port = properties.getProperty("port", "3306").trim();
        long timeout = Long.parseLong(properties.getProperty("connection-timeout-ms", "10000").trim());
        try {
            return new DatabaseSettings.MySqlSettings(
                    required(properties, "host"),
                    Integer.parseInt(port),
                    required(properties, "database"),
                    required(properties, "username"),
                    properties.getProperty("password-env", "RPGQUEST_DB_PASSWORD").trim(),
                    properties.getProperty("ssl-mode", "disable").trim(),
                    new DatabaseSettings.PoolSettings(1, 2, timeout, 1_800_000L, 0L));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port invalide : " + port);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("clé obligatoire absente du fichier cible : " + key);
        }
        return value.trim();
    }

    static String usage() {
        return """
                Usage :
                  audit   --sqlite-file <data.db> [--target-mysql <cible.properties> | --target-sqlite <fichier.db>]
                  dry-run --sqlite-file <data.db> (--target-mysql <cible.properties> | --target-sqlite <fichier.db>)
                  migrate --sqlite-file <data.db> (--target-mysql <cible.properties> | --target-sqlite <fichier.db>) --commit
                Options :
                  --report-dir <dossier>   dossier des rapports .txt/.json (défaut : migration-reports)
                La source est ouverte en lecture seule. Une cible non vide est toujours refusée.
                Voir docs/deployment/MIGRATION_SQLITE_MARIADB.md.""";
    }

    /** Arguments validés. */
    record Arguments(MigrationMode mode, Path sqliteFile, Path targetSqlite, Path targetMysql, Path reportDir) {

        static Arguments parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException("commande manquante");
            }
            Map<String, String> options = new LinkedHashMap<>();
            boolean commit = false;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--commit")) {
                    commit = true;
                } else if (List.of("--sqlite-file", "--target-sqlite", "--target-mysql", "--report-dir").contains(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("valeur manquante pour " + arg);
                    }
                    options.put(arg, args[++i]);
                } else {
                    throw new IllegalArgumentException("option inconnue : " + arg);
                }
            }
            MigrationMode mode = switch (args[0]) {
                case "audit" -> MigrationMode.AUDIT;
                case "dry-run" -> MigrationMode.DRY_RUN;
                case "migrate" -> {
                    if (!commit) {
                        throw new IllegalArgumentException("migrate exige --commit (sinon utiliser dry-run)");
                    }
                    yield MigrationMode.COMMIT;
                }
                default -> throw new IllegalArgumentException("commande inconnue : " + args[0]);
            };
            if (commit && mode != MigrationMode.COMMIT) {
                throw new IllegalArgumentException("--commit n'a de sens qu'avec migrate");
            }
            String sqlite = options.get("--sqlite-file");
            if (sqlite == null) {
                throw new IllegalArgumentException("--sqlite-file est obligatoire");
            }
            String targetSqlite = options.get("--target-sqlite");
            String targetMysql = options.get("--target-mysql");
            if (targetSqlite != null && targetMysql != null) {
                throw new IllegalArgumentException("une seule cible : --target-sqlite OU --target-mysql");
            }
            if (mode != MigrationMode.AUDIT && targetSqlite == null && targetMysql == null) {
                throw new IllegalArgumentException(args[0] + " exige une cible (--target-mysql ou --target-sqlite)");
            }
            return new Arguments(mode, Path.of(sqlite),
                    targetSqlite == null ? null : Path.of(targetSqlite),
                    targetMysql == null ? null : Path.of(targetMysql),
                    Path.of(options.getOrDefault("--report-dir", "migration-reports")));
        }
    }
}
