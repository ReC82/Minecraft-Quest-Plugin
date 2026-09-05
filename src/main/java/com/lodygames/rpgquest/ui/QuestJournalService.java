package com.lodygames.rpgquest.ui;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.config.JournalConfig;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.VariableReward;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;

/**
 * Implémentation {@link QuestJournalUi} : un inventaire paginé (onglets
 * actives/disponibles/terminées) accessible via {@code /quests}, plus une
 * vue détail par quête et un suivi persistant (bossbar optionnelle). Toute
 * la logique de clic vit ici ; {@link QuestJournalListener} ne fait que
 * traduire les événements Bukkit en appels à cette classe (même
 * organisation que {@code DialogueSessionEngine}/{@code
 * DialogueNpcInteractListener}).
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

    static final int TAB_ACTIVE_SLOT = 0;
    static final int TAB_AVAILABLE_SLOT = 1;
    static final int TAB_COMPLETED_SLOT = 2;
    static final int PREV_PAGE_SLOT = 3;
    private static final int PAGE_INDICATOR_SLOT = 4;
    static final int NEXT_PAGE_SLOT = 5;
    static final int CLOSE_SLOT = 8;
    private static final int CONTENT_START_SLOT = 9;
    static final int[] CONTENT_SLOTS = buildContentSlots();

    static final int DETAIL_ICON_SLOT = 13;
    static final int DETAIL_BACK_SLOT = 18;
    static final int DETAIL_TRACK_SLOT = 22;
    static final int DETAIL_CLOSE_SLOT = 26;

    private static final String TRACKED_QUEST_KEY = "__tracked_quest__";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final YamlQuestEngine questEngine;
    private final QuestProgressEngine questProgressEngine;
    private final PlayerVariableRepository variableRepository;
    private final TrackedQuestDisplay trackedDisplay;
    private final Logger logger;

    private final Map<UUID, JournalSession> openSessions = new ConcurrentHashMap<>();
    private final Map<UUID, NamespacedKey> trackedByPlayer = new ConcurrentHashMap<>();

    public QuestJournalService(RPGQuestPlugin plugin, YamlQuestEngine questEngine, QuestProgressEngine questProgressEngine,
                                PlayerVariableRepository variableRepository, JournalConfig config) {
        this.plugin = plugin;
        this.questEngine = questEngine;
        this.questProgressEngine = questProgressEngine;
        this.variableRepository = variableRepository;
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

    /** Listener Bukkit unique (clic/drag/fermeture/connexion/déconnexion) à enregistrer via {@code PlayerListenerService}. */
    public Listener listener() {
        return new QuestJournalListener(this);
    }

    /** {@code /quests} : ouvre toujours sur l'onglet « actives », première page. */
    public void open(Player player) {
        showList(player, JournalTab.ACTIVE, 0);
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
        trackedByPlayer.remove(playerId);
        trackedDisplay.clear(player);
    }

    void handleClose(Player player) {
        openSessions.remove(player.getUniqueId());
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
            if (session == null) {
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
        trackedDisplay.update(player, questOpt.get(), stepView.get());
    }

    // ---- Vue liste ----------------------------------------------------------

    void showList(Player player, JournalTab tab, int requestedPage) {
        UUID playerId = player.getUniqueId();
        questProgressEngine.allStates(playerId).thenAccept(states -> runOnMainThread(() -> {
            List<QuestDefinition> matching = questEngine.quests().stream()
                    .filter(quest -> tabMatches(tab, states.getOrDefault(quest.id(), QuestState.NOT_STARTED)))
                    .sorted(Comparator.comparing(quest -> quest.title().base(), String.CASE_INSENSITIVE_ORDER))
                    .toList();

            int pageCount = JournalPagination.pageCount(matching.size());
            int page = JournalPagination.clampPage(requestedPage, pageCount);
            List<QuestDefinition> pageItems = JournalPagination.pageOf(matching, page);
            List<NamespacedKey> pageIds = pageItems.stream().map(QuestDefinition::id).toList();

            openSessions.put(playerId, JournalSession.list(tab, page, pageIds));

            JournalInventoryHolder holder = new JournalInventoryHolder(JournalKind.LIST);
            Inventory inventory = Bukkit.createInventory(holder, LIST_SIZE, MM.deserialize(TITLE_LIST));
            holder.bind(inventory);

            renderChrome(inventory, tab, page, pageCount);
            for (int i = 0; i < pageItems.size(); i++) {
                QuestDefinition quest = pageItems.get(i);
                QuestState state = states.getOrDefault(quest.id(), QuestState.NOT_STARTED);
                boolean tracked = quest.id().equals(trackedByPlayer.get(playerId));
                inventory.setItem(CONTENT_SLOTS[i], buildIcon(playerId, quest, state, tracked));
            }
            player.openInventory(inventory);
        })).exceptionally(error -> {
            logger.error("Impossible de construire le journal de quêtes pour {}", playerId, error);
            return null;
        });
    }

    private boolean tabMatches(JournalTab tab, QuestState state) {
        return switch (tab) {
            case ACTIVE -> state == QuestState.ACTIVE || state == QuestState.READY_TO_TURN_IN;
            case COMPLETED -> state == QuestState.COMPLETED;
            case AVAILABLE -> state == QuestState.NOT_STARTED || state == QuestState.ABANDONED;
        };
    }

    private void renderChrome(Inventory inventory, JournalTab tab, int page, int pageCount) {
        inventory.setItem(TAB_ACTIVE_SLOT, tabIcon("Actives", Material.BOOK, tab == JournalTab.ACTIVE));
        inventory.setItem(TAB_AVAILABLE_SLOT, tabIcon("Disponibles", Material.WRITABLE_BOOK, tab == JournalTab.AVAILABLE));
        inventory.setItem(TAB_COMPLETED_SLOT, tabIcon("Terminées", Material.ENCHANTED_BOOK, tab == JournalTab.COMPLETED));
        if (page > 0) {
            inventory.setItem(PREV_PAGE_SLOT, navIcon(Material.ARROW, "« Page précédente"));
        }
        inventory.setItem(PAGE_INDICATOR_SLOT, pageIndicatorIcon(page, pageCount));
        if (page < pageCount - 1) {
            inventory.setItem(NEXT_PAGE_SLOT, navIcon(Material.ARROW, "Page suivante »"));
        }
        inventory.setItem(CLOSE_SLOT, navIcon(Material.BARRIER, "Fermer"));
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

        questProgressEngine.allStates(playerId).thenAccept(states -> runOnMainThread(() -> {
            QuestDefinition quest = questOpt.get();
            QuestState state = states.getOrDefault(questId, QuestState.NOT_STARTED);
            boolean tracked = questId.equals(trackedByPlayer.get(playerId));

            openSessions.put(playerId, JournalSession.detail(tab, page, questId));

            JournalInventoryHolder holder = new JournalInventoryHolder(JournalKind.DETAIL);
            Inventory inventory = Bukkit.createInventory(holder, DETAIL_SIZE, MM.deserialize(TITLE_DETAIL));
            holder.bind(inventory);

            inventory.setItem(DETAIL_ICON_SLOT, buildIcon(playerId, quest, state, tracked));
            inventory.setItem(DETAIL_BACK_SLOT, navIcon(Material.ARROW, "« Retour"));
            inventory.setItem(DETAIL_TRACK_SLOT, trackToggleIcon(tracked));
            inventory.setItem(DETAIL_CLOSE_SLOT, navIcon(Material.BARRIER, "Fermer"));

            player.openInventory(inventory);
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
        if (slot == TAB_ACTIVE_SLOT) {
            showList(player, JournalTab.ACTIVE, 0);
            return;
        }
        if (slot == TAB_AVAILABLE_SLOT) {
            showList(player, JournalTab.AVAILABLE, 0);
            return;
        }
        if (slot == TAB_COMPLETED_SLOT) {
            showList(player, JournalTab.COMPLETED, 0);
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

    private ItemStack buildIcon(UUID playerId, QuestDefinition quest, QuestState state, boolean tracked) {
        ItemStack stack = new ItemStack(quest.icon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(buildTitle(quest, state, tracked));
        meta.lore(buildLore(playerId, quest, state, tracked));
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
        return MM.deserialize(color + "<name>" + colorEnd + star, Placeholder.parsed("name", quest.title().base()));
    }

    private List<Component> buildLore(UUID playerId, QuestDefinition quest, QuestState state, boolean tracked) {
        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<gray><description></gray>", Placeholder.parsed("description", quest.description().base())));
        lore.add(Component.empty());
        lore.add(MM.deserialize("<white>Catégorie :</white> <gray><category></gray>",
                Placeholder.unparsed("category", quest.category())));
        lore.add(MM.deserialize("<white>État :</white> " + stateLabel(state)));

        QuestStepProgressView stepView = stepViewFor(playerId, quest, state);
        lore.add(Component.empty());
        lore.add(MM.deserialize("<white>Étape :</white> <gray><step></gray>", Placeholder.unparsed("step", stepView.stepId())));
        for (ObjectiveProgressView objective : stepView.objectives()) {
            lore.add(MM.deserialize("<gray>- <desc> (<current>/<total>)</gray>",
                    Placeholder.unparsed("desc", objective.description()),
                    Placeholder.unparsed("current", String.valueOf(objective.current())),
                    Placeholder.unparsed("total", String.valueOf(objective.total()))));
        }

        if (!quest.rewards().isEmpty()) {
            lore.add(Component.empty());
            lore.add(MM.deserialize("<white>Récompenses :</white>"));
            for (QuestReward reward : quest.rewards()) {
                lore.add(MM.deserialize("<gray>- <reward></gray>", Placeholder.unparsed("reward", describeReward(reward))));
            }
        }

        if (!quest.prerequisites().isEmpty()) {
            lore.add(Component.empty());
            lore.add(MM.deserialize("<white>Prérequis :</white>"));
            for (NamespacedKey prereqId : quest.prerequisites()) {
                String name = questEngine.find(prereqId).map(q -> q.title().base()).orElse(prereqId.toString());
                lore.add(MM.deserialize("<gray>- <name></gray>", Placeholder.parsed("name", name)));
            }
        }

        lore.add(Component.empty());
        lore.add(MM.deserialize("<yellow>Clic gauche</yellow> <gray>: détails</gray>"));
        lore.add(MM.deserialize("<yellow>Clic droit</yellow> <gray>: <hint></gray>",
                Placeholder.unparsed("hint", tracked ? "ne plus suivre" : "suivre")));
        return lore;
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
            objectives.add(new ObjectiveProgressView(QuestObjective.describe(objective), done ? total : 0, total));
        }
        return new QuestStepProgressView(step.id(), objectives);
    }

    private String describeReward(QuestReward reward) {
        return switch (reward) {
            case ExperienceReward r -> r.amount() + " XP";
            case ItemReward r -> r.amount() + "x " + r.material();
            case VariableReward r -> "Variable " + r.key();
            case CommandReward r -> "Bonus spécial";
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
