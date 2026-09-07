package com.lodygames.rpgquest.web.admin;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.PluginConfig;
import com.lodygames.rpgquest.world.WorldService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Implémentation réelle de {@link HealthSource} adossée à l'API publique Paper et à la config du
 * plugin. Lectures légères ({@code getOnlinePlayers().size()}, {@code getWorld(name)}) — même
 * nature que {@code web.WebSnapshotWriter}. Aucune lecture de {@code data.db}.
 */
public final class BukkitHealthSource implements HealthSource {

    private final RPGQuestPlugin plugin;
    private final WorldService worldService;
    private final Supplier<PluginConfig> config;
    private final Instant startedAt = Instant.now();

    public BukkitHealthSource(RPGQuestPlugin plugin, WorldService worldService, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.config = config;
    }

    @Override
    public String pluginName() {
        return "RPGQuest";
    }

    @Override
    public String pluginVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public int playersOnline() {
        return plugin.getServer().getOnlinePlayers().size();
    }

    @Override
    public int maxPlayers() {
        return plugin.getServer().getMaxPlayers();
    }

    @Override
    public long uptimeSeconds() {
        return Duration.between(startedAt, Instant.now()).getSeconds();
    }

    @Override
    public String targetEnv() {
        String env = System.getenv("RPGQUEST_WEB_ADMIN_ENV");
        return env == null || env.isBlank() ? "unknown" : env.trim();
    }

    @Override
    public List<WorldInfo> essentialWorlds() {
        PluginConfig cfg = config.get();
        return List.of(
                world("hub", cfg.hub().world()),
                world("claims", cfg.claims().world()),
                world("wild", cfg.travel().wildWorld()));
    }

    private WorldInfo world(String role, String name) {
        return new WorldInfo(role, name, worldService.find(name).isPresent());
    }
}
