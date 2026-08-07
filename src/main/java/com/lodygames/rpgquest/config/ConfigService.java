package com.lodygames.rpgquest.config;

import com.lodygames.rpgquest.bootstrap.PluginService;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

/**
 * Charge, valide et expose la configuration courante du plugin. {@link #current()}
 * est volontairement mutable : c'est l'unique état modifiable de cette classe,
 * scoped à l'instance du plugin (pas de singleton statique), et sa mutation
 * est le mécanisme même du rechargement à chaud demandé par {@code /rpgquest reload}.
 */
public final class ConfigService implements PluginService {

    private final JavaPlugin plugin;
    private final Logger logger;
    private volatile PluginConfig current;

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        plugin.saveDefaultConfig();
        try {
            current = ConfigValidator.validate(plugin.getConfig());
        } catch (ConfigValidationException e) {
            throw new IllegalStateException("Configuration RPGQuest invalide : " + e.getMessage(), e);
        }
        logApplied("Configuration chargée");
    }

    @Override
    public void stop() {
        // Rien à libérer : la configuration ne détient aucune ressource externe.
    }

    public PluginConfig current() {
        return current;
    }

    /**
     * Recharge {@code config.yml} depuis le disque. En cas d'échec, la
     * configuration précédente reste active et l'exception décrit
     * précisément le problème — aucune donnée ni service n'est affecté.
     */
    public void reload() throws ConfigValidationException {
        plugin.reloadConfig();
        current = ConfigValidator.validate(plugin.getConfig());
        logApplied("Configuration rechargée");
    }

    private void logApplied(String action) {
        logger.info("{} : debug={}, locale={}, database.file={}, resource-pack.enabled={}",
                action, current.debug(), current.locale(), current.databaseFile(), current.resourcePack().enabled());
    }
}
