package com.lodygames.rpgquest.quest.progress;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.database.QuestProgressRecord;
import com.lodygames.rpgquest.database.QuestProgressRepository;
import com.lodygames.rpgquest.economy.QuestRewardDue;
import com.lodygames.rpgquest.economy.QuestRewardPayer;
import com.lodygames.rpgquest.economy.QuestRewardReceipt;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.delivery.CustomItemGiveCommands;
import com.lodygames.rpgquest.item.delivery.ItemDelivery;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.quest.QuestLoadReport;
import com.lodygames.rpgquest.quest.QuestMessages;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.DiscoverWaypointObjective;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.HeartReward;
import com.lodygames.rpgquest.quest.model.TableReward;
import com.lodygames.rpgquest.entitlement.reward.RewardEntitlement;
import com.lodygames.rpgquest.entitlement.reward.RewardSource;
import com.lodygames.rpgquest.entitlement.reward.RewardTable;
import com.lodygames.rpgquest.entitlement.reward.RewardTableDraw;
import com.lodygames.rpgquest.entitlement.reward.RewardTables;
import com.lodygames.rpgquest.quest.model.MoneyReward;
import com.lodygames.rpgquest.health.HeartBonusService;
import com.lodygames.rpgquest.quest.model.ObjectiveType;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.ui.ObjectiveLabels;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.VariableReward;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntBiFunction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
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
    private final List<Consumer<UUID>> loadListeners = new CopyOnWriteArrayList<>();
    private final List<QuestCompletionListener> completionListeners = new CopyOnWriteArrayList<>();
    /** Jeton anti double-clic / spam / appel concurrent d'une remise d'objets (issue #123), par joueur. */
    private final java.util.Set<UUID> deliveriesInFlight = ConcurrentHashMap.newKeySet();
    private volatile QuestObjectiveIndex index = new QuestObjectiveIndex(List.of());
    /** Issue #636 — dialogue PNJ au premier plan : aucun grand titre de quête par-dessus. */
    private final DialogueFocus dialogueFocus = new DialogueFocus(System::currentTimeMillis);
    /**
     * Issue #185 — nombre de waypoints déjà découverts par un joueur, filtré par monde. Simple
     * couture branchée au bootstrap sur {@code waypoint.WaypointService#discoveredCount} : le moteur
     * de quêtes ne dépend jamais du système de waypoints, et réciproquement. Non branchée (tests
     * d'autres objectifs), le mode cumulatif ne compte que les nouvelles découvertes et le dit une
     * fois dans les logs, au lieu de prétendre silencieusement un total de zéro.
     */
    private volatile ToIntBiFunction<UUID, List<String>> discoveredWaypointCounter;
    private final AtomicBoolean missingCounterWarned = new AtomicBoolean();
    /**
     * Issue #178 — nombre de villes existantes déjà découvertes par un joueur ({@code cityId} non
     * nul : 0 ou 1 pour cette ville). Couture branchée au bootstrap sur
     * {@code city.CityDiscoveryService#discoveredCount}, même discipline que pour les waypoints.
     */
    private volatile ToIntBiFunction<UUID, String> discoveredCityCounter;
    /**
     * Issue #70 — nom affiché (texte brut) d'un PNJ logique, pour annoncer « Parler à Garde » plutôt
     * que « Parler au PNJ ». Couture branchée au bootstrap sur les définitions de PNJ ; non branchée
     * (tests), le libellé générique est conservé.
     */
    private volatile Function<String, Optional<String>> npcNames = id -> Optional.empty();

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

    private com.lodygames.rpgquest.feedback.ActionBarFeedback actionBar;
    /** Issue #817 : file des grands titres ; {@code null} (tests) = titres affichés directement. */
    private com.lodygames.rpgquest.feedback.@Nullable QuestTitleQueue titleQueue;
    private @Nullable HeartBonusService heartBonusService;
    /** Tables de récompenses aléatoires (issue #177) ; {@code null} = tirages indisponibles. */
    private @Nullable RewardTables rewardTables;
    /** Issue #183 : XP RPG ou vanilla pour les récompenses {@code EXPERIENCE}. */
    private volatile ExperienceRewardSink experienceRewards = ExperienceRewardSink.VANILLA;

    /**
     * Issue #318 : registre des objets custom, pour connaître l'objet remis par une récompense
     * {@code COMMAND} {@code customitem give} et vérifier la place AVANT la complétion. Absent
     * (tests) = seules les récompenses {@code ITEM} sont vérifiées.
     */
    private volatile YamlCustomItemRegistry customItemRegistry;

    public void setNpcNameResolver(Function<String, Optional<String>> npcNames) {
        this.npcNames = npcNames;
    }

    /**
     * Issue #475 — même résolution de nom affiché de PNJ que l'annonce des objectifs (#70), pour que
     * le journal nomme le destinataire d'une remise (« à remettre à Guide ») sans seconde règle.
     */
    public Function<String, Optional<String>> npcNameResolver() {
        return npcNames;
    }

    public void setCustomItemRegistry(YamlCustomItemRegistry customItemRegistry) {
        this.customItemRegistry = customItemRegistry;
    }

    /** Issues #69 / #209 — canal partagé de l'action bar, branché au démarrage. */
    /** Issue #166 : applique les cœurs gagnés ; absent (tests), les droits restent écrits en base. */
    /** Issue #177 : branché après la construction (le catalogue de récompenses naît plus tard). */
    public void setRewardTables(RewardTables rewardTables) {
        this.rewardTables = rewardTables;
    }

    public void setExperienceRewardSink(ExperienceRewardSink experienceRewards) {
        this.experienceRewards = experienceRewards == null ? ExperienceRewardSink.VANILLA : experienceRewards;
    }

    public void setHeartBonusService(HeartBonusService heartBonusService) {
        this.heartBonusService = heartBonusService;
    }

    public void setActionBar(com.lodygames.rpgquest.feedback.ActionBarFeedback actionBar) {
        this.actionBar = actionBar;
    }

    /** Issue #185 — branché une seule fois au démarrage (voir {@code bootstrap.RPGQuestBootstrap}). */
    public void setTitleQueue(com.lodygames.rpgquest.feedback.QuestTitleQueue titleQueue) {
        this.titleQueue = titleQueue;
    }

    public void setDiscoveredWaypointCounter(ToIntBiFunction<UUID, List<String>> counter) {
        this.discoveredWaypointCounter = counter;
    }

    /** Issue #178 — branché une seule fois au démarrage. */
    public void setDiscoveredCityCounter(ToIntBiFunction<UUID, String> counter) {
        this.discoveredCityCounter = counter;
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

    /**
     * Issue #734 — observateur d'une quête terminée (statistiques). Appelé sur le fil principal au
     * moment de la remise ; l'action qu'il rend ne s'exécute qu'une fois la complétion écrite en base.
     */
    @FunctionalInterface
    public interface QuestCompletionListener {
        @Nullable Runnable completing(Player player, NamespacedKey questId, String completionToken);
    }

    public void onQuestCompleted(QuestCompletionListener listener) {
        completionListeners.add(listener);
    }

    /** Issue #171 — notifié (thread quelconque) quand la progression d'un joueur vient d'être chargée. */
    public void onProgressLoaded(Consumer<UUID> listener) {
        loadListeners.add(listener);
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

    /**
     * Issue #242 : les définitions de quêtes ont été rechargées AILLEURS (publication de contenu
     * depuis le Control Panel, {@code ContentReloadService}). Sans cette reconstruction, l'index et
     * les écouteurs restaient ceux d'avant : une quête publiée à chaud dont le type d'objectif
     * n'était utilisé par aucune autre (le premier SMELT_ITEM, par exemple) n'avait AUCUN écouteur
     * jusqu'au prochain redémarrage — « 0/1 » au retrait du four.
     */
    public void afterExternalReload() {
        if (plugin.getServer().isPrimaryThread()) {
            rebuildIndexAndListeners();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, this::rebuildIndexAndListeners);
        }
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
            case CRAFT_ITEM -> new QuestCraftItemListener(this, plugin);
            case TALK_TO_NPC -> new QuestNpcInteractListener(this, npcIdentityService);
            case REACH_LOCATION -> new QuestLocationListener(this);
            // Issue #123 : DELIVER_ITEM_TO_NPC n'écoute AUCUN événement de jeu, volontairement.
            // Posséder, ramasser, fabriquer ou porter l'objet ne doit jamais faire progresser une
            // remise : seul l'appel explicite de deliverTo(...), déclenché par une action de
            // dialogue sur le bon PNJ, le fait. Un écouteur ici rouvrirait exactement la
            // sémantique de COLLECT_ITEM que ce type d'objectif existe pour éviter.
            case DELIVER_ITEM_TO_NPC -> null;
            case SMELT_ITEM -> new QuestSmeltListener(this);
            // Issue #185 : DISCOVER_WAYPOINT n'écoute aucun événement de jeu. Un écouteur de
            // déplacement ou d'interaction recréerait une seconde règle de découverte à côté de
            // celle du système de waypoints, avec la quasi-certitude de diverger (passage à
            // proximité, téléportation, reclic). La progression vient exclusivement de l'abonnement
            // aux PREMIÈRES découvertes réelles, via setDiscoveredWaypointCounter/handleWaypointDiscovered.
            case DISCOVER_WAYPOINT -> null;
            // Issue #178 : même principe — la progression vient exclusivement des PREMIÈRES
            // découvertes enregistrées par city.CityDiscoveryService (handleCityDiscovered).
            case DISCOVER_CITY -> null;
            // Issue #171 : aucun écouteur de mort générique — seul mob.quest.QuestBossService, qui
            // connaît l'instance possédée, crédite l'objectif (creditQuestBoss).
            case KILL_QUEST_BOSS -> null;
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
                        // Issue #171 : abonnés propres au chargement (boss de quête) — jamais
                        // notifyChanged, dont les abonnés existants (XP, histoires) ne doivent pas
                        // être rejoués à chaque connexion.
                        for (Consumer<UUID> listener : loadListeners) {
                            listener.accept(playerId);
                        }
                        // Rattrapage des récompenses monétaires restées dues (crash, panne SQL).
                        // Ici et nulle part ailleurs : une seule fois par chargement, asynchrone,
                        // borné — jamais une tâche répétitive.
                        recoverPendingMoneyRewards(playerId);
                        // Issue #185 : le mode cumulatif se recalcule depuis le total réel du
                        // joueur, donc une découverte faite avant l'acceptation est prise en compte
                        // dès le chargement, sans instantané stocké.
                        Player online = plugin.getServer().getPlayer(playerId);
                        if (online != null) {
                            runOnMainThread(() -> {
                                advanceSatisfiedSteps(online);
                                refreshCumulativeDiscoveryObjectives(online);
                                // Issue #318 : une remise restée en attente de place (avant une
                                // reconnexion ou un redémarrage) est retentée dès le retour.
                                retryAwaitingTurnIns(online);
                            });
                        }
                    });
        }).exceptionally(error -> {
            logger.error("Impossible de charger la progression de quêtes pour {}", playerId, error);
            return null;
        });
    }

    /**
     * #13 (TC-306) : une quête rechargée dont l'étape courante est déjà entièrement remplie restait
     * bloquée — plus aucun événement ne la faisait progresser (compteur plein) et rien ne testait la
     * fin d'étape. Seules les étapes <strong>non finales</strong> sont avancées ici : passer à
     * l'étape suivante n'accorde rien, alors qu'une remise attend toujours une action du joueur
     * (voir {@link #handleCandidates}). Lit la carte courante sur le thread principal.
     */
    void advanceSatisfiedSteps(Player player) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(player.getUniqueId());
        if (playerActive == null || !player.isOnline()) {
            return;
        }
        for (ActiveQuestProgress progress : List.copyOf(playerActive.values())) {
            if (progress.state() != QuestState.ACTIVE) {
                continue;
            }
            questEngine.find(progress.questId()).ifPresent(quest -> {
                if (progress.currentStepIndex() + 1 < quest.steps().size()) {
                    checkStepCompletion(player, quest, progress);
                }
            });
        }
    }

    public void unloadForPlayer(UUID playerId) {
        activeByPlayer.remove(playerId);
        dialogueFocus.discard(playerId);
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
        return accept(player, questId, ignorePrerequisites, false);
    }

    /**
     * Issue #15 — démarre une quête périodique <strong>déjà attribuée</strong> au joueur pour la
     * période courante. Réservé à {@code PeriodicQuestService}, seul détenteur de l'attribution :
     * c'est l'unique chemin qui franchit le refus {@link Availability.Status#NOT_ASSIGNED}. Toutes
     * les autres règles (déjà active, prérequis) restent celles de {@link #availability}.
     */
    public CompletableFuture<AcceptOutcome> startAssignedPeriodic(Player player, NamespacedKey questId) {
        return accept(player, questId, false, true);
    }

    private CompletableFuture<AcceptOutcome> accept(Player player, NamespacedKey questId, boolean ignorePrerequisites,
                                                    boolean periodicAssigned) {
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
                availability(playerId, questId, ignorePrerequisites, periodicAssigned).thenCompose(availability -> {
                    switch (availability.status()) {
                        case NOT_ASSIGNED:
                            return CompletableFuture.completedFuture(AcceptOutcome.notAssigned());
                        case UNKNOWN:
                            return CompletableFuture.completedFuture(AcceptOutcome.unknown());
                        case ALREADY_ACTIVE:
                            return CompletableFuture.completedFuture(AcceptOutcome.alreadyActive());
                        case NOT_REPEATABLE:
                            return CompletableFuture.completedFuture(AcceptOutcome.notRepeatable());
                        case MISSING_PREREQUISITES:
                            return CompletableFuture.completedFuture(
                                    AcceptOutcome.missingPrerequisites(availability.missingPrerequisites()));
                        case ON_COOLDOWN:
                            return CompletableFuture.completedFuture(
                                    AcceptOutcome.onCooldown(availability.retryAfter()));
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
                    // startQuest efface aussi les compteurs d'une exécution précédente (quête
                    // répétable ou périodique, abandon) : sinon un redémarrage les rechargerait.
                    return repository.startQuest(playerId, questId, firstStepId)
                            .thenApply(v -> AcceptOutcome.accepted());
                });
        future.whenComplete((outcome, error) -> {
            notifyChanged(playerId);
            if (error == null && outcome.result() == AcceptOutcome.Result.ACCEPTED) {
                runOnMainThread(() -> {
                    // Issue #15 : une attribution périodique automatique n'a ni titre « Quête
                    // commencée » ni détail d'objectifs — PeriodicQuestService annonce une seule
                    // ligne par catégorie renouvelée.
                    if (!periodicAssigned) {
                        showQuestStarted(player, quest);
                    }
                    refreshCumulativeDiscoveryObjectives(player);
                });
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
        return availability(playerId, questId, ignorePrerequisites, false);
    }

    private CompletableFuture<Availability> availability(UUID playerId, NamespacedKey questId,
                                                         boolean ignorePrerequisites, boolean periodicAssigned) {
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            return CompletableFuture.completedFuture(Availability.of(Availability.Status.UNKNOWN));
        }
        QuestDefinition quest = questOpt.get();
        // Issue #15 : un modèle périodique n'est jamais disponible par un PNJ, un catalogue, une
        // commande ou un raccourci admin — seulement via startAssignedPeriodic.
        if (quest.isPeriodic() && !periodicAssigned) {
            return CompletableFuture.completedFuture(Availability.of(Availability.Status.NOT_ASSIGNED));
        }

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
            // Issue #13 : anti-farm des quêtes renouvelables. La date de complétion est l'updated_at
            // de la ligne COMPLETED — déjà persistée, aucune colonne ni migration en plus. Un abandon
            // (ABANDONED) ne déclenche pas de délai : il ne rapporte rien.
            if (currentState == QuestState.COMPLETED) {
                Duration remaining = remainingCooldown(quest, existing.get().updatedAt(), Instant.now());
                if (!remaining.isZero()) {
                    return CompletableFuture.completedFuture(Availability.onCooldown(remaining));
                }
            }
            CompletableFuture<List<NamespacedKey>> missingFuture = ignorePrerequisites
                    ? CompletableFuture.completedFuture(List.of())
                    : checkPrerequisites(playerId, quest);
            return missingFuture.thenApply(missing -> missing.isEmpty()
                    ? Availability.of(Availability.Status.AVAILABLE)
                    : new Availability(Availability.Status.MISSING_PREREQUISITES, missing));
        });
    }

    /**
     * Temps restant avant de pouvoir reprendre une quête terminée, {@link Duration#ZERO} si elle
     * peut l'être tout de suite (pas de délai, date inconnue, ou délai écoulé).
     */
    static Duration remainingCooldown(QuestDefinition quest, Instant completedAt, Instant now) {
        if (quest.cooldown().isZero() || completedAt == null) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(now, completedAt.plus(quest.cooldown()));
        return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
    }

    /** Disponibilité d'une quête pour un joueur, et la raison précise quand elle ne l'est pas. */
    public record Availability(Status status, List<NamespacedKey> missingPrerequisites, Duration retryAfter) {

        public enum Status { AVAILABLE, UNKNOWN, ALREADY_ACTIVE, NOT_REPEATABLE, MISSING_PREREQUISITES, ON_COOLDOWN, NOT_ASSIGNED }

        public Availability {
            missingPrerequisites = List.copyOf(missingPrerequisites == null ? List.of() : missingPrerequisites);
        }

        public Availability(Status status, List<NamespacedKey> missingPrerequisites) {
            this(status, missingPrerequisites, null);
        }

        static Availability of(Status status) {
            return new Availability(status, List.of());
        }

        static Availability onCooldown(Duration retryAfter) {
            return new Availability(Status.ON_COOLDOWN, List.of(), retryAfter);
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
        forgetActionBarCounters(playerId, questId);
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
            return CompletableFuture.completedFuture(
                    turnIn(player, quest, cached, "quest.turn-in-inventory-full") ? CompleteOutcome.COMPLETED : CompleteOutcome.INVENTORY_FULL);
        }

        // Issue #15 : une quête périodique ne se termine que depuis une progression réellement
        // active (attribuée puis démarrée). Sans cette garde, une remise forcée (dialogue
        // TURN_IN_QUEST) sur une quête expirée ou abandonnée accorderait ses récompenses hors période.
        if (quest.isPeriodic()) {
            return CompletableFuture.completedFuture(CompleteOutcome.NOT_ACTIVE);
        }
        return repository.find(playerId, questId).thenCompose(record -> {
            if (record.isPresent() && record.get().state() == QuestState.COMPLETED) {
                return CompletableFuture.completedFuture(CompleteOutcome.ALREADY_COMPLETED);
            }
            CompletableFuture<CompleteOutcome> result = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                ActiveQuestProgress progress = new ActiveQuestProgress(questId, quest.steps().size() - 1, QuestState.ACTIVE);
                playerActive.put(questId, progress);
                if (turnIn(player, quest, progress, "quest.turn-in-inventory-full")) {
                    result.complete(CompleteOutcome.COMPLETED);
                } else {
                    // Issue #318 : refus pour inventaire plein — aucune progression fictive ne reste.
                    playerActive.remove(questId, progress);
                    result.complete(CompleteOutcome.INVENTORY_FULL);
                }
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
                showObjectiveProgress(player,
                        new ObjectiveRef(questId, progress.currentStepIndex(), step.id(), i, step.objectives().get(i)),
                        required, required, false);
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

    /**
     * Issue #369 — date de la dernière complétion de chaque quête actuellement {@code COMPLETED}
     * pour ce joueur ({@code updated_at} de la ligne, comme le délai de {@link #availability}).
     * Lecture asynchrone, aucune écriture.
     */
    public CompletableFuture<Map<NamespacedKey, Instant>> completionTimes(UUID playerId) {
        return repository.findAll(playerId).thenApply(records -> {
            Map<NamespacedKey, Instant> completed = new LinkedHashMap<>();
            for (QuestProgressRecord record : records) {
                if (record.state() == QuestState.COMPLETED && record.updatedAt() != null) {
                    completed.put(record.questId(), record.updatedAt());
                }
            }
            return completed;
        });
    }

    /**
     * Issue #13 — temps restant avant de pouvoir refaire chaque quête terminée encore en recharge,
     * pour le journal du joueur. Mêmes règles que {@link #availability} ({@link #remainingCooldown}) :
     * une quête absente de la table est disponible (délai écoulé, aucun délai, ou pas terminée).
     */
    public CompletableFuture<Map<NamespacedKey, Duration>> remainingCooldowns(UUID playerId) {
        return repository.findAll(playerId).thenApply(records -> {
            Instant now = Instant.now();
            Map<NamespacedKey, Duration> remaining = new LinkedHashMap<>();
            for (QuestProgressRecord record : records) {
                if (record.state() != QuestState.COMPLETED) {
                    continue;
                }
                questEngine.find(record.questId())
                        .filter(QuestDefinition::repeatable)
                        .map(quest -> remainingCooldown(quest, record.updatedAt(), now))
                        .filter(left -> !left.isZero())
                        .ifPresent(left -> remaining.put(record.questId(), left));
            }
            return remaining;
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
        return Optional.of(stepView(step, progress::counter));
    }

    /**
     * Issue #79 — vues des étapes courantes de toutes les quêtes actives du joueur, qu'il soit
     * connecté ou non. Connecté (progression chargée) : le cache mémoire, seul à jour. Hors ligne :
     * relecture SQL asynchrone de l'étape et des compteurs persistés, résolue comme le ferait
     * {@link #loadForPlayer} à la reconnexion. Lecture seule : rien n'est mis en cache ni écrit.
     */
    public CompletableFuture<Map<NamespacedKey, QuestStepProgressView>> activeStepViews(UUID playerId) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive != null) {
            Map<NamespacedKey, QuestStepProgressView> views = new LinkedHashMap<>();
            for (NamespacedKey questId : playerActive.keySet()) {
                activeStepView(playerId, questId).ifPresent(view -> views.put(questId, view));
            }
            return CompletableFuture.completedFuture(views);
        }
        return repository.findAll(playerId).thenCompose(records -> {
            Map<NamespacedKey, QuestStepProgressView> views = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> loads = new ArrayList<>();
            for (QuestProgressRecord record : records) {
                if (record.state() != QuestState.ACTIVE && record.state() != QuestState.READY_TO_TURN_IN) {
                    continue;
                }
                Optional<QuestDefinition> questOpt = questEngine.find(record.questId());
                if (questOpt.isEmpty() || questOpt.get().steps().isEmpty()) {
                    continue;
                }
                QuestDefinition quest = questOpt.get();
                QuestStep step = quest.steps().get(Math.max(0, indexOfStep(quest, record.currentStepId())));
                loads.add(repository.findObjectiveProgress(playerId, record.questId(), step.id())
                        .thenAccept(counters -> views.put(record.questId(),
                                stepView(step, i -> counters.getOrDefault(i, 0)))));
            }
            return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> Map.copyOf(views));
        });
    }

    private QuestStepProgressView stepView(QuestStep step, IntUnaryOperator counter) {
        List<ObjectiveProgressView> objectives = new ArrayList<>();
        for (int i = 0; i < step.objectives().size(); i++) {
            QuestObjective objective = step.objectives().get(i);
            objectives.add(new ObjectiveProgressView(describeObjective(objective), counter.applyAsInt(i), requiredAmount(objective)));
        }
        return new QuestStepProgressView(step.id(), objectives);
    }

    // ---- Boss de quête (issue #171) ---------------------------------------

    /** {@code true} si la progression de ce joueur est chargée (connecté, lecture SQL terminée). */
    public boolean isLoaded(UUID playerId) {
        return activeByPlayer.containsKey(playerId);
    }

    /**
     * Objectifs {@code KILL_QUEST_BOSS} que ce joueur doit <strong>réellement</strong> remplir ici et
     * maintenant : quête {@code ACTIVE}, étape courante, objectif non encore rempli. Lecture pure ;
     * vide si la progression n'est pas chargée (l'appelant distingue ce cas avec {@link #isLoaded}).
     */
    public List<ObjectiveRef> pendingQuestBosses(UUID playerId) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive == null || playerActive.isEmpty()) {
            return List.of();
        }
        List<ObjectiveRef> out = new ArrayList<>();
        for (ObjectiveRef ref : index.questBoss()) {
            ActiveQuestProgress progress = playerActive.get(ref.questId());
            if (progress != null && progress.state() == QuestState.ACTIVE
                    && progress.currentStepIndex() == ref.stepIndex()
                    && progress.counter(ref.objectiveIndex()) < requiredAmount(ref.objective())) {
                out.add(ref);
            }
        }
        return out;
    }

    /**
     * Crédite un objectif {@code KILL_QUEST_BOSS} : le boss possédé par ce joueur pour cet objectif
     * vient de mourir dans les conditions d'attribution. Sans effet (et {@code false}) si l'objectif
     * n'est plus en cours — étape changée, quête abandonnée ou déjà rempli : jamais deux crédits.
     */
    public boolean creditQuestBoss(Player player, NamespacedKey questId, String stepId, int objectiveIndex) {
        for (ObjectiveRef ref : pendingQuestBosses(player.getUniqueId())) {
            if (ref.questId().equals(questId) && ref.stepId().equals(stepId) && ref.objectiveIndex() == objectiveIndex) {
                handleCandidates(player, List.of(ref));
                return true;
            }
        }
        return false;
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
     * Issue #475 — {@code true} si une remise à ce PNJ retirerait <strong>réellement</strong> au
     * moins un objet maintenant : un objectif encore incomplet dont le joueur porte le matériau,
     * selon la même règle d'éligibilité que le retrait ({@link QuestItemWithdrawal}). Un objectif
     * déjà rempli ne compte plus, même si le joueur en porte encore. Lecture seule, thread principal.
     */
    public boolean hasDeliverableItems(Player player, String npcId) {
        UUID playerId = player.getUniqueId();
        for (ObjectiveRef ref : activeDeliveryRefs(playerId, npcId)) {
            DeliverItemToNpcObjective objective = (DeliverItemToNpcObjective) ref.objective();
            if (counterOf(playerId, ref) < objective.amount()
                    && QuestItemWithdrawal.available(player.getInventory(), objective.material()) > 0) {
                return true;
            }
        }
        return false;
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

        sendDeliveryFeedback(player, DeliveryOutcome.of(DeliveryOutcome.Status.DELIVERED, lines, allComplete));

        // Après TOUTE la remise seulement : une étape à plusieurs matériaux ne doit pas se terminer
        // au milieu de l'opération, et une quête terminée ici sort de activeByPlayer.
        touchedQuests.forEach((questId, progress) ->
                questEngine.find(questId).ifPresent(quest -> checkStepCompletion(player, quest, progress)));
        notifyChanged(playerId);
        // Issue #475 : une quête qui n'est plus suivie du tout vient d'être rendue (récompenses
        // remises) — READY_TO_TURN_IN (inventaire plein) reste suivie, donc n'est pas annoncée finie.
        Map<NamespacedKey, ActiveQuestProgress> stillTracked = activeByPlayer.getOrDefault(playerId, Map.of());
        List<NamespacedKey> completed = touchedQuests.keySet().stream()
                .filter(questId -> !stillTracked.containsKey(questId))
                .toList();
        return new DeliveryOutcome(DeliveryOutcome.Status.DELIVERED, lines, allComplete, completed);
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
        handleCraftItem(player, material, 1);
    }

    /**
     * Issue #34 : un craft produit souvent plusieurs objets (4 planches par bûche) et un shift-clic
     * enchaîne plusieurs crafts en un seul événement. La progression avance donc du nombre réel
     * d'objets fabriqués, calculé par {@link QuestCraftItemListener}, plafonné à la quantité requise.
     */
    void handleCraftItem(Player player, Material material, int amount) {
        handleCandidates(player, index.craftItem(material), amount);
    }

    /**
     * Issue #141 : une extraction de four peut sortir <strong>plusieurs</strong> objets d'un coup
     * (shift-clic sur le slot de résultat). La progression avance donc de {@code amount} et non de
     * 1 — compter un seul objet rendrait une quête « cuire 8 objets » bien plus longue qu'annoncé.
     */
    void handleSmeltItem(Player player, Material material, int amount) {
        handleCandidates(player, index.smeltItem(material), amount);
    }

    /**
     * Issue #185 — appelé <strong>uniquement</strong> sur une première découverte réelle, jamais sur
     * un reclic ni un passage à proximité : c'est le système de waypoints qui tranche, à partir de
     * l'insertion en base. Un même waypoint ne peut donc pas compter deux fois, même après une mort,
     * une reconnexion ou un redémarrage, et aucune table supplémentaire n'est nécessaire.
     */
    public void handleWaypointDiscovered(Player player, String world) {
        List<ObjectiveRef> newDiscoveriesOnly = index.discoverWaypoint().stream()
                .filter(ref -> ref.objective() instanceof DiscoverWaypointObjective o
                        && o.countMode() == DiscoverWaypointObjective.CountMode.NEW_ONLY
                        && o.matchesWorld(world))
                .toList();
        handleCandidates(player, newDiscoveriesOnly);
        // Le mode cumulatif ne s'incrémente pas : il se recalcule depuis le total du joueur, ce qui
        // reste exact après un redémarrage sans stocker d'instantané.
        refreshCumulativeDiscoveryObjectives(player);
    }

    /**
     * Issue #178 — appelé <strong>uniquement</strong> sur une première découverte de ville réellement
     * insérée en base. Une même ville ne peut donc compter qu'une fois par joueur, quel que soit le
     * nombre de sorties/rentrées, de renommages ou de redémarrages.
     */
    public void handleCityDiscovered(Player player, String cityId) {
        List<ObjectiveRef> newDiscoveriesOnly = index.discoverCity().stream()
                .filter(ref -> ref.objective() instanceof com.lodygames.rpgquest.quest.model.DiscoverCityObjective o
                        && o.countMode() == DiscoverWaypointObjective.CountMode.NEW_ONLY
                        && o.matches(cityId))
                .toList();
        handleCandidates(player, newDiscoveriesOnly);
        refreshCumulativeDiscoveryObjectives(player);
    }

    /**
     * Issue #185, mode {@code INCLUDE_EXISTING} : aligne le compteur sur le nombre réel de waypoints
     * déjà découverts par le joueur (filtré par monde). Idempotent et sans effet de bord sur les
     * découvertes elles-mêmes — appelé à l'acceptation, au changement d'étape, au chargement du
     * joueur et à chaque nouvelle découverte.
     */
    public void refreshCumulativeDiscoveryObjectives(Player player) {
        List<ObjectiveRef> cumulative = new ArrayList<>(index.discoverWaypoint().stream()
                .filter(ref -> ref.objective() instanceof DiscoverWaypointObjective o
                        && o.countMode() == DiscoverWaypointObjective.CountMode.INCLUDE_EXISTING)
                .toList());
        // Issue #178 : les villes suivent exactement la même règle cumulative.
        index.discoverCity().stream()
                .filter(ref -> ref.objective() instanceof com.lodygames.rpgquest.quest.model.DiscoverCityObjective o
                        && o.countMode() == DiscoverWaypointObjective.CountMode.INCLUDE_EXISTING)
                .forEach(cumulative::add);
        if (cumulative.isEmpty()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive == null || playerActive.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (ObjectiveRef ref : cumulative) {
            ActiveQuestProgress progress = playerActive.get(ref.questId());
            if (progress == null || progress.state() != QuestState.ACTIVE
                    || progress.currentStepIndex() != ref.stepIndex()) {
                continue;
            }
            int required = requiredAmount(ref.objective());
            int total = ref.objective() instanceof com.lodygames.rpgquest.quest.model.DiscoverCityObjective city
                    ? countDiscoveredCities(playerId, city.city())
                    : countDiscoveredWaypoints(playerId, ((DiscoverWaypointObjective) ref.objective()).worlds());
            int updated = Math.min(required, total);
            // Jamais de régression : un waypoint désactivé après coup ne doit pas faire reculer une
            // progression déjà acquise.
            if (updated <= progress.counter(ref.objectiveIndex())) {
                continue;
            }
            applyObjectiveCounter(player, ref, progress, updated, required);
            changed = true;
        }
        if (changed) {
            notifyChanged(playerId);
        }
    }

    private int countDiscoveredCities(UUID playerId, String cityId) {
        ToIntBiFunction<UUID, String> counter = discoveredCityCounter;
        return counter == null ? 0 : counter.applyAsInt(playerId, cityId);
    }

    private int countDiscoveredWaypoints(UUID playerId, List<String> worlds) {
        ToIntBiFunction<UUID, List<String>> counter = discoveredWaypointCounter;
        if (counter == null) {
            if (missingCounterWarned.compareAndSet(false, true)) {
                logger.warn("Mode cumulatif de DISCOVER_WAYPOINT demandé sans compteur de découvertes "
                        + "branché : seules les nouvelles découvertes compteront (issue #185).");
            }
            return 0;
        }
        return counter.applyAsInt(playerId, worlds);
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
        handleCandidates(player, candidates, 1);
    }

    /**
     * {@code step} est le nombre de pas à ajouter en une fois (1 pour un événement unitaire). Le
     * compteur est toujours <strong>plafonné</strong> à la quantité requise : un lot plus grand que
     * le reliquat ne « dépasse » jamais, et l'étape ne se termine pas deux fois.
     */
    private void handleCandidates(Player player, List<ObjectiveRef> candidates, int step) {
        if (candidates.isEmpty()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive == null || playerActive.isEmpty()) {
            return;
        }

        List<ObjectiveRef> progressing = new ArrayList<>();
        List<ObjectiveRef> satisfied = new ArrayList<>();
        for (ObjectiveRef ref : candidates) {
            ActiveQuestProgress progress = playerActive.get(ref.questId());
            if (progress == null || progress.state() != QuestState.ACTIVE) {
                continue;
            }
            if (progress.currentStepIndex() != ref.stepIndex()) {
                continue;
            }
            if (progress.counter(ref.objectiveIndex()) >= requiredAmount(ref.objective())) {
                satisfied.add(ref);
                continue;
            }
            progressing.add(ref);
        }
        // Issues #178 / #69 / #209 : un même événement qui fait progresser plusieurs quêtes nomme
        // chaque quête dans l'action bar, sinon « Découvrir 2 villes 1/2 » deux fois est illisible.
        boolean named = progressing.stream().map(ObjectiveRef::questId).distinct().count() > 1;
        for (ObjectiveRef ref : progressing) {
            ActiveQuestProgress progress = playerActive.get(ref.questId());
            int required = requiredAmount(ref.objective());
            int updated = Math.min(required, progress.counter(ref.objectiveIndex()) + Math.max(1, step));
            applyObjectiveCounter(player, ref, progress, updated, required, named);
        }
        // #13 (TC-306) : l'objectif visé était déjà plein, mais son étape n'a jamais été close
        // (compteur rechargé d'une exécution précédente). L'action du joueur termine l'étape par le
        // chemin normal ; remise unique garantie par la bascule mémoire de turnIn.
        for (ObjectiveRef ref : satisfied) {
            ActiveQuestProgress progress = playerActive.get(ref.questId());
            if (progress != null && progress.state() == QuestState.ACTIVE
                    && progress.currentStepIndex() == ref.stepIndex()) {
                questEngine.find(ref.questId()).ifPresent(quest -> checkStepCompletion(player, quest, progress));
            }
        }
        notifyChanged(playerId);
    }

    /**
     * Pose la valeur <strong>absolue</strong> d'un compteur d'objectif, la persiste, affiche la
     * progression et termine l'étape si elle est complète. Partagé par la progression événementielle
     * ({@link #handleCandidates}) et par le recalcul cumulatif de l'issue #185, pour qu'il n'existe
     * jamais deux façons d'écrire un compteur.
     */
    private void applyObjectiveCounter(Player player, ObjectiveRef ref, ActiveQuestProgress progress,
                                        int updated, int required) {
        applyObjectiveCounter(player, ref, progress, updated, required, false);
    }

    private void applyObjectiveCounter(Player player, ObjectiveRef ref, ActiveQuestProgress progress,
                                        int updated, int required, boolean named) {
        UUID playerId = player.getUniqueId();
        progress.setCounter(ref.objectiveIndex(), updated);
        repository.setObjectiveProgress(playerId, ref.questId(), ref.stepId(), ref.objectiveIndex(), updated)
                .exceptionally(error -> {
                    logger.error("Impossible de persister la progression de {} pour {}", ref.questId(), playerId, error);
                    return null;
                });
        showObjectiveProgress(player, ref, updated, required, named);

        if (updated >= required) {
            questEngine.find(ref.questId()).ifPresent(quest -> checkStepCompletion(player, quest, progress));
        }
    }

    private void checkStepCompletion(Player player, QuestDefinition quest, ActiveQuestProgress progress) {
        QuestStep step = quest.steps().get(progress.currentStepIndex());
        for (int i = 0; i < step.objectives().size(); i++) {
            if (progress.counter(i) < requiredAmount(step.objectives().get(i))) {
                return;
            }
        }

        UUID playerId = player.getUniqueId();
        // Issue #817 : l'étape est close — son compteur ne doit plus revenir sur l'action bar.
        forgetActionBarCounters(playerId, quest.id());
        int nextIndex = progress.currentStepIndex() + 1;
        if (nextIndex < quest.steps().size()) {
            progress.advanceToStep(nextIndex);
            // Issue #185 : la nouvelle étape peut porter un objectif cumulatif déjà satisfait.
            runOnMainThread(() -> refreshCumulativeDiscoveryObjectives(player));
            String nextStepId = quest.steps().get(nextIndex).id();
            repository.advanceToStep(playerId, quest.id(), nextStepId).exceptionally(error -> {
                logger.error("Impossible de persister l'avancement d'étape pour {} ({})", quest.id(), playerId, error);
                return null;
            });
            // Issue #70 : l'ActionBar vient de dire « objectif rempli », pas quoi faire ensuite —
            // le joueur restait sans consigne. La nouvelle étape est donc annoncée dans le chat.
            QuestStep nextStep = quest.steps().get(nextIndex);
            runOnMainThread(() -> showStepAdvanced(player, quest, nextStep));
            notifyChanged(playerId);
        } else if (!turnIn(player, quest, progress, "quest.reward-inventory-full")) {
            awaitRoomForRewards(playerId, quest, progress);
        }
    }

    /**
     * Issue #318 : objectifs remplis mais récompenses qui ne tiennent pas dans l'inventaire. La
     * quête passe {@code READY_TO_TURN_IN} (persisté : elle survit à une reconnexion ou un
     * redémarrage), n'accorde rien, et {@link #retryAwaitingTurnIns} la termine dès qu'il y a de
     * la place. Aucune récompense n'est jetée au sol ni perdue.
     */
    private void awaitRoomForRewards(UUID playerId, QuestDefinition quest, ActiveQuestProgress progress) {
        if (progress.state() != QuestState.ACTIVE) {
            return;
        }
        progress.setState(QuestState.READY_TO_TURN_IN);
        String stepId = quest.steps().get(progress.currentStepIndex()).id();
        repository.upsertState(playerId, quest.id(), QuestState.READY_TO_TURN_IN, stepId).exceptionally(error -> {
            logger.error("Impossible de persister l'attente de remise de {} pour {}", quest.id(), playerId, error);
            return null;
        });
        notifyChanged(playerId);
    }

    /**
     * Retente, en silence, la remise des quêtes en attente de place ({@code READY_TO_TURN_IN}) du
     * joueur. Appelé après qu'il a pu libérer de la place (inventaire fermé, objet jeté) et au
     * chargement de sa progression. Coût nul s'il n'a aucune quête en attente.
     */
    public void retryAwaitingTurnIns(Player player) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(player.getUniqueId());
        if (playerActive == null || !player.isOnline()) {
            return;
        }
        for (ActiveQuestProgress progress : List.copyOf(playerActive.values())) {
            if (progress.state() == QuestState.READY_TO_TURN_IN) {
                questEngine.find(progress.questId()).ifPresent(quest -> turnIn(player, quest, progress, null));
            }
        }
    }

    void retryAwaitingTurnInsNextTick(Player player) {
        if (hasAwaitingTurnIn(player.getUniqueId())) {
            runOnMainThread(() -> retryAwaitingTurnIns(player));
        }
    }

    private boolean hasAwaitingTurnIn(UUID playerId) {
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        return playerActive != null && playerActive.values().stream()
                .anyMatch(progress -> progress.state() == QuestState.READY_TO_TURN_IN);
    }

    /** Objets qu'accorderait la remise de {@code questId} (vide si quête inconnue). */
    public List<ItemStack> rewardItems(NamespacedKey questId) {
        return questEngine.find(questId).map(this::rewardItems).orElse(List.of());
    }

    /** Objets qu'accorderait la remise de {@code quest} : récompenses ITEM et {@code customitem give}. */
    List<ItemStack> rewardItems(QuestDefinition quest) {
        List<ItemStack> items = new ArrayList<>();
        for (QuestReward reward : quest.rewards()) {
            if (reward instanceof ItemReward item) {
                items.add(new ItemStack(item.material(), item.amount()));
            } else if (reward instanceof CommandReward command) {
                CustomItemGiveCommands.itemFor(command.command(), customItemRegistry).ifPresent(items::add);
            }
        }
        return items;
    }

    /**
     * @param refusalMessageKey message d'un refus faute de place ; {@code null} pour une reprise
     *     automatique : le joueur a déjà été prévenu, inutile de le répéter à chaque fermeture
     *     d'inventaire.
     * @return {@code false} si rien n'a été remis faute de place (aucun état modifié)
     */
    private boolean turnIn(Player player, QuestDefinition quest, ActiveQuestProgress progress,
                           @Nullable String refusalMessageKey) {
        if (progress.state() == QuestState.COMPLETED) {
            return true;
        }
        // Issue #318 : place vérifiée AVANT toute bascule d'état, dette ou récompense — tout ou rien.
        int missingSlots = ItemDelivery.missingSlots(player.getInventory(), rewardItems(quest));
        if (missingSlots > 0) {
            if (refusalMessageKey != null && player.isOnline()) {
                player.sendMessage(messagesService.current().format(refusalMessageKey,
                        Placeholder.parsed("quest", quest.title().base()),
                        Placeholder.unparsed("slots", String.valueOf(missingSlots))));
            }
            return false;
        }
        // Bascule mémoire immédiate et synchrone : garde anti double-remise.
        progress.setState(QuestState.READY_TO_TURN_IN);
        progress.setState(QuestState.COMPLETED);

        UUID playerId = player.getUniqueId();
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(playerId);
        if (playerActive != null) {
            playerActive.remove(quest.id());
        }
        forgetActionBarCounters(playerId, quest.id());

        // Montants monétaires de CETTE quête, dans leur ordre de déclaration. Chacun devient une
        // dette distincte : plusieurs récompenses MONEY sur une même complétion doivent toutes
        // être payées, et non pas la seule première (défaut mesuré du premier lot).
        List<Long> owed = new ArrayList<>();
        for (QuestReward reward : quest.rewards()) {
            if (reward instanceof MoneyReward money) {
                owed.add((long) money.amount());
            }
        }

        // Issue #166 : droits aux cœurs de CETTE quête, écrits dans la même transaction que la
        // complétion — une quête terminée ne peut donc ni perdre son cœur, ni le donner deux fois.
        List<QuestProgressRepository.HeartGrantRequest> hearts = new ArrayList<>();
        for (QuestReward reward : quest.rewards()) {
            if (reward instanceof HeartReward heart) {
                hearts.add(new QuestProgressRepository.HeartGrantRequest(
                        heart.effectiveGrantKey(quest.id()), heart.hearts()));
            }
        }

        // Issue #177 : tirages de CETTE complétion, faits maintenant (graine = jeton de complétion,
        // donc déterministes) et écrits avec elle : une remise rejouée ne relance jamais un tirage.
        List<Component> drawLines = new ArrayList<>();
        List<RewardEntitlement.Declaration> drawn = drawTables(playerId, quest, progress.rewardGrantId(), drawLines);

        // Complétion ET dettes dans la MÊME transaction (voir
        // QuestProgressRepository#completeQuest) : sans cela, un arrêt brutal entre
        // les deux perd soit la récompense, soit la garantie de ne pas la payer deux fois.
        List<Runnable> onPersisted = new ArrayList<>();
        for (QuestCompletionListener listener : completionListeners) {
            Runnable action = listener.completing(player, quest.id(), progress.rewardGrantId());
            if (action != null) {
                onPersisted.add(action);
            }
        }
        repository.completeQuest(playerId, quest.id(), progress.rewardGrantId(), owed, hearts, drawn)
                .thenAccept(completion -> {
                    onPersisted.forEach(Runnable::run);
                    payRecordedMoneyRewards(playerId, quest, completion.moneyGrantIds());
                    applyHeartGrants(playerId, quest, completion.hearts());
                    if (!drawn.isEmpty() && rewardTables != null) {
                        rewardTables.deliverSoon(List.of(playerId));
                    }
                })
                .exceptionally(error -> {
                    logger.error("Impossible de persister la fin de quête {} pour {}", quest.id(), playerId, error);
                    // La complétion n'est pas persistée : surtout ne rien annoncer comme payé. Le
                    // joueur est averti, et sa quête reste à reprendre au prochain chargement.
                    runOnMainThread(() -> {
                        Player online = plugin.getServer().getPlayer(playerId);
                        if (online != null && !owed.isEmpty()) {
                            online.sendMessage(messagesService.current().format("quest.reward-money-failed"));
                        }
                        if (online != null && !hearts.isEmpty() && heartBonusService != null) {
                            heartBonusService.onQuestCompletionFailed(online);
                        }
                    });
                    return null;
                });

        List<Component> rewardLines = grantRewards(player, quest, progress.rewardGrantId());
        rewardLines.addAll(drawLines);
        showQuestCompleted(player, quest, rewardLines);
        notifyChanged(playerId);
        return true;
    }

    /**
     * Tire chaque table {@code TABLE} de la quête (issue #177) et prépare un droit à récompense par
     * gain (source {@code QUEST}, clé {@code quest:<jeton>:<table>.<entrée>:<joueur>}). Table inconnue
     * ou désactivée, ou droits à récompense désactivés ({@code entitlements.yml}) : aucun tirage, le
     * joueur et le journal le disent — jamais un gain inventé. Même règle que les boss communautaires.
     */
    private List<RewardEntitlement.Declaration> drawTables(UUID playerId, QuestDefinition quest, String token,
                                                           List<Component> lines) {
        List<RewardEntitlement.Declaration> drawn = new ArrayList<>();
        for (QuestReward reward : quest.rewards()) {
            if (!(reward instanceof TableReward tableReward)) {
                continue;
            }
            if (rewardTables != null && !rewardTables.enabled()) {
                logger.warn("Quête {} : droits à récompense désactivés (entitlements.yml) — aucun tirage « {} » pour {}.",
                        quest.id(), tableReward.tableId(), playerId);
                lines.add(messagesService.current().format("quest.reward-line-table-disabled",
                        Placeholder.unparsed("table", tableReward.tableId())));
                continue;
            }
            Optional<RewardTable> table = rewardTables == null ? Optional.empty()
                    : rewardTables.table(tableReward.tableId()).filter(RewardTable::enabled);
            if (table.isEmpty()) {
                logger.warn("Quête {} : table de récompenses « {} » inconnue ou désactivée — aucun tirage pour {}.",
                        quest.id(), tableReward.tableId(), playerId);
                lines.add(messagesService.current().format("quest.reward-line-table-unavailable",
                        Placeholder.unparsed("table", tableReward.tableId())));
                continue;
            }
            List<RewardTableDraw.Win> wins = RewardTableDraw.wins(table.get(), "quest:" + token, playerId,
                    RewardSource.QUEST, quest.id().toString(), rewardTables::label,
                    rewardTables::rewardEnabled);
            if (wins.isEmpty()) {
                lines.add(messagesService.current().format("quest.reward-line-table-none",
                        Placeholder.unparsed("table", table.get().label())));
            }
            for (RewardTableDraw.Win win : wins) {
                drawn.add(win.declaration());
                lines.add(messagesService.current().format("quest.reward-line-table-win",
                        Placeholder.unparsed("item", rewardTables.label(win.entry().rewardKey())),
                        Placeholder.unparsed("table", table.get().label())));
            }
            logger.info("Quête {} : tirage « {} » v{} pour {} → {}.", quest.id(), table.get().id(),
                    table.get().version(), playerId, wins.stream().map(w -> w.entry().id()).toList());
        }
        return drawn;
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

    /**
     * Applique les droits aux cœurs tout juste écrits (issue #166). Hors ligne entre-temps : rien à
     * faire, le bonus est recalculé depuis la base à la prochaine connexion.
     */
    private void applyHeartGrants(UUID playerId, QuestDefinition quest,
                                  List<QuestProgressRepository.HeartGrantOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        if (heartBonusService == null) {
            logger.warn("Cœur(s) de la quête {} enregistré(s) pour {} mais aucun service de santé branché : "
                    + "bonus appliqué à la prochaine connexion.", quest.id(), playerId);
            return;
        }
        runOnMainThread(() -> {
            Player online = plugin.getServer().getPlayer(playerId);
            if (online != null) {
                heartBonusService.onQuestCompleted(online, quest.title().base(), outcomes);
            }
        });
    }

    // ---- Notifications sobres (Title/ActionBar Adventure, jamais le chat) ----------------------
    //
    // Trois événements, trois canaux distincts, choisis pour ne jamais spammer le chat (voir
    // messages.yml pour le contenu, jamais une quête codée en dur ici) :
    //  - démarrage/fin de quête (rares, méritent l'attention) : Title/Subtitle plein écran, bref ;
    //  - progression d'un objectif (fréquente, peut arriver plusieurs fois par seconde en combat) :
    //    ActionBar, qui se remplace en place plutôt que d'empiler des lignes.

    /**
     * Annonce le démarrage, <strong>et ce qu'il faut faire</strong> (issue #235).
     *
     * <p>Le Title seul ne suffisait pas : il disparaît en deux secondes et ne dit que le nom de la
     * quête. Un joueur qui vient de l'accepter savait donc qu'« une quête a commencé » sans savoir
     * quoi en faire — c'est le reproche exact du retour utilisateur d'onboarding. Les objectifs de
     * la première étape sont donc envoyés dans le chat, où ils restent lisibles et consultables.</p>
     *
     * <p>Aucune règle dupliquée : le libellé d'un objectif vient de {@link ObjectiveLabels}, le
     * même que l'ActionBar de progression et le journal, et la quantité de
     * {@link QuestObjective#requiredAmount}. Les textes vivent dans {@code messages.yml}.</p>
     */
    /**
     * Issue #13 : dit au joueur qu'une quête renouvelable n'est pas encore reprenable, et dans
     * combien de temps. Appelé par les points d'entrée joueur (dialogue, {@code /quest accept}) —
     * jamais par {@link #accept} lui-même, pour qu'un démarrage admin refusé ne notifie pas le joueur.
     */
    public void showCooldown(Player player, NamespacedKey questId, Duration retryAfter) {
        String questName = questEngine.find(questId).map(q -> q.title().base()).orElse(questId.toString());
        runOnMainThread(() -> {
            if (player.isOnline()) {
                player.sendMessage(messagesService.current().format("quest.on-cooldown",
                        Placeholder.parsed("quest", questName),
                        Placeholder.unparsed("time", CooldownText.format(retryAfter))));
            }
        });
    }

    private void showQuestStarted(Player player, QuestDefinition quest) {
        if (!player.isOnline()) {
            return;
        }
        Component title = messagesService.current().format("quest.started-title");
        Component subtitle = messagesService.current().format(
                "quest.started-subtitle", Placeholder.parsed("quest", quest.title().base()));
        // Issue #636 : quête acceptée depuis un dialogue — la fenêtre reste lisible, le nœud
        // suivant le dit déjà ; les objectifs ci-dessous restent dans le chat.
        if (!dialogueFocus.isFocused(player.getUniqueId())) {
            Title started = Title.title(title, subtitle, FEEDBACK_TITLE_TIMES);
            if (titleQueue != null) {
                titleQueue.started(player, started);
            } else {
                player.showTitle(started);
            }
        }

        // Une quête sans étape ne passe pas la validation du parseur, mais on ne le suppose pas ici.
        if (quest.steps().isEmpty()) {
            return;
        }
        announceObjectives(player, quest, quest.steps().get(0), "quest.started-objectives-header");
    }

    /**
     * Issue #70 — au passage à l'étape suivante, dit au joueur <strong>quoi faire maintenant</strong> :
     * mêmes lignes d'objectifs qu'au démarrage, sous un en-tête « étape suivante ».
     */
    private void showStepAdvanced(Player player, QuestDefinition quest, QuestStep step) {
        // Planifiée au tick suivant : une quête abandonnée entre-temps n'est plus annoncée.
        Map<NamespacedKey, ActiveQuestProgress> playerActive = activeByPlayer.get(player.getUniqueId());
        if (!player.isOnline() || playerActive == null || !playerActive.containsKey(quest.id())) {
            return;
        }
        announceObjectives(player, quest, step, "quest.step-advanced-header");
    }

    private void announceObjectives(Player player, QuestDefinition quest, QuestStep step, String headerKey) {
        if (step.objectives().isEmpty()) {
            return;
        }
        player.sendMessage(messagesService.current().format(headerKey,
                Placeholder.parsed("quest", quest.title().base())));
        for (QuestObjective objective : step.objectives()) {
            // Issue #238 : le libellé vient d'ObjectiveLabels, qui rend un composant
            // « translatable » — le client affiche donc « Graines de blé » et non « WHEAT_SEEDS ».
            // Il faut un Placeholder.component et non unparsed : un composant aplati en chaîne
            // perdrait justement la clé de traduction.
            Component label = ObjectiveLabels.targetName(objective, npcNames);
            // Issue #70 : pas de « (x1) » pour un objectif binaire (parler, atteindre un lieu).
            player.sendMessage(ObjectiveLabels.isCountable(objective)
                    ? messagesService.current().format("quest.started-objective",
                            Placeholder.component("objective", label),
                            Placeholder.unparsed("total", String.valueOf(requiredAmount(objective))))
                    : messagesService.current().format("quest.started-objective-single",
                            Placeholder.component("objective", label)));
        }
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
        // Issue #636 : une seule annonce, priorité au dialogue ouvert. Pas de titre par-dessus la
        // fenêtre : une ligne dans le prochain nœud, puis le bilan dans le chat à sa fermeture.
        DialogueFocus.Deferred completion = new DialogueFocus.Deferred(quest.title().base(), rewardLines);
        if (dialogueFocus.defer(player.getUniqueId(), completion)) {
            scheduleFocusExpiry(player.getUniqueId());
            return;
        }
        Component title = messagesService.current().format("quest.completed-title");
        Component subtitle = messagesService.current().format(
                "quest.completed-subtitle", Placeholder.parsed("quest", quest.title().base()));
        // Issue #817 : deux quêtes terminées ensemble — la seconde attend la fin de la première au
        // lieu de l'effacer ; le bilan de chacune part immédiatement dans le chat.
        Title completed = Title.title(title, subtitle, FEEDBACK_TITLE_TIMES);
        if (titleQueue != null) {
            titleQueue.completed(player, completed);
        } else {
            player.showTitle(completed);
        }
        sendRewardSummary(player, completion, false);
    }

    /**
     * Bilan des récompenses réellement accordées (jamais celles seulement prévues). Après un
     * dialogue, une quête sans récompense est quand même confirmée d'une ligne : c'était sa seule
     * annonce hors de la fenêtre.
     */
    private void sendRewardSummary(Player player, DialogueFocus.Deferred completion, boolean afterDialogue) {
        if (completion.rewardLines().isEmpty()) {
            if (afterDialogue) {
                player.sendMessage(messagesService.current().format(
                        "quest.completed-after-dialogue", Placeholder.parsed("quest", completion.questTitle())));
            }
            return;
        }
        player.sendMessage(messagesService.current().format(
                "quest.reward-summary-header", Placeholder.parsed("quest", completion.questTitle())));
        completion.rewardLines().forEach(player::sendMessage);
    }

    // ---- Focus d'un dialogue PNJ (issue #636) --------------------------------------------------

    /** Clic sur un PNJ dont le dialogue va s'ouvrir — avant la progression TALK_TO_NPC du même clic. */
    public void expectDialogue(Player player) {
        dialogueFocus.expect(player.getUniqueId());
    }

    /**
     * Un nœud de dialogue va être affiché : renouvelle le focus et rend, une seule fois, les lignes
     * « quête terminée » à ajouter à sa réplique.
     */
    public List<Component> dialogueShown(Player player) {
        dialogueFocus.shown(player.getUniqueId());
        return dialogueFocus.takeNotices(player.getUniqueId()).stream()
                .map(questTitle -> messagesService.current().format(
                        "quest.completed-in-dialogue", Placeholder.parsed("quest", questTitle)))
                .toList();
    }

    /** Fin observée du dialogue (fermeture par un choix, échec d'ouverture, déplacement, téléportation). */
    public void dialogueEnded(Player player) {
        List<DialogueFocus.Deferred> released = dialogueFocus.end(player.getUniqueId());
        if (player.isOnline()) {
            released.forEach(completion -> sendRewardSummary(player, completion, true));
        }
    }

    void dialogueMaybeLeftByMove(Player player) {
        if (dialogueFocus.endsOnMove(player.getUniqueId())) {
            dialogueEnded(player);
        }
    }

    boolean hasDialogueFocus(UUID playerId) {
        return dialogueFocus.isFocused(playerId) || dialogueFocus.hasPending(playerId);
    }

    /**
     * Échap ferme une fenêtre Paper Dialog sans rien dire au serveur : faute d'autre signal, les
     * annonces retenues partent à l'expiration du délai de garde (vérifiée chaque seconde, et
     * seulement tant qu'il reste une annonce en attente pour CE joueur).
     */
    private void scheduleFocusExpiry(UUID playerId) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!dialogueFocus.hasPending(playerId)) {
                return;
            }
            List<DialogueFocus.Deferred> released = dialogueFocus.expireIfDue(playerId);
            Player online = plugin.getServer().getPlayer(playerId);
            if (!released.isEmpty()) {
                if (online != null) {
                    released.forEach(completion -> sendRewardSummary(online, completion, true));
                }
                return;
            }
            scheduleFocusExpiry(playerId);
        }, 20L);
    }

    private void forgetActionBarCounters(UUID playerId, NamespacedKey questId) {
        if (actionBar != null) {
            actionBar.forgetQuest(playerId, questId.toString());
        }
    }

    /**
     * Affiche la progression d'un objectif. Par le canal partagé (#69 / #209 / #817), qui regroupe
     * les compteurs récents et les attribue à leur quête quand il y en a plusieurs ; sans lui
     * (tests), un message par progression, nommant la quête si le même événement en touche plusieurs.
     */
    private void showObjectiveProgress(Player player, ObjectiveRef ref, int current, int total, boolean named) {
        if (!player.isOnline()) {
            return;
        }
        String title = questEngine.find(ref.questId()).map(q -> q.title().base()).orElse(ref.questId().getKey());
        // Issue #71 : même libellé qu'au démarrage (#238) et que le journal — « Araignée 3/5 »,
        // traduit côté client, jamais « Tuer SPIDER » ni l'identifiant d'un PNJ.
        Component label = ObjectiveLabels.targetName(ref.objective(), npcNames);
        var messages = messagesService.current();
        TagResolver counts = TagResolver.resolver(Placeholder.unparsed("current", String.valueOf(current)),
                Placeholder.unparsed("total", String.valueOf(total)));
        Component alone = messages.format("quest.objective-progress", Placeholder.component("objective", label), counts);
        if (actionBar != null) {
            actionBar.objective(player, new com.lodygames.rpgquest.feedback.ActionBarFeedback.Progress(
                    ref.questId().toString(), ref.questId() + "/" + ref.stepId() + "/" + ref.objectiveIndex(),
                    title, label, alone, shortTitle -> messages.format("quest.objective-progress-entry",
                            Placeholder.unparsed("quest", shortTitle), counts)));
            return;
        }
        player.sendActionBar(named
                ? messages.format("quest.objective-progress-named", Placeholder.parsed("quest", title),
                        Placeholder.component("objective", label), counts)
                : alone);
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
    private List<Component> grantRewards(Player player, QuestDefinition quest, String grantId) {
        List<Component> lines = new ArrayList<>();
        int experienceIndex = 0;
        for (QuestReward reward : quest.rewards()) {
            switch (reward) {
                case ExperienceReward r -> {
                    // Une clé par récompense EXPERIENCE : deux lignes d'XP d'une même quête ne se dédupliquent pas.
                    boolean rpg = experienceRewards.grant(player, r.amount(), grantId + "#" + experienceIndex++);
                    lines.add(messagesService.current().format(
                            rpg ? "quest.reward-line-rpg-experience" : "quest.reward-line-experience",
                            Placeholder.unparsed("amount", String.valueOf(r.amount()))));
                }
                case ItemReward r -> {
                    // Place vérifiée par turnIn juste avant : la remise ne peut pas être refusée ici.
                    if (!ItemDelivery.deliver(player, new ItemStack(r.material(), r.amount())).delivered()) {
                        logger.error("Récompense {}x {} de {} non remise à {} malgré la vérification de place",
                                r.amount(), r.material(), quest.id(), player.getUniqueId());
                        continue;
                    }
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
                // HeartReward : idem, le droit est écrit avec la complétion et annoncé après le
                // commit — voir applyHeartGrants (issue #166).
                case HeartReward r -> { }
                // TableReward : tirée et écrite avec la complétion (drawTables), remise par les
                // droits à récompense (issue #177).
                case TableReward r -> { }
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
        return QuestObjective.describe(objective, npcNames);
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
