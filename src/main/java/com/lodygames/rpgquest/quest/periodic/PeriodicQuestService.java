package com.lodygames.rpgquest.quest.periodic;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.config.PeriodicQuestConfig;
import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository;
import com.lodygames.rpgquest.database.PeriodicQuestAssignmentRepository.Assignment;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestPeriod;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.AcceptOutcome;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

/**
 * Issue #15 — quêtes quotidiennes et hebdomadaires.
 *
 * <p><strong>Modèles</strong> : les quêtes {@code period: daily|weekly} chargées par
 * {@link YamlQuestEngine}. <strong>Attribution</strong> : par joueur et par période, persistée dans
 * {@code periodic_quest_assignments} — une sélection déterministe ({@link PeriodicQuestSelector})
 * insérée de façon idempotente, puis chaque quête est démarrée dans le moteur de quêtes habituel
 * ({@link QuestProgressEngine#startAssignedPeriodic}) : progression, remise, récompenses et
 * persistance sont exactement celles d'une quête ordinaire, une complétion = un jeton de paiement.</p>
 *
 * <p><strong>Déblocage</strong> : rien n'est attribué tant que le joueur n'a pas ouvert son journal
 * de quêtes une première fois ({@link #unlockAndRefresh}). Ensuite, ses quêtes se renouvellent à la
 * connexion (après chargement de sa progression) et, pour les joueurs en ligne, au plus une minute
 * après chaque réinitialisation.</p>
 *
 * <p><strong>Expiration</strong> : à la réinitialisation, une quête de la période échue encore
 * active est abandonnée (sans récompense) ; une quête finie qui attendait seulement de la place
 * dans l'inventaire ({@code READY_TO_TURN_IN}) est conservée — elle a été réussie à temps.</p>
 *
 * <p>Un rafraîchissement par joueur à la fois (chaîne de futurs) : clics répétés sur l'onglet,
 * connexion et cadence de reset simultanées ne font jamais deux attributions ni deux démarrages.</p>
 */
public final class PeriodicQuestService implements PluginService {

    /** Variable joueur posée à la première ouverture du journal : les quêtes périodiques sont débloquées. */
    public static final String UNLOCKED_KEY = "__periodic_quests_unlocked__";
    private static final long CHECK_PERIOD_TICKS = 20L * 60;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Ce que montre un onglet du journal : la période en cours et ses quêtes, dans l'ordre. */
    public record PeriodView(QuestPeriod period, String periodKey, List<Assignment> assignments, Instant nextReset) {
    }

    private final RPGQuestPlugin plugin;
    private final YamlQuestEngine questEngine;
    private final QuestProgressEngine progressEngine;
    private final PeriodicQuestAssignmentRepository repository;
    private final PlayerVariableRepository variables;
    private final Supplier<PeriodicQuestConfig> config;
    private final Supplier<Instant> clock;
    private final Executor mainThread;
    private final Logger logger;

    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    /** Joueurs débloqués en ligne → clés des périodes déjà traitées (la cadence ne relance que s'il y a du neuf). */
    private final Map<UUID, Map<QuestPeriod, String>> handledKeys = new ConcurrentHashMap<>();
    private BukkitTask task;

    public PeriodicQuestService(RPGQuestPlugin plugin, YamlQuestEngine questEngine, QuestProgressEngine progressEngine,
                                PeriodicQuestAssignmentRepository repository, PlayerVariableRepository variables,
                                Supplier<PeriodicQuestConfig> config, Supplier<Instant> clock, Executor mainThread,
                                Logger logger) {
        this.plugin = plugin;
        this.questEngine = questEngine;
        this.progressEngine = progressEngine;
        this.repository = repository;
        this.variables = variables;
        this.config = config;
        this.clock = clock;
        this.mainThread = mainThread;
        this.logger = logger;
    }

    @Override
    public void start() {
        progressEngine.onProgressLoaded(this::handleProgressLoaded);
        plugin.getServer().getPluginManager().registerEvents(new QuitListener(), plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkResets,
                CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        chains.clear();
        handledKeys.clear();
    }

    public boolean enabled() {
        return config.get().enabled();
    }

    public PeriodCalendar calendar() {
        return PeriodCalendar.of(config.get());
    }

    /** Modèles chargés pour une période, par identifiant. */
    public List<QuestDefinition> templates(QuestPeriod period) {
        return questEngine.quests().stream().filter(quest -> quest.period() == period).toList();
    }

    /**
     * Ouverture du journal : débloque les quêtes périodiques du joueur (une fois pour toutes), puis
     * attribue/démarre celles de la période en cours. Sans effet si la fonction est désactivée.
     */
    public CompletableFuture<Void> unlockAndRefresh(Player player) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        UUID playerId = player.getUniqueId();
        boolean known = handledKeys.containsKey(playerId);
        handledKeys.putIfAbsent(playerId, new ConcurrentHashMap<>());
        CompletableFuture<Void> unlock = known ? CompletableFuture.completedFuture(null)
                : variables.set(playerId, UNLOCKED_KEY, "1");
        return unlock.thenCompose(v -> refresh(player));
    }

    /** Lecture d'un onglet : la période en cours, sans rien écrire. */
    public CompletableFuture<PeriodView> view(UUID playerId, QuestPeriod period) {
        PeriodCalendar calendar = calendar();
        Instant now = clock.get();
        String key = calendar.periodKey(period, now);
        return repository.findPeriod(playerId, period.yamlKey(), key)
                .thenApply(rows -> new PeriodView(period, key, rows, calendar.nextReset(period, now)));
    }

    /** Attribue, démarre et fait expirer ce qui doit l'être, une seule passe à la fois par joueur. */
    public CompletableFuture<Void> refresh(Player player) {
        UUID playerId = player.getUniqueId();
        return chains.compute(playerId, (id, previous) -> (previous == null
                ? CompletableFuture.<Void>completedFuture(null)
                : previous.handle((v, error) -> (Void) null))
                .thenCompose(v -> doRefresh(player))
                .exceptionally(error -> {
                    logger.error("Quêtes périodiques : rafraîchissement impossible pour {}", playerId, error);
                    return null;
                }));
    }

    private CompletableFuture<Void> doRefresh(Player player) {
        PeriodicQuestConfig settings = config.get();
        if (!settings.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        PeriodCalendar calendar = PeriodCalendar.of(settings);
        Instant now = clock.get();
        UUID playerId = player.getUniqueId();
        return repository.findOpen(playerId)
                .thenCompose(open -> closeStale(player, open, calendar, now))
                .thenCompose(v -> assignAndStart(player, QuestPeriod.DAILY, settings, calendar, now))
                .thenCompose(v -> assignAndStart(player, QuestPeriod.WEEKLY, settings, calendar, now));
    }

    private CompletableFuture<Void> closeStale(Player player, List<Assignment> open, PeriodCalendar calendar,
                                               Instant now) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Assignment assignment : open) {
            QuestPeriod period = QuestPeriod.fromYaml(assignment.periodType()).orElse(null);
            if (period != null && assignment.periodKey().equals(calendar.periodKey(period, now))) {
                continue;
            }
            chain = chain.thenCompose(v -> expire(player, assignment));
        }
        return chain;
    }

    private CompletableFuture<Void> expire(Player player, Assignment assignment) {
        NamespacedKey questId = NamespacedKey.fromString(assignment.questId());
        if (questId == null || !PeriodicQuestAssignmentRepository.STARTED.equals(assignment.status())) {
            return repository.close(player.getUniqueId(), assignment).thenApply(closed -> null);
        }
        return progressEngine.stateOf(player.getUniqueId(), questId).thenCompose(state -> {
            if (state != QuestState.ACTIVE) {
                // Terminée, déjà abandonnée, ou finie en attente de place (READY_TO_TURN_IN) : rien à retirer.
                return repository.close(player.getUniqueId(), assignment).thenApply(closed -> null);
            }
            return repository.close(player.getUniqueId(), assignment).thenCompose(closed -> {
                if (!closed) {
                    return CompletableFuture.completedFuture(null);
                }
                return onMain(() -> {
                    progressEngine.abandon(player, questId);
                    if (player.isOnline()) {
                        player.sendMessage(MM.deserialize(
                                "<gray>⌛ Quête expirée, sans récompense :</gray> <white><quest></white>",
                                Placeholder.component("quest", titleOf(questId))));
                    }
                    return null;
                });
            });
        });
    }

    private CompletableFuture<Void> assignAndStart(Player player, QuestPeriod period, PeriodicQuestConfig settings,
                                                   PeriodCalendar calendar, Instant now) {
        UUID playerId = player.getUniqueId();
        String key = calendar.periodKey(period, now);
        boolean[] fresh = {false};
        return repository.findPeriod(playerId, period.yamlKey(), key).thenCompose(existing -> {
            if (!existing.isEmpty()) {
                return CompletableFuture.completedFuture(existing);
            }
            List<String> pool = templates(period).stream().map(quest -> quest.id().toString()).toList();
            int count = settings.count(period);
            if (pool.isEmpty() || count == 0) {
                return CompletableFuture.completedFuture(List.<Assignment>of());
            }
            return repository.previousQuestIds(playerId, period.yamlKey(), key).thenCompose(previous -> {
                List<String> chosen = PeriodicQuestSelector.select(playerId, period.yamlKey(), key, pool,
                        new HashSet<>(previous), count);
                fresh[0] = true;
                return repository.assign(playerId, period.yamlKey(), key, chosen);
            });
        }).thenCompose(rows -> startAssigned(player, rows).thenRun(() -> {
            handledKeys.computeIfPresent(playerId, (id, keys) -> {
                keys.put(period, key);
                return keys;
            });
            if (fresh[0] && !rows.isEmpty() && player.isOnline()) {
                mainThread.execute(() -> player.sendMessage(MM.deserialize(
                        "<gold>✦ Nouvelles " + period.label().toLowerCase(java.util.Locale.ROOT)
                                + " :</gold> <gray>ouvre ton journal de quêtes.</gray>")));
            }
        }));
    }

    private CompletableFuture<Void> startAssigned(Player player, List<Assignment> rows) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Assignment row : rows) {
            if (!PeriodicQuestAssignmentRepository.ASSIGNED.equals(row.status())) {
                continue;
            }
            NamespacedKey questId = NamespacedKey.fromString(row.questId());
            if (questId == null) {
                continue;
            }
            chain = chain.thenCompose(v -> {
                if (!player.isOnline()) {
                    // Repris à la prochaine connexion : la ligne reste ASSIGNED.
                    return CompletableFuture.completedFuture(null);
                }
                return onMain(() -> progressEngine.startAssignedPeriodic(player, questId))
                        .thenCompose(future -> future)
                        .thenCompose(outcome -> {
                            AcceptOutcome.Result result = outcome.result();
                            if (result == AcceptOutcome.Result.ACCEPTED || result == AcceptOutcome.Result.ALREADY_ACTIVE) {
                                return repository.markStarted(player.getUniqueId(), row).thenApply(started -> null);
                            }
                            // Modèle retiré depuis (UNKNOWN_QUEST) : la ligne reste ASSIGNED, affichée
                            // « indisponible », et sera close à la réinitialisation.
                            logger.warn("Quête périodique {} non démarrée pour {} : {}", questId,
                                    player.getUniqueId(), result);
                            return CompletableFuture.<Void>completedFuture(null);
                        });
            });
        }
        return chain;
    }

    // ---- Connexion, déconnexion, cadence ------------------------------------

    private void handleProgressLoaded(UUID playerId) {
        if (!enabled()) {
            return;
        }
        variables.get(playerId, UNLOCKED_KEY).thenAccept(value -> {
            if (value.isEmpty()) {
                return;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            handledKeys.putIfAbsent(playerId, new ConcurrentHashMap<>());
            refresh(player);
        }).exceptionally(error -> {
            logger.error("Quêtes périodiques : lecture du déblocage impossible pour {}", playerId, error);
            return null;
        });
    }

    /** Toutes les minutes : relance les joueurs débloqués en ligne dont une période vient de changer. */
    void checkResets() {
        PeriodicQuestConfig settings = config.get();
        if (!settings.enabled()) {
            return;
        }
        PeriodCalendar calendar = PeriodCalendar.of(settings);
        Instant now = clock.get();
        List<UUID> due = new ArrayList<>();
        handledKeys.forEach((playerId, keys) -> {
            for (QuestPeriod period : QuestPeriod.values()) {
                if (!calendar.periodKey(period, now).equals(keys.get(period))) {
                    due.add(playerId);
                    return;
                }
            }
        });
        for (UUID playerId : due) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                refresh(player);
            }
        }
    }

    private net.kyori.adventure.text.Component titleOf(NamespacedKey questId) {
        return questEngine.find(questId).map(quest -> MM.deserialize(quest.title().base()))
                .orElse(net.kyori.adventure.text.Component.text(questId.toString()));
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        mainThread.execute(() -> {
            try {
                result.complete(action.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private final class QuitListener implements Listener {
        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            handledKeys.remove(playerId);
            chains.remove(playerId);
        }
    }
}
