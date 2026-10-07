package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRecord;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.economy.QuestRewardDue;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.economy.QuestRewardReceipt;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestLoadReport;
import com.lodygames.rpgquest.quest.QuestMessages;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.MoneyReward;
import com.lodygames.rpgquest.quest.model.ObjectiveType;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.VariableReward;
import java.time.Duration;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
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

    /**
     * Durées d'un Title de notification de quête (démarrage/fin) : bref et sobre — jamais le
     * chat, voir {@link #showQuestStarted}/{@link #showQuestCompleted}/{@link
     * #showObjectiveProgress} et {@code messages.yml} pour le contenu (entièrement
     * personnalisable, jamais une quête codée en dur ici).
     */
    private static final Title.Times FEEDBACK_TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500));

    /**
     * Nombre maximal de récompenses monétaires dues reprises en une fois, à la connexion d'un
     * joueur. Borne volontaire : une reprise est un rattrapage, pas un traitement par lots.
     */
    private static final int RECOVERY_BATCH = 20;

    /**
     * Au-delà de ce nombre d'échecs, une dette n'est plus reprise automatiquement et attend une
     * action du panel. S'acharner ne corrigerait pas la cause et noierait les logs.
     */
    private static final int RECOVERY_MAX_ATTEMPTS = 5;

    private final RPGQuestPlugin plugin;
    private final YamlQuestEngine questEngine;
    private final QuestProgressRepository repository;
    private final PlayerVariableRepository variableRepository;
    private final QuestMessagesService messagesService;
    private final NpcIdentityService npcIdentityService;
    private final QuestRewardPayer rewardPayer;
    private final Logger logger;

    private final Map<UUID, Map<NamespacedKey, ActiveQuestProgress>> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<ObjectiveType, List<Listener>> registeredListeners = new EnumMap<>(ObjectiveType.class);
    private final List<Consumer<UUID>> progressListeners = new CopyOnWriteArrayList<>();
    /** Jeton anti double-clic / spam / appel concurrent d'une remise d'objets (issue #123), par joueur. */
    private final java.util.Set<UUID> deliveriesInFlight = ConcurrentHashMap.newKeySet();
    private volatile QuestObjectiveIndex index = new QuestObjectiveIndex(List.of());

    public QuestProgressEngine(RPGQuestPlugin plugin, YamlQuestEngine questEngine, QuestProgressRepository repository,
                                PlayerVariableRepository variableRepository, QuestMessagesService messagesService,
                                NpcIdentityService npcIdentityService, QuestRewardPayer rewardPayer) {
        this.plugin = plugin;
        this.questEngine = questEngine;
        this.repository = repository;
        this.variableRepository = variableRepository;
        this.messagesService = messagesService;
        this.npcIdentityService = npcIdentityService;
        this.rewardPayer = rewardPayer;
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        rebuildIndexAndListeners();
    }

    @Override
    public void stop() {
        registeredListeners.values().forEach(listeners -> listeners.forEach(HandlerList::unregisterAll));
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
                List<Listener> listeners = createListeners(type);
                listeners.forEach(listener -> plugin.getServer().getPluginManager().registerEvents(listener, plugin));
                registeredListeners.put(type, listeners);
            } else if (!needed && registered) {
                registeredListeners.remove(type).forEach(HandlerList::unregisterAll);
            }
        }
    }

    /**
     * {@code TALK_TO_NPC} enregistre en plus {@link QuestCitizensNpcInteractListener} quand Citizens
     * est actif : Citizens ne propage pas toujours {@code PlayerInteractEntityEvent} pour ses propres
     * entités, donc {@link QuestNpcInteractListener} (vanilla) ne suffit pas à lui seul dans ce cas.
     * Les deux écouteurs ne se chevauchent jamais sur une même entité (voir leurs gardes respectives).
     */
    private List<Listener> createListeners(ObjectiveType type) {
        Listener primary = switch (type) {
            case BREAK_BLOCK -> new QuestBlockBreakListener(this);
            case PLACE_BLOCK -> new QuestBlockPlaceListener(this);
            case KILL_ENTITY -> new QuestEntityDeathListener(this);
            case COLLECT_ITEM -> new QuestItemPickupListener(this);
            case CRAFT_ITEM -> new QuestCraftItemListener(this);
            case TALK_TO_NPC -> new QuestNpcInteractListener(this, npcIdentityService);
            case REACH_LOCATION -> new QuestLocationListener(this);
            // Issue #123 : DELIVER_ITEM_TO_NPC n'écoute AUCUN événement de jeu, volontairement.
            // Posséder, ramasser, fabriquer ou porter l'objet ne doit jamais faire progresser une
            // remise : seul l'appel explicite de deliverTo(...), déclenché par une action de
            // dialogue sur le bon PNJ, le fait. Un écouteur ici rouvrirait exactement la
            // sémantique de COLLECT_ITEM que ce type d'objectif existe pour éviter.
            case DELIVER_ITEM_TO_NPC -> null;
        };
        if (primary == null) {
            return List.of();
        }
        if (type == ObjectiveType.TALK_TO_NPC && npcIdentityService.citizensAvailable()) {
            return List.of(primary, new QuestCitizensNpcInteractListener(this, npcIdentityService));
        }
        return List.of(primary);
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
                    .thenRun(() -> {
                        activeByPlayer.put(playerId, map);
                        // Rattrapage des récompenses monétaires restées dues (crash, panne SQL).
                        // Ici et nulle part ailleurs : une seule fois par chargement, asynchrone,
                        // borné — jamais une tâche répétitive.
                        recoverPendingMoneyRewards(playerId);
                    });
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
        return accept(player, questId, false);
    }

    /**
     * Variante avec {@code ignorePrerequisites} : {@code true} saute uniquement la vérification des
     * prérequis (quête déjà {@code ACTIVE}/{@code COMPLETED} non répétable reste refusée à
     * l'identique). Réservé à un raccourci d'administration explicite ({@code
     * /rpgadmin quest start <joueur> <id> force}) — la valeur par défaut {@code false} garde le
     * respect strict des prérequis pour tout le reste (dialogues, moteur de Story, commande joueur).
     */
    public CompletableFuture<AcceptOutcome> accept(Player player, NamespacedKey questId, boolean ignorePrerequisites) {
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            return CompletableFuture.completedFuture(AcceptOutcome.unknown());
        }
        QuestDefinition quest = questOpt.get();
        UUID playerId = player.getUniqueId();

        // Issue #12 : les règles de refus sont décrites UNE SEULE FOIS, dans availability(). Le
        // signal visuel au-dessus des PNJ les réutilise telles quelles ; les dupliquer ailleurs
        // aurait garanti une divergence tôt ou tard.
        CompletableFuture<AcceptOutcome> future =
                availability(playerId, questId, ignorePrerequisites).thenCompose(availability -> {
                    switch (availability.status()) {
                        case UNKNOWN:
                            return CompletableFuture.completedFuture(AcceptOutcome.unknown());
                        case ALREADY_ACTIVE:
                            return CompletableFuture.completedFuture(AcceptOutcome.alreadyActive());
                        case NOT_REPEATABLE:
                            return CompletableFuture.completedFuture(AcceptOutcome.notRepeatable());
                        case MISSING_PREREQUISITES:
                            return CompletableFuture.completedFuture(
                                    AcceptOutcome.missingPrerequisites(availability.missingPrerequisites()));
                        default:
                            break;
                    }
                    Map<NamespacedKey, ActiveQuestProgress> playerActive =
                            activeByPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                    ActiveQuestProgress progress = new ActiveQuestProgress(questId, 0, QuestState.ACTIVE);
                    // putIfAbsent : deux acceptations concurrentes ne doivent pas écraser une
                    // progression déjà posée entre la vérification et ici.
                    if (playerActive.putIfAbsent(questId, progress) != null) {
                        return CompletableFuture.completedFuture(AcceptOutcome.alreadyActive());
                    }

                    String firstStepId = quest.steps().get(0).id();
                    return repository.upsertState(playerId, questId, QuestState.ACTIVE, firstStepId)
                            .thenApply(v -> AcceptOutcome.accepted());
                });
        future.whenComplete((outcome, error) -> {
            notifyChanged(playerId);
            if (error == null && outcome.result() == AcceptOutcome.Result.ACCEPTED) {
                runOnMainThread(() -> showQuestStarted(player, quest));
            }
        });
        return future;
    }

    /**
     * Issue #12 — <strong>une quête est-elle réellement disponible pour ce joueur ?</strong>
     * Lecture pure : aucune écriture, aucun effet de bord.
     *
     * <p>C'est la <strong>source unique</strong> des règles de refus. {@link #accept} s'en sert
     * avant d'écrire, et le signal visuel au-dessus des PNJ (issue #12) s'en sert pour décider
     * d'afficher quelque chose. Il n'existe donc aucune seconde implémentation susceptible de
     * diverger : une quête verrouillée, déjà active, ou terminée et non répétable ne peut pas
     * être annoncée comme nouvelle.</p>
     *
     * @param ignorePrerequisites ne saute <strong>que</strong> la vérification des prérequis, comme
     *                            dans {@link #accept} — l'état de la quête reste décisif
     */
    public CompletableFuture<Availability> availability(UUID playerId, NamespacedKey questId,
                                                        boolean ignorePrerequisites) {
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Availability.of(Availability.Status.UNKNOWN));
        }
        QuestDefinition quest = questOpt.get();

        // Lecture sans création : demander la disponibilité ne doit pas instancier une entrée de
        // progression pour un joueur qui n'en a pas.
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive != null && playerActive.containsKey(questId)) {
            return CompletableFuture.completedFuture(Availability.of(Availability.Status.ALREADY_ACTIVE));
        }

        return repository.find(playerId, questId).thenCompose(existing -> {
            QuestState currentState = existing.map(QuestProgressRecord::state).orElse(QuestState.NOT_STARTED);
            if (currentState == QuestState.ACTIVE || currentState == QuestState.READY_TO_TURN_IN) {
                return CompletableFuture.completedFuture(Availability.of(Availability.Status.ALREADY_ACTIVE));
            }
            if (currentState == QuestState.COMPLETED && !quest.repeatable()) {
                return CompletableFuture.completedFuture(Availability.of(Availability.Status.NOT_REPEATABLE));
            }
            CompletableFuture<List<NamespacedKey>> missingFuture = ignorePrerequisites
                    ? CompletableFuture.completedFuture(List.of())
                    : checkPrerequisites(playerId, quest);
            return missingFuture.thenApply(missing -> missing.isEmpty()
                    ? Availability.of(Availability.Status.AVAILABLE)
                    : new Availability(Availability.Status.MISSING_PREREQUISITES, missing));
        });
    }

    /** Disponibilité d'une quête pour un joueur, et la raison précise quand elle ne l'est pas. */
    public record Availability(Status status, List<NamespacedKey> missingPrerequisites) {

        public enum Status { AVAILABLE, UNKNOWN, ALREADY_ACTIVE, NOT_REPEATABLE, MISSING_PREREQUISITES }

        public Availability {
            missingPrerequisites = List.copyOf(missingPrerequisites == null ? List.of() : missingPrerequisites);
        }

        static Availability of(Status status) {
            return new Availability(status, List.of());
        }

        public boolean available() {
            return status == Status.AVAILABLE;
        }
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

    /**
     * Outil d'administration pour les tests ({@code /quest admin reset}) : supprime totalement
     * l'état persisté et les compteurs d'objectifs de {@code questId} pour {@code playerId}, et
     * retire toute progression en mémoire. La ligne disparaît plutôt que d'être remise à
     * {@code NOT_STARTED} : {@link #accept} la traite ensuite comme jamais commencée, y compris
     * pour une quête {@code repeatable: false} déjà {@code COMPLETED} (le blocage de
     * {@link #accept} ne porte que sur une ligne {@code COMPLETED} existante). Ne touche ni à
     * l'inventaire, ni à l'économie, ni aux autres quêtes du joueur.
     */
    public CompletableFuture<Void> resetQuest(UUID playerId, NamespacedKey questId) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive != null) {
            playerActive.remove(questId);
        }
        CompletableFuture<Void> future = repository.deleteQuest(playerId, questId);
        future.whenComplete((v, error) -> notifyChanged(playerId));
        return future;
    }

    /** Équivalent de {@link #resetQuest} pour toutes les quêtes de {@code playerId} en une fois. */
    public CompletableFuture<Void> resetAllQuests(UUID playerId) {
        activeByPlayer.remove(playerId);
        CompletableFuture<Void> future = repository.deleteAllForPlayer(playerId);
        future.whenComplete((v, error) -> notifyChanged(playerId));
        return future;
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
                showObjectiveProgress(player, step.objectives().get(i), required, required);
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

    // ---- Remise d'objets à un PNJ (issue #123) ---------------------------

    /**
     * État de remise, en lecture seule, des objectifs {@code DELIVER_ITEM_TO_NPC} que {@code npcId}
     * attend <strong>réellement</strong> de ce joueur ici et maintenant : quête {@code ACTIVE},
     * étape courante. Liste vide = ce PNJ n'attend rien (mauvais PNJ, quête non acceptée, ou étape
     * différente) — c'est ce que le dialogue consulte pour n'afficher l'option de remise que
     * lorsqu'elle a un sens.
     *
     * <p>Ne modifie rien, ne touche pas à l'inventaire, ne journalise pas : appelable à chaque
     * ouverture de nœud de dialogue sans effet de bord.</p>
     */
    public List<DeliveryLine> pendingDeliveries(UUID playerId, String npcId) {
        List<DeliveryLine> lines = new ArrayList<>();
        for (ObjectiveRef ref : activeDeliveryRefs(playerId, npcId)) {
            DeliverItemToNpcObjective objective = (DeliverItemToNpcObjective) ref.objective();
            int delivered = counterOf(playerId, ref);
            lines.add(new DeliveryLine(objective.material(), delivered, objective.amount()));
        }
        return lines;
    }

    /** {@code true} s'il reste au moins un objet à remettre à ce PNJ — raccourci pour les conditions de dialogue. */
    public boolean hasPendingDelivery(UUID playerId, String npcId) {
        return pendingDeliveries(playerId, npcId).stream().anyMatch(line -> !line.complete());
    }

    /**
     * Remet en <strong>une seule opération</strong> tout ce que le joueur possède d'utile pour les
     * objectifs de remise de {@code npcId} (issue #123) : plusieurs matériaux, plusieurs objectifs
     * et plusieurs quêtes à la fois, sans obliger le joueur à cliquer une fois par matériau.
     *
     * <p>Ordre des opérations, volontairement rigide :</p>
     * <ol>
     *   <li>jeton anti-concurrence par joueur ({@link #deliveriesInFlight}) — un double-clic, un
     *       spam ou un appel réentrant est refusé avec {@link DeliveryOutcome.Status#BUSY} sans rien
     *       retirer ;</li>
     *   <li>pour chaque objectif, le reliquat est calculé depuis le compteur <em>en mémoire</em>
     *       (déjà à jour, synchrone) ;</li>
     *   <li>{@link QuestItemWithdrawal#withdraw} retire au plus ce reliquat et renvoie ce qu'il a
     *       <strong>réellement</strong> retiré ;</li>
     *   <li>le compteur n'avance que de cette quantité, puis est persisté. Il est donc impossible de
     *       progresser sans retrait, ou de retirer plus que nécessaire ;</li>
     *   <li>la complétion d'étape n'est évaluée qu'une fois toute la remise appliquée — une étape à
     *       quatre matériaux ne se termine pas au milieu de l'opération.</li>
     * </ol>
     *
     * <p>Les objets remis ne sont jamais restitués : ils sont « en sécurité auprès du PNJ ». La
     * progression est persistée immédiatement, donc acquise après une mort, une déconnexion ou un
     * redémarrage.</p>
     */
    public DeliveryOutcome deliverTo(Player player, String npcId) {
        UUID playerId = player.getUniqueId();
        List<ObjectiveRef> refs = activeDeliveryRefs(playerId, npcId);
        if (refs.isEmpty()) {
            player.sendMessage(messagesService.current().format("quest.delivery-none"));
            return DeliveryOutcome.of(DeliveryOutcome.Status.NO_OBJECTIVE, List.of(), false);
        }
        if (!deliveriesInFlight.add(playerId)) {
            // Déjà en cours pour ce joueur : ne rien retirer, ne rien progresser, ne rien dire de
            // plus (la remise en cours parlera elle-même) — jamais un second retrait.
            return DeliveryOutcome.of(DeliveryOutcome.Status.BUSY, pendingDeliveries(playerId, npcId), false);
        }
        try {
            return applyDelivery(player, refs);
        } finally {
            deliveriesInFlight.remove(playerId);
        }
    }

    private DeliveryOutcome applyDelivery(Player player, List<ObjectiveRef> refs) {
        UUID playerId = player.getUniqueId();
        List<DeliveryLine> lines = new ArrayList<>();
        Map<NamespacedKey, ActiveQuestProgress> touchedQuests = new LinkedHashMap<>();
        int totalTaken = 0;

        for (ObjectiveRef ref : refs) {
            DeliverItemToNpcObjective objective = (DeliverItemToNpcObjective) ref.objective();
            ActiveQuestProgress progress = activeProgress(playerId, ref.questId());
            if (progress == null) {
                continue; // la quête a cessé d'être active entre-temps : ne jamais retirer pour rien.
            }
            int delivered = progress.counter(ref.objectiveIndex());
            int remaining = objective.amount() - delivered;
            int taken = QuestItemWithdrawal.withdraw(player.getInventory(), objective.material(), remaining);
            if (taken > 0) {
                int updated = delivered + taken;
                progress.setCounter(ref.objectiveIndex(), updated);
                persistCounter(playerId, ref, updated);
                touchedQuests.put(ref.questId(), progress);
                totalTaken += taken;
                lines.add(new DeliveryLine(objective.material(), updated, objective.amount(), taken));
            } else {
                lines.add(new DeliveryLine(objective.material(), delivered, objective.amount(), 0));
            }
        }

        boolean allComplete = lines.stream().allMatch(DeliveryLine::complete);
        if (totalTaken == 0) {
            DeliveryOutcome.Status status = allComplete
                    ? DeliveryOutcome.Status.ALREADY_COMPLETE
                    : DeliveryOutcome.Status.NOTHING_USEFUL;
            player.sendMessage(messagesService.current().format(allComplete
                    ? "quest.delivery-already-complete"
                    : "quest.delivery-nothing-useful"));
            return DeliveryOutcome.of(status, lines, allComplete);
        }

        DeliveryOutcome outcome = DeliveryOutcome.of(DeliveryOutcome.Status.DELIVERED, lines, allComplete);
        sendDeliveryFeedback(player, outcome);

        // Après TOUTE la remise seulement : une étape à plusieurs matériaux ne doit pas se terminer
        // au milieu de l'opération, et une quête terminée ici sort de activeByPlayer.
        touchedQuests.forEach((questId, progress) ->
                questEngine.find(questId).ifPresent(quest -> checkStepCompletion(player, quest, progress)));
        notifyChanged(playerId);
        return outcome;
    }

    /**
     * Récapitulatif dans le CHAT (jamais l'ActionBar) : une remise groupée touche plusieurs
     * objectifs d'un coup, et autant de messages d'ActionBar s'écraseraient l'un l'autre — le
     * joueur ne verrait que le dernier. Les noms d'objets utilisent leur clé de traduction vanilla,
     * donc s'affichent dans la langue du client.
     */
    private void sendDeliveryFeedback(Player player, DeliveryOutcome outcome) {
        QuestMessages messages = messagesService.current();
        player.sendMessage(messages.format("quest.delivery-delivered",
                Placeholder.component("items", joinItems(outcome.justDelivered(), DeliveryLine::justNow))));
        List<DeliveryLine> missing = outcome.stillMissing();
        if (missing.isEmpty()) {
            player.sendMessage(messages.format("quest.delivery-all-done"));
        } else {
            player.sendMessage(messages.format("quest.delivery-remaining",
                    Placeholder.component("missing", joinItems(missing, DeliveryLine::remaining))));
        }
    }

    /** « 2 × Cuir, 1 × Pierre » — quantité puis nom traduit côté client, séparés par des virgules. */
    private static Component joinItems(List<DeliveryLine> lines, java.util.function.ToIntFunction<DeliveryLine> count) {
        Component joined = Component.empty();
        boolean first = true;
        for (DeliveryLine line : lines) {
            if (!first) {
                joined = joined.append(Component.text(", "));
            }
            first = false;
            joined = joined.append(Component.text(count.applyAsInt(line) + " × "))
                    .append(Component.translatable(line.material()));
        }
        return joined;
    }

    /**
     * Objectifs de remise que {@code npcId} attend réellement de ce joueur : quête {@code ACTIVE}
     * et <strong>étape courante</strong> uniquement. Un objectif d'une étape future ou passée n'est
     * jamais remisable, et un PNJ qui n'est destinataire de rien ne reçoit jamais rien.
     */
    private List<ObjectiveRef> activeDeliveryRefs(UUID playerId, String npcId) {
        if (npcId == null || npcId.isBlank()) {
            return List.of();
        }
        List<ObjectiveRef> out = new ArrayList<>();
        for (ObjectiveRef ref : index.deliverToNpc(npcId)) {
            ActiveQuestProgress progress = activeProgress(playerId, ref.questId());
            if (progress != null && progress.currentStepIndex() == ref.stepIndex()) {
                out.add(ref);
            }
        }
        return out;
    }

    private ActiveQuestProgress activeProgress(UUID playerId, NamespacedKey questId) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive == null) {
            return null;
        }
        ActiveQuestProgress progress = playerActive.get(questId);
        return (progress != null && progress.state() == QuestState.ACTIVE) ? progress : null;
    }

    private int counterOf(UUID playerId, ObjectiveRef ref) {
        ActiveQuestProgress progress = activeProgress(playerId, ref.questId());
        return progress == null ? 0 : progress.counter(ref.objectiveIndex());
    }

    private void persistCounter(UUID playerId, ObjectiveRef ref, int value) {
        repository.setObjectiveProgress(playerId, ref.questId(), ref.stepId(), ref.objectiveIndex(), value)
                .exceptionally(error -> {
                    logger.error("Impossible de persister la remise de {} pour {}", ref.questId(), playerId, error);
                    return null;
                });
    }

    // ---- Événements de jeu (appelés par les listeners du même package) ---

    void handleBreakBlock(Player player, Material material) {
        List<ObjectiveRef> candidates = index.breakBlock(material);
        traceBreakBlockChain(player, material, candidates);
        handleCandidates(player, candidates);
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

    /**
     * TODO(debug bug BREAK_BLOCK wild) : instrumentation temporaire, à retirer une fois la cause
     * confirmée (voir {@code docs/claude-reports/} pour l'investigation). Reconstitue, en lecture
     * seule et <strong>sans jamais muter aucun état</strong>, exactement ce que {@link
     * #handleCandidates} s'apprête à faire pour {@code BREAK_BLOCK} — pour savoir précisément où la
     * chaîne réelle s'arrête sur le serveur (index sans candidat ? quête pas active ? mauvaise
     * étape ? déjà au plafond ? incrément appliqué mais rien ne se passe après ?), sans devoir
     * deviner depuis un environnement de développement qui ne reproduit pas le bug. N'affecte jamais
     * {@code handleCandidates}, appelé séparément juste après avec les mêmes {@code candidates}.
     *
     * <p>Journalise uniquement si {@code player} a au moins une quête {@code ACTIVE} dont l'étape
     * courante contient un objectif {@code BREAK_BLOCK} (n'importe quel matériau) — jamais à chaque
     * cassage de bloc par n'importe quel joueur, pour ne jamais spammer les logs.</p>
     */
    private void traceBreakBlockChain(Player player, Material material, List<ObjectiveRef> candidates) {
        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive == null || playerActive.isEmpty()) {
            return;
        }

        List<String> activeBreakBlockQuests = new ArrayList<>();
        for (var entry : playerActive.entrySet()) {
            ActiveQuestProgress progress = entry.getValue();
            if (progress.state() != QuestState.ACTIVE) {
                continue;
            }
            questEngine.find(entry.getKey()).ifPresent(quest -> {
                QuestStep step = quest.steps().get(progress.currentStepIndex());
                boolean hasBreakBlockHere = step.objectives().stream().anyMatch(o -> o instanceof BreakBlockObjective);
                if (hasBreakBlockHere) {
                    activeBreakBlockQuests.add(entry.getKey() + ":" + step.id());
                }
            });
        }
        if (activeBreakBlockQuests.isEmpty()) {
            return; // le gate demandé : ce joueur n'a aucune quête BREAK_BLOCK active en ce moment.
        }

        List<String> candidateDescriptions = candidates.stream()
                .map(ref -> ref.questId() + ":" + ref.stepId() + ":obj" + ref.objectiveIndex())
                .toList();

        List<String> evaluations = new ArrayList<>();
        boolean anyProgressed = false;
        for (ObjectiveRef ref : candidates) {
            String label = ref.questId() + ":" + ref.stepId() + ":obj" + ref.objectiveIndex();
            ActiveQuestProgress progress = playerActive.get(ref.questId());
            if (progress == null) {
                evaluations.add(label + "=SKIP(quest_not_in_active_cache)");
                continue;
            }
            if (progress.state() != QuestState.ACTIVE) {
                evaluations.add(label + "=SKIP(quest_state_" + progress.state() + ")");
                continue;
            }
            if (progress.currentStepIndex() != ref.stepIndex()) {
                evaluations.add(label + "=SKIP(step_mismatch_current_index_" + progress.currentStepIndex() + ")");
                continue;
            }
            int required = requiredAmount(ref.objective());
            int before = progress.counter(ref.objectiveIndex());
            if (before >= required) {
                evaluations.add(label + "=SKIP(already_at_cap_" + before + "/" + required + ")");
                continue;
            }
            evaluations.add(label + "=WILL_INCREMENT(" + before + "/" + required + "->" + (before + 1) + "/" + required + ")");
            anyProgressed = true;
        }

        String outcome = candidates.isEmpty() ? "no_candidates_from_index"
                : anyProgressed ? "progressed" : "no_active_match";
        QuestTraceLogger.logBreakBlock(logger, player.getName(), playerId,
                player.getWorld() != null ? player.getWorld().getName() : "?", material.name(),
                activeBreakBlockQuests, candidateDescriptions, evaluations, outcome);
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
            showObjectiveProgress(player, ref.objective(), updated, required);

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
            // Pas de notification dédiée ici : le passage à l'étape suivante suit toujours la
            // complétion du dernier objectif de l'étape précédente, déjà signalée à l'instant par
            // showObjectiveProgress (ActionBar) — un second message ferait doublon.
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

        // Montants monétaires de CETTE quête, dans leur ordre de déclaration. Chacun devient une
        // dette distincte : plusieurs récompenses MONEY sur une même complétion doivent toutes
        // être payées, et non pas la seule première (défaut mesuré du premier lot).
        List<Long> owed = new ArrayList<>();
        for (QuestReward reward : quest.rewards()) {
            if (reward instanceof MoneyReward money) {
                owed.add((long) money.amount());
            }
        }

        // Complétion ET dettes dans la MÊME transaction (voir
        // QuestProgressRepository#completeQuestWithMoneyDebts) : sans cela, un arrêt brutal entre
        // les deux perd soit la récompense, soit la garantie de ne pas la payer deux fois.
        repository.completeQuestWithMoneyDebts(playerId, quest.id(), progress.rewardGrantId(), owed)
                .thenAccept(grantIds -> payRecordedMoneyRewards(playerId, quest, grantIds))
                .exceptionally(error -> {
                    logger.error("Impossible de persister la fin de quête {} pour {}", quest.id(), playerId, error);
                    // La complétion n'est pas persistée : surtout ne rien annoncer comme payé. Le
                    // joueur est averti, et sa quête reste à reprendre au prochain chargement.
                    runOnMainThread(() -> {
                        Player online = plugin.getServer().getPlayer(playerId);
                        if (online != null && !owed.isEmpty()) {
                            online.sendMessage(messagesService.current().format("quest.reward-money-failed"));
                        }
                    });
                    return null;
                });

        List<Component> rewardLines = grantRewards(player, quest);
        showQuestCompleted(player, quest, rewardLines);
        notifyChanged(playerId);
    }

    /**
     * Paie les dettes tout juste enregistrées pour cette complétion. Appelé une fois, après que la
     * complétion et les dettes sont <strong>durablement</strong> en base : si le serveur s'arrête
     * ici, les dettes survivent et la reprise les retrouvera.
     */
    private void payRecordedMoneyRewards(UUID playerId, QuestDefinition quest, List<String> grantIds) {
        for (String grantId : grantIds) {
            payMoneyReward(playerId, quest.title().base(), grantId, true);
        }
    }

    // ---- Notifications sobres (Title/ActionBar Adventure, jamais le chat) ----------------------
    //
    // Trois événements, trois canaux distincts, choisis pour ne jamais spammer le chat (voir
    // messages.yml pour le contenu, jamais une quête codée en dur ici) :
    //  - démarrage/fin de quête (rares, méritent l'attention) : Title/Subtitle plein écran, bref ;
    //  - progression d'un objectif (fréquente, peut arriver plusieurs fois par seconde en combat) :
    //    ActionBar, qui se remplace en place plutôt que d'empiler des lignes.

    private void showQuestStarted(Player player, QuestDefinition quest) {
        if (!player.isOnline()) {
            return;
        }
        Component title = messagesService.current().format("quest.started-title");
        Component subtitle = messagesService.current().format(
                "quest.started-subtitle", Placeholder.parsed("quest", quest.title().base()));
        player.showTitle(Title.title(title, subtitle, FEEDBACK_TITLE_TIMES));
    }

    /**
     * Title/Subtitle bref (voir {@link #showQuestStarted}) pour l'annonce, puis un résumé dans le
     * chat des récompenses réellement accordées par {@code quest} — {@code rewardLines} vient de
     * {@link #grantRewards}, jamais d'une liste de récompenses fictives ou seulement prévues (celles-là
     * restent dans le journal, {@code QuestJournalService}). Rien n'est envoyé dans le chat si la
     * quête ne donne aucune récompense.
     */
    private void showQuestCompleted(Player player, QuestDefinition quest, List<Component> rewardLines) {
        if (!player.isOnline()) {
            return;
        }
        Component title = messagesService.current().format("quest.completed-title");
        Component subtitle = messagesService.current().format(
                "quest.completed-subtitle", Placeholder.parsed("quest", quest.title().base()));
        player.showTitle(Title.title(title, subtitle, FEEDBACK_TITLE_TIMES));

        if (rewardLines.isEmpty()) {
            return;
        }
        player.sendMessage(messagesService.current().format(
                "quest.reward-summary-header", Placeholder.parsed("quest", quest.title().base())));
        rewardLines.forEach(player::sendMessage);
    }

    private void showObjectiveProgress(Player player, QuestObjective objective, int current, int total) {
        if (!player.isOnline()) {
            return;
        }
        Component message = messagesService.current().format("quest.objective-progress",
                Placeholder.unparsed("objective", describeObjective(objective)),
                Placeholder.unparsed("current", String.valueOf(current)),
                Placeholder.unparsed("total", String.valueOf(total)));
        player.sendActionBar(message);
    }

    /**
     * Applique chaque récompense de {@code quest} et retourne, dans le même ordre, un message décrivant
     * ce qui a été réellement accordé (pour {@link #showQuestCompleted}). {@code VariableReward} n'a
     * pas de ligne : c'est un état interne du plugin (ex. un drapeau consulté par une condition de
     * dialogue), jamais quelque chose que le joueur reçoit visiblement.
     *
     * <p>{@code MoneyReward} n'a pas de ligne ici non plus, et pour une raison différente et
     * importante (issue #16) : son crédit est <strong>asynchrone</strong>. Une ligne construite
     * maintenant annoncerait un gain avant d'avoir la moindre preuve qu'il a eu lieu. Le message
     * part donc plus tard, depuis {@link #payMoneyReward}, et seulement si la base a confirmé.</p>
     */
    private List<Component> grantRewards(Player player, QuestDefinition quest) {
        List<Component> lines = new ArrayList<>();
        for (QuestReward reward : quest.rewards()) {
            switch (reward) {
                case ExperienceReward r -> {
                    player.giveExp(r.amount());
                    lines.add(messagesService.current().format("quest.reward-line-experience",
                            Placeholder.unparsed("amount", String.valueOf(r.amount()))));
                }
                case ItemReward r -> {
                    ItemStack stack = new ItemStack(r.material(), r.amount());
                    player.getInventory().addItem(stack).values()
                            .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                    lines.add(messagesService.current().format("quest.reward-line-item",
                            Placeholder.unparsed("amount", String.valueOf(r.amount())),
                            Placeholder.unparsed("item", r.material().toString())));
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
                    lines.add(messagesService.current().format("quest.reward-line-special"));
                }
                // MoneyReward : rien ici. Son crédit est asynchrone ET passe par une dette
                // durable enregistrée avec la complétion — voir payRecordedMoneyRewards.
                case MoneyReward r -> { }
            }
        }
        return lines;
    }

    /**
     * Paie une dette monétaire puis — <strong>seulement si la base l'a confirmé</strong> — annonce
     * le gain et le nouveau solde (issue #16).
     *
     * <p>Quatre propriétés tenues ici, chacune pour une raison concrète :</p>
     * <ul>
     *   <li><strong>jamais deux fois</strong> : {@code grantId} est l'identité de paiement
     *       enregistrée en base à la complétion. Une reprise réutilise la MÊME, donc le passage
     *       conditionnel {@code PENDING → PAID} ne peut l'emporter qu'une fois ;</li>
     *   <li><strong>jamais annoncé sans crédit</strong> : un échec produit un message explicite,
     *       pas un silence et pas un faux succès, et la dette reste payable ;</li>
     *   <li><strong>jamais de répétition infinie de messages</strong> : une reprise réussie parle
     *       une fois ; une reprise qui échoue ne parle <strong>pas</strong> au joueur
     *       ({@code announceFailure} est faux), elle incrémente le compteur de tentatives et laisse
     *       la main au panel. Sans cela, un joueur dont la base est en panne recevrait le même
     *       message d'échec à chaque reconnexion ;</li>
     *   <li><strong>le chat, jamais l'ActionBar</strong> : la progression des objectifs occupe
     *       l'ActionBar et se remplace en place.</li>
     * </ul>
     */
    private void payMoneyReward(UUID playerId, String questTitle, String grantId, boolean announceFailure) {
        rewardPayer.payQuestReward(playerId, grantId)
                .exceptionally(error -> {
                    logger.error("Échec du paiement de la récompense monétaire {} pour {}", grantId, playerId, error);
                    rewardPayer.recordQuestRewardFailure(grantId, error.getClass().getSimpleName()
                            + ": " + error.getMessage());
                    return QuestRewardReceipt.failed();
                })
                .thenAccept(receipt -> runOnMainThread(() -> {
                    if (receipt.status() == QuestRewardReceipt.Status.FAILED && announceFailure) {
                        rewardPayer.recordQuestRewardFailure(grantId, "paiement refusé ou dette introuvable");
                    }
                    Player online = plugin.getServer().getPlayer(playerId);
                    if (online == null) {
                        // Déconnecté entre la remise et la confirmation : l'argent est en base (le
                        // portefeuille est persistant), seul le message est perdu. Il reverra son
                        // solde dans le journal ou avec /money.
                        return;
                    }
                    switch (receipt.status()) {
                        case CREDITED -> online.sendMessage(messagesService.current().format("quest.reward-money-credited",
                                Placeholder.parsed("quest", questTitle),
                                Placeholder.unparsed("amount", String.valueOf(receipt.amount())),
                                Placeholder.unparsed("balance", String.valueOf(receipt.balanceAfter()))));
                        case ALREADY_CREDITED -> logger.warn(
                                "Récompense monétaire {} déjà réglée pour {} (complétion n°{}) : aucun second "
                                        + "crédit, aucun message au joueur.", grantId, playerId, receipt.occurrence());
                        case FAILED -> {
                            if (announceFailure) {
                                online.sendMessage(messagesService.current().format("quest.reward-money-failed"));
                            }
                        }
                    }
                    // Un solde affiché doit suivre la transaction : le journal ouvert se recompose.
                    notifyChanged(playerId);
                }));
    }

    /**
     * Reprend les récompenses monétaires restées dues pour ce joueur (issue #16, second lot).
     *
     * <p>Appelée <strong>une fois par chargement de joueur</strong>, jamais dans une boucle par
     * tick : c'est le seul moment où une dette oubliée a une chance d'être payée sans intervention.
     * La lecture est <strong>bornée</strong> ({@link #RECOVERY_BATCH}) et les dettes ayant déjà
     * échoué {@link #RECOVERY_MAX_ATTEMPTS} fois sont <strong>laissées au panel</strong> plutôt que
     * réessayées indéfiniment — si le paiement échoue encore et encore, la cause est ailleurs et
     * s'acharner ne ferait que remplir les logs.</p>
     *
     * <p>Une reprise ne rejoue <strong>que</strong> le crédit monétaire : ni l'XP, ni les objets,
     * ni les variables, ni les commandes, ni la complétion de quête elle-même. Elle ne touche
     * qu'aux lignes de dette déjà écrites.</p>
     */
    private void recoverPendingMoneyRewards(UUID playerId) {
        rewardPayer.pendingQuestRewards(playerId, RECOVERY_BATCH).thenAccept(dues -> {
            for (QuestRewardDue due : dues) {
                if (due.attempts() >= RECOVERY_MAX_ATTEMPTS) {
                    logger.warn("Récompense monétaire {} laissée en attente pour {} : {} tentatives "
                                    + "déjà échouées ({}). Reprise manuelle depuis PlugAdmin.",
                            due.grantId(), playerId, due.attempts(), due.lastError());
                    continue;
                }
                String title = questEngine.find(NamespacedKey.fromString(due.questId()))
                        .map(quest -> quest.title().base())
                        // La quête a pu être supprimée depuis : la dette reste due, et son montant
                        // est celui figé à la complétion. On ne devine aucun titre.
                        .orElse(due.questId());
                payMoneyReward(playerId, title, due.grantId(), false);
            }
        }).exceptionally(error -> {
            logger.error("Impossible de relire les récompenses monétaires dues de {}", playerId, error);
            return null;
        });
    }

    private int requiredAmount(QuestObjective objective) {
        return QuestObjective.requiredAmount(objective);
    }

    private String describeObjective(QuestObjective objective) {
        return QuestObjective.describe(objective);
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
