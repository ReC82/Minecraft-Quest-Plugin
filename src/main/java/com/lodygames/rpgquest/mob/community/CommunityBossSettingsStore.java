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
        yaml.set("participation-radius", settings.participationRadius());
        yaml.set("glow", settings.glow());
        yaml.set("glow-radius", settings.glowRadius());
        yaml.set("surface-only", settings.surfaceOnly());
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

    /** Une distance hors bornes ne doit pas mettre le scheduler en pause : seule elle revient au défaut. */
    private int participationRadius(YamlConfiguration yaml) {
        int radius = yaml.getInt("participation-radius", CommunityBossSettings.DEFAULT_PARTICIPATION_RADIUS);
        if (radius < CommunityBossSettings.MIN_PARTICIPATION_RADIUS
                || radius > CommunityBossSettings.MAX_PARTICIPATION_RADIUS) {
            logger.warn("mobs/community-bosses.yml : participation-radius {} hors bornes ({} à {}), {} utilisé.",
                    radius, CommunityBossSettings.MIN_PARTICIPATION_RADIUS,
                    CommunityBossSettings.MAX_PARTICIPATION_RADIUS, CommunityBossSettings.DEFAULT_PARTICIPATION_RADIUS);
            return CommunityBossSettings.DEFAULT_PARTICIPATION_RADIUS;
        }
        return radius;
    }

    /** Même règle pour le contour (#471) : une valeur hors bornes revient seule au défaut. */
    private int glowRadius(YamlConfiguration yaml) {
        int radius = yaml.getInt("glow-radius", CommunityBossSettings.DEFAULT_GLOW_RADIUS);
        if (radius < CommunityBossSettings.MIN_GLOW_RADIUS || radius > CommunityBossSettings.MAX_GLOW_RADIUS) {
            logger.warn("mobs/community-bosses.yml : glow-radius {} hors bornes ({} à {}), {} utilisé.",
                    radius, CommunityBossSettings.MIN_GLOW_RADIUS, CommunityBossSettings.MAX_GLOW_RADIUS,
                    CommunityBossSettings.DEFAULT_GLOW_RADIUS);
            return CommunityBossSettings.DEFAULT_GLOW_RADIUS;
        }
        return radius;
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
                    yaml.getInt("max-active", CommunityBossSettings.DEFAULT_MAX_ACTIVE),
                    participationRadius(yaml),
                    yaml.getBoolean("glow", true),
                    glowRadius(yaml),
                    yaml.getBoolean("surface-only", true));
        } catch (Exception e) {
            logger.warn("Impossible de charger mobs/community-bosses.yml, scheduler désactivé par défaut : {}",
                    e.getMessage());
            return CommunityBossSettings.defaults();
        }
    }
}
