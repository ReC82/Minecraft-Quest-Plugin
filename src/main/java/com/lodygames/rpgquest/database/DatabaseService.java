package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.ConfigService;
import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * Adapte {@link DatabaseManager} au cycle de vie {@link PluginService}. Le <strong>moteur</strong>
 * (SQLite, MySQL…) est choisi par la configuration : ce service ne construit le
 * {@link DatabaseEngine} et le {@link DatabaseManager} qu'à l'appel de {@link #start()}, jamais
 * avant, car il a besoin de {@link ConfigService} déjà démarré.
 */
public final class DatabaseService implements PluginService {

    private final Path dataFolder;
    private final ConfigService configService;
    private final Logger logger;
    private DatabaseManager databaseManager;

    public DatabaseService(Path dataFolder, ConfigService configService, Logger logger) {
        this.dataFolder = dataFolder;
        this.configService = configService;
        this.logger = logger;
    }

    @Override
    public void start() {
        DatabaseSettings settings = configService.current().database();
        DatabaseEngine engine = DatabaseEngineFactory.create(settings, dataFolder);
        logger.info("Base de données RPGQuest : moteur {}.", engine.describe());
        databaseManager = new DatabaseManager(engine);
        databaseManager.initialize().exceptionally(error -> {
            logger.error("Impossible d'initialiser la base de données RPGQuest ({}).", engine.describe(), error);
            return null;
        });
    }

    @Override
    public void stop() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    public DatabaseManager databaseManager() {
        return databaseManager;
    }
}
