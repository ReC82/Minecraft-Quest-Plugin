package com.lodygames.rpgquest.database;

import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.ConfigService;
import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * Adapte {@link DatabaseManager} au cycle de vie {@link PluginService}. Le
 * nom du fichier de base de données vient de {@link ConfigService}, donc ce
 * service doit démarrer après lui : {@link #start()} ne construit le
 * {@link DatabaseManager} qu'à l'appel, jamais avant.
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
        String fileName = configService.current().databaseFile();
        databaseManager = new DatabaseManager(dataFolder.resolve(fileName));
        databaseManager.initialize().exceptionally(error -> {
            logger.error("Impossible d'initialiser la base de données RPGQuest.", error);
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
