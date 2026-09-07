package com.lodygames.rpgquest.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.UnaryOperator;

/**
 * Moteur MySQL/MariaDB — <strong>reconnu et modélisé</strong> par l'issue #40, mais son ouverture
 * de connexion échoue proprement : l'implémentation réelle (driver JDBC, pool de connexions,
 * traduction des types de colonnes) est l'objet de l'<strong>issue #41</strong>.
 *
 * <p>Ce que #40 fournit déjà et que #41 réutilisera tel quel : les paramètres validés
 * ({@link DatabaseSettings.MySqlSettings}), le {@link MySqlDialect} (upsert / insert-ignore /
 * identité / existence de colonne), l'{@link MigrationTableHistory} portable, et ce point de
 * câblage unique. #41 n'aura qu'à remplacer le corps de {@link #openConnection()} par un
 * {@code DataSource} de pool et à ajouter la dépendance driver dans {@code plugin.yml}.</p>
 */
public final class MySqlDatabaseEngine implements DatabaseEngine {

    private final DatabaseSettings.MySqlSettings settings;
    private final UnaryOperator<String> env;
    private final SqlDialect dialect = new MySqlDialect();
    private final SchemaHistory schemaHistory = new MigrationTableHistory();

    public MySqlDatabaseEngine(DatabaseSettings.MySqlSettings settings, UnaryOperator<String> env) {
        this.settings = settings;
        this.env = env;
    }

    @Override
    public DatabaseType type() {
        return DatabaseType.MYSQL;
    }

    @Override
    public String describe() {
        return "mysql " + settings.username() + "@" + settings.host() + ":" + settings.port()
                + "/" + settings.database();
    }

    @Override
    public Connection openConnection() throws SQLException {
        String hint = settings.resolvePassword(env).isEmpty()
                ? " La variable d'environnement « " + settings.passwordEnv() + " » n'est pas définie."
                : "";
        throw new SQLException(
                "Le backend MySQL/MariaDB n'est pas encore disponible (issue #41). "
                        + "Utiliser « database.type: sqlite » pour démarrer." + hint);
    }

    @Override
    public void configureSession(Connection connection) {
        // #41 : réglages de session MySQL éventuels (fuseau horaire, sql_mode…)
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override
    public SchemaHistory schemaHistory() {
        return schemaHistory;
    }
}
