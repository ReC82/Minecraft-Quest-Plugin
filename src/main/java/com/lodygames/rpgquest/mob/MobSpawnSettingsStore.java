package com.lodygames.rpgquest.mob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.YamlConfiguration;
import org.slf4j.Logger;

/**
 * Charge/sauvegarde {@code mobs/spawn-settings.yml} : le throttle global du tirage aléatoire de
 * mobs spéciaux dans le Wild (voir {@link MobSpawnSettings}). Un seul fichier, pas de registre --
 * modifiable depuis le Control Panel sans jamais écrire de YAML à la main (mission #174).
 * Fichier absent (première installation) = valeurs par défaut rétro-compatibles, pas une erreur.
 */
public final class MobSpawnSettingsStore {

    private final Path file;
    private final Logger logger;
    private volatile MobSpawnSettings current;

    public MobSpawnSettingsStore(Path mobsDirectory, Logger logger) {
        this.file = mobsDirectory.resolve("spawn-settings.yml");
        this.logger = logger;
        this.current = load();
    }

    public MobSpawnSettings current() {
        return current;
    }

    public synchronized MobSpawnSettings reload() {
        this.current = load();
        return current;
    }

    public synchronized void save(MobSpawnSettings settings) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("enabled", settings.enabled());
        yaml.set("chance", settings.chance());
        if (settings.maxSimultaneousSpecial() != null) {
            yaml.set("max-simultaneous-special", settings.maxSimultaneousSpecial());
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(tmp, "# Tirage aléatoire de mobs spéciaux dans le Wild — édité via le Control Panel (issue #174).\n"
                + yaml.saveToString());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        this.current = settings;
    }

    private MobSpawnSettings load() {
        if (!Files.exists(file)) {
            return MobSpawnSettings.defaults();
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            boolean enabled = yaml.getBoolean("enabled", true);
            double chance = yaml.getDouble("chance", 1.0);
            Integer max = yaml.isSet("max-simultaneous-special") ? yaml.getInt("max-simultaneous-special") : null;
            return new MobSpawnSettings(enabled, chance, max);
        } catch (Exception e) {
            logger.warn("Impossible de charger mobs/spawn-settings.yml, valeurs par défaut utilisées : {}", e.getMessage());
            return MobSpawnSettings.defaults();
        }
    }
}
