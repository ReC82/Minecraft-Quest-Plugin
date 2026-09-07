package com.lodygames.rpgquest.database;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * <strong>Unique</strong> point où le moteur concret est choisi à partir de la configuration
 * (issue #40). Le reste du code (repositories, services de gameplay) ne fait jamais ce choix et ne
 * teste jamais le type de moteur : c'est ici, une fois, et nulle part ailleurs.
 */
public final class DatabaseEngineFactory {

    private DatabaseEngineFactory() {
    }

    /**
     * @param settings   configuration {@code database:} validée
     * @param dataFolder dossier de données du plugin (base du fichier SQLite)
     */
    public static DatabaseEngine create(DatabaseSettings settings, Path dataFolder) {
        return create(settings, dataFolder, System::getenv);
    }

    /** Variante injectable pour les tests (source des variables d'environnement explicite). */
    public static DatabaseEngine create(DatabaseSettings settings, Path dataFolder, UnaryOperator<String> env) {
        return switch (settings.type()) {
            case SQLITE -> new SqliteDatabaseEngine(dataFolder.resolve(settings.sqlite().file()));
            case MYSQL -> new MySqlDatabaseEngine(settings.mysql(), env);
        };
    }
}
