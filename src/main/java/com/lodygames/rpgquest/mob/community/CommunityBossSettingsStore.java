package com.lodygames.rpgquest.mob.community;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Charge/sauvegarde {@code mobs/community-bosses.yml} (issue #175), même discipline que
 * {@link com.lodygames.rpgquest.mob.MobSpawnSettingsStore} : un seul fichier, écriture atomique,
 * fichier absent ou illisible = valeurs par défaut (scheduler désactivé), jamais une erreur fatale.
 * {@link #save} est appelé hors du thread principal (action d'agent).
 */
public final class CommunityBossSettingsStore {

    private final Path file;
    private final Logger logger;
    private volatile CommunityBossSettings current;

    public CommunityBossSettingsStore(Path mobsDirectory, Logger logger) {
        this.file = mobsDirectory.resolve("community-bosses.yml");
        this.logger = logger;
        this.current = load();
    }

    public CommunityBossSettings current() {
        return current;
    }

    public synchronized CommunityBossSettings reload() {
        this.current = load();
        return current;
    }

    public synchronized void save(CommunityBossSettings settings) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", settings.enabled());
        yaml.set("interval-minutes", settings.intervalMinutes());
        yaml.set("max-active", settings.maxActive());
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(tmp, "# Boss communautaires du Wild — édité via le Control Panel (issue #175).\n"
                + yaml.saveToString());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        this.current = settings;
    }

    private CommunityBossSettings load() {
        if (!Files.exists(file)) {
            return CommunityBossSettings.defaults();
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            return new CommunityBossSettings(
                    yaml.getBoolean("enabled", false),
                    yaml.getInt("interval-minutes", CommunityBossSettings.DEFAULT_INTERVAL_MINUTES),
                    yaml.getInt("max-active", CommunityBossSettings.DEFAULT_MAX_ACTIVE));
        } catch (Exception e) {
            logger.warn("Impossible de charger mobs/community-bosses.yml, scheduler désactivé par défaut : {}",
                    e.getMessage());
            return CommunityBossSettings.defaults();
        }
    }
}
