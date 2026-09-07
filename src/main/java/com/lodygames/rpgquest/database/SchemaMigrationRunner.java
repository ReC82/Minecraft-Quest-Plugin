package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applique une liste de {@link SchemaMigration} dans l'ordre, en s'appuyant sur un
 * {@link SchemaHistory} pour savoir où reprendre et un {@link SqlDialect} pour les différences
 * moteur (issue #40).
 *
 * <p>Garanties :</p>
 * <ul>
 *   <li>migrations appliquées <strong>dans l'ordre croissant</strong> de version, exactement une
 *       fois (celles ≤ version appliquée sont ignorées) ;</li>
 *   <li>rejouer sur une base à jour est un <strong>no-op</strong> ;</li>
 *   <li>en cas d'échec : {@link SchemaMigrationException} identifiant la migration fautive, et la
 *       version enregistrée ne dépasse pas la dernière migration réussie ;</li>
 *   <li>la version cible attendue par le build est exposée par {@link #targetVersion()}.</li>
 * </ul>
 *
 * <p>Aucune gestion de transaction ici : selon le moteur, le DDL est ou non transactionnel
 * (SQLite : oui pour l'essentiel ; MySQL : {@code CREATE/ALTER} auto-commit). Les migrations sont
 * donc écrites pour être <strong>idempotentes</strong> ({@code CREATE TABLE IF NOT EXISTS},
 * {@code ALTER TABLE} gardé par {@code columnExists}).</p>
 */
public final class SchemaMigrationRunner {

    private final List<SchemaMigration> migrations;
    private final SchemaHistory history;
    private final SqlDialect dialect;

    public SchemaMigrationRunner(List<SchemaMigration> migrations, SchemaHistory history, SqlDialect dialect) {
        List<SchemaMigration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(SchemaMigration::version));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).version() == sorted.get(i - 1).version()) {
                throw new IllegalArgumentException("version de migration dupliquée : V" + sorted.get(i).version());
            }
        }
        this.migrations = List.copyOf(sorted);
        this.history = history;
        this.dialect = dialect;
    }

    /**
     * Applique les migrations en attente. Renvoie la version de schéma après exécution.
     */
    public int run(Connection connection) throws SQLException {
        history.ensureInitialised(connection);
        int applied = history.currentVersion(connection);

        for (SchemaMigration migration : migrations) {
            if (migration.version() <= applied) {
                continue;
            }
            try {
                migration.step().apply(connection, dialect);
            } catch (SQLException | RuntimeException e) {
                throw new SchemaMigrationException(
                        "Échec de la migration de schéma V" + migration.version()
                                + " (« " + migration.name() + " ») sur le moteur " + dialect.name()
                                + " : " + e.getMessage(), e);
            }
            history.recordApplied(connection, migration.version(), migration.name());
            applied = migration.version();
        }
        return applied;
    }

    /** Version de schéma attendue par ce build (la plus haute migration connue). */
    public int targetVersion() {
        return migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
    }
}
