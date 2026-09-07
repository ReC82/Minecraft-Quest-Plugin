package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Agent sortant RPGQuest → PlugAdmin (issue #51) — service Paper. Le plugin <strong>initie</strong>
 * toutes les connexions (HTTPS sortant), aucune écoute entrante côté VeryGames.
 *
 * <p>Cette classe se limite au câblage Bukkit : elle planifie {@link AgentLoop#heartbeatTick()} et
 * {@link AgentLoop#pollTick()} sur des threads <strong>asynchrones</strong> (jamais le thread
 * principal) et les annule proprement à l'arrêt. Toute la logique (backoff, idempotence, logs
 * limités) vit dans {@link AgentLoop}, testable sans serveur.</p>
 *
 * <p>Fail-closed : si {@link AgentConfig#enabled()} est faux (activation absente ou secret
 * manquant), {@link #start()} ne planifie rien.</p>
 */
public final class PlugAdminAgent implements PluginService {

    private final RPGQuestPlugin plugin;
    private final Logger logger;
    private final AgentConfig config;
    private final HeartbeatPayload heartbeatPayload;
    private final AgentActionExecutor executor;
    private final ProcessedActionCache processed;

    private AgentLoop loop;
    private BukkitTask heartbeatTask;
    private BukkitTask pollTask;

    public PlugAdminAgent(RPGQuestPlugin plugin, AgentConfig config, HeartbeatPayload heartbeatPayload,
                          AgentActionExecutor executor) {
        this.plugin = plugin;
        this.logger = plugin.getSLF4JLogger();
        this.config = config;
        this.heartbeatPayload = heartbeatPayload;
        this.executor = executor;
        this.processed = new ProcessedActionCache();
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            logger.info("Agent PlugAdmin inactif (configuration désactivée ou incomplète).");
            return;
        }
        loop = new AgentLoop(logger, config, heartbeatPayload, executor, processed, new PlugAdminClient(config));

        long heartbeatTicks = Math.max(1, config.heartbeatSeconds()) * 20L;
        long pollTicks = Math.max(1, config.pollSeconds()) * 20L;
        heartbeatTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, loop::heartbeatTick, 40L, heartbeatTicks);
        if (config.actionsEnabled()) {
            pollTask = plugin.getServer().getScheduler()
                    .runTaskTimerAsynchronously(plugin, loop::pollTick, 100L, pollTicks);
        }
        logger.info("Agent PlugAdmin démarré : cible {} ({}), heartbeat {}s, actions {}.",
                config.agentId(), config.baseUrl(), config.heartbeatSeconds(),
                config.actionsEnabled() ? "poll " + config.pollSeconds() + "s" : "désactivées");
    }

    @Override
    public void stop() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        loop = null;
    }
}
