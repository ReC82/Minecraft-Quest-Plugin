package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.economy.CurrencyName;
import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.JournalConfig;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.item.RpgItemKeys;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.HeartReward;
import com.lodygames.rpgquest.quest.model.MoneyReward;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestPeriod;
import com.lodygames.rpgquest.quest.periodic.PeriodicProgress;
import com.lodygames.rpgquest.quest.periodic.PeriodicQuestService;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.VariableReward;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;

/**
 * Implémentation {@link QuestJournalUi} : un inventaire paginé (deux onglets
 * « Quêtes en cours » / « Quêtes terminées ») ouvert soit par un clic droit
 * sur l'item {@link RpgItemKeys#JOURNAL_QUETES} (remis par le Libraire),
 * soit par {@code /quests}, plus une vue détail par quête et un suivi
 * persistant (bossbar optionnelle). Le journal ne liste <strong>que</strong>
 * les quêtes déjà connues du joueur (acceptées puis actives, ou terminées) :
 * jamais de catalogue des quêtes non découvertes. Toute la logique de clic
 * vit ici ; {@link QuestJournalListener} ne fait que traduire les événements
 * Bukkit en appels à cette classe (même organisation que {@code
 * DialogueSessionEngine}/{@code DialogueNpcInteractListener}).
 *
 * <p>L'affichage ne se rafraîchit jamais par sondage : {@link #handleProgressChanged}
 * est appelé par {@link QuestProgressEngine#onProgressChanged} uniquement
 * quand la progression d'un joueur change réellement, et ne touche
 * l'inventaire/la bossbar que si ce joueur a effectivement un menu ouvert ou
 * une quête suivie.</p>
 */
public final class QuestJournalService implements QuestJournalUi {

    static final String TITLE_LIST = "Journal de quêtes";
    static final String TITLE_DETAIL = "Détails de la quête";

    private static final int LIST_SIZE = 54;
    private static final int DETAIL_SIZE = 27;

    static final int TAB_IN_PROGRESS_SLOT = 0;
    static final int TAB_COMPLETED_SLOT = 1;
    /** Issue #15 : onglets des quêtes quotidiennes et hebdomadaires (absents si la fonction est désactivée). */
    static final int TAB_DAILY_SLOT = 2;
    static final int TAB_WEEKLY_SLOT = 3;
    static final int PREV_PAGE_SLOT = 4;
    private static final int PAGE_INDICATOR_SLOT = 5;
    static final int NEXT_PAGE_SLOT = 6;
    /**
     * Bourse du joueur (issue #16) : un emplacement <strong>inerte</strong> de la barre du haut,
     * placé là pour que le solde soit visible dans une interface déjà ouverte par les joueurs
     * plutôt que derrière une commande à connaître. Cliquer dessus ne fait rien — c'est un
     * indicateur, pas un bouton, et {@link #handleListClick} l'ignore comme les autres slots
     * décoratifs.
     */
    static final int BALANCE_SLOT = 7;
    static final int CLOSE_SLOT = 8;
    private static final int CONTENT_START_SLOT = 9;
    static final int[] CONTENT_SLOTS = buildContentSlots();

    /** Même bourse que {@link #BALANCE_SLOT}, dans la vue détail : le solde suit le joueur. */
    static final int DETAIL_BALANCE_SLOT = 4;
    /**
     * Issue #475 (retour du 17/10) : la vue détail n'est plus UNE infobulle géante, trop haute pour
     * une fenêtre 854×480 (son début sortait de l'écran), mais une rangée d'objets lus de gauche à
     * droite — la quête (titre, état, palier), puis description, objectifs, récompenses et
     * prérequis, chacun découpé en pages de {@link #MAX_DETAIL_LORE_LINES} lignes au plus.
     * {@code DETAIL_ICON_SLOT} est le premier de la rangée : l'icône de la quête.
     */
    static final int DETAIL_ICON_SLOT = 10;
    static final int DETAIL_LAST_SECTION_SLOT = 17;
    /** Lignes de lore par objet de la vue détail : titre compris, ~120 px d'interface de haut. */
    static final int MAX_DETAIL_LORE_LINES = 10;
    static final int DETAIL_BACK_SLOT = 18;
    static final int DETAIL_TRACK_SLOT = 22;
    static final int DETAIL_CLOSE_SLOT = 26;

    private static final String TRACKED_QUEST_KEY = "__tracked_quest__";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final YamlQuestEngine questEngine;
    private final QuestProgressEngine questProgressEngine;
    private final PlayerVariableRepository variableRepository;
    private final YamlCustomItemRegistry customItemRegistry;
    private final EconomyService economyService;
    private final TrackedQuestDisplay trackedDisplay;
    private final Logger logger;

    private final Map<UUID, JournalSession> openSessions = new ConcurrentHashMap<>();
    private final Map<UUID, NamespacedKey> trackedByPlayer = new ConcurrentHashMap<>();
    /**
     * Jeton du dernier rendu demandé par joueur. Les rendus sont asynchrones (lectures en base) :
     * sans ce jeton, un rafraîchissement lancé avant un clic d'onglet pouvait se terminer après lui
     * et ramener le joueur sur l'ancien onglet (issue #15, quêtes démarrées pendant l'ouverture).
     */
    private final Map<UUID, Long> renderTickets = new ConcurrentHashMap<>();
    /**
     * Onglet périodique demandé dont l'attribution est en cours. Tant qu'il est là, un
     * rafraîchissement déclenché par la progression (quête démarrée par cette même attribution) ne
     * réaffiche pas l'ancien onglet : c'est l'onglet demandé qui s'affichera, avec l'état à jour.
     */
    private final Map<UUID, JournalTab> pendingPeriodicTabs = new ConcurrentHashMap<>();

    /**
     * Issue #475 — étiquette de contexte lisible d'une quête (« Amélioration du kit — palier 2 »),
     * branchée au démarrage. Sans elle, une quête de palier du kit s'affichait sous son seul titre
     * narratif (« Explorateur »), impossible à reconnaître comme l'amélioration du kit.
     */
    private volatile Function<NamespacedKey, Optional<String>> contextLabels = id -> Optional.empty();

    /** Issue #15 : quêtes quotidiennes/hebdomadaires, branchées au démarrage (absentes = onglets masqués). */
    private volatile PeriodicQuestService periodicQuests;

    public QuestJournalService(RPGQuestPlugin plugin, YamlQuestEngine questEngine, QuestProgressEngine questProgressEngine,
                                PlayerVariableRepository variableRepository, YamlCustomItemRegistry customItemRegistry,
                                EconomyService economyService, JournalConfig config) {
        this.plugin = plugin;
        this.questEngine = questEngine;
        this.questProgressEngine = questProgressEngine;
        this.variableRepository = variableRepository;
        this.customItemRegistry = customItemRegistry;
        this.economyService = economyService;
        this.trackedDisplay = new TrackedQuestDisplay(config.trackerEnabled());
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public void start() {
        questProgressEngine.onProgressChanged(this::handleProgressChanged);
    }

    @Override
    public void stop() {
        for (UUID playerId : openSessions.keySet()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                player.closeInventory();
            }
        }
        openSessions.clear();
        for (UUID playerId : trackedByPlayer.keySet()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                trackedDisplay.clear(player);
            }
        }
        trackedByPlayer.clear();
        trackedDisplay.clearAll();
    }

    /**
     * Issue #171 — ligne d'indication sous un objectif (boss de quête : où il est, ou pourquoi il
     * n'est pas encore là). Lecture seule, thread principal.
     */
    @FunctionalInterface
    public interface ObjectiveHints {
        Optional<Component> hint(UUID playerId, NamespacedKey questId, String stepId, int objectiveIndex);
    }

    private volatile ObjectiveHints objectiveHints = (playerId, questId, stepId, index) -> Optional.empty();

    public void setObjectiveHints(ObjectiveHints objectiveHints) {
        this.objectiveHints = objectiveHints;
    }

    /** Issue #171 : ajoute l'indication de l'objectif {@code index} s'il en a une (boss de quête seulement). */
    private void addObjectiveHint(List<Component> lore, UUID playerId, QuestDefinition quest, QuestState state,
                                  String stepId, QuestObjective objective, int index) {
        if (state == QuestState.ACTIVE
                && objective instanceof com.lodygames.rpgquest.quest.model.KillQuestBossObjective) {
            objectiveHints.hint(playerId, quest.id(), stepId, index)
                    .map(line -> line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                    .ifPresent(lore::add);
        }
    }

    /** Issue #475 — voir {@link #contextLabels}. Texte brut, jamais un identifiant interne. */
    public void setPeriodicQuests(PeriodicQuestService periodicQuests) {
        this.periodicQuests = periodicQuests;
    }

    private boolean periodicEnabled() {
        PeriodicQuestService periodic = periodicQuests;
        return periodic != null && periodic.enabled();
    }

    public void setContextLabels(Function<NamespacedKey, Optional<String>> contextLabels) {
        this.contextLabels = contextLabels;
    }

    /** Listener Bukkit unique (clic/drag/fermeture/connexion/déconnexion) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new QuestJournalListener(this);
    }

    /** {@code /quests} ou clic droit sur le journal : ouvre toujours sur l'onglet « en cours », première page. */
    public void open(Player player) {
        showList(player, JournalTab.IN_PROGRESS, 0);
        // Issue #15 : la première ouverture du journal débloque les quêtes périodiques du joueur ;
        // chaque ouverture rattrape aussi une réinitialisation survenue depuis.
        if (periodicEnabled()) {
            periodicQuests.unlockAndRefresh(player);
        }
    }

    /** Onglet quotidien/hebdomadaire : attribue la période en cours si besoin, puis l'affiche. */
    private void openPeriodicTab(Player player, JournalTab tab) {
        if (!periodicEnabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        pendingPeriodicTabs.put(playerId, tab);
        periodicQuests.unlockAndRefresh(player).whenComplete((v, error) -> runOnMainThread(() -> {
            // Rien si le joueur est allé ailleurs entre-temps (autre onglet, fermeture).
            if (pendingPeriodicTabs.remove(playerId, tab)) {
                showList(player, tab, 0);
            }
        }));
    }

    /** {@code true} si {@code stack} est l'item Journal des quêtes RPGQuest (identité PDC, jamais le nom/lore). */
    public boolean isJournalItem(ItemStack stack) {
        return stack != null
                && customItemRegistry.identify(stack).map(RpgItemKeys.JOURNAL_QUETES::equals).orElse(false);
    }

    /** Suivi actuel du joueur, si présent. Public : réutilisable par une future sidebar/scoreboard. */
    public Optional<NamespacedKey> trackedQuestOf(UUID playerId) {
        return Optional.ofNullable(trackedByPlayer.get(playerId));
    }

    /**
     * Oublie la quête suivie d'un joueur et retire sa bossbar (reset admin « nouveau joueur » —
     * voir {@code player.PlayerResetService} ; la variable persistée {@code __tracked_quest__} est
     * effacée séparément par le wipe des variables). Sans effet si le joueur est hors ligne.
     */
    public void clearTrackingFor(UUID playerId) {
        trackedByPlayer.remove(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            trackedDisplay.clear(player);
        }
    }

    // ---- Cycle de vie joueur ----------------------------------------------

    void handleJoin(Player player) {
        UUID playerId = player.getUniqueId();
        variableRepository.get(playerId, TRACKED_QUEST_KEY).thenAccept(value -> runOnMainThread(() -> {
            if (value.isEmpty() || value.get().isBlank()) {
                return;
            }
            NamespacedKey questId = NamespacedKey.fromString(value.get());
            if (questId == null) {
                return;
            }
            trackedByPlayer.put(playerId, questId);
            refreshTrackedDisplay(player);
        })).exceptionally(error -> {
            logger.error("Impossible de charger la quête suivie de {}", playerId, error);
            return null;
        });
    }

    void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        openSessions.remove(playerId);
        renderTickets.remove(playerId);
        pendingPeriodicTabs.remove(playerId);
        trackedByPlayer.remove(playerId);
        trackedDisplay.clear(player);
    }

    /**
     * Appelé sur {@code InventoryCloseEvent} d'un inventaire du journal. Efface la session car le
     * joueur quitte le menu. <strong>Contrat d'ordre</strong> : {@link #showList}/{@link #showDetail}
     * (re)posent la session <em>après</em> {@code player.openInventory(...)}, précisément parce que
     * ce {@code openInventory} déclenche d'abord ce {@code handleClose} pour le menu précédent — sans
     * quoi une simple navigation (changement d'onglet, retour, bouton Fermer) laisserait
     * {@code openSessions} vide et tous les clics suivants inertes.
     */
    void handleClose(Player player) {
        openSessions.remove(player.getUniqueId());
        // Journal fermé pendant une attribution : ne pas le rouvrir de force ensuite.
        pendingPeriodicTabs.remove(player.getUniqueId());
    }

    JournalSession sessionOf(Player player) {
        return openSessions.get(player.getUniqueId());
    }

    // ---- Rafraîchissement piloté par la progression -----------------------

    private void handleProgressChanged(UUID playerId) {
        runOnMainThread(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            refreshTrackedDisplay(player);
            JournalSession session = openSessions.get(playerId);
            if (session == null || pendingPeriodicTabs.containsKey(playerId)) {
                return;
            }
            if (session.isDetail()) {
                showDetail(player, session.tab(), session.page(), session.detailQuestId());
            } else {
                showList(player, session.tab(), session.page());
            }
        });
    }

    private void refreshTrackedDisplay(Player player) {
        UUID playerId = player.getUniqueId();
        NamespacedKey trackedId = trackedByPlayer.get(playerId);
        if (trackedId == null) {
            trackedDisplay.clear(player);
            return;
        }
        Optional<QuestDefinition> questOpt = questEngine.find(trackedId);
        if (questOpt.isEmpty()) {
            // Quête introuvable (supprimée par un reload) : seul cas où le suivi est effacé automatiquement.
            trackedByPlayer.remove(playerId);
            variableRepository.set(playerId, TRACKED_QUEST_KEY, "").exceptionally(error -> {
                logger.error("Impossible d'effacer la quête suivie de {}", playerId, error);
                return null;
            });
            trackedDisplay.clear(player);
            return;
        }
        Optional<QuestStepProgressView> stepView = questProgressEngine.activeStepView(playerId, trackedId);
        if (stepView.isEmpty()) {
            // Suivie mais pas (ou plus) active (pas encore acceptée, ou déjà remise/abandonnée) :
            // rien à afficher sur la bossbar, mais le suivi lui-même reste enregistré — un joueur peut
            // suivre une quête avant même de l'accepter, ou vouloir la retrouver après une remise.
            trackedDisplay.clear(player);
            return;
        }
        trackedDisplay.update(player, questOpt.get(), stepView.get(),
                objectivesOf(questOpt.get(), stepView.get().stepId()), questProgressEngine.npcNameResolver());
    }

    // ---- Vue liste ----------------------------------------------------------

    private long nextRenderTicket(UUID playerId) {
        return renderTickets.merge(playerId, 1L, Long::sum);
    }

    /** {@code false} si un rendu plus récent a été demandé depuis {@code ticket} : celui-ci est périmé. */
    private boolean isLatestRender(UUID playerId, long ticket) {
        return renderTickets.getOrDefault(playerId, 0L) == ticket;
    }

    void showList(Player player, JournalTab requestedTab, int requestedPage) {
        UUID playerId = player.getUniqueId();
        long ticket = nextRenderTicket(playerId);
        JournalTab shownTab = requestedTab.period() != null && !periodicEnabled()
                ? JournalTab.IN_PROGRESS : requestedTab;
        CompletableFuture<Map<NamespacedKey, QuestState>> statesFuture = questProgressEngine.allStates(playerId);
        CompletableFuture<Map<NamespacedKey, Duration>> cooldownsFuture = readCooldowns(playerId);
        CompletableFuture<Optional<Long>> balanceFuture = readBalance(playerId);
        CompletableFuture<Map<QuestPeriod, PeriodicQuestService.PeriodView>> periodsFuture = readPeriods(playerId);
        CompletableFuture.allOf(statesFuture, cooldownsFuture, balanceFuture, periodsFuture).thenRun(() -> runOnMainThread(() -> {
            if (!isLatestRender(playerId, ticket)) {
                return;
            }
            JournalTab tab = shownTab;
            Map<NamespacedKey, QuestState> states = statesFuture.join();
            Map<NamespacedKey, Duration> cooldowns = cooldownsFuture.join();
            Optional<Long> balance = balanceFuture.join();
            Map<QuestPeriod, PeriodicProgress> progress = new EnumMap<>(QuestPeriod.class);
            periodsFuture.join().forEach((p, view) ->
                    progress.put(p, PeriodicProgress.of(p, view.assignments(), states)));
            Optional<PeriodicQuestService.PeriodView> period = Optional.ofNullable(tab.period())
                    .map(periodsFuture.join()::get);
            Optional<PeriodicProgress> shownProgress = period.map(view -> progress.get(view.period()));

            List<NamespacedKey> matching = shownProgress.map(PeriodicJournalEntries::layout)
                    .orElseGet(() -> journalEntries(playerId, tab, states));
            int pageCount = shownProgress.isPresent() ? 1 : JournalPagination.pageCount(matching.size());
            int page = JournalPagination.clampPage(requestedPage, pageCount);
            List<NamespacedKey> pageIds = shownProgress.isPresent() ? matching : JournalPagination.pageOf(matching, page);

            JournalInventoryHolder holder = new JournalInventoryHolder(JournalKind.LIST);
            // Retour TC-350 : sur un onglet périodique, le titre porte l'avancement, lisible sans survol.
            Component title = shownProgress.map(p -> (Component) Component.text(p.shortSummary()))
                    .orElseGet(() -> MM.deserialize(TITLE_LIST));
            Inventory inventory = Bukkit.createInventory(holder, LIST_SIZE, title);
            holder.bind(inventory);

            renderChrome(inventory, tab, page, pageCount, balance, progress);
            if (shownProgress.isPresent() && !pageIds.isEmpty()) {
                inventory.setItem(PAGE_INDICATOR_SLOT,
                        PeriodicJournalEntries.summaryIcon(shownProgress.get(), period.get(), Instant.now()));
                inventory.setItem(CONTENT_SLOTS[0], PeriodicJournalEntries.toDoHeader(shownProgress.get()));
                inventory.setItem(CONTENT_SLOTS[PeriodicJournalEntries.completedHeaderIndex(shownProgress.get())],
                        PeriodicJournalEntries.completedHeader(shownProgress.get()));
            }
            for (int i = 0; i < pageIds.size(); i++) {
                NamespacedKey questId = pageIds.get(i);
                if (questId == null) {
                    continue;
                }
                Optional<QuestDefinition> quest = questEngine.find(questId);
                if (quest.isEmpty()) {
                    inventory.setItem(CONTENT_SLOTS[i], unavailableIcon());
                    continue;
                }
                QuestState state = shownProgress.map(p -> p.stateOf(questId))
                        .orElseGet(() -> states.getOrDefault(questId, QuestState.NOT_STARTED));
                boolean tracked = questId.equals(trackedByPlayer.get(playerId));
                ItemStack icon = buildIcon(playerId, quest.get(), state, cooldownOf(cooldowns, questId), tracked);
                if (period.isPresent()) {
                    PeriodicJournalEntries.decorate(icon, state, period.get(), Instant.now());
                }
                inventory.setItem(CONTENT_SLOTS[i], icon);
            }
            if (period.isPresent() && pageIds.isEmpty()) {
                inventory.setItem(CONTENT_SLOTS[0], PeriodicJournalEntries.emptyIcon(period.get(), Instant.now()));
            }
            // openInventory AVANT d'enregistrer la session : quand il remplace un menu déjà ouvert,
            // il déclenche un InventoryCloseEvent SYNCHRONE pour l'ancien inventaire, que
            // QuestJournalListener traduit en handleClose -> openSessions.remove(playerId). Poser la
            // session ensuite garantit qu'elle survit à ce close (bug de navigation directionnelle :
            // COMPLETED -> IN_PROGRESS et le bouton Fermer devenaient inertes après le 1er changement
            // d'onglet, la session ayant été effacée par le close du ré-affichage).
            player.openInventory(inventory);
            openSessions.put(playerId, JournalSession.list(tab, page, pageIds));
        })).exceptionally(error -> {
            logger.error("Impossible de construire le journal de quêtes pour {}", playerId, error);
            return null;
        });
    }

    /**
     * Issue #15 : les attributions de la période en cours, quotidienne et hebdomadaire — l'onglet
     * affiché s'en sert pour ses sections, les onglets du haut pour leur résumé. Vide si la
     * fonction est désactivée. Une simple lecture : n'attribue ni ne démarre rien.
     */
    private CompletableFuture<Map<QuestPeriod, PeriodicQuestService.PeriodView>> readPeriods(UUID playerId) {
        if (!periodicEnabled()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        CompletableFuture<PeriodicQuestService.PeriodView> daily = periodicQuests.view(playerId, QuestPeriod.DAILY);
        CompletableFuture<PeriodicQuestService.PeriodView> weekly = periodicQuests.view(playerId, QuestPeriod.WEEKLY);
        return daily.thenCombine(weekly, (d, w) -> Map.of(QuestPeriod.DAILY, d, QuestPeriod.WEEKLY, w));
    }

    /**
     * Issue #475 — les entrées d'un onglet viennent de la <strong>progression du joueur</strong>,
     * plus seulement des définitions chargées. Une quête en cours dont la définition manque (retirée
     * ou en échec de chargement après une publication) disparaissait sans un mot alors que sa
     * progression existe toujours ; elle reste désormais visible dans « en cours » sous une icône
     * « indisponible », et un avertissement est journalisé pour l'administrateur. Une quête
     * <em>terminée</em> retirée, elle, n'a plus rien à montrer : elle n'est pas listée.
     *
     * <p>Ordre stable : quêtes connues par titre, puis les indisponibles par identifiant.</p>
     */
    private List<NamespacedKey> journalEntries(UUID playerId, JournalTab tab, Map<NamespacedKey, QuestState> states) {
        List<QuestDefinition> known = new ArrayList<>();
        List<NamespacedKey> unavailable = new ArrayList<>();
        states.forEach((questId, state) -> {
            if (!tabMatches(tab, state)) {
                return;
            }
            Optional<QuestDefinition> quest = questEngine.find(questId);
            if (quest.isPresent() && quest.get().isPeriodic()) {
                // Issue #15 : une quête quotidienne/hebdomadaire ne vit que dans son propre onglet.
                return;
            }
            if (quest.isPresent()) {
                known.add(quest.get());
            } else if (tab == JournalTab.IN_PROGRESS) {
                unavailable.add(questId);
            }
        });
        if (!unavailable.isEmpty()) {
            logger.warn("Journal de {} : quête(s) en cours sans définition chargée {} — progression conservée, "
                    + "vérifier la publication du contenu.", playerId, unavailable);
        }
        List<NamespacedKey> entries = new ArrayList<>();
        known.stream()
                .sorted(Comparator.comparing((QuestDefinition quest) -> quest.title().base(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(quest -> quest.id().toString()))
                .forEach(quest -> entries.add(quest.id()));
        unavailable.stream().sorted(Comparator.comparing(NamespacedKey::toString)).forEach(entries::add);
        return entries;
    }

    /** Icône d'une quête en cours dont la définition est introuvable (voir {@link #journalEntries}). */
    private ItemStack unavailableIcon() {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<gray>Quête momentanément indisponible</gray>"));
        meta.lore(List.of(
                MM.deserialize("<white>État :</white> <yellow>Active</yellow>"),
                MM.deserialize("<gray>Ta progression est conservée.</gray>"),
                MM.deserialize("<dark_gray>Elle réapparaîtra dès que la quête sera de nouveau publiée.</dark_gray>")));
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean tabMatches(JournalTab tab, QuestState state) {
        return switch (tab) {
            case IN_PROGRESS -> state == QuestState.ACTIVE || state == QuestState.READY_TO_TURN_IN;
            case COMPLETED -> state == QuestState.COMPLETED;
            case DAILY, WEEKLY -> false;
        };
    }

    private void renderChrome(Inventory inventory, JournalTab tab, int page, int pageCount, Optional<Long> balance,
                              Map<QuestPeriod, PeriodicProgress> progress) {
        inventory.setItem(TAB_IN_PROGRESS_SLOT, tabIcon("Quêtes en cours", Material.BOOK, tab == JournalTab.IN_PROGRESS));
        inventory.setItem(TAB_COMPLETED_SLOT, tabIcon("Quêtes terminées", Material.ENCHANTED_BOOK, tab == JournalTab.COMPLETED));
        if (periodicEnabled()) {
            inventory.setItem(TAB_DAILY_SLOT, periodicTabIcon(QuestPeriod.DAILY, Material.CLOCK, tab == JournalTab.DAILY,
                    progress.get(QuestPeriod.DAILY)));
            inventory.setItem(TAB_WEEKLY_SLOT, periodicTabIcon(QuestPeriod.WEEKLY, Material.RECOVERY_COMPASS,
                    tab == JournalTab.WEEKLY, progress.get(QuestPeriod.WEEKLY)));
        }
        if (page > 0) {
            inventory.setItem(PREV_PAGE_SLOT, navIcon(Material.ARROW, "« Page précédente"));
        }
        inventory.setItem(PAGE_INDICATOR_SLOT, pageIndicatorIcon(page, pageCount));
        if (page < pageCount - 1) {
            inventory.setItem(NEXT_PAGE_SLOT, navIcon(Material.ARROW, "Page suivante »"));
        }
        inventory.setItem(BALANCE_SLOT, balanceIcon(balance));
        inventory.setItem(CLOSE_SLOT, navIcon(Material.BARRIER, "Fermer"));
    }

    /**
     * Lecture du solde qui <strong>ne peut pas faire échouer le journal</strong> (issue #16) : une
     * erreur de base rend un {@link Optional#empty()}, jamais un futur en échec et jamais un
     * « 0 pièce » inventé — un zéro affiché à la place d'une panne serait un mensonge, et un joueur
     * croirait avoir été volé.
     */
    private CompletableFuture<Optional<Long>> readBalance(UUID playerId) {
        return economyService.balance(playerId)
                .thenApply(Optional::of)
                .exceptionally(error -> {
                    logger.error("Impossible de lire le solde de {} pour le journal", playerId, error);
                    return Optional.empty();
                });
    }

    /**
     * Temps restant des quêtes renouvelables en recharge (issue #13). Comme la bourse, une erreur de
     * lecture ne fait pas échouer le journal : elle rend {@code null} (voir {@link #cooldownOf}) et la
     * ligne de recharge disparaît, plutôt que d'annoncer à tort une quête disponible.
     */
    private CompletableFuture<Map<NamespacedKey, Duration>> readCooldowns(UUID playerId) {
        return questProgressEngine.remainingCooldowns(playerId)
                .exceptionally(error -> {
                    logger.error("Impossible de lire les délais de recharge de {} pour le journal", playerId, error);
                    return null;
                });
    }

    /** Recharge d'une quête : {@code null} si la lecture a échoué, {@link Duration#ZERO} si aucune. */
    private static Duration cooldownOf(Map<NamespacedKey, Duration> cooldowns, NamespacedKey questId) {
        return cooldowns == null ? null : cooldowns.getOrDefault(questId, Duration.ZERO);
    }

    /**
     * Indicateur de bourse. Le montant vient du <strong>portefeuille persistant</strong> (la même
     * source que {@code /money} et que le panel), jamais d'un objet d'inventaire : aucun item n'est
     * compté comme de la monnaie.
     */
    private ItemStack balanceIcon(Optional<Long> balance) {
        ItemStack stack = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = stack.getItemMeta();
        if (balance.isPresent()) {
            meta.displayName(MM.deserialize("<gold>Bourse :</gold> <white><amount> " + CurrencyName.get() + "</white>",
                    Placeholder.unparsed("amount", String.valueOf(balance.get()))));
            meta.lore(List.of(MM.deserialize("<dark_gray>Mis à jour à chaque transaction.</dark_gray>")));
        } else {
            meta.displayName(MM.deserialize("<gold>Bourse :</gold> <red>indisponible</red>"));
            meta.lore(List.of(MM.deserialize("<dark_gray>Le solde n'a pas pu être lu. Réessaie plus tard.</dark_gray>")));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack tabIcon(String label, Material material, boolean active) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        String color = active ? "<yellow><bold>" : "<gray>";
        String colorEnd = active ? "</bold></yellow>" : "</gray>";
        meta.displayName(MM.deserialize(color + "<label>" + colorEnd, Placeholder.unparsed("label", label)));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack navIcon(Material material, String label) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<yellow><label></yellow>", Placeholder.unparsed("label", label)));
        stack.setItemMeta(meta);
        return stack;
    }

    /** Onglet périodique : son avancement sous le libellé (« Quotidiennes : 1/2 terminée — 1 restante »). */
    private ItemStack periodicTabIcon(QuestPeriod period, Material material, boolean active, PeriodicProgress progress) {
        ItemStack stack = tabIcon(period.label(), material, active);
        if (progress != null && progress.total() > 0) {
            ItemMeta meta = stack.getItemMeta();
            meta.lore(List.of(MM.deserialize("<gray><summary></gray>",
                    Placeholder.unparsed("summary", progress.summary()))));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack pageIndicatorIcon(int page, int pageCount) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MM.deserialize("<gray>Page <current>/<total></gray>",
                Placeholder.unparsed("current", String.valueOf(page + 1)),
                Placeholder.unparsed("total", String.valueOf(pageCount))));
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- Vue détail -----------------------------------------------------

    void showDetail(Player player, JournalTab tab, int page, NamespacedKey questId) {
        UUID playerId = player.getUniqueId();
        Optional<QuestDefinition> questOpt = questEngine.find(questId);
        if (questOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Cette quête n'existe plus.</red>"));
            showList(player, tab, page);
            return;
        }

        long ticket = nextRenderTicket(playerId);
        CompletableFuture<Map<NamespacedKey, QuestState>> statesFuture = questProgressEngine.allStates(playerId);
        CompletableFuture<Map<NamespacedKey, Duration>> cooldownsFuture = readCooldowns(playerId);
        CompletableFuture<Optional<Long>> balanceFuture = readBalance(playerId);
        CompletableFuture.allOf(statesFuture, cooldownsFuture, balanceFuture).thenRun(() -> runOnMainThread(() -> {
            if (!isLatestRender(playerId, ticket)) {
                return;
            }
            Map<NamespacedKey, QuestState> states = statesFuture.join();
            Optional<Long> balance = balanceFuture.join();
            QuestDefinition quest = questOpt.get();
            QuestState state = states.getOrDefault(questId, QuestState.NOT_STARTED);
            boolean tracked = questId.equals(trackedByPlayer.get(playerId));

            JournalInventoryHolder holder = new JournalInventoryHolder(JournalKind.DETAIL);
            Inventory inventory = Bukkit.createInventory(holder, DETAIL_SIZE, MM.deserialize(TITLE_DETAIL));
            holder.bind(inventory);

            inventory.setItem(DETAIL_BALANCE_SLOT, balanceIcon(balance));
            List<ItemStack> sections = buildDetailItems(
                    playerId, quest, state, cooldownOf(cooldownsFuture.join(), questId), tracked);
            int shown = Math.min(sections.size(), DETAIL_LAST_SECTION_SLOT - DETAIL_ICON_SLOT + 1);
            if (shown < sections.size()) {
                logger.warn("Détails de {} : {} pages d'infobulle, {} affichées", questId, sections.size(), shown);
            }
            for (int i = 0; i < shown; i++) {
                inventory.setItem(DETAIL_ICON_SLOT + i, sections.get(i));
            }
            inventory.setItem(DETAIL_BACK_SLOT, navIcon(Material.ARROW, "« Retour"));
            inventory.setItem(DETAIL_TRACK_SLOT, trackToggleIcon(tracked));
            inventory.setItem(DETAIL_CLOSE_SLOT, navIcon(Material.BARRIER, "Fermer"));

            // Session enregistrée APRÈS openInventory — voir la note dans showList (le close
            // synchrone du menu précédent effacerait sinon la session tout juste posée).
            player.openInventory(inventory);
            openSessions.put(playerId, JournalSession.detail(tab, page, questId));
        })).exceptionally(error -> {
            logger.error("Impossible de construire la vue détail pour {}", playerId, error);
            return null;
        });
    }

    private ItemStack trackToggleIcon(boolean tracked) {
        ItemStack stack = new ItemStack(tracked ? Material.NETHER_STAR : Material.COMPASS);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(tracked
                ? MM.deserialize("<yellow>★ Ne plus suivre</yellow>")
                : MM.deserialize("<green>Suivre cette quête</green>"));
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- Clics ------------------------------------------------------------

    void handleListClick(Player player, JournalSession session, int slot, boolean rightClick) {
        pendingPeriodicTabs.remove(player.getUniqueId());
        if (slot == TAB_IN_PROGRESS_SLOT) {
            showList(player, JournalTab.IN_PROGRESS, 0);
            return;
        }
        if (slot == TAB_COMPLETED_SLOT) {
            showList(player, JournalTab.COMPLETED, 0);
            return;
        }
        if (slot == TAB_DAILY_SLOT) {
            openPeriodicTab(player, JournalTab.DAILY);
            return;
        }
        if (slot == TAB_WEEKLY_SLOT) {
            openPeriodicTab(player, JournalTab.WEEKLY);
            return;
        }
        if (slot == PREV_PAGE_SLOT) {
            showList(player, session.tab(), session.page() - 1);
            return;
        }
        if (slot == NEXT_PAGE_SLOT) {
            showList(player, session.tab(), session.page() + 1);
            return;
        }
        if (slot == CLOSE_SLOT) {
            closeNextTick(player);
            return;
        }

        int contentIndex = contentIndexOf(slot);
        if (contentIndex < 0 || contentIndex >= session.pageQuests().size()) {
            return;
        }
        NamespacedKey questId = session.pageQuests().get(contentIndex);
        if (questId == null) {
            return; // en-tête de section ou case vide d'un onglet périodique
        }
        if (rightClick) {
            toggleTracking(player, questId);
            showList(player, session.tab(), session.page());
        } else {
            showDetail(player, session.tab(), session.page(), questId);
        }
    }

    void handleDetailClick(Player player, JournalSession session, int slot) {
        if (slot == DETAIL_BACK_SLOT) {
            showList(player, session.tab(), session.page());
            return;
        }
        if (slot == DETAIL_CLOSE_SLOT) {
            closeNextTick(player);
            return;
        }
        if (slot == DETAIL_TRACK_SLOT) {
            toggleTracking(player, session.detailQuestId());
            showDetail(player, session.tab(), session.page(), session.detailQuestId());
        }
    }

    private void toggleTracking(Player player, NamespacedKey questId) {
        UUID playerId = player.getUniqueId();
        NamespacedKey current = trackedByPlayer.get(playerId);
        if (questId.equals(current)) {
            trackedByPlayer.remove(playerId);
            variableRepository.set(playerId, TRACKED_QUEST_KEY, "").exceptionally(error -> {
                logger.error("Impossible de persister l'arrêt du suivi de {} pour {}", questId, playerId, error);
                return null;
            });
            trackedDisplay.clear(player);
        } else {
            trackedByPlayer.put(playerId, questId);
            variableRepository.set(playerId, TRACKED_QUEST_KEY, questId.toString()).exceptionally(error -> {
                logger.error("Impossible de persister le suivi de {} pour {}", questId, playerId, error);
                return null;
            });
            refreshTrackedDisplay(player);
        }
    }

    private int contentIndexOf(int slot) {
        if (slot < CONTENT_START_SLOT || slot >= CONTENT_START_SLOT + CONTENT_SLOTS.length) {
            return -1;
        }
        return slot - CONTENT_START_SLOT;
    }

    // ---- Construction des icônes -------------------------------------------

    /**
     * Icône d'une quête dans la <strong>liste</strong> : infobulle <em>compacte</em> — nom, état,
     * compteurs de l'étape en cours, indications de clic. Tout le reste (description, catégorie,
     * récompenses, prérequis) est dans la vue détail, voir {@link #buildDetailItems}.
     *
     * <p>Les attributs vanilla de l'objet servant d'icône sont masqués : une quête dont l'icône
     * est une épée affichait « Quand dans la main principale : … dégâts d'attaque », information
     * parasite qui n'a rien à voir avec la quête.</p>
     */
    private ItemStack buildIcon(UUID playerId, QuestDefinition quest, QuestState state, Duration cooldown,
                                boolean tracked) {
        return detailItem(quest.icon(), buildTitle(quest, state, tracked),
                buildCompactLore(playerId, quest, state, cooldown, tracked));
    }

    private static ItemStack detailItem(Material material, Component title, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(title);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        stack.setItemMeta(meta);
        return stack;
    }

    private Component buildTitle(QuestDefinition quest, QuestState state, boolean tracked) {
        String color = switch (state) {
            case ACTIVE, READY_TO_TURN_IN -> "<yellow>";
            case COMPLETED -> "<green>";
            default -> "<white>";
        };
        String colorEnd = color.replace("<", "</");
        String star = tracked ? " <gold>★</gold>" : "";
        return MM.deserialize(color + "<name>" + colorEnd + star, Placeholder.parsed("name", npcNamed(quest.title().base())));
    }

    /** Au-delà de ce nombre, les compteurs sont résumés : une infobulle de liste reste courte. */
    private static final int MAX_COMPACT_COUNTERS = 4;

    /**
     * Infobulle de <strong>liste</strong> : état, compteurs de l'étape en cours, indications de
     * clic. Volontairement sans description, sans récompenses, sans prérequis, sans catégorie —
     * tout cela est à un clic, dans les détails.
     *
     * <p>L'identifiant technique de l'étape ({@code prove_worth}…) n'est plus affiché : c'est une
     * donnée interne, illisible pour un joueur, et elle occupait une ligne entière.</p>
     */
    private List<Component> buildCompactLore(UUID playerId, QuestDefinition quest, QuestState state,
                                             Duration cooldown, boolean tracked) {
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<white>État :</white> " + stateLabel(state)));
        addContextLine(lore, quest);
        addReplayLines(lore, quest, state, cooldown);

        QuestStepProgressView stepView = stepViewFor(playerId, quest, state);
        List<QuestObjective> objectives = objectivesOf(quest, stepView.stepId());
        List<ObjectiveProgressView> progress = stepView.objectives();
        if (!progress.isEmpty()) {
            lore.add(Component.empty());
        }
        int shown = Math.min(progress.size(), MAX_COMPACT_COUNTERS);
        for (int i = 0; i < shown; i++) {
            lore.add(counterLine(progress.get(i), i < objectives.size() ? objectives.get(i) : null));
            if (i < objectives.size()) {
                addObjectiveHint(lore, playerId, quest, state, stepView.stepId(), objectives.get(i), i);
            }
        }
        if (progress.size() > shown) {
            lore.add(MM.deserialize("<dark_gray>+<n> autre(s) — voir les détails</dark_gray>",
                    Placeholder.unparsed("n", String.valueOf(progress.size() - shown))));
        }

        lore.add(Component.empty());
        lore.add(MM.deserialize("<yellow>Clic gauche</yellow> <gray>: détails</gray>"));
        lore.add(MM.deserialize("<yellow>Clic droit</yellow> <gray>: <hint></gray>",
                Placeholder.unparsed("hint", tracked ? "ne plus suivre" : "suivre")));
        return lore;
    }

    /**
     * Une ligne de compteur, courte : « Araignée 3/5 ». Le nom vient de la clé de traduction
     * vanilla (voir {@link ObjectiveLabels}), donc le client l'affiche en français — jamais
     * « Tuer SPIDER ». Un objectif binaire (parler, atteindre) n'affiche pas de compteur.
     */
    private Component counterLine(ObjectiveProgressView progress, QuestObjective objective) {
        boolean done = progress.current() >= progress.total();
        // Issue #475 : une remise nomme son PNJ destinataire (« à remettre à Guide »), même règle
        // que l'annonce des objectifs (#70) — le joueur sait à qui apporter les objets restants.
        Component name = objective != null
                ? ObjectiveLabels.targetName(objective, questProgressEngine.npcNameResolver())
                : Component.text(progress.description());
        Component line = Component.text("• ", done ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .append(name.colorIfAbsent(done ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        if (objective == null || ObjectiveLabels.isCountable(objective)) {
            line = line.append(Component.text(" " + progress.current() + "/" + progress.total(),
                    done ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        }
        return line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /** Objectifs de l'étape {@code stepId} tels que définis par la quête (ordre identique à la vue). */
    private List<QuestObjective> objectivesOf(QuestDefinition quest, String stepId) {
        for (QuestStep step : quest.steps()) {
            if (step.id().equals(stepId)) {
                return step.objectives();
            }
        }
        return List.of();
    }

    /** Issue #475 : {@code %npc_name:<id>%} → nom affiché actuel du PNJ (renommage propagé au rendu). */
    private String npcNamed(String raw) {
        return com.lodygames.rpgquest.npc.NpcNameTokens.resolve(raw, questProgressEngine.npcNameResolver());
    }

    /**
     * Objets de la vue <strong>détails</strong> (issue #475, retour du 17/10) : tout ce qui a été
     * retiré de la liste, réparti en sections — la quête, puis description, objectifs, récompenses
     * et prérequis — et chaque section en pages de {@link #MAX_DETAIL_LORE_LINES} lignes. Aucune
     * infobulle ne dépasse donc la hauteur d'une petite fenêtre ; rien n'est tronqué.
     */
    private List<ItemStack> buildDetailItems(UUID playerId, QuestDefinition quest, QuestState state,
                                             Duration cooldown, boolean tracked) {
        List<ItemStack> items = new ArrayList<>();
        List<Component> summary = new ArrayList<>();
        summary.add(MM.deserialize("<white>État :</white> " + stateLabel(state)));
        addContextLine(summary, quest);
        summary.add(MM.deserialize("<white>Catégorie :</white> <gray><category></gray>",
                Placeholder.unparsed("category", quest.category())));
        addReplayLines(summary, quest, state, cooldown);
        JournalReplayText.cadenceLine(quest).map(MM::deserialize).ifPresent(summary::add);
        summary.add(Component.empty());
        summary.add(MM.deserialize("<dark_gray>Survole les objets à droite :</dark_gray>"));
        summary.add(MM.deserialize("<dark_gray>description, objectifs, récompenses.</dark_gray>"));
        addPages(items, quest.icon(), buildTitle(quest, state, tracked), summary);

        // Issue #475 : la description est repliée (une ligne de lore n'est jamais coupée par le
        // client, elle débordait de l'écran) et ses PNJ cités par id portent leur nom actuel.
        List<Component> description = new ArrayList<>();
        for (String line : LoreWrap.wrap(npcNamed(quest.description().base()), LoreWrap.DEFAULT_WIDTH)) {
            description.add(MM.deserialize("<gray>" + line + "</gray>"));
        }
        addPages(items, Material.WRITABLE_BOOK, MM.deserialize("<white>Description</white>"), description);

        QuestStepProgressView stepView = stepViewFor(playerId, quest, state);
        List<QuestObjective> objectives = objectivesOf(quest, stepView.stepId());
        List<Component> objectiveLines = new ArrayList<>();
        for (int i = 0; i < stepView.objectives().size(); i++) {
            objectiveLines.add(counterLine(stepView.objectives().get(i),
                    i < objectives.size() ? objectives.get(i) : null));
            if (i < objectives.size()) {
                addObjectiveHint(objectiveLines, playerId, quest, state, stepView.stepId(), objectives.get(i), i);
            }
        }
        addPages(items, Material.TARGET, MM.deserialize("<white>Objectifs</white>"), objectiveLines);

        List<Component> rewards = new ArrayList<>();
        for (QuestReward reward : quest.rewards()) {
            describeReward(reward).ifPresent(r -> rewards.add(Component.text("- ", NamedTextColor.GRAY)
                    .append(r.colorIfAbsent(NamedTextColor.GRAY))));
        }
        addPages(items, Material.CHEST, MM.deserialize("<white>Récompenses</white>"), rewards);

        List<Component> prerequisites = new ArrayList<>();
        for (NamespacedKey prereqId : quest.prerequisites()) {
            String name = questEngine.find(prereqId).map(q -> q.title().base()).orElse(prereqId.toString());
            prerequisites.add(MM.deserialize("<gray>- <name></gray>", Placeholder.parsed("name", name)));
        }
        addPages(items, Material.PAPER, MM.deserialize("<white>Prérequis</white>"), prerequisites);
        return items;
    }

    /** Une section en pages bornées : « Objectifs », puis « Objectifs (2/2) »… ; rien si elle est vide. */
    private static void addPages(List<ItemStack> items, Material icon, Component title, List<Component> lines) {
        List<List<Component>> pages = LorePages.split(lines, MAX_DETAIL_LORE_LINES);
        for (int i = 0; i < pages.size(); i++) {
            Component pageTitle = pages.size() == 1 ? title
                    : title.append(Component.text(" (" + (i + 1) + "/" + pages.size() + ")", NamedTextColor.GRAY));
            List<Component> lore = pages.get(i).stream()
                    .map(line -> line.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false))
                    .toList();
            items.add(detailItem(icon, pageTitle.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                    false), lore));
        }
    }

    /** Issue #475 — « Amélioration du kit — palier 2 » sous l'état, quand la quête en a une. */
    private void addContextLine(List<Component> lore, QuestDefinition quest) {
        contextLabels.apply(quest.id()).filter(label -> !label.isBlank()).ifPresent(label ->
                lore.add(MM.deserialize("<aqua><label></aqua>", Placeholder.unparsed("label", label))));
    }

    /**
     * Issue #13 : étiquette « Rencontre » et, pour une quête renouvelable terminée, quand la refaire.
     * {@code cooldown} vaut {@link Duration#ZERO} quand la lecture des délais a réussi sans trouver
     * de recharge, {@code null} quand elle a échoué : dans ce cas rien n'est promis au joueur.
     */
    private void addReplayLines(List<Component> lore, QuestDefinition quest, QuestState state, Duration cooldown) {
        JournalReplayText.encounterTag(quest).map(MM::deserialize).ifPresent(lore::add);
        if (cooldown != null) {
            JournalReplayText.replayLine(quest, state, cooldown).map(MM::deserialize).ifPresent(lore::add);
        }
    }

    private String stateLabel(QuestState state) {
        return switch (state) {
            case NOT_STARTED -> "<gray>Non commencée</gray>";
            case ACTIVE -> "<yellow>Active</yellow>";
            case READY_TO_TURN_IN -> "<gold>Prête à remettre</gold>";
            case COMPLETED -> "<green>Terminée</green>";
            case FAILED -> "<red>Échouée</red>";
            case ABANDONED -> "<gray>Abandonnée</gray>";
        };
    }

    private QuestStepProgressView stepViewFor(UUID playerId, QuestDefinition quest, QuestState state) {
        if (state == QuestState.ACTIVE || state == QuestState.READY_TO_TURN_IN) {
            Optional<QuestStepProgressView> view = questProgressEngine.activeStepView(playerId, quest.id());
            if (view.isPresent()) {
                return view.get();
            }
        }
        return fallbackStepView(quest, state);
    }

    private QuestStepProgressView fallbackStepView(QuestDefinition quest, QuestState state) {
        QuestStep step = quest.steps().get(0);
        boolean done = state == QuestState.COMPLETED;
        List<ObjectiveProgressView> objectives = new ArrayList<>();
        for (QuestObjective objective : step.objectives()) {
            int total = QuestObjective.requiredAmount(objective);
            objectives.add(new ObjectiveProgressView(
                    QuestObjective.describe(objective, questProgressEngine.npcNameResolver()), done ? total : 0, total));
        }
        return new QuestStepProgressView(step.id(), objectives);
    }

    /**
     * Récompense telle qu'elle doit être <strong>montrée au joueur</strong>, ou vide si elle ne
     * doit pas l'être.
     *
     * <p>Deux choix assumés : le nom de l'objet vient de sa clé de traduction vanilla (« Épée en
     * fer », pas {@code IRON_SWORD}), et une récompense {@code VARIABLE} n'est <strong>jamais</strong>
     * affichée — c'est un état interne (ex. {@code CLAIM_TIER_1}, un droit débloqué) dont
     * l'identifiant n'a aucun sens pour un joueur et ne doit pas fuiter dans une infobulle.</p>
     */
    private Optional<Component> describeReward(QuestReward reward) {
        return switch (reward) {
            case ExperienceReward r -> Optional.of(Component.text(r.amount() + " XP"));
            case ItemReward r -> Optional.of(Component.text(r.amount() + "× ")
                    .append(Component.translatable(r.material())));
            case VariableReward r -> Optional.empty();
            case CommandReward r -> Optional.of(Component.text("Bonus spécial"));
            // Issue #16 : annoncé comme PRÉVU, pas comme reçu. C'est bien l'objet de cette
            // infobulle (elle décrit la définition de la quête, pas l'historique du joueur) ; la
            // confirmation du crédit, elle, arrive dans le chat et seulement après la base.
            case MoneyReward r -> Optional.of(Component.text(CurrencyName.amount(r.amount())));
            // Issue #166 : prévu, une seule fois par joueur et par clé de droit.
            case HeartReward r -> Optional.of(Component.text("+" + r.hearts() + " ❤ vie maximale"));
            // Issue #177 : une chance, pas une promesse — le gain réel s'affiche à la remise.
            case com.lodygames.rpgquest.quest.model.TableReward r -> Optional.of(Component.text("Tirage au sort"));
        };
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /**
     * Fermer l'inventaire directement depuis le clic sur « Fermer » ne fonctionne pas de façon fiable :
     * on est encore à l'intérieur du traitement de {@code InventoryClickEvent} (juste annulé), et le
     * paquet de resynchronisation que le serveur envoie ensuite pour ce clic annulé peut arriver après
     * le paquet de fermeture et faire réapparaître le menu côté client. Reporter la fermeture au tick
     * suivant garantit qu'aucun paquet lié au clic n'est plus en vol quand elle a lieu.
     */
    private void closeNextTick(Player player) {
        runOnMainThread(player::closeInventory);
    }

    private static int[] buildContentSlots() {
        int[] slots = new int[JournalPagination.PAGE_SIZE];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = CONTENT_START_SLOT + i;
        }
        return slots;
    }
}
