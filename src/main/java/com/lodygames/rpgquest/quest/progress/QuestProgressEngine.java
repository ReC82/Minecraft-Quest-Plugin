package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRecord;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.quest.QuestLoadReport;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.ObjectiveType;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.VariableReward;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;

/**
 * Orchestre l'acceptation, la progression, la remise et l'abandon des
 * quêtes. Garde en mémoire (par joueur) uniquement les quêtes {@code
 * ACTIVE} : c'est la seule structure consultée sur le chemin chaud des
 * événements de jeu, jamais la base de données ni la liste complète des
 * quêtes. Chaque mutation de compteur/étape/état est appliquée en mémoire
 * de façon synchrone puis persistée en tâche de fond — c'est cette
 * synchronicité de la mise à jour mémoire (avant toute opération async) qui
 * empêche les doubles incréments et les doubles remises : un second
 * événement arrivant avant la fin de la persistance voit déjà le nouvel
 * état en mémoire et est ignoré.
 */
public final class QuestProgressEngine implements PluginService {

    private final RPGQuestPlugin plugin;
    private final YamlQuestEngine questEngine;
    private final QuestProgressRepository repository;
    private final PlayerVariableRepository variableRepository;
    private final QuestMessagesService messagesService;
    private final Logger logger;

    private final Map<UUID, Map<NamespacedKey, ActiveQuestProgress>> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<ObjectiveType, Listener> registeredListeners = new EnumMap<>(ObjectiveType.class);
    private final List<Consumer<UUID>> progressListeners = new CopyOnWriteArrayList<>();
    private volatile QuestObjectiveIndex index = new QuestObjectiveIndex(List.of());

    public QuestProgressEngine(RPGQuestPlugin plugin, YamlQuestEngine questEngine, QuestProgressRepository repository,
                                PlayerVariableRepository variableRepository, QuestMessagesService messagesService) {
        this.plugin = plugin;
        this.questEngine = questEngine;
        this.repository = repository;
        this.variableRepository = variableRepository;
        this.messagesService = messagesService;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        rebuildIndexAndListeners();
    }

    @Override
    public void stop() {
        registeredListeners.values().forEach(HandlerList::unregisterAll);
        registeredListeners.clear();
    }

    /** Écouteur de connexion/déconnexion à enregistrer sans condition, contrairement aux listeners d'objectifs. */
    public Listener connectionListener() {
        return new QuestProgressConnectionListener(this);
    }

    /**
     * Notifié après toute mutation de la progression d'un joueur (acceptation,
     * incrément d'objectif, changement d'étape, remise, abandon). Utilisé par
     * le journal de quêtes ({@code ui}) pour rafraîchir un menu ouvert ou la
     * bossbar de suivi sans tâche répétitive — l'affichage ne se met à jour
     * qu'en réaction à un changement réel, jamais par sondage périodique.
     */
    public void onProgressChanged(Consumer<UUID> listener) {
        progressListeners.add(listener);
    }

    private void notifyChanged(UUID playerId) {
        for (Consumer<UUID> listener : progressListeners) {
            listener.accept(playerId);
        }
    }

    /** Recharge les définitions de quêtes puis reconstruit index et listeners en conséquence. */
    public QuestLoadReport reloadQuestDefinitions() {
        QuestLoadReport report = questEngine.reload();
        rebuildIndexAndListeners();
        return report;
    }

    private void rebuildIndexAndListeners() {
        QuestObjectiveIndex newIndex = new QuestObjectiveIndex(questEngine.quests());
        this.index = newIndex;

        for (ObjectiveType type : ObjectiveType.values()) {
            boolean needed = !newIndex.isEmpty(type);
            boolean registered = registeredListeners.containsKey(type);
            if (needed && !registered) {
                Listener listener = createListener(type);
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
                registeredListeners.put(type, listener);
            } else if (!needed && registered) {
                HandlerList.unregisterAll(registeredListeners.remove(type));
            }
        }
    }

    private Listener createListener(ObjectiveType type) {
        return switch (type) {
            case BREAK_BLOCK -> new QuestBlockBreakListener(this);
            case PLACE_BLOCK -> new QuestBlockPlaceListener(this);
            case KILL_ENTITY -> new QuestEntityDeathListener(this);
            case COLLECT_ITEM -> new QuestItemPickupListener(this);
            case CRAFT_ITEM -> new QuestCraftItemListener(this);
            case TALK_TO_NPC -> new QuestNpcInteractListener(this);
            case REACH_LOCATION -> new QuestLocationListener(this);
        };
    }

    // ---- Cycle de vie joueur -------------------------------------------------

    /** Recharge depuis la base la progression active du joueur (reconnexion en cours d'étape incluse). */
    public CompletableFuture<Void> loadForPlayer(UUID playerId) {
        return repository.findAll(playerId).thenCompose(records -> {
            Map<NamespacedKey, ActiveQuestProgress> map = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> counterLoads = new ArrayList<>();

            for (QuestProgressRecord record : records) {
                if (record.state() != QuestState.ACTIVE && record.state() != QuestState.READY_TO_TURN_IN) {
                    continue;
                }
                Optional<QuestDefinition> questOpt = questEngine.find(record.questId());
                if (questOpt.isEmpty()) {
                    continue;
                }
                QuestDefinition quest = questOpt.get();
                int stepIndex = Math.max(0, indexOfStep(quest, record.currentStepId()));
                ActiveQuestProgress progress = new ActiveQuestProgress(record.questId(), stepIndex, record.state());
                map.put(record.questId(), progress);

                String stepId = quest.steps().get(stepIndex).id();
                counterLoads.add(repository.findObjectiveProgress(playerId, record.questId(), stepId)
                        .thenAccept(counters -> counters.forEach(progress::setCounter)));
            }

            return CompletableFuture.allOf(counterLoads.toArray(CompletableFuture[]::new))
                    .thenRun(() -> activeByPlayer.put(playerId, map));
        }).exceptionally(error -> {
            logger.error("Impossible de charger la progression de quêtes pour {}", playerId, error);
            return null;
        });
    }

    public void unloadForPlayer(UUID playerId) {
        activeByPlayer.remove(playerId);
    }

    private int indexOfStep(QuestDefinition quest, String stepId) {
        List<QuestStep> steps = quest.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Commandes joueur ------------------------------------------------

    public CompletableFuture<AcceptOutcome> accept(Player player, NamespacedKey questId) {
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            return CompletableFuture.completedFuture(AcceptOutcome.unknown());
        }
        QuestDefinition quest = questOpt.get();
        UUID playerId = player.getUniqueId();

        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        if (playerActive.containsKey(questId)) {
            return CompletableFuture.completedFuture(AcceptOutcome.alreadyActive());
        }

        CompletableFuture<AcceptOutcome> future = repository.find(playerId, questId).thenCompose(existing -> {
            QuestState currentState = existing.map(QuestProgressRecord::state).orElse(QuestState.NOT_STARTED);
            if (currentState == QuestState.ACTIVE || currentState == QuestState.READY_TO_TURN_IN) {
                return CompletableFuture.completedFuture(AcceptOutcome.alreadyActive());
            }
            if (currentState == QuestState.COMPLETED && !quest.repeatable()) {
                return CompletableFuture.completedFuture(AcceptOutcome.notRepeatable());
            }

            return checkPrerequisites(playerId, quest).thenCompose(missing -> {
                if (!missing.isEmpty()) {
                    return CompletableFuture.completedFuture(AcceptOutcome.missingPrerequisites(missing));
                }

                ActiveQuestProgress progress = new ActiveQuestProgress(questId, 0, QuestState.ACTIVE);
                playerActive.put(questId, progress);

                String firstStepId = quest.steps().get(0).id();
                return repository.upsertState(playerId, questId, QuestState.ACTIVE, firstStepId)
                        .thenApply(v -> AcceptOutcome.accepted());
            });
        });
        future.whenComplete((outcome, error) -> notifyChanged(playerId));
        return future;
    }

    private CompletableFuture<List<NamespacedKey>> checkPrerequisites(UUID playerId, QuestDefinition quest) {
        if (quest.prerequisites().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CompletableFuture<Boolean>> checks = quest.prerequisites().stream()
                .map(prereqId -> repository.find(playerId, prereqId)
                        .thenApply(opt -> opt.map(r -> r.state() == QuestState.COMPLETED).orElse(false)))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenApply(v -> {
            List<NamespacedKey> missing = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                if (!checks.get(i).join()) {
                    missing.add(quest.prerequisites().get(i));
                }
            }
            return missing;
        });
    }

    public AbandonOutcome abandon(Player player, NamespacedKey questId) {
        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        ActiveQuestProgress progress = playerActive == null ? null : playerActive.get(questId);
        if (progress == null || (progress.state() != QuestState.ACTIVE && progress.state() != QuestState.READY_TO_TURN_IN)) {
            return AbandonOutcome.NOTHING_TO_ABANDON;
        }

        playerActive.remove(questId);
        repository.upsertState(playerId, questId, QuestState.ABANDONED, null).exceptionally(error -> {
            logger.error("Impossible de persister l'abandon de {} pour {}", questId, playerId, error);
            return null;
        });
        notifyChanged(playerId);
        return AbandonOutcome.ABANDONED;
    }

    /**
     * Force la fin d'une quête sans passer par la progression normale des
     * objectifs — utilisé par la commande admin {@code /quest complete}
     * (tests) et par l'action de dialogue {@code TURN_IN_QUEST} (usage
     * joueur légitime, ex. remise à un PNJ). Si aucune progression n'est en
     * mémoire (jamais acceptée, ou déjà remise et donc évincée du cache),
     * on consulte la base avant de conclure — sans quoi une quête déjà
     * terminée naturellement serait traitée comme neuve et sa récompense
     * accordée une seconde fois.
     */
    public CompletableFuture<CompleteOutcome> forceComplete(Player player, NamespacedKey questId) {
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            return CompletableFuture.completedFuture(CompleteOutcome.UNKNOWN_QUEST);
        }
        QuestDefinition quest = questOpt.get();
        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

        ActiveQuestProgress cached = playerActive.get(questId);
        if (cached != null) {
            if (cached.state() == QuestState.COMPLETED) {
                return CompletableFuture.completedFuture(CompleteOutcome.ALREADY_COMPLETED);
            }
            turnIn(player, quest, cached);
            return CompletableFuture.completedFuture(CompleteOutcome.COMPLETED);
        }

        return repository.find(playerId, questId).thenCompose(record -> {
            if (record.isPresent() && record.get().state() == QuestState.COMPLETED) {
                return CompletableFuture.completedFuture(CompleteOutcome.ALREADY_COMPLETED);
            }
            CompletableFuture<CompleteOutcome> result = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                ActiveQuestProgress progress = new ActiveQuestProgress(questId, quest.steps().size() - 1, QuestState.ACTIVE);
                playerActive.put(questId, progress);
                turnIn(player, quest, progress);
                result.complete(CompleteOutcome.COMPLETED);
            });
            return result;
        });
    }

    /** État d'une quête pour un joueur : cache si active, sinon base (NOT_STARTED si aucune ligne). */
    public CompletableFuture<QuestState> stateOf(UUID playerId, NamespacedKey questId) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive != null) {
            ActiveQuestProgress cached = playerActive.get(questId);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached.state());
            }
        }
        return repository.find(playerId, questId).thenApply(opt -> opt.map(QuestProgressRecord::state).orElse(QuestState.NOT_STARTED));
    }

    /**
     * Satisfait manuellement tous les objectifs de l'étape courante (ex.
     * appelée par une action de dialogue ADVANCE_QUEST) et avance comme si
     * le joueur les avait complétés en jeu : étape suivante, ou remise si
     * c'était la dernière. Ne fait rien si la quête n'est pas {@code ACTIVE}
     * pour ce joueur.
     *
     * @return {@code true} si une quête active a bien été avancée
     */
    public boolean advanceStep(Player player, NamespacedKey questId) {
        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        ActiveQuestProgress progress = playerActive == null ? null : playerActive.get(questId);
        if (progress == null || progress.state() != QuestState.ACTIVE) {
            return false;
        }
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            return false;
        }
        QuestDefinition quest = questOpt.get();
        QuestStep step = quest.steps().get(progress.currentStepIndex());
        for (int i = 0; i < step.objectives().size(); i++) {
            int required = requiredAmount(step.objectives().get(i));
            if (progress.counter(i) < required) {
                progress.setCounter(i, required);
                repository.setObjectiveProgress(playerId, questId, step.id(), i, required).exceptionally(error -> {
                    logger.error("Impossible de persister l'avancement forcé de {} pour {}", questId, playerId, error);
                    return null;
                });
            }
        }
        checkStepCompletion(player, quest, progress);
        return true;
    }

    public CompletableFuture<Map<NamespacedKey, QuestState>> allStates(UUID playerId) {
        return repository.findAll(playerId).thenApply(records -> {
            Map<NamespacedKey, QuestState> states = new LinkedHashMap<>();
            for (QuestDefinition quest : questEngine.quests()) {
                states.put(quest.id(), QuestState.NOT_STARTED);
            }
            for (QuestProgressRecord record : records) {
                states.put(record.questId(), record.state());
            }
            return states;
        });
    }

    public Optional<QuestStepProgressView> activeStepView(UUID playerId, NamespacedKey questId) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive == null) {
            return Optional.empty();
        }
        ActiveQuestProgress progress = playerActive.get(questId);
        if (progress == null) {
            return Optional.empty();
        }
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            return Optional.empty();
        }
        QuestStep step = questOpt.get().steps().get(progress.currentStepIndex());
        List<ObjectiveProgressView> objectives = new ArrayList<>();
        for (int i = 0; i < step.objectives().size(); i++) {
            QuestObjective objective = step.objectives().get(i);
            objectives.add(new ObjectiveProgressView(describeObjective(objective), progress.counter(i), requiredAmount(objective)));
        }
        return Optional.of(new QuestStepProgressView(step.id(), objectives));
    }

    // ---- Événements de jeu (appelés par les listeners du même package) ---

    void handleBreakBlock(Player player, Material material) {
        handleCandidates(player, index.breakBlock(material));
    }

    void handlePlaceBlock(Player player, Material material) {
        handleCandidates(player, index.placeBlock(material));
    }

    void handleKillEntity(Player player, EntityType entityType) {
        handleCandidates(player, index.killEntity(entityType));
    }

    void handleCollectItem(Player player, Material material) {
        handleCandidates(player, index.collectItem(material));
    }

    void handleCraftItem(Player player, Material material) {
        handleCandidates(player, index.craftItem(material));
    }

    void handleTalkToNpc(Player player, String npcId) {
        handleCandidates(player, index.talkToNpc(npcId));
    }

    void handleReachLocation(Player player, String world, double x, double y, double z) {
        List<ObjectiveRef> candidates = index.reachLocation(world).stream()
                .filter(ref -> ref.objective() instanceof ReachLocationObjective loc && withinRadius(loc, x, y, z))
                .toList();
        handleCandidates(player, candidates);
    }

    private boolean withinRadius(ReachLocationObjective location, double x, double y, double z) {
        double dx = location.x() - x;
        double dy = location.y() - y;
        double dz = location.z() - z;
        return (dx * dx + dy * dy + dz * dz) <= (location.radius() * location.radius());
    }

    private void handleCandidates(Player player, List<ObjectiveRef> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive == null || playerActive.isEmpty()) {
            return;
        }

        for (ObjectiveRef ref : candidates) {
            ActiveQuestProgress progress = playerActive.get(ref.questId());
            if (progress == null || progress.state() != QuestState.ACTIVE) {
                continue;
            }
            if (progress.currentStepIndex() != ref.stepIndex()) {
                continue;
            }
            int required = requiredAmount(ref.objective());
            if (progress.counter(ref.objectiveIndex()) >= required) {
                continue;
            }

            int updated = progress.increment(ref.objectiveIndex());
            repository.setObjectiveProgress(playerId, ref.questId(), ref.stepId(), ref.objectiveIndex(), updated)
                    .exceptionally(error -> {
                        logger.error("Impossible de persister la progression de {} pour {}", ref.questId(), playerId, error);
                        return null;
                    });

            if (updated >= required) {
                questEngine.find(ref.questId()).ifPresent(quest -> checkStepCompletion(player, quest, progress));
            }
        }
        notifyChanged(playerId);
    }

    private void checkStepCompletion(Player player, QuestDefinition quest, ActiveQuestProgress progress) {
        QuestStep step = quest.steps().get(progress.currentStepIndex());
        for (int i = 0; i < step.objectives().size(); i++) {
            if (progress.counter(i) < requiredAmount(step.objectives().get(i))) {
                return;
            }
        }

        UUID playerId = player.getUniqueId();
        int nextIndex = progress.currentStepIndex() + 1;
        if (nextIndex < quest.steps().size()) {
            progress.advanceToStep(nextIndex);
            String nextStepId = quest.steps().get(nextIndex).id();
            repository.upsertState(playerId, quest.id(), QuestState.ACTIVE, nextStepId).exceptionally(error -> {
                logger.error("Impossible de persister l'avancement d'étape pour {} ({})", quest.id(), playerId, error);
                return null;
            });
            player.sendMessage(messagesService.current().format("quest.step-completed",
                    Placeholder.unparsed("quest", quest.title().base()),
                    Placeholder.unparsed("step", nextStepId)));
            notifyChanged(playerId);
        } else {
            turnIn(player, quest, progress);
        }
    }

    private void turnIn(Player player, QuestDefinition quest, ActiveQuestProgress progress) {
        if (progress.state() == QuestState.COMPLETED) {
            return;
        }
        // Bascule mémoire immédiate et synchrone : garde anti double-remise.
        progress.setState(QuestState.READY_TO_TURN_IN);
        progress.setState(QuestState.COMPLETED);

        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive != null) {
            playerActive.remove(quest.id());
        }

        repository.upsertState(playerId, quest.id(), QuestState.COMPLETED, null).exceptionally(error -> {
            logger.error("Impossible de persister la fin de quête {} pour {}", quest.id(), playerId, error);
            return null;
        });

        grantRewards(player, quest);
        player.sendMessage(messagesService.current().format(
                "quest.completed", Placeholder.unparsed("quest", quest.title().base())));
        notifyChanged(playerId);
    }

    private void grantRewards(Player player, QuestDefinition quest) {
        for (QuestReward reward : quest.rewards()) {
            switch (reward) {
                case ExperienceReward r -> player.giveExp(r.amount());
                case ItemReward r -> {
                    ItemStack stack = new ItemStack(r.material(), r.amount());
                    player.getInventory().addItem(stack).values()
                            .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                }
                case VariableReward r -> variableRepository.set(player.getUniqueId(), r.key(), r.value())
                        .exceptionally(error -> {
                            logger.error("Impossible d'appliquer la récompense variable {} pour {}",
                                    r.key(), player.getUniqueId(), error);
                            return null;
                        });
                case CommandReward r -> {
                    String command = r.command().replace("%player%", player.getName());
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
                }
            }
        }
    }

    private int requiredAmount(QuestObjective objective) {
        return QuestObjective.requiredAmount(objective);
    }

    private String describeObjective(QuestObjective objective) {
        return QuestObjective.describe(objective);
    }
}
