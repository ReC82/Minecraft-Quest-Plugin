package com.lodygames.rpgquest.web.agent;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.content.pack.ContentFamily;
import com.lodygames.rpgquest.content.pack.ContentPack;
import com.lodygames.rpgquest.content.pack.ContentPackAssembler;
import com.lodygames.rpgquest.content.pack.ContentPackMapper;
import com.lodygames.rpgquest.content.pack.ContentPackSerializer;
import com.lodygames.rpgquest.content.reload.ContentReloadService;
import com.lodygames.rpgquest.content.reload.ReloadFamily;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.database.NpcSkinSourceRepository;
import com.lodygames.rpgquest.database.WalletRepository;
import com.lodygames.rpgquest.economy.EconomyService;
import com.lodygames.rpgquest.economy.QuestRewardDue;
import com.lodygames.rpgquest.permission.LuckPermsBridge;
import com.lodygames.rpgquest.permission.ManagedNode;
import com.lodygames.rpgquest.economy.TransactionType;
import com.lodygames.rpgquest.dialogue.DialogueCatalog;
import com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor;
import com.lodygames.rpgquest.dialogue.DialogueDefinitionStore;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.model.AdvanceQuestAction;
import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.DeliverQuestItemsAction;
import com.lodygames.rpgquest.dialogue.model.DialogueAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueDraft;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.GiveItemAction;
import com.lodygames.rpgquest.dialogue.model.GiveStarterKitAction;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.OpenDialogueAction;
import com.lodygames.rpgquest.dialogue.model.OpenMerchantAction;
import com.lodygames.rpgquest.dialogue.model.PendingDeliveryCondition;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.RunSafeCommandAction;
import com.lodygames.rpgquest.dialogue.model.SetVariableAction;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.dialogue.model.TakeItemAction;
import com.lodygames.rpgquest.dialogue.model.TurnInQuestAction;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.ops.ServerLogBuffer;
import com.lodygames.rpgquest.ops.ServerOpsService;
import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.mob.MobSpawnSettings;
import com.lodygames.rpgquest.mob.MobSpawnSettingsStore;
import com.lodygames.rpgquest.mob.SpecialMobDefinitionStore;
import com.lodygames.rpgquest.mob.SpecialMobLoadIssue;
import com.lodygames.rpgquest.mob.SpecialMobLoadReport;
import com.lodygames.rpgquest.mob.SpecialMobRegistry;
import com.lodygames.rpgquest.mob.SpecialMobService;
import com.lodygames.rpgquest.mob.model.EnragedAbility;
import com.lodygames.rpgquest.mob.model.MobAbility;
import com.lodygames.rpgquest.mob.model.MobCategory;
import com.lodygames.rpgquest.mob.model.SpecialMobDefinition;
import com.lodygames.rpgquest.mob.model.SummonOnDamageAbility;
import com.lodygames.rpgquest.npc.CitizensNpc;
import com.lodygames.rpgquest.npc.CitizensSpawnCoordinator;
import com.lodygames.rpgquest.npc.CitizensSpawnPlanner;
import com.lodygames.rpgquest.npc.NpcCatalog;
import com.lodygames.rpgquest.npc.NpcDefinitionStore;
import com.lodygames.rpgquest.npc.BukkitNpcPlacementProbe;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner;
import com.lodygames.rpgquest.npc.SkinPreservationPlanner;
import com.lodygames.rpgquest.npc.NpcLoadIssue;
import com.lodygames.rpgquest.npc.QuestGiverStore;
import com.lodygames.rpgquest.npc.YamlNpcEngine;
import com.lodygames.rpgquest.npc.model.NpcDefinition;
import com.lodygames.rpgquest.player.PlayerResetService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.DeliverItemToNpcObjective;
import com.lodygames.rpgquest.quest.model.DiscoverWaypointObjective;
import com.lodygames.rpgquest.quest.model.SmeltItemObjective;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.CraftItemObjective;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
import com.lodygames.rpgquest.quest.model.MoneyReward;
import com.lodygames.rpgquest.quest.model.KillEntityObjective;
import com.lodygames.rpgquest.quest.model.PlaceBlockObjective;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestObjective;
import com.lodygames.rpgquest.quest.model.QuestReward;
import com.lodygames.rpgquest.quest.model.ReachLocationObjective;
import com.lodygames.rpgquest.quest.model.TalkToNpcObjective;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.model.QuestStep;
import com.lodygames.rpgquest.quest.model.VariableReward;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import com.lodygames.rpgquest.story.StoryService;
import com.lodygames.rpgquest.story.model.StoryDefinition;
import com.lodygames.rpgquest.travel.beacon.TravelBeaconService;
import com.lodygames.rpgquest.travel.beacon.model.TravelBeacon;
import com.lodygames.rpgquest.waypoint.WaypointService;
import com.lodygames.rpgquest.waypoint.model.Waypoint;
import io.papermc.paper.ban.BanListType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Implémentation réelle de {@link AgentActions} : chaque opération délègue à un service métier
 * existant du plugin (issue #51 / outillage Control Panel). Toute mutation est exécutée
 * <strong>sur le thread principal</strong> (l'agent poll depuis un thread asynchrone) via
 * {@link #onMain}, exactement comme {@code RpgAdminCommand} le fait pour les mêmes services.
 *
 * <p>Rien ici ne reproduit la logique de quête / story / reset : on appelle {@link
 * QuestProgressEngine#accept}, {@link QuestProgressEngine#forceComplete},
 * {@link QuestProgressEngine#resetQuest}, {@link StoryService#adminAdvance},
 * {@link StoryService#adminComplete}, {@link PlayerResetService#previewReset} /
 * {@link PlayerResetService#resetToNewPlayer}, {@link YamlCustomItemRegistry#create}.</p>
 */
public final class BukkitAgentActions implements AgentActions {

    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final int MAX_GIVE_AMOUNT = 64;

    private final RPGQuestPlugin plugin;
    private final YamlQuestEngine questEngine;
    private final QuestProgressEngine questProgressEngine;
    private final StoryService storyService;
    private final YamlCustomItemRegistry customItemRegistry;
    private final PlayerResetService playerResetService;
    private final PlayerVariableWriter variableWriter;
    private final YamlDialogueEngine dialogueEngine;
    private final NpcIdentityService npcIdentityService;
    private final NpcBindingRepository npcBindingRepository;
    /** Source d'apparence enregistrée par RPGQuest, pour la reconduire au travers d'un renommage. */
    private final NpcSkinSourceRepository npcSkinSourceRepository;
    /** Même forme d'identifiant logique que l'exécuteur et le panel. */
    private static final java.util.regex.Pattern NPC_ID_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9._-]{1,64}");
    private final YamlNpcEngine npcEngine;
    private final NpcDefinitionStore npcStore;
    private final QuestGiverStore questGiverStore;
    private final Supplier<Set<String>> allowedSpawnWorlds;
    private final DialogueDefinitionStore dialogueStore;
    private final DialogueDefinitionEditor dialogueEditor;
    private final WaypointService waypointService;
    private final TravelBeaconService travelBeaconService;
    private final SpecialMobRegistry mobRegistry;
    private final SpecialMobService mobService;
    private final SpecialMobDefinitionStore mobDefinitionStore;
    private final MobSpawnSettingsStore mobSpawnSettingsStore;
    private final Supplier<String> wildWorldSupplier;
    /** Réglages du Hub : monde, id logique du Guide, bornes de recherche d'emplacement. */
    private final Supplier<com.lodygames.rpgquest.config.HubConfig> hubConfig;
    /** Issue #95 — annonce globale et tampon de console (exploitation serveur). */
    private final ServerOpsService serverOpsService;
    /** Issue #131 — service central de rechargement du contenu. */
    private final ContentReloadService contentReloadService;
    /**
     * Issue #210 — position sûre du Hub pour le renvoi d'un joueur. Même source que la Pierre de
     * retour et le filet de sécurité des claims : jamais une coordonnée figée.
     */
    private final Supplier<java.util.Optional<org.bukkit.Location>> hubRescueTarget;
    /** Issue #140 — service monétaire et journal. {@code null} = économie indisponible, dit comme tel. */
    private final EconomyService economyService;
    private final WalletRepository walletRepository;
    /** Issue #194 — suppression d'une définition de quête/story sur le serveur, avec sauvegarde. */
    private final com.lodygames.rpgquest.content.ContentDefinitionDeleter contentDeleter;
    /**
     * Issue #200 — pont vers LuckPerms. Construit ici et non injecté : il est sans état, et son
     * indisponibilité (LuckPerms absent) est un état normal qu'il rapporte lui-même.
     */
    private final LuckPermsBridge luckPermsBridge;

    /** Issue #213 — emplacements de construction. Source de vérité : sa base, pas ce cache-ci. */
    private final com.lodygames.rpgquest.building.BuildingSiteService buildingSiteService;
    private final com.lodygames.rpgquest.building.BuildingLibrary buildingLibraryService;
    private final com.lodygames.rpgquest.building.BuildingPlacementService buildingPlacements;
    private final com.lodygames.rpgquest.building.SchematicGateway schematicGateway;

    public BukkitAgentActions(RPGQuestPlugin plugin, YamlQuestEngine questEngine,
                              QuestProgressEngine questProgressEngine, StoryService storyService,
                              YamlCustomItemRegistry customItemRegistry, PlayerResetService playerResetService,
                              PlayerVariableWriter variableWriter, YamlDialogueEngine dialogueEngine,
                              NpcIdentityService npcIdentityService, NpcBindingRepository npcBindingRepository,
                              NpcSkinSourceRepository npcSkinSourceRepository,
                              YamlNpcEngine npcEngine, NpcDefinitionStore npcStore, QuestGiverStore questGiverStore,
                              Supplier<Set<String>> allowedSpawnWorlds, DialogueDefinitionStore dialogueStore,
                              DialogueDefinitionEditor dialogueEditor, WaypointService waypointService,
                              TravelBeaconService travelBeaconService, SpecialMobRegistry mobRegistry,
                              SpecialMobService mobService, SpecialMobDefinitionStore mobDefinitionStore,
                              MobSpawnSettingsStore mobSpawnSettingsStore, Supplier<String> wildWorldSupplier,
                              Supplier<com.lodygames.rpgquest.config.HubConfig> hubConfig,
                              ServerOpsService serverOpsService,
                              ContentReloadService contentReloadService,
                              Supplier<java.util.Optional<org.bukkit.Location>> hubRescueTarget,
                              EconomyService economyService, WalletRepository walletRepository,
                              com.lodygames.rpgquest.building.BuildingSiteService buildingSiteService,
                              com.lodygames.rpgquest.building.BuildingLibrary buildingLibraryService,
                              com.lodygames.rpgquest.building.BuildingPlacementService buildingPlacements,
                              com.lodygames.rpgquest.building.SchematicGateway schematicGateway) {
        this.plugin = plugin;
        this.questEngine = questEngine;
        this.questProgressEngine = questProgressEngine;
        this.storyService = storyService;
        this.customItemRegistry = customItemRegistry;
        this.playerResetService = playerResetService;
        this.variableWriter = variableWriter;
        this.dialogueEngine = dialogueEngine;
        this.npcIdentityService = npcIdentityService;
        this.npcBindingRepository = npcBindingRepository;
        this.npcSkinSourceRepository = npcSkinSourceRepository;
        this.npcEngine = npcEngine;
        this.npcStore = npcStore;
        this.questGiverStore = questGiverStore;
        this.allowedSpawnWorlds = allowedSpawnWorlds;
        this.dialogueStore = dialogueStore;
        this.dialogueEditor = dialogueEditor;
        this.waypointService = waypointService;
        this.travelBeaconService = travelBeaconService;
        this.mobRegistry = mobRegistry;
        this.mobService = mobService;
        this.mobDefinitionStore = mobDefinitionStore;
        this.mobSpawnSettingsStore = mobSpawnSettingsStore;
        this.wildWorldSupplier = wildWorldSupplier;
        this.hubConfig = hubConfig;
        this.serverOpsService = serverOpsService;
        this.contentReloadService = contentReloadService;
        this.hubRescueTarget = hubRescueTarget;
        this.economyService = economyService;
        this.walletRepository = walletRepository;
        this.buildingSiteService = buildingSiteService;
        this.buildingLibraryService = buildingLibraryService;
        this.buildingPlacements = buildingPlacements;
        this.schematicGateway = schematicGateway;
        // Issue #194 : dossiers réels du plugin, et sauvegardes HORS des dossiers de contenu pour
        // ne jamais être relues comme des définitions.
        java.nio.file.Path data = plugin.getDataFolder().toPath();
        this.contentDeleter = new com.lodygames.rpgquest.content.ContentDefinitionDeleter(
                data.resolve("quests"), data.resolve("stories"),
                data.resolve("content-backups"));
        this.luckPermsBridge = new LuckPermsBridge(plugin.getSLF4JLogger());
    }

    // ---- Lectures -------------------------------------------------------------------------------

    @Override
    public CompletableFuture<List<PlayerSummary>> onlinePlayers() {
        return onMain(() -> {
            List<PlayerSummary> list = new ArrayList<>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Location l = p.getLocation();
                list.add(new PlayerSummary(
                        p.getUniqueId().toString(), p.getName(),
                        l.getWorld() == null ? "?" : l.getWorld().getName(),
                        l.getBlockX(), l.getBlockY(), l.getBlockZ()));
            }
            return CompletableFuture.completedFuture(List.copyOf(list));
        });
    }

    @Override
    public CompletableFuture<List<PlayerCatalogEntry>> playerCatalog(int limit) {
        // 1) instantané des connectés sur le thread principal (Location = API main-thread) ;
        // 2) parcours de getOfflinePlayers() (lecture disque) sur un thread asynchrone Bukkit —
        //    jamais sur le thread principal. Aucune écriture, aucune commande.
        return onMain(() -> {
            Map<UUID, int[]> onlinePos = new HashMap<>();
            Map<UUID, String> onlineWorld = new HashMap<>();
            Map<UUID, String> onlineName = new HashMap<>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                Location l = p.getLocation();
                onlinePos.put(p.getUniqueId(), new int[] {l.getBlockX(), l.getBlockY(), l.getBlockZ()});
                onlineWorld.put(p.getUniqueId(), l.getWorld() == null ? "?" : l.getWorld().getName());
                onlineName.put(p.getUniqueId(), p.getName());
            }
            return done(new OnlineSnapshot(onlinePos, onlineWorld, onlineName));
        }).thenCompose(snap -> async(() -> {
            Map<UUID, PlayerCatalogEntry> byUuid = new LinkedHashMap<>();
            ProfileBanList profileBans = plugin.getServer().getBanList(BanListType.PROFILE);

            for (OfflinePlayer op : plugin.getServer().getOfflinePlayers()) {
                UUID id = op.getUniqueId();
                if (id == null || byUuid.containsKey(id)) {
                    continue;
                }
                boolean online = snap.world().containsKey(id) || op.isOnline();
                String name = op.getName() != null ? op.getName() : snap.name().get(id);
                Long first = positiveOrNull(op.getFirstPlayed());
                Long last = online ? System.currentTimeMillis() : positiveOrNull(lastSeenMillis(op));
                boolean banned = op.isBanned();
                String banReason = banned ? banReason(profileBans, op) : null;
                int[] pos = snap.pos().get(id);
                byUuid.put(id, new PlayerCatalogEntry(
                        id.toString(), name, online, op.hasPlayedBefore(), first, last, banned, banReason,
                        online ? snap.world().get(id) : null,
                        pos == null ? null : pos[0], pos == null ? null : pos[1], pos == null ? null : pos[2],
                        op.isOp(), op.isWhitelisted()));
            }
            // Filet : un connecté sans fichier playerdata encore écrit (rare) ne doit pas manquer.
            for (Map.Entry<UUID, String> e : snap.world().entrySet()) {
                UUID id = e.getKey();
                if (byUuid.containsKey(id)) {
                    continue;
                }
                int[] pos = snap.pos().get(id);
                OfflinePlayer op = plugin.getServer().getOfflinePlayer(id);
                byUuid.put(id, new PlayerCatalogEntry(
                        id.toString(), snap.name().get(id), true, true, positiveOrNull(op.getFirstPlayed()),
                        System.currentTimeMillis(), op.isBanned(),
                        op.isBanned() ? banReason(profileBans, op) : null,
                        e.getValue(), pos == null ? null : pos[0], pos == null ? null : pos[1],
                        pos == null ? null : pos[2], op.isOp(), op.isWhitelisted()));
            }
            List<PlayerCatalogEntry> out = new ArrayList<>(byUuid.values());
            if (limit > 0 && out.size() > limit) {
                // Tri minimal côté agent quand on doit tronquer : garder les plus récents / connectés.
                out.sort((a, b) -> {
                    if (a.online() != b.online()) {
                        return a.online() ? -1 : 1;
                    }
                    long la = a.lastSeen() == null ? 0L : a.lastSeen();
                    long lb = b.lastSeen() == null ? 0L : b.lastSeen();
                    return Long.compare(lb, la);
                });
                out = new ArrayList<>(out.subList(0, limit));
            }
            return List.copyOf(out);
        }));
    }

    private record OnlineSnapshot(Map<UUID, int[]> pos, Map<UUID, String> world, Map<UUID, String> name) {
    }

    private static Long positiveOrNull(long millis) {
        return millis > 0L ? millis : null;
    }

    /** Dernière présence connue : {@code getLastSeen()} (Paper), repli {@code getLastLogin()}. */
    private static long lastSeenMillis(OfflinePlayer op) {
        long seen = op.getLastSeen();
        return seen > 0L ? seen : op.getLastLogin();
    }

    private static String banReason(ProfileBanList bans, OfflinePlayer op) {
        try {
            BanEntry<PlayerProfile> entry = bans.getBanEntry(op.getPlayerProfile());
            String reason = entry == null ? null : entry.getReason();
            return reason == null || reason.isBlank() ? null : reason;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<QuestSummary> questDefinitions() {
        List<QuestSummary> out = new ArrayList<>();
        for (QuestDefinition q : questEngine.quests()) {
            if (q.secret()) {
                continue;
            }
            List<QuestStepSummary> steps = new ArrayList<>();
            for (QuestStep s : q.steps()) {
                List<String> objectives = new ArrayList<>();
                List<ObjectiveSummary> objectiveDetails = new ArrayList<>();
                for (QuestObjective o : s.objectives()) {
                    int amount = QuestObjective.requiredAmount(o);
                    String raw = QuestObjective.describe(o) + " (x" + amount + ")";
                    objectives.add(raw);
                    // Issue #185 : la portée et la règle de comptage voyagent avec l'objectif,
                    // sinon le panel afficherait une ligne qui ne dit pas ce que le joueur doit faire.
                    List<String> objectiveWorlds = o instanceof DiscoverWaypointObjective d ? d.worlds() : List.of();
                    String countMode = o instanceof DiscoverWaypointObjective d ? d.countMode().name() : null;
                    objectiveDetails.add(new ObjectiveSummary(
                            o.type().name(), objectiveTarget(o), amount, raw, objectiveNpc(o),
                            objectiveWorlds, countMode));
                }
                steps.add(new QuestStepSummary(s.id(), objectives, objectiveDetails));
            }
            out.add(new QuestSummary(
                    q.id().toString(), q.title().base(), q.category(), q.repeatable(),
                    q.prerequisites().stream().map(NamespacedKey::toString).toList(),
                    steps, describeRewards(q.rewards()), rewardDetails(q.rewards()),
                    q.giver(), null));
        }
        return out;
    }

    @Override
    public CompletableFuture<List<QuestPlayerState>> questStatus(UUID playerId) {
        return questProgressEngine.allStates(playerId).thenApply(states -> {
            List<QuestPlayerState> out = new ArrayList<>();
            for (QuestDefinition q : questEngine.quests()) {
                if (q.secret()) {
                    continue;
                }
                QuestState state = states.getOrDefault(q.id(), QuestState.NOT_STARTED);
                String currentStepId = null;
                List<ObjectiveState> objectives = List.of();
                if (state == QuestState.ACTIVE || state == QuestState.READY_TO_TURN_IN) {
                    Optional<QuestStepProgressView> view = questProgressEngine.activeStepView(playerId, q.id());
                    if (view.isPresent()) {
                        currentStepId = view.get().stepId();
                        objectives = new ArrayList<>();
                        for (ObjectiveProgressView o : view.get().objectives()) {
                            objectives.add(new ObjectiveState(o.description(), o.current(), o.total()));
                        }
                    }
                }
                out.add(new QuestPlayerState(q.id().toString(), q.title().base(), state.name(),
                        currentStepId, objectives));
            }
            return out;
        });
    }

    @Override
    public List<StorySummary> storyDefinitions() {
        List<StorySummary> out = new ArrayList<>();
        for (StoryDefinition s : storyService.stories()) {
            if (s.secret()) {
                continue;
            }
            out.add(new StorySummary(s.id(), s.name().base(),
                    s.questIds().stream().map(NamespacedKey::toString).toList()));
        }
        return out;
    }

    @Override
    public CompletableFuture<List<StoryPlayerState>> storyStatus(UUID playerId) {
        return storyService.info(playerId).thenApply(infos -> {
            List<StoryPlayerState> out = new ArrayList<>();
            for (StoryService.StoryInfo info : infos) {
                if (info.story().secret()) {
                    continue;
                }
                int total = info.story().questIds().size();
                int index = Math.min(Math.max(info.currentIndex(), 0), Math.max(total - 1, 0));
                String currentQuest = total == 0 ? null : info.story().questIds().get(index).toString();
                out.add(new StoryPlayerState(info.story().id(), info.story().name().base(),
                        info.state().name(), index + 1, total, currentQuest));
            }
            return out;
        });
    }

    @Override
    public List<ItemSummary> itemDefinitions() {
        List<ItemSummary> out = new ArrayList<>();
        for (CustomItemDefinition d : customItemRegistry.items()) {
            out.add(new ItemSummary(d.id().toString(), d.displayName(), d.type().name()));
        }
        return out;
    }

    @Override
    public ContentExportResult exportContent(String family, List<String> ids) {
        String f = family == null || family.isBlank() ? "all" : family.trim().toLowerCase(Locale.ROOT);
        ContentPackAssembler assembler = new ContentPackAssembler(
                () -> questEngine.quests().stream().map(ContentPackMapper::toQuestEntry).toList(),
                () -> storyService.stories().stream().map(ContentPackMapper::toStoryEntry).toList(),
                () -> dialogueEngine.dialogues().stream().map(ContentPackMapper::toDialogueEntry).toList(),
                () -> npcEngine.definitions().stream().map(ContentPackMapper::toNpcEntry).toList(),
                () -> plugin.getPluginMeta().getVersion());

        Instant now = Instant.now();
        ContentPack pack;
        if ("all".equals(f)) {
            pack = assembler.exportAll(now);
        } else {
            Optional<ContentFamily> resolved = ContentFamily.fromWire(f);
            if (resolved.isEmpty()) {
                return ContentExportResult.failure("Famille de contenu inconnue : « " + f + " ».");
            }
            List<String> wanted = ids == null ? List.of() : ids.stream().filter(s -> s != null && !s.isBlank()).toList();
            pack = wanted.isEmpty()
                    ? assembler.exportFamily(resolved.get(), now)
                    : assembler.exportSelection(resolved.get(), Set.copyOf(wanted), now);
        }
        String yaml = ContentPackSerializer.toYaml(pack);
        return new ContentExportResult(true, "OK", pack.format(), pack.schemaVersion(), f,
                pack.metadata().counts(), pack.totalElements(), yaml);
    }

    @Override
    public CompletableFuture<NpcCatalogView> npcDefinitions() {
        // Extraction Bukkit -> types simples, puis dérivation pure par NpcCatalog. Aucune lecture du
        // monde (position / PNJ Citizens non tagués = hors périmètre V1). Le seul accès disque est
        // le SELECT des liaisons Citizens, déjà asynchrone.
        List<NpcCatalog.DialogueLink> dialogueLinks = new ArrayList<>();
        for (DialogueDefinition d : dialogueEngine.dialogues()) {
            if (!DEFAULT_NAMESPACE.equals(d.id().getNamespace())) {
                continue;
            }
            int choiceCount = 0;
            List<String> starts = new ArrayList<>();
            for (DialogueNode node : d.nodes().values()) {
                choiceCount += node.choices().size();
                node.choices().forEach(choice -> choice.actions().stream()
                        .filter(a -> a instanceof StartQuestAction)
                        .map(a -> ((StartQuestAction) a).questId().toString())
                        .forEach(q -> {
                            if (!starts.contains(q)) {
                                starts.add(q);
                            }
                        }));
            }
            dialogueLinks.add(new NpcCatalog.DialogueLink(d.id().getKey(), d.id().toString(),
                    d.nodes().size(), choiceCount, List.copyOf(starts), d.startNode().speaker()));
        }

        List<NpcCatalog.QuestLink> questLinks = new ArrayList<>();
        for (QuestDefinition q : questEngine.quests()) {
            List<String> talk = new ArrayList<>();
            // Issue #226 : les remises comptent aussi. Un PNJ destinataire d'un
            // DELIVER_ITEM_TO_NPC n'apparaissait dans aucune colonne du catalogue, donc le
            // supprimer rendait la quête infinissable sans qu'aucun écran ne l'ait annoncé.
            List<String> deliver = new ArrayList<>();
            for (QuestStep step : q.steps()) {
                for (QuestObjective objective : step.objectives()) {
                    if (objective instanceof TalkToNpcObjective t && !talk.contains(t.npcId())) {
                        talk.add(t.npcId());
                    }
                    if (objective instanceof DeliverItemToNpcObjective d
                            && !deliver.contains(d.npcId())) {
                        deliver.add(d.npcId());
                    }
                }
            }
            questLinks.add(new NpcCatalog.QuestLink(q.id().toString(), q.giver(), List.copyOf(talk),
                    List.copyOf(deliver)));
        }

        List<NpcCatalog.LogicalDefinition> defs = new ArrayList<>();
        for (NpcDefinition d : npcEngine.definitions()) {
            defs.add(new NpcCatalog.LogicalDefinition(d.id(), d.displayName(), d.description(),
                    d.dialogueId(), d.role(), d.enabled()));
        }

        boolean citizensAvailable = npcIdentityService.citizensAvailable();
        return npcBindingRepository.loadAll().thenApply(bindings -> {
            List<NpcCatalog.CitizensBinding> cb = new ArrayList<>();
            for (NpcBindingRepository.Binding b : bindings) {
                cb.add(new NpcCatalog.CitizensBinding(b.npcId(), b.citizensNumericId()));
            }
            NpcCatalog.Result result = NpcCatalog.build(defs, dialogueLinks, questLinks, cb, citizensAvailable);
            List<NpcSummary> npcs = new ArrayList<>();
            for (NpcCatalog.NpcRow r : result.npcs()) {
                List<NpcWarning> warnings = new ArrayList<>();
                for (NpcCatalog.Warning w : r.warnings()) {
                    warnings.add(new NpcWarning(w.code(), w.severity(), w.message()));
                }
                npcs.add(new NpcSummary(r.id(), r.displayName(), r.logicalDefinitionPresent(),
                        r.citizensBindingPresent(), r.citizensNumericId(), r.bindingCount(), r.enabled(),
                        r.description(), r.role(), r.definedDialogueId(), r.hasDialogue(), r.dialogueId(),
                        r.dialogueNodes(), r.dialogueChoices(), r.dialogueStartsQuests(), r.questsGiven(),
                        r.questsReferenced(), r.questsDelivering(), r.sources(), r.state(),
                        List.copyOf(warnings)));
            }
            return new NpcCatalogView(List.copyOf(npcs), result.canonicalIds(), result.definedIds(),
                    result.citizensAvailable(), result.total(), result.withDefinition(),
                    result.withoutDefinition(), result.bound(), result.withWarnings());
        });
    }

    @Override
    public CompletableFuture<MutationResult> npcDefinitionCreate(String id, String displayName, String dialogueId,
                                                                 String role, boolean enabled) {
        return writeDefinition(id, displayName, dialogueId, role, enabled, true);
    }

    @Override
    public CompletableFuture<MutationResult> npcDefinitionUpdate(String id, String displayName, String dialogueId,
                                                                 String role, boolean enabled) {
        return writeDefinition(id, displayName, dialogueId, role, enabled, false);
    }

    /** Écriture d'une définition PNJ (create/update) : IO hors thread principal, aucun listener à rebrancher. */
    private CompletableFuture<MutationResult> writeDefinition(String id, String displayName, String dialogueId,
                                                             String role, boolean enabled, boolean create) {
        NpcDefinition definition;
        try {
            definition = new NpcDefinition(id, displayName, null, blank(dialogueId), blank(role), enabled);
        } catch (IllegalArgumentException e) {
            return done(MutationResult.of(false, "INVALID", e.getMessage()));
        }
        NpcDefinitionStore.Result r = create ? npcStore.create(definition) : npcStore.update(definition);
        npcEngine.reload();
        List<String> effects = new ArrayList<>();
        if (r.file() != null) {
            effects.add("npcs/" + r.file());
        }
        if (r.report() != null) {
            for (NpcLoadIssue issue : r.report().issues()) {
                effects.add("⚠ " + issue.file() + " : " + issue.message());
            }
        }
        return done(new MutationResult(r.ok(), r.code(), r.message(), List.copyOf(effects)));
    }

    /**
     * Issue #226 — supprime la définition logique seule, après sauvegarde, et <strong>après avoir
     * revérifié ici</strong> que personne n'en dépend.
     *
     * <p><strong>Pourquoi revalider, puisque l'écran l'a déjà fait.</strong> Un aperçu de
     * dépendances décrit l'état du serveur à l'instant où il a été calculé. Entre cet instant et le
     * clic de confirmation, une quête a pu désigner ce PNJ comme donneur, un objectif de remise a pu
     * apparaître. Faire confiance à l'aperçu, c'est accepter de casser du contenu sur la foi d'une
     * page périmée : on recompte donc sur les moteurs en mémoire, qui sont la vérité du moment.</p>
     */
    @Override
    public CompletableFuture<MutationResult> npcDefinitionDelete(String id, String expectDialogueId) {
        Optional<NpcDefinition> definition = npcEngine.find(id);
        if (definition.isEmpty()) {
            return done(MutationResult.of(true, "ABSENT",
                    "Aucune définition logique « " + safe(id) + " » : rien à supprimer."));
        }
        // Le dialogue attendu : si l'écran décrivait un autre état, on s'arrête. C'est le même
        // garde-fou que l'identifiant Citizens attendu pour une liaison.
        String declared = definition.get().dialogueId() == null ? "" : definition.get().dialogueId();
        String expected = expectDialogueId == null ? "" : expectDialogueId.trim();
        if (!expected.isEmpty() && !expected.equalsIgnoreCase(declared)) {
            return done(MutationResult.of(false, "DIALOGUE_MISMATCH",
                    "« " + id + " » ne déclare pas le dialogue « " + safe(expected) + " » mais "
                            + (declared.isEmpty() ? "aucun" : "« " + declared + " »")
                            + ". Rien n'a été supprimé — rafraîchissez le catalogue PNJ."));
        }

        // Revalidation des références de contenu, sur les moteurs en mémoire.
        List<String> givers = new ArrayList<>();
        List<String> talks = new ArrayList<>();
        List<String> deliveries = new ArrayList<>();
        for (QuestDefinition q : questEngine.quests()) {
            if (q.giver() != null && id.equals(q.giver().trim())) {
                givers.add(q.id().toString());
            }
            for (QuestStep step : q.steps()) {
                for (QuestObjective objective : step.objectives()) {
                    if (objective instanceof TalkToNpcObjective t && id.equals(t.npcId())
                            && !talks.contains(q.id().toString())) {
                        talks.add(q.id().toString());
                    }
                    if (objective instanceof DeliverItemToNpcObjective d && id.equals(d.npcId())
                            && !deliveries.contains(q.id().toString())) {
                        deliveries.add(q.id().toString());
                    }
                }
            }
        }
        if (!givers.isEmpty() || !talks.isEmpty() || !deliveries.isEmpty()) {
            List<String> why = new ArrayList<>();
            if (!givers.isEmpty()) {
                why.add("donneur de " + String.join(", ", givers));
            }
            if (!talks.isEmpty()) {
                why.add("cible d'un objectif « parler à » dans " + String.join(", ", talks));
            }
            if (!deliveries.isEmpty()) {
                why.add("destinataire d'une remise dans " + String.join(", ", deliveries));
            }
            return done(MutationResult.of(false, "REFERENCED",
                    "« " + id + " » est encore référencé (" + String.join(" ; ", why)
                            + "). Supprimer sa définition rendrait ce contenu injouable : corrigez "
                            + "d'abord ces références. Rien n'a été supprimé."));
        }

        NpcDefinitionStore.Result r = npcStore.deleteDefinition(id, npcBackupDirectory());
        if (!r.ok()) {
            return done(MutationResult.of(false, r.code(), r.message()));
        }
        npcEngine.reload();
        List<String> effects = new ArrayList<>();
        if (r.file() != null) {
            effects.add("npcs/" + r.file() + " supprimé");
        }
        if (!declared.isEmpty()) {
            effects.add("dialogue « " + declared + " » CONSERVÉ");
        }
        return done(new MutationResult(true, r.code(), r.message(), List.copyOf(effects)));
    }

    /**
     * Dossier des sauvegardes de définitions PNJ supprimées : à côté du dossier {@code npcs/}, donc
     * dans le dossier de données du plugin, et <strong>jamais</strong> dedans — un fichier de
     * sauvegarde que le chargeur relirait recréerait le PNJ qu'on vient de supprimer.
     */
    private java.nio.file.Path npcBackupDirectory() {
        return plugin.getDataFolder().toPath().resolve("npc-backups");
    }

    /** Issue #226 — délier sans détruire : le PNJ Citizens continue d'exister en jeu. */
    @Override
    public CompletableFuture<MutationResult> npcCitizensUnlink(String npcId, int expectedCitizensId) {
        return npcIdentityService.unbindCitizens(npcId, expectedCitizensId).thenApply(r ->
                new MutationResult(r.ok(), r.code(), r.message(),
                        r.ok() && "UNLINKED".equals(r.code())
                                ? List.of("liaison " + npcId + " <-> Citizens #"
                                        + expectedCitizensId + " retirée",
                                        "PNJ Citizens #" + expectedCitizensId + " CONSERVÉ")
                                : List.of()));
    }

    /**
     * Issue #226 — détruire le PNJ Citizens désigné par la liaison, et retirer cette liaison.
     *
     * <p>L'entité à détruire est résolue par la <strong>liaison en base</strong>, pas par
     * l'identifiant numérique seul : la destruction exige que l'UUID <em>et</em> l'identifiant
     * numérique correspondent ({@code destroyIfMatches}). Un identifiant numérique recyclé par
     * Citizens depuis l'affichage de l'écran ne peut donc pas faire détruire le voisin.</p>
     */
    @Override
    public CompletableFuture<MutationResult> npcCitizensDelete(String npcId, int expectedCitizensId) {
        if (!npcIdentityService.citizensAvailable()) {
            return done(MutationResult.of(false, "CITIZENS_UNAVAILABLE",
                    "Citizens n'est pas actif sur ce serveur."));
        }
        return npcIdentityService.bindingOf(npcId, expectedCitizensId).thenCompose(maybe -> {
            if (maybe.isEmpty()) {
                return done(MutationResult.of(false, "MISMATCH",
                        "Aucune liaison « " + safe(npcId) + " » ↔ Citizens #" + expectedCitizensId
                                + " : rien n'a été détruit. Rafraîchissez le catalogue PNJ — "
                                + "supprimer un PNJ Citizens sur la foi d'un écran périmé "
                                + "détruirait peut-être le mauvais."));
            }
            NpcBindingRepository.Binding binding = maybe.get();
            // Registre Citizens -> thread principal obligatoire.
            return onMain(() -> done(npcIdentityService.destroyBoundCitizens(binding)))
                    .thenCompose(destroyed -> {
                        if (!Boolean.TRUE.equals(destroyed)) {
                            return done(MutationResult.of(false, "NOT_DESTROYED",
                                    "Le PNJ Citizens #" + expectedCitizensId + " n'a pas pu être "
                                            + "détruit : son UUID ne correspond plus à la liaison "
                                            + "enregistrée. Rien n'a été détruit, la liaison est "
                                            + "conservée."));
                        }
                        return npcIdentityService.forgetBinding(binding).thenApply(ignored ->
                                new MutationResult(true, "DELETED",
                                        "PNJ Citizens #" + expectedCitizensId + " détruit, et sa "
                                                + "liaison avec « " + npcId + " » retirée.",
                                        List.of("Citizens #" + expectedCitizensId + " détruit",
                                                "liaison retirée",
                                                "définition logique « " + npcId + " » CONSERVÉE")));
                    });
        }).exceptionally(error -> MutationResult.of(false, "ERROR",
                "Échec de la suppression Citizens : " + rootName(error)));
    }

    // ---- Emplacements de construction (issue #213) ---------------------------------------------

    /**
     * Catalogue des emplacements. Lecture du cache mémoire du service, lui-même chargé de la base au
     * démarrage : aucun accès disque ici, donc aucune latence sur le thread de l'agent.
     *
     * <p>{@code worldLoaded} est la seule information que seul le serveur peut donner, et elle
     * demande le thread principal : un emplacement dans un monde déchargé reste valide, mais le dire
     * évite qu'un administrateur croie son emplacement perdu.</p>
     */
    @Override
    public CompletableFuture<BuildingSiteCatalogView> buildingSites() {
        List<com.lodygames.rpgquest.building.model.BuildingSite> sites = buildingSiteService.all();
        return onMain(() -> {
            Set<String> loaded = new java.util.HashSet<>();
            for (World world : plugin.getServer().getWorlds()) {
                loaded.add(world.getName());
            }
            List<BuildingSiteSummary> rows = new ArrayList<>();
            for (com.lodygames.rpgquest.building.model.BuildingSite site : sites) {
                rows.add(new BuildingSiteSummary(site.id(), site.name(), site.description(),
                        site.world(), site.x(), site.y(), site.z(), site.facing().name(),
                        site.status().name(), site.createdBy(), site.createdAt().toString(),
                        loaded.contains(site.world())));
            }
            return done(new BuildingSiteCatalogView(List.copyOf(rows),
                    buildingSiteService.worlds(), rows.size(), placementRows()));
        });
    }

    /** Projection des bâtiments posés, partagée par le catalogue et la bibliothèque. */
    private List<BuildingPlacementSummary> placementRows() {
        List<BuildingPlacementSummary> rows = new ArrayList<>();
        for (com.lodygames.rpgquest.building.model.BuildingPlacement placement
                : buildingPlacements.all()) {
            String name = buildingLibraryService.find(placement.buildingId())
                    .map(com.lodygames.rpgquest.building.model.BuildingDefinition::name)
                    // Le bâtiment a pu être retiré de la bibliothèque après la pose : on garde la
                    // ligne et on le dit, plutôt que de faire disparaître un bâtiment réellement
                    // posé parce que sa définition a bougé.
                    .orElse(placement.buildingId() + " (hors bibliothèque)");
            rows.add(new BuildingPlacementSummary(placement.siteId(), placement.buildingId(), name,
                    placement.world(), placement.anchorX(), placement.anchorY(),
                    placement.anchorZ(), placement.rotationDegrees(),
                    placement.minX(), placement.minY(), placement.minZ(),
                    placement.maxX(), placement.maxY(), placement.maxZ(),
                    placement.placedBy(), placement.placedAt().toString(),
                    placement.restorable()));
        }
        return rows;
    }

    @Override
    public CompletableFuture<BuildingLibraryView> buildingLibrary() {
        List<BuildingDefinitionSummary> rows = new ArrayList<>();
        for (com.lodygames.rpgquest.building.model.BuildingDefinition definition
                : buildingLibraryService.all()) {
            rows.add(new BuildingDefinitionSummary(definition.id(), definition.name(),
                    definition.description(),
                    definition.sizeX(), definition.sizeY(), definition.sizeZ(),
                    definition.anchorX(), definition.anchorY(), definition.anchorZ(),
                    definition.front().name(), definition.materials(),
                    definition.schematic(), schematicGateway.has(definition.schematic()),
                    definition.version()));
        }
        return done(new BuildingLibraryView(List.copyOf(rows), buildingLibraryService.problems(),
                schematicGateway.available(), schematicGateway.unavailableReason()));
    }

    /**
     * {@code building.placement.preview}. Sur le thread principal, parce que le comptage des blocs
     * non-air lit le monde — et lire des blocs hors du thread principal n'est pas sûr.
     */
    @Override
    public CompletableFuture<BuildingPreviewView> buildingPlacementPreview(String siteId,
                                                                           String buildingId) {
        return onMain(() -> {
            com.lodygames.rpgquest.building.BuildingPlacementService.Preview preview =
                    buildingPlacements.preview(siteId, buildingId);
            if (preview.site() == null || preview.definition() == null) {
                return done(new BuildingPreviewView(false, siteId, "", "", "", 0, 0, 0,
                        buildingId, "", 0, 0, 0, "", 0, 0, 0, 0, 0, 0, 0, 0L, -1L,
                        preview.refusals(), preview.warnings()));
            }
            var site = preview.site();
            var building = preview.definition();
            var footprint = preview.footprint();
            return done(new BuildingPreviewView(preview.placeable(),
                    site.id(), site.name(), site.facing().name(),
                    site.world(), site.x(), site.y(), site.z(),
                    building.id(), building.name(),
                    building.sizeX(), building.sizeY(), building.sizeZ(),
                    building.front().name(),
                    preview.rotationDegrees(),
                    footprint.minX(), footprint.minY(), footprint.minZ(),
                    footprint.maxX(), footprint.maxY(), footprint.maxZ(),
                    footprint.blockCount(), preview.nonAirBlocks(),
                    preview.refusals(), preview.warnings()));
        });
    }

    /**
     * {@code building.placement.place}. Sur le thread principal : coller écrit dans le monde, et
     * WorldEdit doit être appelé là où Bukkit l'autorise.
     */
    @Override
    public CompletableFuture<MutationResult> buildingPlacementPlace(String siteId,
                                                                    String buildingId,
                                                                    String placedBy) {
        return onMain(() -> buildingPlacements.place(siteId, buildingId, placedBy)
                .thenApply(result -> result.placed()
                        ? new MutationResult(true, "PLACED",
                                "« " + result.placement().buildingId() + " » posé sur "
                                        + result.placement().siteId() + ", tourné de "
                                        + result.placement().rotationLabel() + ", emprise "
                                        + result.placement().footprint().label() + ".",
                                List.of("rotation: " + result.placement().rotationLabel(),
                                        "emprise: " + result.placement().footprint().label(),
                                        "sauvegarde: " + result.placement().backupSchematic()))
                        : MutationResult.of(false, "REFUSED", result.error()))
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec de la pose : " + rootName(error))));
    }

    @Override
    public CompletableFuture<MutationResult> buildingPlacementRollback(String siteId) {
        return onMain(() -> buildingPlacements.rollback(siteId)
                .thenApply(result -> result.restored()
                        ? new MutationResult(true, "RESTORED",
                                "Zone restaurée et " + siteId + " libéré (« "
                                        + result.placement().buildingId() + " » retiré).",
                                List.of("emprise: " + result.placement().footprint().label()))
                        : MutationResult.of(false, "REFUSED", result.error()))
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec de la restauration : " + rootName(error))));
    }

    @Override
    public CompletableFuture<MutationResult> buildingSiteRename(String id, String name) {
        return buildingSiteService.rename(id, name)
                .thenApply(updated -> updated
                        .map(site -> new MutationResult(true, "RENAMED",
                                "Emplacement « " + site.id() + " » renommé « " + site.name() + " ».",
                                List.of("name: " + site.name())))
                        .orElseGet(() -> MutationResult.of(false, "NOT_FOUND",
                                "Aucun emplacement « " + safe(id) + " » : rien n'a été renommé.")))
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec du renommage : " + rootName(error)));
    }

    @Override
    public CompletableFuture<MutationResult> buildingSiteDescribe(String id, String description) {
        return buildingSiteService.describe(id, description)
                .thenApply(updated -> updated
                        .map(site -> new MutationResult(true, "DESCRIBED",
                                site.description().isEmpty()
                                        ? "Description de « " + site.id() + " » effacée."
                                        : "Description de « " + site.id() + " » enregistrée.",
                                List.of()))
                        .orElseGet(() -> MutationResult.of(false, "NOT_FOUND",
                                "Aucun emplacement « " + safe(id) + " » : rien n'a été modifié.")))
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec de l'enregistrement : " + rootName(error)));
    }

    @Override
    public CompletableFuture<MutationResult> buildingSiteFacing(String id, String rawFacing) {
        var facing = com.lodygames.rpgquest.building.model.Facing.of(rawFacing);
        if (facing.isEmpty()) {
            return done(MutationResult.of(false, "INVALID",
                    "Orientation inconnue « " + safe(rawFacing) + " » — attendu NORTH, EAST, SOUTH "
                            + "ou WEST."));
        }
        return buildingSiteService.reface(id, facing.get())
                .thenApply(updated -> updated
                        .map(site -> new MutationResult(true, "REFACED",
                                "Emplacement « " + site.id() + " » orienté vers le "
                                        + site.facing().label() + ".",
                                List.of("position inchangée : " + site.positionLabel())))
                        .orElseGet(() -> MutationResult.of(false, "NOT_FOUND",
                                "Aucun emplacement « " + safe(id) + " » : rien n'a été modifié.")))
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec du changement d'orientation : " + rootName(error)));
    }

    /**
     * Supprime le marqueur logique. <strong>Aucun bloc du monde n'est touché</strong>, et l'effet est
     * annoncé comme tel : c'est la question que se posera forcément l'administrateur.
     */
    @Override
    public CompletableFuture<MutationResult> buildingSiteDelete(String id) {
        return buildingSiteService.delete(id)
                .thenApply(removed -> removed
                        ? new MutationResult(true, "DELETED",
                                "Emplacement « " + safe(id) + " » supprimé.",
                                List.of("aucun bloc du monde n'a été modifié"))
                        : MutationResult.of(true, "ABSENT",
                                "Aucun emplacement « " + safe(id) + " » : rien à supprimer."))
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec de la suppression : " + rootName(error)));
    }

    @Override
    public CompletableFuture<MutationResult> questGiverSet(String rawQuestId, String npcId) {
        if (npcEngine.find(npcId).isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_NPC",
                    "Aucune définition logique de PNJ « " + safe(npcId) + " » — créer d'abord la définition."));
        }
        NamespacedKey questId = resolveKey(rawQuestId);
        if (questId == null || questEngine.find(questId).isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_QUEST", "Quête inconnue : " + safe(rawQuestId)));
        }
        QuestGiverStore.Result r = questGiverStore.setGiver(rawQuestId, npcId);
        if (!r.ok()) {
            return done(MutationResult.of(false, r.code(), r.message()));
        }
        // Le rebuild du moteur de quêtes (dé)branche des listeners -> thread principal obligatoire.
        return onMain(() -> {
            questProgressEngine.reloadQuestDefinitions();
            return done(new MutationResult(true, "SET", r.message(),
                    List.of("quests/" + r.file(), "giver: " + npcId)));
        });
    }

    /**
     * Issue #172/#196 — catalogues réels de la version installée. Tout vient des registres Bukkit,
     * jamais d'une liste figée côté panel : une mise à jour de Minecraft fait donc évoluer les
     * listes sans toucher au code.
     *
     * <p>Filtrage assumé : seuls les types d'entité <em>vivants et invocables</em> sont proposés
     * (un item tombé, une flèche ou un marqueur n'est pas un profil de mob possible). L'ordre est
     * alphabétique pour que la recherche soit prévisible.</p>
     */
    @Override
    public CompletableFuture<MobCatalogsView> mobCatalogs() {
        // Registres et mondes = API Bukkit -> thread principal.
        return onMain(() -> {
            List<String> entities = new ArrayList<>();
            for (org.bukkit.entity.EntityType type : org.bukkit.entity.EntityType.values()) {
                if (type.isAlive() && type.isSpawnable()) {
                    entities.add(type.name());
                }
            }
            Collections.sort(entities);

            List<String> particles = new ArrayList<>();
            List<String> colorable = new ArrayList<>();
            for (org.bukkit.Particle particle : org.bukkit.Particle.values()) {
                particles.add(particle.name());
                // #195 : une couleur n'est proposée que si le type l'accepte réellement. Les
                // particules colorables de Paper portent un type de données dédié (DustOptions /
                // DustTransition) — on ne devine jamais, on lit le contrat du type.
                Class<?> data = particle.getDataType();
                if (data != null && (org.bukkit.Particle.DustOptions.class.isAssignableFrom(data)
                        || org.bukkit.Particle.DustTransition.class.isAssignableFrom(data)
                        || org.bukkit.Color.class.isAssignableFrom(data))) {
                    colorable.add(particle.name());
                }
            }
            Collections.sort(particles);
            Collections.sort(colorable);

            List<String> sounds = new ArrayList<>();
            for (org.bukkit.Sound sound : org.bukkit.Registry.SOUNDS) {
                sounds.add(sound.getKey().getKey().toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
            }
            Collections.sort(sounds);

            List<String> biomes = new ArrayList<>();
            for (org.bukkit.block.Biome biome : org.bukkit.Registry.BIOME) {
                biomes.add(biome.getKey().getKey().toUpperCase(java.util.Locale.ROOT));
            }
            Collections.sort(biomes);

            List<String> worlds = new ArrayList<>();
            for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                worlds.add(world.getName());
            }
            Collections.sort(worlds);

            return done(new MobCatalogsView(List.copyOf(entities), List.copyOf(particles),
                    List.copyOf(sounds), List.copyOf(biomes), List.copyOf(worlds),
                    wildWorldSupplier.get() == null ? "" : wildWorldSupplier.get(), List.copyOf(colorable)));
        });
    }

    /**
     * Issue #196 — catalogue complet des matériaux de la version installée.
     *
     * <p>Trois filtres, chacun pour une raison précise :</p>
     * <ul>
     *   <li>{@code isLegacy()} — les constantes {@code LEGACY_*} sont les matériaux d'avant
     *       l'aplatissement de 1.13. Les proposer donnerait des doublons trompeurs
     *       ({@code LEGACY_WOOD_SWORD} à côté de {@code WOODEN_SWORD}) et des objets que le
     *       serveur ne sait plus fabriquer.</li>
     *   <li>{@code isAir()} — un {@code ItemStack} d'air est une pile vide : ni icône visible, ni
     *       récompense livrable.</li>
     *   <li>{@code isItem()} — c'est la seule garantie que l'API donne sur « peut exister comme
     *       objet ». Ce qui ne la vérifie pas mais reste un bloc est renvoyé à part, pour être
     *       expliqué au lieu d'être tu.</li>
     * </ul>
     *
     * <p>Lecture pure d'un registre en mémoire : aucun accès disque, aucune requête SQL. Elle est
     * tout de même faite sur le thread principal, parce que l'énumération des matériaux fait partie
     * de l'API Bukkit et que rien ne garantit sa consultation hors du thread serveur.</p>
     */
    /**
     * Issue #194 — suppression de la copie serveur d'une quête ou d'une story.
     *
     * <p>La suppression du fichier est faite hors du thread principal (entrée/sortie disque), mais
     * la <strong>relecture</strong> des définitions revient sur le thread principal : recharger les
     * quêtes rebranche des écouteurs d'événements, ce qui n'est pas permis ailleurs.</p>
     *
     * <p>Aucune progression de joueur n'est touchée, et rien d'autre n'est supprimé — ni PNJ, ni
     * dialogue, ni les quêtes qu'une story enchaînait.</p>
     */
    @Override
    public CompletableFuture<MutationResult> contentDefinitionDelete(String kind, String id) {
        return CompletableFuture.supplyAsync(() -> contentDeleter.delete(kind, id))
                .thenCompose(result -> {
                    if (!result.ok()) {
                        return done(MutationResult.of(false, result.code(), result.message()));
                    }
                    return onMain(() -> {
                        String reloaded;
                        if ("quests".equals(kind)) {
                            // Le rebuild du moteur de quêtes (dé)branche des listeners.
                            questEngine.reload();
                            questProgressEngine.reloadQuestDefinitions();
                            reloaded = questEngine.quests().size() + " quête(s) rechargée(s)";
                        } else {
                            reloaded = storyService.reloadDefinitions().loaded().size()
                                    + " story(ies) rechargée(s)";
                        }
                        return done(new MutationResult(true, "DELETED",
                                result.message() + " " + reloaded + ".",
                                List.of(kind + "/" + result.file(), "sauvegarde: " + result.backupPath())));
                    });
                });
    }

    @Override
    public CompletableFuture<ItemCatalogsView> itemCatalogs() {
        return onMain(() -> {
            List<String> items = new ArrayList<>();
            List<String> blocksWithoutItem = new ArrayList<>();
            int legacyExcluded = 0;

            for (org.bukkit.Material material : org.bukkit.Material.values()) {
                if (material.isLegacy()) {
                    legacyExcluded++;
                    continue;
                }
                if (material.isAir()) {
                    continue;
                }
                if (material.isItem()) {
                    items.add(material.name());
                } else if (material.isBlock()) {
                    blocksWithoutItem.add(material.name());
                }
            }
            Collections.sort(items);
            Collections.sort(blocksWithoutItem);

            return done(new ItemCatalogsView(List.copyOf(items), List.copyOf(blocksWithoutItem),
                    org.bukkit.Bukkit.getMinecraftVersion(), legacyExcluded));
        });
    }

    /**
     * Issue #165 — renommage du PNJ Citizens lié. Le ciblage part de la <strong>liaison
     * persistée</strong> ({@code npcId} → UUID Citizens), jamais du nom affiché ni d'une sélection :
     * deux administrateurs qui renomment en même temps ne peuvent pas se tromper de PNJ.
     */
    @Override
    public CompletableFuture<MutationResult> citizensRename(String npcId, String newName) {
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.length() > 48) {
            return done(MutationResult.of(false, "INVALID_NAME",
                    "Nom invalide : 1 à 48 caractères attendus."));
        }
        if (!npcIdentityService.citizensAvailable()) {
            return done(MutationResult.of(false, "CITIZENS_UNAVAILABLE", "Citizens n'est pas disponible."));
        }
        return citizensUuidOf(npcId).thenCompose(uuid -> {
            if (uuid.isEmpty()) {
                return done(MutationResult.of(false, "NO_CITIZENS_BINDING",
                        "Aucun PNJ Citizens lié à « " + safe(npcId) + " » — le lier d'abord."));
            }
            UUID citizensUuid = uuid.get();
            // Le skin doit survivre au renommage. Un PNJ Citizens de type PLAYER sans skin explicite
            // dérive son apparence de son NOM : setName() la changerait donc aussi, ce que personne
            // ne demande en corrigeant un nom. On rattache l'apparence AVANT de renommer — à la
            // source enregistrée si nous en avons une, sinon à l'ancien nom, ce qui reconduit à
            // l'identique ce que les joueurs voyaient déjà (jamais un skin neuf).
            return npcSkinSourceRepository.find(citizensUuid)
                    .thenCompose(recorded -> onMain(() -> done(renameKeepingSkin(citizensUuid, name, recorded))))
                    .thenCompose(outcome -> outcome.record() == null
                            ? done(outcome.result())
                            : npcSkinSourceRepository
                                    .save(citizensUuid, outcome.record().kind(), outcome.record().value())
                                    .thenApply(ignored -> outcome.result()));
        });
    }

    /**
     * Résultat d'un renommage, plus — le cas échéant — la source d'apparence à mémoriser une fois
     * l'opération réussie. La base est asynchrone et le registre Citizens est strictement
     * thread-principal : séparer les deux évite d'écrire une source qui n'aurait jamais été
     * appliquée.
     */
    private record RenameOutcome(MutationResult result, NpcSkinSourceRepository.SkinSource record) {
    }

    /**
     * Thread principal : décide si le renommage est sûr pour l'apparence, puis renomme.
     *
     * <p><strong>Ce que Citizens permet réellement de savoir.</strong> L'artefact
     * {@code citizensapi} — la seule dépendance Citizens de ce projet — <strong>n'expose aucune
     * API de skin</strong> : ni {@code SkinTrait}, ni clé de skin dans l'enum public
     * {@code NPC.Metadata} (vérifié sur {@code citizensapi:2.0.43}). Il est donc
     * <strong>impossible</strong> de lire le skin actuellement appliqué à un PNJ, et donc de le
     * restituer, sauf si c'est nous qui l'avons posé et enregistré.</p>
     *
     * <p>Conséquence assumée, plutôt qu'une préservation prétendue :</p>
     * <ul>
     *   <li>PNJ <strong>non joueur</strong> (villageois, zombie…) : aucun skin en jeu, le nom
     *       n'influence rien → renommage libre ;</li>
     *   <li>PNJ joueur avec une <strong>source enregistrée</strong> par le panel : elle est
     *       réappliquée avant le renommage → apparence réellement conservée ;</li>
     *   <li>PNJ joueur <strong>sans source connue</strong> (skin posé directement via Citizens, ou
     *       antérieur à la migration V27) : on <strong>refuse</strong>. Rattacher l'ancien nom
     *       écraserait un éventuel skin explicite par une texture dérivée d'un pseudo — un
     *       remplacement silencieux. Mieux vaut refuser et dire quoi faire.</li>
     * </ul>
     */
    private RenameOutcome renameKeepingSkin(UUID citizensUuid, String name,
                                            Optional<NpcSkinSourceRepository.SkinSource> recorded) {
        Optional<String> before = npcIdentityService.citizensNameOf(citizensUuid);
        if (before.isEmpty()) {
            return new RenameOutcome(MutationResult.of(false, "CITIZENS_NPC_MISSING",
                    "Le PNJ Citizens lié est introuvable dans le registre (supprimé ?)."), null);
        }
        String previousName = before.get();

        // Décision isolée dans une classe pure (testable sans Citizens) : voir SkinPreservationPlanner.
        SkinPreservationPlanner.Plan plan = SkinPreservationPlanner.plan(
                npcIdentityService.citizensIsPlayerType(citizensUuid), recorded.isPresent());
        if (!plan.allowsRename()) {
            return new RenameOutcome(MutationResult.of(false, plan.code(), plan.message()), null);
        }
        if (plan.decision() == SkinPreservationPlanner.Decision.RENAME_FREELY) {
            return renameOnly(citizensUuid, name, previousName, " " + plan.message(),
                    "citizens:skin-preserved=not-applicable");
        }

        NpcSkinSourceRepository.SkinSource source = recorded.orElseThrow();
        boolean anchored = source.kind() == NpcSkinSourceRepository.Kind.URL
                ? npcIdentityService.applyCitizensSkin(citizensUuid, source.value())
                : npcIdentityService.pinCitizensSkinToName(citizensUuid, source.value());
        if (!anchored) {
            // Échec d'ancrage : ne jamais renommer derrière, et ne jamais rendre un faux succès.
            return new RenameOutcome(MutationResult.of(false, "SKIN_ANCHOR_FAILED",
                    "Renommage annulé : Citizens a refusé de réappliquer le skin enregistré avant "
                            + "le renommage. Aucun nom n'a été changé, l'apparence est intacte."), null);
        }
        return renameOnly(citizensUuid, name, previousName,
                " Skin conservé : la source enregistrée a été réappliquée avant le renommage.",
                "citizens:skin-preserved=recorded");
    }

    /** Renomme, une fois la question de l'apparence tranchée. Thread principal. */
    private RenameOutcome renameOnly(UUID citizensUuid, String name, String previousName,
                                     String note, String effect) {
        Optional<String> previous = npcIdentityService.renameCitizensFor(citizensUuid, name);
        if (previous.isEmpty()) {
            return new RenameOutcome(MutationResult.of(false, "CITIZENS_NPC_MISSING",
                    "Le PNJ Citizens lié est introuvable dans le registre (supprimé ?)."), null);
        }
        List<String> effects = new ArrayList<>();
        effects.add("citizens:name=" + name);
        effects.add(effect);
        return new RenameOutcome(new MutationResult(true, "RENAMED",
                "Nom en jeu : « " + previousName + " » → « " + name + " ». "
                        + "Identifiant logique et liaisons inchangés." + note,
                effects), null);
    }

    /**
     * Issue #165 — skin MineSkin. L'URL est validée <strong>côté serveur</strong> (jamais une
     * commande libre venue du navigateur) et le PNJ est ciblé par la liaison persistée.
     */
    @Override
    public CompletableFuture<MutationResult> citizensSkin(String npcId, String value, boolean byPlayerName) {
        String raw = value == null ? "" : value.trim();
        if (byPlayerName) {
            if (!NpcIdentityService.isValidSkinPlayerName(raw)) {
                return done(MutationResult.of(false, "INVALID_PLAYER_NAME",
                        "Pseudo Minecraft invalide. Format accepté : 3 à 16 caractères, lettres, "
                                + "chiffres et « _ »."));
            }
        } else if (!NpcIdentityService.isValidMineSkinUrl(raw)) {
            return done(MutationResult.of(false, "INVALID_URL",
                    "Lien MineSkin invalide. Format accepté : https://minesk.in/<identifiant>."));
        }
        if (!npcIdentityService.citizensAvailable()) {
            return done(MutationResult.of(false, "CITIZENS_UNAVAILABLE", "Citizens n'est pas disponible."));
        }
        return citizensUuidOf(npcId).thenCompose(uuid -> {
            if (uuid.isEmpty()) {
                return done(MutationResult.of(false, "NO_CITIZENS_BINDING",
                        "Aucun PNJ Citizens lié à « " + safe(npcId) + " » — le lier d'abord."));
            }
            UUID citizensUuid = uuid.get();
            return onMain(() -> {
                boolean accepted = byPlayerName
                        ? npcIdentityService.pinCitizensSkinToName(citizensUuid, raw)
                        : npcIdentityService.applyCitizensSkin(citizensUuid, raw);
                if (!accepted) {
                    return done(MutationResult.of(false, "SKIN_REFUSED",
                            "Citizens a refusé la demande de skin (PNJ introuvable, ou type de PNJ "
                                    + "sans skin). Le skin précédent est conservé."));
                }
                // Citizens résout et télécharge lui-même, de façon asynchrone : RPGQuest ne fait
                // aucun appel réseau et ne peut donc honnêtement confirmer que la PRISE EN COMPTE,
                // jamais le rendu visuel ni l'existence du compte visé.
                return done(new MutationResult(true, "SKIN_REQUESTED",
                        "Demande de skin transmise à Citizens (" + (byPlayerName ? "pseudo " : "lien ")
                                + raw + "). La résolution est asynchrone et faite par Citizens : "
                                + "vérifier en jeu. Le nom en jeu n'est pas modifié, et cette source "
                                + "sera reconduite lors d'un renommage.",
                        List.of(byPlayerName ? "citizens:skin-player=" + raw : "citizens:skin-url=" + raw)));
            }).thenCompose(result -> result.ok()
                    ? npcSkinSourceRepository
                            .save(citizensUuid,
                                    byPlayerName ? NpcSkinSourceRepository.Kind.NAME
                                            : NpcSkinSourceRepository.Kind.URL,
                                    raw)
                            .thenApply(ignored -> result)
                    : done(result));
        });
    }

    // ---- Création complète d'un PNJ depuis le panel (définition + spawn + lien + skin) --------

    /** Identifiant logique dérivé d'un nom affiché : minuscules ASCII, « _ » comme séparateur. */
    static String slugify(String displayName) {
        String plain = displayName == null ? "" : displayName.replaceAll("<[^>]*>", "");
        String ascii = java.text.Normalizer.normalize(plain, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String slug = ascii.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.length() > 48 ? slug.substring(0, 48).replaceAll("_+$", "") : slug;
    }

    @Override
    public CompletableFuture<CitizensProvisionResult> citizensProvision(
            String displayName, String skinValue, boolean skinByPlayerName,
            String world, Double x, Double y, Double z, Float yaw, Float pitch) {

        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 128 || name.indexOf('\n') >= 0) {
            return done(CitizensProvisionResult.reject("INVALID_NAME",
                    "Nom manquant, trop long (128 maximum) ou multi-ligne."));
        }
        String npcId = slugify(name);
        if (npcId.isEmpty() || !NPC_ID_PATTERN.matcher(npcId).matches()) {
            return done(CitizensProvisionResult.reject("INVALID_NAME",
                    "Impossible de déduire un identifiant technique de « " + safe(name) + " » : "
                            + "ajouter au moins une lettre ou un chiffre."));
        }
        // Le skin est validé AVANT toute création : une URL invalide ne doit jamais laisser un PNJ
        // derrière elle, ni être silencieusement remplacée par autre chose.
        String skin = skinValue == null ? "" : skinValue.trim();
        if (!skin.isEmpty()) {
            boolean valid = skinByPlayerName
                    ? NpcIdentityService.isValidSkinPlayerName(skin)
                    : NpcIdentityService.isValidMineSkinUrl(skin);
            if (!valid) {
                return done(CitizensProvisionResult.reject("INVALID_SKIN", skinByPlayerName
                        ? "Pseudo Minecraft invalide : 3 à 16 caractères, lettres, chiffres et « _ »."
                        : "Lien MineSkin invalide. Format accepté : https://minesk.in/<identifiant>."));
            }
        }
        if (npcEngine.find(npcId).isPresent()) {
            // Garde-fou anti-doublon : un double clic ou une requête rejouée retombe ici.
            return done(CitizensProvisionResult.reject("NPC_ID_TAKEN",
                    "Une définition « " + npcId + " » existe déjà (déduite du nom « " + safe(name)
                            + " »). Choisir un autre nom, ou modifier le PNJ existant."));
        }

        boolean explicit = world != null && !world.isBlank();
        if (explicit != (x != null && y != null && z != null)) {
            return done(CitizensProvisionResult.reject("PARTIAL_LOCATION",
                    "Localisation incomplète : renseigner le monde ET X, Y, Z ensemble, ou laisser "
                            + "le tout vide pour un placement automatique près du Guide."));
        }

        float finalYaw = yaw == null ? 0f : yaw;
        float finalPitch = pitch == null ? 0f : pitch;

        CompletableFuture<ResolvedSpot> target = explicit
                ? done(new ResolvedSpot(true, null, null, world.trim(), x, y, z))
                : resolveSpotNearGuide();

        return target.thenCompose(spot -> {
            if (!spot.ok()) {
                return done(CitizensProvisionResult.reject(spot.code(), spot.message()));
            }
            // 1) Définition RPGQuest, via le chemin d'écriture habituel.
            return writeDefinition(npcId, name, null, null, true, true).thenCompose(defResult -> {
                if (!defResult.ok()) {
                    return done(CitizensProvisionResult.reject(defResult.code(), defResult.message()));
                }
                // 2) Apparition + liaison, par le parcours canonique (avec son propre rollback).
                return citizensCreate(npcId, spot.world(), spot.x(), spot.y(), spot.z(), finalYaw, finalPitch)
                        .thenCompose(created -> {
                            if (!created.ok()) {
                                return compensate(npcId, name, created.code(), created.message());
                            }
                            return applyOptionalSkin(npcId, skin, skinByPlayerName).thenApply(skinOutcome ->
                                    new CitizensProvisionResult(true,
                                            skinOutcome.applied() ? "PROVISIONED" : "PROVISIONED_SKIN_FAILED",
                                            "PNJ « " + name + " » créé : identifiant RPGQuest « " + npcId
                                                    + " », Citizens #" + created.citizensNumericId() + ", "
                                                    + spot.describe() + ". " + skinOutcome.note(),
                                            npcId, created.citizensNumericId(), name,
                                            spot.world(), spot.x(), spot.y(), spot.z(), finalYaw, finalPitch,
                                            skinOutcome.note(), false, created.effects()));
                        });
            });
        });
    }

    /**
     * Nettoyage compensatoire d'une tentative de création qui a échoué après l'écriture de la
     * définition — et <strong>gestion des échecs du nettoyage lui-même</strong>.
     *
     * <p>Deux garde-fous avant de supprimer quoi que ce soit :</p>
     * <ul>
     *   <li>la définition n'est retirée que si <strong>aucune liaison Citizens</strong> ne la
     *       référence. Sans cela, une tentative concurrente ayant réussi verrait son travail
     *       détruit par le nettoyage de celle qui a échoué ;</li>
     *   <li>si le retrait échoue, on ne prétend pas avoir nettoyé : le code devient
     *       {@code PROVISION_CLEANUP_INCOMPLETE} et le message nomme <em>exactement</em> ce qui
     *       reste à retirer à la main.</li>
     * </ul>
     */
    private CompletableFuture<CitizensProvisionResult> compensate(String npcId, String displayName,
                                                                  String failureCode, String failureMessage) {
        return npcBindingRepository.loadAll().thenApply(bindings -> {
            boolean stillLinked = bindings.stream().anyMatch(b -> b.npcId().equalsIgnoreCase(npcId));
            if (stillLinked) {
                return new CitizensProvisionResult(false, "PROVISION_CLEANUP_SKIPPED",
                        failureMessage + " La définition « " + npcId + " » a été CONSERVÉE : une liaison "
                                + "Citizens la référence déjà (une autre tentative a abouti). Rien n'a été "
                                + "supprimé — vérifier la fiche de ce PNJ avant toute action.",
                        npcId, null, displayName, null, null, null, null, null, null, null, false, List.of());
            }
            NpcDefinitionStore.Result removed = npcStore.deleteForRollback(npcId);
            npcEngine.reload();
            if (removed.ok()) {
                return new CitizensProvisionResult(false, "PROVISION_ROLLED_BACK",
                        failureMessage + " Nettoyage complet : la définition « " + npcId + " » créée par "
                                + "cette tentative a été retirée. Aucun PNJ partiel ne subsiste.",
                        null, null, displayName, null, null, null, null, null, null, null, true,
                        List.of("rollback: npcs/" + npcId + ".yml retiré"));
            }
            return new CitizensProvisionResult(false, "PROVISION_CLEANUP_INCOMPLETE",
                    failureMessage + " ATTENTION : le nettoyage a ÉCHOUÉ. La définition « " + npcId
                            + " » subsiste et doit être retirée à la main (" + removed.message() + ").",
                    npcId, null, displayName, null, null, null, null, null, null, null, false,
                    List.of("rollback incomplet: npcs/" + npcId + ".yml"));
        }).exceptionally(err -> new CitizensProvisionResult(false, "PROVISION_CLEANUP_INCOMPLETE",
                failureMessage + " ATTENTION : le nettoyage n'a pas pu être évalué (" + rootName(err)
                        + "). Vérifier si une définition « " + npcId + " » subsiste.",
                npcId, null, displayName, null, null, null, null, null, null, null, false, List.of()));
    }

    /** Ce que le skin est devenu : appliqué ou non, et le texte à montrer dans les deux cas. */
    private record SkinOutcome(boolean applied, String note) {
    }

    /**
     * Applique le skin demandé, et décrit honnêtement ce qui s'est passé — jamais un succès muet.
     *
     * <p>Un refus de Citizens ici ne détruit pas le PNJ : il est créé, lié et valide. On le dit, on
     * marque le résultat d'un code distinct ({@code PROVISIONED_SKIN_FAILED}) et on indique où
     * réappliquer le skin. Détruire un PNJ correct parce qu'une texture n'a pas été acceptée serait
     * un nettoyage disproportionné.</p>
     */
    private CompletableFuture<SkinOutcome> applyOptionalSkin(String npcId, String skin, boolean byPlayerName) {
        if (skin.isEmpty()) {
            return done(new SkinOutcome(true,
                    "Aucun skin demandé : le PNJ garde l'apparence par défaut de Citizens pour un PNJ "
                            + "de type joueur. Aucun appel réseau n'a été fait."));
        }
        return citizensSkin(npcId, skin, byPlayerName).thenApply(r -> r.ok()
                ? new SkinOutcome(true, "Skin demandé à Citizens (" + (byPlayerName ? "pseudo " : "lien ")
                        + skin + ") : résolution asynchrone, à vérifier en jeu.")
                : new SkinOutcome(false, "ATTENTION : le PNJ est bien créé et lié, mais le skin n'a PAS "
                        + "été appliqué (" + r.message() + "). Le réappliquer depuis « Nom en jeu & "
                        + "apparence » — le PNJ lui-même n'a pas besoin d'être recréé."));
    }

    /** Emplacement résolu, ou refus explicite. */
    private record ResolvedSpot(boolean ok, String code, String message,
                                String world, Double x, Double y, Double z) {
        String describe() {
            return world + " " + fmt(x) + " / " + fmt(y) + " / " + fmt(z);
        }
    }

    /**
     * Cherche un emplacement libre et sûr près du <strong>Guide</strong>, identifié par sa
     * définition stable ({@code hub.guide-npc-id}) et jamais par son nom affiché.
     *
     * <p>Chaque impossibilité a son propre code et son propre message : Guide absent, Guide non lié
     * à un PNJ Citizens, Guide hors du Hub configuré, ou aucun emplacement sûr dans les bornes.
     * Aucun PNJ partiel n'est jamais créé.</p>
     */
    private CompletableFuture<ResolvedSpot> resolveSpotNearGuide() {
        String guideId = hubConfig.get().guideNpcId();
        String hubWorld = hubConfig.get().world();
        if (npcEngine.find(guideId).isEmpty()) {
            return done(new ResolvedSpot(false, "GUIDE_MISSING",
                    "Aucune définition « " + guideId + " » (le Guide) : impossible de placer un PNJ "
                            + "automatiquement. Donner une position explicite, ou créer le Guide.",
                    null, null, null, null));
        }
        return npcBindingRepository.loadAll().thenCompose(bindings -> {
            Optional<UUID> guideUuid = bindings.stream()
                    .filter(b -> b.npcId().equalsIgnoreCase(guideId))
                    .map(NpcBindingRepository.Binding::citizensUuid)
                    .findFirst();
            if (guideUuid.isEmpty()) {
                return done(new ResolvedSpot(false, "GUIDE_NOT_LINKED",
                        "Le Guide « " + guideId + " » n'est lié à aucun PNJ Citizens : sa position est "
                                + "inconnue. Donner une position explicite, ou lier le Guide d'abord.",
                        null, null, null, null));
            }
            return onMain(() -> {
                Optional<CitizensNpc> guide = npcIdentityService.citizensRoster().stream()
                        .filter(n -> n.uuid().equals(guideUuid.get())).findFirst();
                if (guide.isEmpty() || !guide.get().hasLocation()) {
                    return done(new ResolvedSpot(false, "GUIDE_LOCATION_UNKNOWN",
                            "Citizens n'expose aucune position pour le Guide « " + guideId + " » : "
                                    + "impossible de placer un PNJ à côté. Donner une position explicite.",
                            null, null, null, null));
                }
                CitizensNpc g = guide.get();
                if (!g.world().equalsIgnoreCase(hubWorld)) {
                    return done(new ResolvedSpot(false, "GUIDE_OUTSIDE_HUB",
                            "Le Guide « " + guideId + " » est dans « " + g.world() + " », alors que le Hub "
                                    + "configuré est « " + hubWorld + " » : le placement automatique est "
                                    + "ambigu. Donner une position explicite, ou corriger « hub.world ».",
                            null, null, null, null));
                }
                World w = plugin.getServer().getWorld(hubWorld);
                if (w == null) {
                    return done(new ResolvedSpot(false, "HUB_NOT_LOADED",
                            "Le monde Hub « " + hubWorld + " » n'est pas chargé.", null, null, null, null));
                }
                // Cases déjà occupées par un PNJ Citizens, pour ne jamais en superposer deux.
                java.util.Set<String> occupied = new java.util.HashSet<>();
                for (CitizensNpc other : npcIdentityService.citizensRoster()) {
                    if (other.hasLocation() && other.world().equalsIgnoreCase(hubWorld)) {
                        int ox = (int) Math.floor(other.x());
                        int oy = (int) Math.floor(other.y());
                        int oz = (int) Math.floor(other.z());
                        for (int dy = 0; dy < NpcPlacementPlanner.REQUIRED_CLEARANCE; dy++) {
                            occupied.add(BukkitNpcPlacementProbe.cellKey(ox, oy + dy, oz));
                        }
                    }
                }
                NpcPlacementPlanner.Limits limits = new NpcPlacementPlanner.Limits(
                        hubConfig.get().placementRadius(), hubConfig.get().placementVertical(),
                        hubConfig.get().placementAttempts());
                NpcPlacementPlanner.Result found = NpcPlacementPlanner.findNearest(
                        (int) Math.floor(g.x()), (int) Math.floor(g.y()), (int) Math.floor(g.z()),
                        limits, new BukkitNpcPlacementProbe(w, occupied));
                if (!found.found()) {
                    return done(new ResolvedSpot(false, "NO_SAFE_SPOT",
                            "Aucun emplacement libre et sûr près du Guide (" + found.inspected()
                                    + " emplacements examinés, rayon " + limits.horizontalRadius()
                                    + " blocs" + (found.failure() == NpcPlacementPlanner.Failure.BUDGET_EXHAUSTED
                                    ? ", budget d'essais épuisé" : "") + "). Donner une position explicite, "
                                    + "ou dégager l'espace autour du Guide.",
                            null, null, null, null));
                }
                NpcPlacementPlanner.Spot s = found.spot().orElseThrow();
                return done(new ResolvedSpot(true, null, null, hubWorld,
                        s.x() + 0.5, (double) s.y(), s.z() + 0.5));
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> citizensMove(String npcId, String world,
                                                          double x, double y, double z,
                                                          float yaw, float pitch) {
        String worldName = world == null ? "" : world.trim();
        String positionError = CitizensSpawnPlanner.positionError(x, y, z, yaw, pitch);
        if (positionError != null) {
            return done(MutationResult.of(false, "INVALID_POSITION", positionError));
        }
        Set<String> allowed = allowedSpawnWorlds.get();
        if (worldName.isEmpty() || allowed.stream().noneMatch(w -> w.equalsIgnoreCase(worldName))) {
            return done(MutationResult.of(false, "UNKNOWN_WORLD",
                    "Monde « " + safe(world) + " » hors de la liste blanche RPGQuest " + allowed + "."));
        }
        if (!npcIdentityService.citizensAvailable()) {
            return done(MutationResult.of(false, "CITIZENS_UNAVAILABLE", "Citizens n'est pas disponible."));
        }
        return citizensUuidOf(npcId).thenCompose(uuid -> {
            if (uuid.isEmpty()) {
                return done(MutationResult.of(false, "NO_CITIZENS_BINDING",
                        "Aucun PNJ Citizens lié à « " + safe(npcId) + " » — le lier d'abord."));
            }
            UUID citizensUuid = uuid.get();
            return onMain(() -> {
                World w = plugin.getServer().getWorld(worldName);
                if (w == null) {
                    return done(MutationResult.of(false, "UNKNOWN_WORLD",
                            "Le monde « " + safe(worldName) + " » n'est pas chargé sur le serveur."));
                }
                if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
                    return done(MutationResult.of(false, "INVALID_POSITION",
                            "Y hors des limites réelles de « " + worldName + " » ("
                                    + w.getMinHeight() + " à " + (w.getMaxHeight() - 1) + ")."));
                }
                // Sûreté de l'arrivée : même critère que le placement automatique — sol praticable
                // et deux cases libres. On ne casse ni ne pose jamais de bloc pour y arriver.
                NpcPlacementPlanner.Probe probe = new BukkitNpcPlacementProbe(w, java.util.Set.of());
                int bx = (int) Math.floor(x);
                int by = (int) Math.floor(y);
                int bz = (int) Math.floor(z);
                if (!NpcPlacementPlanner.isAcceptable(bx, by, bz, probe)) {
                    return done(MutationResult.of(false, "UNSAFE_DESTINATION",
                            "Destination refusée : il faut un sol praticable et deux cases libres "
                                    + "au-dessus, sans liquide ni portail. La position actuelle du PNJ "
                                    + "est inchangée."));
                }

                Optional<org.bukkit.Location> before = npcIdentityService.citizensRoster().stream()
                        .filter(n -> n.uuid().equals(citizensUuid) && n.hasLocation())
                        .findFirst()
                        .map(n -> new org.bukkit.Location(plugin.getServer().getWorld(n.world()),
                                n.x(), n.y(), n.z()));

                org.bukkit.Location target = new org.bukkit.Location(w, x, y, z, yaw, pitch);
                Optional<org.bukkit.Location> after = npcIdentityService.moveCitizens(citizensUuid, target);
                if (after.isEmpty()) {
                    return done(MutationResult.of(false, "CITIZENS_NPC_MISSING",
                            "Le PNJ Citizens lié est introuvable dans le registre (supprimé ?)."));
                }
                org.bukkit.Location reached = after.get();
                boolean sameWorld = reached.getWorld() != null
                        && reached.getWorld().getName().equalsIgnoreCase(worldName);
                boolean close = Math.abs(reached.getX() - x) < 1.0 && Math.abs(reached.getY() - y) < 1.0
                        && Math.abs(reached.getZ() - z) < 1.0;
                if (!sameWorld || !close) {
                    // Vérification réelle plutôt que supposition : Citizens n'a pas appliqué.
                    return done(MutationResult.of(false, "MOVE_NOT_APPLIED",
                            "Citizens n'a pas appliqué le déplacement : la position enregistrée reste "
                                    + describe(reached) + (before.isPresent()
                                    ? " (demandée : " + worldName + " " + fmt(x) + " / " + fmt(y) + " / "
                                            + fmt(z) + ")" : "") + "."));
                }
                boolean spawned = npcIdentityService.citizensRoster().stream()
                        .anyMatch(n -> n.uuid().equals(citizensUuid) && n.spawned());
                return done(new MutationResult(true, "MOVED",
                        "PNJ déplacé vers " + describe(reached) + "."
                                + (spawned ? "" : " Ce PNJ n'était pas matérialisé : c'est sa "
                                        + "position ENREGISTRÉE qui a changé, et il n'a pas été "
                                        + "fait apparaître pour autant.")
                                + " Identité Citizens, identifiant RPGQuest, skin, traits et liaisons "
                                + "dialogues/quêtes inchangés.",
                        List.of("citizens:moved=" + describe(reached))));
            });
        });
    }

    /** « monde x / y / z », pour un message lisible. */
    private static String describe(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            return "(position inconnue)";
        }
        return location.getWorld().getName() + " " + fmt(location.getX()) + " / "
                + fmt(location.getY()) + " / " + fmt(location.getZ());
    }

    /** UUID Citizens lié à un id logique RPGQuest, depuis la liaison persistée (base, async). */
    private CompletableFuture<Optional<UUID>> citizensUuidOf(String npcId) {
        String id = npcId == null ? "" : npcId.trim();
        return npcBindingRepository.loadAll().thenApply(bindings -> bindings.stream()
                .filter(b -> b.npcId().equalsIgnoreCase(id))
                .map(NpcBindingRepository.Binding::citizensUuid)
                .findFirst());
    }

    /**
     * Issue #165 — « regarder les joueurs ». État <strong>explicite</strong> : {@code enabled} est
     * posé tel quel, jamais inversé. Rejouer la requête donne donc le même résultat.
     */
    @Override
    public CompletableFuture<MutationResult> citizensLookClose(String npcId, boolean enabled, Double range) {
        return withBoundCitizens(npcId, uuid ->
                toMutation(npcIdentityService.setLookClose(uuid, enabled, range)));
    }

    /**
     * Issue #165 — promenade. Activer exige une ancre : sans elle, Citizens ne borne pas la zone.
     * Quand aucune n'est fournie, on prend la position <strong>actuellement connue</strong> du PNJ
     * et on le dit — on n'invente jamais un point de départ.
     */
    @Override
    public CompletableFuture<MutationResult> citizensWander(String npcId, boolean enabled,
                                                            String world, Double x, Double y, Double z,
                                                            int xRange, int yRange, boolean confirmReplace) {
        if (!enabled) {
            return withBoundCitizens(npcId, uuid -> toMutation(npcIdentityService.disableWander(uuid)));
        }
        return withBoundCitizens(npcId, uuid -> {
            org.bukkit.Location anchor = resolveAnchor(uuid, world, x, y, z);
            if (anchor == null) {
                return MutationResult.of(false, "NO_ANCHOR",
                        "Aucune ancre utilisable : ce PNJ n'a pas de position connue et aucune n'a été "
                                + "fournie. Sans ancre, Citizens ne bornerait pas la promenade — rien "
                                + "n'a été modifié.");
            }
            return toMutation(npcIdentityService.enableWander(uuid, anchor, xRange, yRange, confirmReplace));
        });
    }

    /**
     * Ancre explicite si les quatre champs sont donnés et le monde chargé ; sinon la position
     * actuellement connue du PNJ. Jamais une position inventée.
     */
    private org.bukkit.Location resolveAnchor(UUID citizensUuid, String world, Double x, Double y, Double z) {
        if (world != null && !world.isBlank() && x != null && y != null && z != null) {
            World w = plugin.getServer().getWorld(world.trim());
            return w == null ? null : new org.bukkit.Location(w, x, y, z);
        }
        return npcIdentityService.citizensRoster().stream()
                .filter(n -> n.uuid().equals(citizensUuid) && n.hasLocation())
                .findFirst()
                .map(n -> {
                    World w = plugin.getServer().getWorld(n.world());
                    return w == null ? null : new org.bukkit.Location(w, n.x(), n.y(), n.z());
                })
                .orElse(null);
    }

    /**
     * Garde commune aux écritures de comportement : Citizens disponible, PNJ effectivement lié,
     * puis exécution sur le thread principal — l'API Citizens l'exige.
     */
    private CompletableFuture<MutationResult> withBoundCitizens(
            String npcId, java.util.function.Function<UUID, MutationResult> body) {
        if (!npcIdentityService.citizensAvailable()) {
            return done(MutationResult.of(false, "CITIZENS_UNAVAILABLE", "Citizens n'est pas disponible."));
        }
        return citizensUuidOf(npcId).thenCompose(uuid -> {
            if (uuid.isEmpty()) {
                return done(MutationResult.of(false, "NO_CITIZENS_BINDING",
                        "Aucun PNJ Citizens lié à « " + safe(npcId) + " » — le lier d'abord."));
            }
            return onMain(() -> done(body.apply(uuid.get())));
        });
    }

    private static MutationResult toMutation(com.lodygames.rpgquest.npc.NpcBehaviourOutcome outcome) {
        return MutationResult.of(outcome.ok(), outcome.code(), outcome.message());
    }

    @Override
    public CompletableFuture<CitizensRosterView> citizensRoster() {
        boolean available = npcIdentityService.citizensAvailable();
        if (!available) {
            return done(new CitizensRosterView(false, List.of(), 0, 0, 0));
        }
        // Registre Citizens = API main-thread ; liaisons = base async. Croisement sans lecture du monde.
        return npcBindingRepository.loadAll().thenCompose(bindings -> {
            Map<UUID, String> linkedByUuid = new HashMap<>();
            for (NpcBindingRepository.Binding b : bindings) {
                linkedByUuid.put(b.citizensUuid(), b.npcId());
            }
            return onMain(() -> {
                List<CitizensNpcSummary> rows = new ArrayList<>();
                int available2 = 0;
                int linked = 0;
                for (CitizensNpc n : npcIdentityService.citizensRoster()) {
                    String linkedNpcId = linkedByUuid.get(n.uuid());
                    boolean free = linkedNpcId == null;
                    if (free) {
                        available2++;
                    } else {
                        linked++;
                    }
                    rows.add(new CitizensNpcSummary(n.numericId(), n.uuid().toString(), n.name(),
                            linkedNpcId, free, n.spawned(),
                            n.hasLocation() ? n.world() : null,
                            n.hasLocation() ? n.x() : null, n.hasLocation() ? n.y() : null,
                            n.hasLocation() ? n.z() : null,
                            n.hasLocation() ? n.yaw() : null, n.hasLocation() ? n.pitch() : null,
                            n.liveLocation(), n.shouldSpawn(), n.chunkLoaded(),
                            // Comportements (issue #165) : null = INCONNU (build Citizens qui ne les
                            // expose pas), jamais « désactivé ». La fiche doit pouvoir le dire.
                            n.lookClose() == null ? null : n.lookClose().enabled(),
                            n.lookClose() == null ? null : n.lookClose().range(),
                            n.wander() == null ? null : n.wander().enabled(),
                            n.wander() == null ? null : n.wander().provider(),
                            n.wander() == null ? null : n.wander().waypointCount(),
                            n.wander() == null || !n.wander().hasAnchor() ? null : n.wander().anchorWorld(),
                            n.wander() == null || !n.wander().hasAnchor() ? null : n.wander().anchorX(),
                            n.wander() == null || !n.wander().hasAnchor() ? null : n.wander().anchorY(),
                            n.wander() == null || !n.wander().hasAnchor() ? null : n.wander().anchorZ(),
                            n.wander() == null ? null : n.wander().xRange(),
                            n.wander() == null ? null : n.wander().yRange()));
                }
                rows.sort((a, b) -> Integer.compare(a.numericId(), b.numericId()));
                return done(new CitizensRosterView(true, List.copyOf(rows), rows.size(), available2, linked));
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> citizensLink(String npcId, int citizensNumericId) {
        var definition = npcEngine.find(npcId);
        if (definition.isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_NPC",
                    "Aucune définition logique « " + safe(npcId) + " » — créer d'abord la définition."));
        }
        if (!definition.get().enabled()) {
            return done(MutationResult.of(false, "DISABLED",
                    "La définition « " + npcId + " » est désactivée — la réactiver avant de lier."));
        }
        if (!npcIdentityService.citizensAvailable()) {
            return done(MutationResult.of(false, "CITIZENS_UNAVAILABLE",
                    "Citizens n'est pas actif sur ce serveur."));
        }
        // 1) Résoudre le PNJ Citizens sur le thread principal, 2) écrire la liaison en base (async).
        return onMain(() -> done(npcIdentityService.citizensByNumericId(citizensNumericId).orElse(null)))
                .thenCompose(ref -> {
                    if (ref == null) {
                        return done(MutationResult.of(false, "UNKNOWN_CITIZENS",
                                "Aucun PNJ Citizens #" + citizensNumericId + " dans le registre."));
                    }
                    return npcIdentityService.bindCitizens(npcId, ref).thenApply(bind ->
                            new MutationResult(bind.ok(), bind.code(), bind.message(),
                                    bind.ok() ? List.of("Citizens #" + bind.citizensNumericId() + " <-> " + npcId)
                                              : List.of()));
                });
    }

    @Override
    public CompletableFuture<CitizensCreateResult> citizensCreate(String npcId, String world,
                                                                  double x, double y, double z,
                                                                  float yaw, float pitch) {
        Optional<NpcDefinition> definition = npcEngine.find(npcId);
        boolean present = definition.isPresent();
        boolean enabled = present && definition.get().enabled();
        boolean citizensAvailable = npcIdentityService.citizensAvailable();
        Set<String> allowed = allowedSpawnWorlds.get();
        String worldName = world == null ? "" : world.trim();
        String posLabel = worldName + " " + fmt(x) + " / " + fmt(y) + " / " + fmt(z)
                + " (yaw " + fmt(yaw) + " / pitch " + fmt(pitch) + ")";

        // 1) Préconditions logiques pures — besoin des bindings pour « npc_id déjà lié ? » (base, async).
        return npcBindingRepository.loadAll().thenCompose(bindings -> {
            boolean npcIdLinked = bindings.stream().anyMatch(b -> b.npcId().equals(npcId));
            CitizensSpawnPlanner.Plan plan = CitizensSpawnPlanner.plan(npcId, present, enabled, npcIdLinked,
                    citizensAvailable, worldName, allowed, x, y, z, yaw, pitch);
            if (plan.action() == CitizensSpawnPlanner.Action.REJECT) {
                return done(CitizensCreateResult.reject(npcId, plan.code(), plan.message()));
            }
            String displayName = definition.get().displayName();

            // 2) Thread principal : le monde doit être chargé + Y dans les limites réelles du monde.
            return onMain(() -> {
                World w = plugin.getServer().getWorld(worldName);
                if (w == null) {
                    return done(CitizensCreateResult.reject(npcId, "UNKNOWN_WORLD",
                            "Le monde « " + safe(worldName) + " » n'est pas chargé sur le serveur cible."));
                }
                if (y < w.getMinHeight() || y >= w.getMaxHeight()) {
                    return done(CitizensCreateResult.reject(npcId, "INVALID_POSITION",
                            "Y=" + fmt(y) + " hors des limites du monde « " + w.getName() + " » ("
                                    + w.getMinHeight() + " à " + w.getMaxHeight() + ")."));
                }
                return done((CitizensCreateResult) null);
            }).thenCompose(worldFailure -> {
                if (worldFailure != null) {
                    return done(worldFailure);
                }
                // 3) create -> bind -> (ok | rollback) : orchestration pure, collaborateurs hoppant sur le main.
                CitizensSpawnCoordinator.Spawner spawner = new CitizensSpawnCoordinator.Spawner() {
                    @Override
                    public CompletableFuture<Optional<CitizensNpc>> createAndSpawn() {
                        return onMain(() -> {
                            World w = plugin.getServer().getWorld(worldName);
                            if (w == null) {
                                return done(Optional.<CitizensNpc>empty());
                            }
                            return done(npcIdentityService.createCitizensNpc(displayName,
                                    new Location(w, x, y, z, yaw, pitch)));
                        });
                    }

                    @Override
                    public CompletableFuture<Boolean> destroyCreated(CitizensNpc created) {
                        return onMain(() -> done(
                                npcIdentityService.destroyCitizensNpc(created.numericId(), created.uuid())));
                    }
                };
                return CitizensSpawnCoordinator.run(npcId, posLabel, spawner, npcIdentityService::bindCitizens)
                        .thenApply(r -> new CitizensCreateResult(r.ok(), r.code(), r.message(),
                                r.citizensNumericId(), r.npcId(), r.effects(), r.rolledBack()));
            });
        }).exceptionally(error -> new CitizensCreateResult(false, "ERROR",
                "Échec du spawn : " + rootName(error), null, npcId, List.of(), false));
    }

    /** Formate un nombre : entier si rond, sinon décimal court. */
    private static String fmt(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String fmt(float v) {
        return fmt((double) v);
    }

    // ---- Dialogues (V1 /dialogues) -----------------------------------------------------------

    @Override
    public CompletableFuture<DialogueCatalogView> dialogueDefinitions() {
        // Extraction Bukkit -> types simples, puis dérivation pure par DialogueCatalog. Aucun accès
        // disque hors du reload déjà fait au démarrage / après écriture.
        Set<String> knownQuestIds = new java.util.LinkedHashSet<>();
        for (QuestDefinition q : questEngine.quests()) {
            knownQuestIds.add(q.id().toString().toLowerCase(Locale.ROOT));
        }

        Set<String> canonicalNpcIds = new java.util.LinkedHashSet<>();
        List<DialogueCatalog.NpcDialogueDecl> npcDecls = new ArrayList<>();
        for (NpcDefinition d : npcEngine.definitions()) {
            canonicalNpcIds.add(d.id().toLowerCase(Locale.ROOT));
            if (d.dialogueId() != null) {
                npcDecls.add(new DialogueCatalog.NpcDialogueDecl(d.id(), d.dialogueId()));
            }
        }
        for (QuestDefinition q : questEngine.quests()) {
            if (q.giver() != null && !q.giver().isBlank()) {
                canonicalNpcIds.add(q.giver().trim().toLowerCase(Locale.ROOT));
            }
            for (QuestStep step : q.steps()) {
                for (QuestObjective objective : step.objectives()) {
                    if (objective instanceof TalkToNpcObjective t && t.npcId() != null) {
                        canonicalNpcIds.add(t.npcId().trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        List<DialogueCatalog.LogicalDialogue> logical = new ArrayList<>();
        for (DialogueDefinition d : dialogueEngine.dialogues()) {
            logical.add(new DialogueCatalog.LogicalDialogue(
                    d.id().toString().toLowerCase(Locale.ROOT), d.startNodeId(), orderedNodes(d)));
        }

        List<DialogueCatalog.LoadIssue> issues = new ArrayList<>();
        dialogueEngine.lastReport().issues().forEach(i ->
                issues.add(new DialogueCatalog.LoadIssue(i.file(), i.message())));

        DialogueCatalog.View view = DialogueCatalog.build(logical, issues, npcDecls, canonicalNpcIds, knownQuestIds);
        return done(toView(view));
    }

    /** Nœuds ordonnés pour l'affichage : le nœud de départ d'abord, puis les autres par id. */
    private static List<DialogueCatalog.Node> orderedNodes(DialogueDefinition d) {
        List<DialogueNode> nodes = new ArrayList<>(d.nodes().values());
        nodes.sort((a, b) -> {
            boolean sa = a.id().equals(d.startNodeId());
            boolean sb = b.id().equals(d.startNodeId());
            return sa != sb ? (sa ? -1 : 1) : a.id().compareTo(b.id());
        });
        List<DialogueCatalog.Node> out = new ArrayList<>();
        for (DialogueNode n : nodes) {
            List<DialogueCatalog.Choice> choices = new ArrayList<>();
            for (DialogueChoice c : n.choices()) {
                List<DialogueCatalog.Action> actions = new ArrayList<>();
                for (DialogueAction a : c.actions()) {
                    actions.add(actionSummary(a));
                }
                List<DialogueCatalog.Condition> conditions = new ArrayList<>();
                for (DialogueCondition cond : c.conditions()) {
                    conditions.add(conditionSummary(cond));
                }
                choices.add(new DialogueCatalog.Choice(c.text().base(), c.next(), actions, conditions));
            }
            out.add(new DialogueCatalog.Node(n.id(), n.speaker(), n.text().base(), choices));
        }
        return out;
    }

    private static DialogueCatalog.Action actionSummary(DialogueAction a) {
        return switch (a) {
            case StartQuestAction x -> new DialogueCatalog.Action("START_QUEST", x.questId().toString(), null,
                    "START_QUEST " + x.questId());
            case AdvanceQuestAction x -> new DialogueCatalog.Action("ADVANCE_QUEST", x.questId().toString(), null,
                    "ADVANCE_QUEST " + x.questId());
            case TurnInQuestAction x -> new DialogueCatalog.Action("TURN_IN_QUEST", x.questId().toString(), null,
                    "TURN_IN_QUEST " + x.questId());
            case GiveItemAction x -> new DialogueCatalog.Action("GIVE_ITEM", x.material().name(),
                    Integer.toString(x.amount()), "GIVE_ITEM " + x.amount() + "x " + x.material().name());
            case TakeItemAction x -> new DialogueCatalog.Action("TAKE_ITEM", x.material().name(),
                    Integer.toString(x.amount()), "TAKE_ITEM " + x.amount() + "x " + x.material().name());
            case SetVariableAction x -> new DialogueCatalog.Action("SET_VARIABLE", x.key(), x.value(),
                    "SET_VARIABLE " + x.key() + " = " + x.value());
            case RunSafeCommandAction x -> new DialogueCatalog.Action("RUN_SAFE_COMMAND",
                    x.command().strip().split("\\s+", 2)[0], x.command(), "RUN_SAFE_COMMAND " + x.command());
            case OpenDialogueAction x -> new DialogueCatalog.Action("OPEN_DIALOGUE", x.dialogueId().toString(), null,
                    "OPEN_DIALOGUE " + x.dialogueId());
            case OpenMerchantAction x -> new DialogueCatalog.Action("OPEN_MERCHANT", x.merchantId().toString(), null,
                    "OPEN_MERCHANT " + x.merchantId());
            case GiveStarterKitAction ignored ->
                    new DialogueCatalog.Action("GIVE_STARTER_KIT", null, null, "GIVE_STARTER_KIT");
            case DeliverQuestItemsAction x -> new DialogueCatalog.Action("DELIVER_QUEST_ITEMS", x.npcId(), null,
                    x.npcId() == null ? "DELIVER_QUEST_ITEMS (PNJ du dialogue)" : "DELIVER_QUEST_ITEMS " + x.npcId());
            case CloseAction ignored -> new DialogueCatalog.Action("CLOSE", null, null, "CLOSE");
        };
    }

    private static DialogueCatalog.Condition conditionSummary(DialogueCondition c) {
        if (c instanceof NegatedCondition negated) {
            DialogueCatalog.Condition inner = conditionSummary(negated.inner());
            return new DialogueCatalog.Condition(inner.kind(), inner.target(), inner.value(),
                    "NOT(" + inner.raw() + ")", true);
        }
        return switch (c) {
            case QuestStateCondition x -> new DialogueCatalog.Condition("QUEST_STATE", x.questId().toString(),
                    x.state().name(), "QUEST_STATE " + x.questId() + " = " + x.state(), false);
            case HasItemCondition x -> new DialogueCatalog.Condition("HAS_ITEM", x.material().name(),
                    Integer.toString(x.amount()), "HAS_ITEM " + x.amount() + "x " + x.material().name(), false);
            case HasPermissionCondition x -> new DialogueCatalog.Condition("HAS_PERMISSION", x.permission(), null,
                    "HAS_PERMISSION " + x.permission(), false);
            case VariableEqualsCondition x -> new DialogueCatalog.Condition("VARIABLE_EQUALS", x.key(), x.value(),
                    "VARIABLE_EQUALS " + x.key() + " = " + x.value(), false);
            case LacksCustomItemCondition x -> new DialogueCatalog.Condition("LACKS_CUSTOM_ITEM",
                    x.itemId().toString(), null, "LACKS_CUSTOM_ITEM " + x.itemId(), false);
            case PendingDeliveryCondition x -> new DialogueCatalog.Condition("HAS_PENDING_DELIVERY", x.npcId(), null,
                    x.npcId() == null ? "HAS_PENDING_DELIVERY (PNJ du dialogue)" : "HAS_PENDING_DELIVERY " + x.npcId(),
                    false);
            case NegatedCondition ignored -> throw new IllegalStateException("négation déjà traitée");
            default -> new DialogueCatalog.Condition(c.type().name(), null, null, c.type().name(), false);
        };
    }

    private static DialogueCatalogView toView(DialogueCatalog.View v) {
        List<DialogueSummary> dialogues = new ArrayList<>();
        for (DialogueCatalog.DialogueSummary d : v.dialogues()) {
            List<DialogueNodeSummary> nodes = new ArrayList<>();
            for (DialogueCatalog.NodeSummary n : d.nodes()) {
                List<DialogueChoiceSummary> choices = new ArrayList<>();
                for (DialogueCatalog.ChoiceSummary c : n.choices()) {
                    List<DialogueActionSummary> acts = new ArrayList<>();
                    for (DialogueCatalog.ActionSummary a : c.actions()) {
                        acts.add(new DialogueActionSummary(a.kind(), a.target(), a.value(), a.raw()));
                    }
                    List<DialogueConditionSummary> conds = new ArrayList<>();
                    for (DialogueCatalog.ConditionSummary cs : c.conditions()) {
                        conds.add(new DialogueConditionSummary(cs.kind(), cs.target(), cs.value(), cs.raw(), cs.negated()));
                    }
                    choices.add(new DialogueChoiceSummary(c.text(), c.nextNodeId(), acts, conds));
                }
                nodes.add(new DialogueNodeSummary(n.id(), n.speaker(), n.text(), n.start(), n.reachable(), choices));
            }
            List<DialogueWarning> warnings = new ArrayList<>();
            for (DialogueCatalog.Warning w : d.warnings()) {
                warnings.add(new DialogueWarning(w.code(), w.severity(), w.message()));
            }
            dialogues.add(new DialogueSummary(d.id(), d.key(), d.startNodeId(), d.linkedNpcIds(),
                    d.nodeCount(), d.choiceCount(), d.referencedQuestIds(), d.startsQuestIds(),
                    List.copyOf(nodes), List.copyOf(warnings)));
        }
        List<DialogueLoadIssueSummary> loadIssues = new ArrayList<>();
        for (DialogueCatalog.LoadIssueSummary li : v.loadIssues()) {
            loadIssues.add(new DialogueLoadIssueSummary(li.file(), li.message()));
        }
        List<DialogueMissingDeclared> missing = new ArrayList<>();
        for (DialogueCatalog.MissingDeclared m : v.declaredButMissing()) {
            missing.add(new DialogueMissingDeclared(m.npcId(), m.dialogueId()));
        }
        return new DialogueCatalogView(List.copyOf(dialogues), List.copyOf(loadIssues), List.copyOf(missing),
                v.total(), v.withWarnings(), v.nodeTotal());
    }

    @Override
    public CompletableFuture<MutationResult> dialogueDefinitionCreate(String key, String speaker, String text) {
        DialogueDraft draft;
        try {
            draft = DialogueDraft.skeleton(key, speaker, text, "Au revoir");
        } catch (IllegalArgumentException e) {
            return done(MutationResult.of(false, "INVALID", e.getMessage()));
        }
        DialogueDefinitionStore.Result r = dialogueStore.create(draft);
        if (!r.ok()) {
            String msg = r.issues().isEmpty() ? r.message() : r.message() + " — " + String.join(" ; ", r.issues());
            return done(MutationResult.of(false, r.code(), msg));
        }
        // Rechargement en mémoire pour que le dialogue soit immédiatement ouvrable en jeu.
        dialogueEngine.reload();
        return done(new MutationResult(true, r.code(), r.message(),
                List.of("dialogues/" + r.file(), "start: " + draft.startNodeId())));
    }

    // ---- Édition guidée d'un dialogue existant (issue #82 phase 1) -----------------------------
    //
    // L'IO disque + le re-parse se font sur le thread appelant (poll asynchrone de l'agent), jamais
    // sur le thread principal — comme dialogueDefinitionCreate. YamlDialogueEngine.reload() se
    // contente de relire les fichiers et d'échanger une référence volatile : sûr hors thread
    // principal.

    @Override
    public CompletableFuture<MutationResult> dialogueNodeUpdate(String dialogueId, String nodeId, String speaker,
                                                                String text) {
        return applyEdit(dialogueEditor.updateNode(dialogueId, nodeId, speaker, text));
    }

    @Override
    public CompletableFuture<MutationResult> dialogueNodeCreate(String dialogueId, String nodeId, String speaker,
                                                                String text) {
        return applyEdit(dialogueEditor.createNode(dialogueId, nodeId, speaker, text, null));
    }

    @Override
    public CompletableFuture<MutationResult> dialogueChoiceAdd(String dialogueId, String nodeId, String choiceText,
                                                               String nextNodeId, boolean close) {
        return applyEdit(dialogueEditor.addChoice(dialogueId, nodeId, choiceText, nextNodeId, close));
    }

    @Override
    public CompletableFuture<MutationResult> dialogueChoiceUpdate(
            String dialogueId, String nodeId, int choiceIndex, String choiceText, String nextNodeId, boolean close,
            DialogueDefinitionEditor.QuestActionEdit questAction,
            DialogueDefinitionEditor.QuestConditionEdit questCondition) {
        return applyEdit(dialogueEditor.updateChoice(dialogueId, nodeId, choiceIndex, choiceText, nextNodeId, close,
                questAction, questCondition));
    }

    @Override
    public CompletableFuture<MutationResult> dialogueChoiceDelete(String dialogueId, String nodeId, int choiceIndex) {
        return applyEdit(dialogueEditor.deleteChoice(dialogueId, nodeId, choiceIndex));
    }

    private CompletableFuture<MutationResult> applyEdit(DialogueDefinitionEditor.Result r) {
        if (!r.ok()) {
            String msg = r.issues().isEmpty() ? r.message() : r.message() + " — " + String.join(" ; ", r.issues());
            return done(MutationResult.of(false, r.code(), msg));
        }
        // Rechargement en mémoire : le dialogue édité est immédiatement pris en compte en jeu.
        dialogueEngine.reload();
        return done(new MutationResult(true, r.code(), r.message(), r.effects()));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public CompletableFuture<ResetPreview> resetPreview(UUID playerId) {
        return onMain(() -> playerResetService.previewReset(playerId).thenApply(preview -> {
            List<ResetPreviewLine> lines = new ArrayList<>();
            for (PlayerResetService.ResetCategory c : preview.categories()) {
                lines.add(new ResetPreviewLine(c.label(), c.count(), c.detail()));
            }
            return new ResetPreview(preview.online(), lines);
        }));
    }

    // ---- Mutations -----------------------------------------------------------------------------

    @Override
    public CompletableFuture<MutationResult> questStart(UUID playerId, String rawQuestId, boolean force) {
        NamespacedKey questId = resolveKey(rawQuestId);
        if (questId == null || questEngine.find(questId).isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_QUEST", "Quête inconnue : " + safe(rawQuestId)));
        }
        return onMain(() -> {
            Player target = plugin.getServer().getPlayer(playerId);
            if (target == null) {
                return done(MutationResult.of(false, "OFFLINE",
                        "Le joueur doit être connecté pour démarrer une quête."));
            }
            return questProgressEngine.accept(target, questId, force).thenApply(outcome -> switch (outcome.result()) {
                case ACCEPTED -> new MutationResult(true, "ACCEPTED",
                        "Quête démarrée : " + questId + (force ? " (prérequis ignorés)" : ""), List.of());
                case ALREADY_ACTIVE -> MutationResult.of(false, "ALREADY_ACTIVE", "Quête déjà active : " + questId);
                case NOT_REPEATABLE -> MutationResult.of(false, "NOT_REPEATABLE",
                        "Déjà terminée (non répétable) : " + questId + " — réinitialiser d'abord.");
                case MISSING_PREREQUISITES -> new MutationResult(false, "MISSING_PREREQUISITES",
                        "Prérequis manquants — cocher « ignorer les prérequis » pour passer outre.",
                        outcome.missingPrerequisites().stream().map(NamespacedKey::toString).toList());
                case UNKNOWN_QUEST -> MutationResult.of(false, "UNKNOWN_QUEST", "Quête inconnue : " + questId);
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> questComplete(UUID playerId, String rawQuestId) {
        NamespacedKey questId = resolveKey(rawQuestId);
        if (questId == null || questEngine.find(questId).isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_QUEST", "Quête inconnue : " + safe(rawQuestId)));
        }
        List<String> effects = describeRewards(questEngine.find(questId).get().rewards());
        return onMain(() -> {
            Player target = plugin.getServer().getPlayer(playerId);
            if (target == null) {
                return done(MutationResult.of(false, "OFFLINE",
                        "Le joueur doit être connecté pour compléter une quête."));
            }
            return questProgressEngine.forceComplete(target, questId).thenApply(outcome -> switch (outcome) {
                case COMPLETED -> new MutationResult(true, "COMPLETED",
                        "Quête terminée : " + questId + " — récompenses appliquées.", effects);
                case ALREADY_COMPLETED -> MutationResult.of(false, "ALREADY_COMPLETED",
                        "Déjà terminée : " + questId + " — aucune récompense re-créditée.");
                case UNKNOWN_QUEST -> MutationResult.of(false, "UNKNOWN_QUEST", "Quête inconnue : " + questId);
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> questReset(UUID playerId, String rawQuestId) {
        NamespacedKey questId = resolveKey(rawQuestId);
        if (questId == null || questEngine.find(questId).isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_QUEST", "Quête inconnue : " + safe(rawQuestId)));
        }
        return questProgressEngine.resetQuest(playerId, questId)
                .thenApply(v -> new MutationResult(true, "RESET",
                        "Quête réinitialisée (progression + compteurs) : " + questId,
                        List.of("Un reset ne retire PAS les récompenses déjà accordées (XP, objets, "
                                + "variables comme CLAIM_TIER_1).")))
                .exceptionally(e -> MutationResult.of(false, "ERROR", "Échec du reset : " + rootName(e)));
    }

    @Override
    public CompletableFuture<MutationResult> storyAdvance(UUID playerId, String storyId) {
        String id = storyId == null ? "" : storyId.toLowerCase(Locale.ROOT).trim();
        return onMain(() -> {
            Player target = plugin.getServer().getPlayer(playerId);
            if (target == null) {
                return done(MutationResult.of(false, "OFFLINE",
                        "Le joueur doit être connecté pour avancer une story."));
            }
            return storyService.adminAdvance(target, id).thenApply(r -> switch (r.outcome()) {
                case ADVANCED -> new MutationResult(true, "ADVANCED",
                        "Story " + id + " : étape " + r.stepNumber() + "/" + r.totalSteps()
                                + " maintenant active (" + r.nextQuestId() + ").",
                        List.of("Quête complétée : " + r.completedQuestId()
                                + (r.completedQuestWasAlreadyDone() ? " (déjà faite)" : " (récompenses appliquées)")));
                case STORY_COMPLETED -> new MutationResult(true, "STORY_COMPLETED",
                        "Story " + id + " TERMINÉE.",
                        List.of("Dernière quête complétée : " + r.completedQuestId()));
                case ALREADY_COMPLETED -> MutationResult.of(false, "ALREADY_COMPLETED",
                        "Story " + id + " déjà terminée.");
                case UNKNOWN_STORY -> MutationResult.of(false, "UNKNOWN_STORY", "Story inconnue : " + safe(id));
                default -> MutationResult.of(false, r.outcome().name(),
                        "Story " + id + " : " + r.outcome().name() + " (voir la console).");
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> storyComplete(UUID playerId, String storyId) {
        String id = storyId == null ? "" : storyId.toLowerCase(Locale.ROOT).trim();
        return onMain(() -> {
            Player target = plugin.getServer().getPlayer(playerId);
            if (target == null) {
                return done(MutationResult.of(false, "OFFLINE",
                        "Le joueur doit être connecté pour compléter une story."));
            }
            return storyService.adminComplete(target, id).thenApply(r -> {
                List<String> quests = r.completedQuests().stream().map(NamespacedKey::toString).toList();
                return switch (r.outcome()) {
                    case COMPLETED -> new MutationResult(true, "COMPLETED",
                            "Story " + id + " complétée. Toutes les récompenses (dont les VARIABLE "
                                    + "comme CLAIM_TIER_1) appliquées une seule fois.", quests);
                    case ALREADY_COMPLETED -> MutationResult.of(false, "ALREADY_COMPLETED",
                            "Story " + id + " déjà terminée.");
                    case BLOCKED -> new MutationResult(false, "BLOCKED",
                            "Story " + id + " : arrêtée sur une quête problématique ("
                                    + r.blockedOnQuestId() + ").", quests);
                    case UNKNOWN_STORY -> MutationResult.of(false, "UNKNOWN_STORY", "Story inconnue : " + safe(id));
                };
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> giveItem(UUID playerId, String rawItemId, int amount) {
        if (amount <= 0 || amount > MAX_GIVE_AMOUNT) {
            return done(MutationResult.of(false, "BAD_AMOUNT",
                    "Quantité invalide (attendu 1 à " + MAX_GIVE_AMOUNT + ")."));
        }
        NamespacedKey itemId = resolveKey(rawItemId);
        if (itemId == null || customItemRegistry.find(itemId).isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_ITEM", "Objet inconnu : " + safe(rawItemId)));
        }
        String displayName = customItemRegistry.find(itemId).map(CustomItemDefinition::displayName).orElse(itemId.toString());
        return onMain(() -> {
            Player target = plugin.getServer().getPlayer(playerId);
            if (target == null) {
                return done(MutationResult.of(false, "OFFLINE",
                        "Le joueur doit être connecté pour recevoir un objet."));
            }
            Optional<ItemStack> stack = customItemRegistry.create(itemId, amount);
            if (stack.isEmpty()) {
                return done(MutationResult.of(false, "ITEM_BUILD_FAILED",
                        "Impossible de fabriquer l'objet " + itemId + "."));
            }
            var leftover = target.getInventory().addItem(stack.get());
            int dropped = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (dropped > 0) {
                leftover.values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
            }
            String msg = amount + "x " + itemId + " donné à " + target.getName()
                    + (dropped > 0 ? " (" + dropped + " au sol, inventaire plein)" : "");
            return done(new MutationResult(true, "GIVEN", msg,
                    List.of(amount + "x « " + displayName + " » (" + itemId + ")")));
        });
    }

    @Override
    public CompletableFuture<MutationResult> resetConfirm(UUID playerId, String playerName) {
        return onMain(() -> playerResetService.resetToNewPlayer(playerId, playerName == null ? "" : playerName)
                .thenApply(summary -> new MutationResult(true, "RESET_DONE",
                        "Joueur remis à zéro (état RPGQuest « jamais joué »).",
                        List.of(summary.online()
                                ? "Objets RPGQuest retirés : " + Math.max(summary.inventoryItemsRemoved(), 0)
                                : "Joueur hors ligne — nettoyage d'inventaire différé à sa prochaine connexion.")))
                .exceptionally(e -> MutationResult.of(false, "ERROR", "Échec du reset : " + rootName(e))));
    }

    @Override
    public CompletableFuture<MutationResult> variableSet(UUID playerId, String key, String value) {
        String v = value == null ? "" : value;
        return variableWriter.set(playerId, key, v)
                .thenApply(x -> new MutationResult(true, "SET",
                        "Variable " + key + " = " + v + " (outil debug bas niveau).",
                        List.of("Une écriture de variable ne reproduit jamais une progression de "
                                + "quête / story à elle seule.")))
                .exceptionally(e -> MutationResult.of(false, "ERROR", "Échec de l'écriture : " + rootName(e)));
    }

    @Override
    public CompletableFuture<MutationResult> banPlayer(UUID playerId, String playerName, String reason) {
        String cleanReason = reason == null || reason.isBlank() ? "Banni du serveur." : reason.trim();
        String label = playerName != null && !playerName.isBlank() ? playerName : playerId.toString();
        return onMain(() -> {
            try {
                OfflinePlayer op = plugin.getServer().getOfflinePlayer(playerId);
                ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
                PlayerProfile profile = op.getPlayerProfile();
                boolean already = bans.isBanned(profile);
                bans.addBan(profile, cleanReason, (Instant) null, "PlugAdmin");
                List<String> effects = new ArrayList<>();
                effects.add("Bannissement permanent (BanList de profil Paper).");
                Player online = op.getPlayer();
                if (online != null) {
                    online.kick(Component.text("Banni : " + cleanReason));
                    effects.add(label + " était connecté — expulsé.");
                }
                return done(new MutationResult(true, already ? "ALREADY_BANNED" : "BANNED",
                        already ? label + " était déjà banni — raison mise à jour." : label + " a été banni.",
                        List.copyOf(effects)));
            } catch (RuntimeException e) {
                return done(MutationResult.of(false, "ERROR", "Échec du bannissement : " + rootName(e)));
            }
        });
    }

    @Override
    public CompletableFuture<MutationResult> unbanPlayer(UUID playerId, String playerName) {
        String label = playerName != null && !playerName.isBlank() ? playerName : playerId.toString();
        return onMain(() -> {
            try {
                OfflinePlayer op = plugin.getServer().getOfflinePlayer(playerId);
                ProfileBanList bans = plugin.getServer().getBanList(BanListType.PROFILE);
                PlayerProfile profile = op.getPlayerProfile();
                if (!bans.isBanned(profile)) {
                    return done(new MutationResult(true, "NOT_BANNED", label + " n'était pas banni.", List.of()));
                }
                bans.pardon(profile);
                return done(new MutationResult(true, "UNBANNED", label + " a été débanni.",
                        List.of("Le joueur peut de nouveau rejoindre le serveur.")));
            } catch (RuntimeException e) {
                return done(MutationResult.of(false, "ERROR", "Échec du débannissement : " + rootName(e)));
            }
        });
    }

    // ---- Réseau de voyage (action travel.catalog, issue #152) — lecture seule ------------------

    @Override
    public CompletableFuture<TravelCatalogView> travelCatalog() {
        return onMain(() -> {
            List<Waypoint> waypoints = waypointService.all();
            List<TravelBeacon> beacons = travelBeaconService.all();

            Map<String, String> beaconIdByInstance = new HashMap<>();
            Map<String, String> waypointIdByInstance = new HashMap<>();
            for (TravelBeacon b : beacons) {
                if (b.isAutoGenerated()) {
                    beaconIdByInstance.put(instanceKey(b.world(), b.biomeInstance()), b.id());
                }
            }
            for (Waypoint w : waypoints) {
                waypointIdByInstance.put(instanceKey(w.world(), w.biomeInstance()), w.id());
            }

            List<WaypointSummary> waypointSummaries = new ArrayList<>();
            for (Waypoint w : waypoints) {
                String pairedBeaconId = beaconIdByInstance.get(instanceKey(w.world(), w.biomeInstance()));
                waypointSummaries.add(new WaypointSummary(w.id(), w.displayName(), w.world(), w.biomeKey(),
                        w.biomeInstance(), w.x(), w.y(), w.z(), w.active(), w.modelVersion(), pairedBeaconId));
            }

            List<BeaconSummary> beaconSummaries = new ArrayList<>();
            int autoGenerated = 0;
            for (TravelBeacon b : beacons) {
                String pairedWaypointId = b.isAutoGenerated()
                        ? waypointIdByInstance.get(instanceKey(b.world(), b.biomeInstance())) : null;
                if (b.isAutoGenerated()) {
                    autoGenerated++;
                }
                beaconSummaries.add(new BeaconSummary(b.id(), b.world(), b.x(), b.y(), b.z(), b.active(),
                        b.modelVersion(), b.isAutoGenerated(), b.biomeInstance(), pairedWaypointId));
            }

            // Issue #156 : la raison de chaque appariement manquant, pas seulement sa liste.
            List<UnpairedHubInstance> unpaired = new ArrayList<>();
            for (TravelBeaconService.PairingGap gap : travelBeaconService.hubPairingGaps()) {
                unpaired.add(new UnpairedHubInstance(gap.waypointId(), gap.biomeInstance(), gap.biomeKey(),
                        gap.x(), gap.z(), gap.attempts(), gap.nextRetryEpochMs(), gap.inProgress(),
                        gap.nearestBeaconDistance()));
            }

            TravelCatalogView view = new TravelCatalogView(List.copyOf(waypointSummaries), List.copyOf(beaconSummaries),
                    waypointSummaries.size(), beaconSummaries.size(), autoGenerated, List.copyOf(unpaired),
                    System.currentTimeMillis());
            return CompletableFuture.completedFuture(view);
        });
    }

    private static String instanceKey(String world, String biomeInstance) {
        return world + "#" + biomeInstance;
    }

    // ---- Mobs spéciaux / boss (issue #169, lot 1) ----------------------------------------------

    @Override
    public CompletableFuture<MobCatalogView> mobDefinitions() {
        return onMain(() -> {
            SpecialMobLoadReport report = mobRegistry.lastReport();
            List<MobProfileSummary> profiles = new ArrayList<>();
            for (SpecialMobDefinition def : report.loaded()) {
                profiles.add(toSummary(def));
            }
            MobSpawnSettings settings = mobSpawnSettingsStore.current();
            List<String> issues = new ArrayList<>();
            for (SpecialMobLoadIssue issue : report.issues()) {
                issues.add(issue.file() + " : " + issue.message());
            }
            return done(new MobCatalogView(List.copyOf(profiles),
                    new MobSpawnSettingsView(settings.enabled(), settings.chance(), settings.maxSimultaneousSpecial()),
                    !issues.isEmpty(), List.copyOf(issues)));
        });
    }

    private MobProfileSummary toSummary(SpecialMobDefinition def) {
        Double enragedHealthFraction = null;
        Double enragedSpeedMultiplier = null;
        Double enragedDamageMultiplier = null;
        String summonEntityType = null;
        Integer summonAmount = null;
        Double summonChance = null;
        Integer summonCooldownSeconds = null;
        Integer summonMaxAlive = null;
        List<String> abilitiesSummary = new ArrayList<>();
        for (MobAbility ability : def.abilities()) {
            switch (ability) {
                case EnragedAbility a -> {
                    enragedHealthFraction = a.healthFraction();
                    enragedSpeedMultiplier = a.speedMultiplier();
                    enragedDamageMultiplier = a.damageMultiplier();
                    abilitiesSummary.add("Enragé sous " + Math.round(a.healthFraction() * 100) + "% PV (x"
                            + a.speedMultiplier() + " vitesse, x" + a.damageMultiplier() + " dégâts)");
                }
                case SummonOnDamageAbility a -> {
                    summonEntityType = a.summonedEntityType().name();
                    summonAmount = a.amount();
                    summonChance = a.chance();
                    summonCooldownSeconds = a.cooldownSeconds();
                    summonMaxAlive = a.maxAlive();
                    abilitiesSummary.add("Invoque " + a.amount() + "x " + a.summonedEntityType()
                            + " (" + Math.round(a.chance() * 100) + "%, cooldown " + a.cooldownSeconds()
                            + "s, max " + a.maxAlive() + ")");
                }
                case com.lodygames.rpgquest.mob.model.StrongerExplosionAbility a ->
                        abilitiesSummary.add("Explosion x" + a.radiusMultiplier());
                case com.lodygames.rpgquest.mob.model.ExplosiveOnAttackAbility a ->
                        abilitiesSummary.add("Explosif au contact (portée " + a.triggerRangeBlocks() + ")");
                case com.lodygames.rpgquest.mob.model.SplitOnHitAbility a ->
                        abilitiesSummary.add("Se divise (profondeur " + a.maxDepth() + ", x" + a.maxChildrenPerHit() + ")");
            }
        }
        return new MobProfileSummary(def.id().asString(), def.category().name(), def.enabled(),
                def.entityType().name(), def.displayName(), def.spawnChance(),
                List.copyOf(def.allowedWorlds()), List.copyOf(def.allowedBiomes()), List.copyOf(def.allowedZones()),
                def.health(), def.damage(), def.speed(), def.armor(), def.knockbackResistance(), def.scale(),
                def.creeperExplosionRadius(), def.particle() == null ? null : def.particle().name(),
                def.sound() == null ? null : def.sound().name(), def.xpReward(), def.maxPopulation(),
                mobService.populationOf(def.id()), enragedHealthFraction, enragedSpeedMultiplier,
                enragedDamageMultiplier, summonEntityType, summonAmount, summonChance, summonCooldownSeconds,
                summonMaxAlive, List.copyOf(abilitiesSummary));
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionCreate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive) {
        return writeMobDefinition(id, category, enabled, entityType, displayName, spawnChance, worlds, biomes, zones,
                health, damage, speed, armor, knockbackResistance, scale, creeperExplosionRadius, particle, sound,
                xpReward, maxPopulation, enragedHealthFraction, enragedSpeedMultiplier, enragedDamageMultiplier,
                summonEntityType, summonAmount, summonChance, summonCooldownSeconds, summonMaxAlive, true);
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionUpdate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive) {
        return writeMobDefinition(id, category, enabled, entityType, displayName, spawnChance, worlds, biomes, zones,
                health, damage, speed, armor, knockbackResistance, scale, creeperExplosionRadius, particle, sound,
                xpReward, maxPopulation, enragedHealthFraction, enragedSpeedMultiplier, enragedDamageMultiplier,
                summonEntityType, summonAmount, summonChance, summonCooldownSeconds, summonMaxAlive, false);
    }

    @SuppressWarnings("removal") // Particle/Sound#valueOf : voir la justification dans SpecialMobDefinitionParser.
    private CompletableFuture<MutationResult> writeMobDefinition(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive, boolean create) {
        List<MobAbility> abilities = new ArrayList<>();
        try {
            if (enragedHealthFraction != null || enragedSpeedMultiplier != null || enragedDamageMultiplier != null) {
                abilities.add(new EnragedAbility(enragedHealthFraction, enragedSpeedMultiplier, enragedDamageMultiplier));
            }
            if (summonEntityType != null || summonAmount != null || summonChance != null
                    || summonCooldownSeconds != null || summonMaxAlive != null) {
                org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.fromName(
                        summonEntityType == null ? "" : summonEntityType.toLowerCase(Locale.ROOT));
                abilities.add(new SummonOnDamageAbility(type, summonAmount, summonChance, summonCooldownSeconds, summonMaxAlive));
            }

            SpecialMobDefinition definition = new SpecialMobDefinition(
                    resolveKey(id), MobCategory.valueOf(category.toUpperCase(Locale.ROOT)), enabled,
                    org.bukkit.entity.EntityType.fromName(entityType.toLowerCase(Locale.ROOT)), displayName, spawnChance,
                    Set.copyOf(worlds), Set.copyOf(biomes), Set.copyOf(zones), health, damage, speed, armor,
                    knockbackResistance, scale, creeperExplosionRadius,
                    particle == null ? null : org.bukkit.Particle.valueOf(particle.toUpperCase(Locale.ROOT)),
                    sound == null ? null : org.bukkit.Sound.valueOf(sound.toUpperCase(Locale.ROOT)),
                    abilities, List.of(), xpReward, maxPopulation);

            SpecialMobDefinitionStore.Result r = create
                    ? mobDefinitionStore.create(definition) : mobDefinitionStore.update(definition);
            mobRegistry.reload();
            List<String> effects = new ArrayList<>();
            if (r.file() != null) {
                effects.add("mobs/" + r.file());
            }
            if (r.report() != null) {
                for (SpecialMobLoadIssue issue : r.report().issues()) {
                    effects.add("⚠ " + issue.file() + " : " + issue.message());
                }
            }
            return done(new MutationResult(r.ok(), r.code(), r.message(), List.copyOf(effects)));
        } catch (IllegalArgumentException | NullPointerException e) {
            return done(MutationResult.of(false, "INVALID", e.getMessage()));
        }
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionToggle(String id, boolean enabled) {
        NamespacedKey key = resolveKey(id);
        if (key == null) {
            return done(MutationResult.of(false, "INVALID", "Identifiant de profil invalide : " + safe(id)));
        }
        Optional<SpecialMobDefinition> existing = mobDefinitionStore.find(key);
        if (existing.isEmpty()) {
            return done(MutationResult.of(false, "NOT_FOUND", "Aucun profil « " + key + " » à basculer."));
        }
        SpecialMobDefinition current = existing.get();
        SpecialMobDefinition toggled = new SpecialMobDefinition(current.id(), current.category(), enabled,
                current.entityType(), current.displayName(), current.spawnChance(), current.allowedWorlds(),
                current.allowedBiomes(), current.allowedZones(), current.health(), current.damage(), current.speed(),
                current.armor(), current.knockbackResistance(), current.scale(), current.creeperExplosionRadius(),
                current.particle(), current.sound(), current.abilities(), current.drops(), current.xpReward(),
                current.maxPopulation());
        SpecialMobDefinitionStore.Result r = mobDefinitionStore.update(toggled);
        mobRegistry.reload();
        return done(new MutationResult(r.ok(), r.code(), r.message(), List.of()));
    }

    @Override
    public CompletableFuture<MutationResult> mobSpawnSettingsSet(boolean enabled, double chance, Integer maxSimultaneousSpecial) {
        try {
            MobSpawnSettings settings = new MobSpawnSettings(enabled, chance, maxSimultaneousSpecial);
            mobSpawnSettingsStore.save(settings);
            return done(MutationResult.of(true, "UPDATED", "Throttle Wild mis à jour."));
        } catch (IllegalArgumentException e) {
            return done(MutationResult.of(false, "INVALID", e.getMessage()));
        } catch (java.io.IOException e) {
            return done(MutationResult.of(false, "ERROR", "Écriture impossible : " + e.getMessage()));
        }
    }

    @Override
    public CompletableFuture<MutationResult> mobTestSpawn(String definitionId, String playerName) {
        NamespacedKey key = resolveKey(definitionId);
        if (key == null) {
            return done(MutationResult.of(false, "INVALID", "Identifiant de profil invalide : " + safe(definitionId)));
        }
        Optional<SpecialMobDefinition> def = mobRegistry.find(key);
        if (def.isEmpty()) {
            return done(MutationResult.of(false, "UNKNOWN_MOB", "Profil inconnu : " + key));
        }
        return onMain(() -> {
            Player player = plugin.getServer().getPlayerExact(playerName);
            if (player == null) {
                return done(MutationResult.of(false, "OFFLINE", "Joueur hors ligne : " + safe(playerName)));
            }
            String wildWorld = wildWorldSupplier.get();
            if (wildWorld == null || !wildWorld.equalsIgnoreCase(player.getWorld().getName())) {
                return done(MutationResult.of(false, "NOT_IN_WILD",
                        player.getName() + " doit être dans le monde Wild (" + wildWorld + ") pour ce test."));
            }
            Optional<Location> safeLocation = mobService.findTestSpawnLocation(player);
            if (safeLocation.isEmpty()) {
                return done(MutationResult.of(false, "NO_SAFE_LOCATION",
                        "Aucune position sûre trouvée près de " + player.getName() + "."));
            }
            org.bukkit.entity.LivingEntity entity = (org.bukkit.entity.LivingEntity)
                    player.getWorld().spawnEntity(safeLocation.get(), def.get().entityType());
            mobService.applyTestInstance(entity, def.get());
            return done(new MutationResult(true, "SPAWNED",
                    "Instance de test « " + key + " » apparue près de " + player.getName() + ".", List.of()));
        });
    }

    @Override
    public CompletableFuture<MutationResult> mobTestClear() {
        return onMain(() -> {
            int removed = mobService.clearTestInstances();
            return done(new MutationResult(true, "CLEARED", removed + " instance(s) de test supprimée(s).", List.of()));
        });
    }

    // ---- Exploitation serveur (issue #95) -----------------------------------------------------

    @Override
    public CompletableFuture<AnnounceResult> announce(String message, String channel) {
        // Envoi à des entités Bukkit : thread principal obligatoire.
        return onMain(() -> {
            ServerOpsService.AnnounceOutcome outcome = serverOpsService.announce(message, channel);
            return done(new AnnounceResult(outcome.ok(), outcome.code(), outcome.message(),
                    outcome.channel(), outcome.recipients(), outcome.online()));
        });
    }

    @Override
    public CompletableFuture<ServerLogsView> serverLogs(long afterSequence, int limit) {
        // Lecture d'un tampon en mémoire, synchronisé : aucun accès disque, aucun besoin du main.
        ServerLogBuffer.Snapshot snapshot = serverOpsService.logs(afterSequence, limit);
        List<ServerLogLine> lines = new ArrayList<>();
        for (ServerLogBuffer.Line line : snapshot.lines()) {
            lines.add(new ServerLogLine(line.sequence(), line.epochMillis(), line.level(),
                    line.source(), line.message()));
        }
        return CompletableFuture.completedFuture(new ServerLogsView(List.copyOf(lines),
                snapshot.firstSequence(), snapshot.lastSequence(), snapshot.dropped(),
                snapshot.capacity(), snapshot.gap(), serverOpsService.consoleLimitation().orElse(null)));
    }

    // ---- Rechargement du contenu (issue #131) -------------------------------------------------

    @Override
    public CompletableFuture<ContentReloadView> contentReload(List<String> families, boolean apply) {
        java.util.Set<ReloadFamily> requested = new java.util.LinkedHashSet<>();
        for (String token : families) {
            java.util.Optional<ReloadFamily> family = ReloadFamily.fromWire(token);
            if (family.isEmpty()) {
                return CompletableFuture.completedFuture(new ContentReloadView(false, "UNKNOWN_FAMILY",
                        "Famille de contenu inconnue : « " + safe(token) + " ». Attendu : "
                                + String.join(", ", java.util.Arrays.stream(ReloadFamily.values())
                                        .map(ReloadFamily::wire).toList()) + ".",
                        List.of(), List.of(), List.of(), contentReloadService.runtimeHash(), 0L, false));
            }
            requested.add(family.get());
        }
        // Le rechargement permute des ensembles lus par des listeners du thread principal :
        // on l'exécute donc SUR le thread principal, comme toute mutation d'état de jeu.
        return onMain(() -> {
            ContentReloadService.ReloadResult result = apply
                    ? contentReloadService.reload(requested)
                    : contentReloadService.preview(requested);
            List<ContentReloadFamilyView> familyViews = new ArrayList<>();
            for (ContentReloadService.FamilyOutcome outcome : result.families()) {
                familyViews.add(new ContentReloadFamilyView(outcome.family().wire(),
                        outcome.family().label(), outcome.loaded(), outcome.issues(),
                        outcome.messages(), outcome.ids()));
            }
            return done(new ContentReloadView(result.applied(), result.code(), result.message(),
                    List.copyOf(familyViews), result.referenceErrors(),
                    result.suggestedFamilies().stream().map(ReloadFamily::wire).toList(),
                    result.runtimeHash(), result.durationMillis(), result.restartRequired()));
        });
    }

    // ---- Administration de joueur (issue #210) ------------------------------------------------

    @Override
    public CompletableFuture<MutationResult> setOperator(UUID playerId, String playerName, boolean op) {
        String label = label(playerName, playerId);
        return onMain(() -> {
            try {
                OfflinePlayer target = plugin.getServer().getOfflinePlayer(playerId);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    // Jamais d'élévation d'un UUID inconnu : ce serait accorder OP à un compte que
                    // le serveur n'a jamais vu, sur la seule foi d'une saisie.
                    return done(MutationResult.of(false, "UNKNOWN_PLAYER",
                            label + " n'est pas connu de ce serveur : aucune élévation possible."));
                }
                if (target.isOp() == op) {
                    return done(new MutationResult(true, op ? "ALREADY_OP" : "NOT_OP",
                            label + (op ? " était déjà OP." : " n'était pas OP."), List.of()));
                }
                target.setOp(op);
                // Relecture : on rapporte l'état RÉEL, pas l'intention.
                boolean actual = plugin.getServer().getOfflinePlayer(playerId).isOp();
                if (actual != op) {
                    return done(MutationResult.of(false, "NOT_APPLIED",
                            "Le statut OP de " + label + " n'a pas changé (relu : "
                                    + (actual ? "OP" : "non OP") + ")."));
                }
                plugin.getSLF4JLogger().warn("[player-admin] {} est désormais {} (OP Minecraft).",
                        label, op ? "OP" : "non OP");
                return done(new MutationResult(true, op ? "OPPED" : "DEOPPED",
                        label + (op ? " est désormais OP." : " n'est plus OP."),
                        List.of("OP Minecraft uniquement : rôle PlugAdmin, droits de construction et "
                                + "bypass de gameplay sont inchangés.")));
            } catch (RuntimeException e) {
                return done(MutationResult.of(false, "ERROR", "Échec : " + rootName(e)));
            }
        });
    }

    @Override
    public CompletableFuture<MutationResult> sendToHub(UUID playerId, String playerName) {
        String label = label(playerName, playerId);
        return onMain(() -> {
            try {
                Player online = plugin.getServer().getPlayer(playerId);
                if (online == null) {
                    return done(MutationResult.of(false, "OFFLINE",
                            label + " est hors ligne : un renvoi au Hub exige un joueur connecté."));
                }
                // Même source que la Pierre de retour et le filet de sécurité des claims : le spawn
                // du village résolu, jamais une coordonnée figée.
                java.util.Optional<org.bukkit.Location> target = hubRescueTarget.get();
                if (target.isEmpty()) {
                    return done(MutationResult.of(false, "NO_DESTINATION",
                            "Aucune position de Hub ne se résout : renvoi impossible. Vérifier le "
                                    + "spawn du village."));
                }
                String fromWorld = online.getWorld().getName();
                online.teleport(target.get());
                // Relecture : on ne rapporte un succès que si le joueur a réellement bougé.
                String toWorld = online.getWorld().getName();
                plugin.getSLF4JLogger().info("[player-admin] {} renvoyé au Hub ({} -> {}).",
                        label, fromWorld, toWorld);
                return done(new MutationResult(true, "SENT",
                        label + " a été renvoyé au Hub (" + fromWorld + " → " + toWorld + ").",
                        List.of("Inventaire, Acte de propriété, claim et progression inchangés.")));
            } catch (RuntimeException e) {
                return done(MutationResult.of(false, "ERROR", "Échec : " + rootName(e)));
            }
        });
    }

    @Override
    public CompletableFuture<MutationResult> kickPlayer(UUID playerId, String playerName, String reason) {
        String label = label(playerName, playerId);
        return onMain(() -> {
            try {
                Player online = plugin.getServer().getPlayer(playerId);
                if (online == null) {
                    return done(MutationResult.of(false, "OFFLINE",
                            label + " est hors ligne : rien à expulser."));
                }
                String cleanReason = reason == null || reason.isBlank() ? "Expulsé par un administrateur"
                        : reason.strip();
                // Texte littéral : une raison n'est ni une commande, ni du MiniMessage.
                online.kick(Component.text(cleanReason));
                plugin.getSLF4JLogger().info("[player-admin] {} expulsé : {}", label, cleanReason);
                return done(new MutationResult(true, "KICKED",
                        label + " a été expulsé.", List.of("Raison affichée : " + cleanReason)));
            } catch (RuntimeException e) {
                return done(MutationResult.of(false, "ERROR", "Échec : " + rootName(e)));
            }
        });
    }

    @Override
    public CompletableFuture<MutationResult> setWhitelisted(UUID playerId, String playerName,
                                                            boolean whitelisted) {
        String label = label(playerName, playerId);
        return onMain(() -> {
            try {
                OfflinePlayer target = plugin.getServer().getOfflinePlayer(playerId);
                boolean enforced = plugin.getServer().hasWhitelist();
                if (target.isWhitelisted() == whitelisted) {
                    return done(new MutationResult(true, whitelisted ? "ALREADY_WHITELISTED" : "NOT_WHITELISTED",
                            label + (whitelisted ? " était déjà sur la whitelist."
                                    : " n'était pas sur la whitelist."),
                            whitelistNote(enforced)));
                }
                target.setWhitelisted(whitelisted);
                boolean actual = plugin.getServer().getOfflinePlayer(playerId).isWhitelisted();
                if (actual != whitelisted) {
                    return done(MutationResult.of(false, "NOT_APPLIED",
                            "La whitelist de " + label + " n'a pas changé (relu : "
                                    + (actual ? "présent" : "absent") + ")."));
                }
                plugin.getSLF4JLogger().info("[player-admin] whitelist {} : {}",
                        whitelisted ? "+" : "-", label);
                return done(new MutationResult(true, whitelisted ? "WHITELISTED" : "UNWHITELISTED",
                        label + (whitelisted ? " a été ajouté à la whitelist."
                                : " a été retiré de la whitelist."),
                        whitelistNote(enforced)));
            } catch (RuntimeException e) {
                return done(MutationResult.of(false, "ERROR", "Échec : " + rootName(e)));
            }
        });
    }

    /**
     * Dit si la whitelist est réellement appliquée. L'omettre laisserait croire qu'ajouter un joueur
     * protège le serveur alors que la whitelist peut être désactivée.
     */
    private static List<String> whitelistNote(boolean enforced) {
        return enforced ? List.of("La whitelist est active sur ce serveur.")
                : List.of("Attention : la whitelist est DÉSACTIVÉE sur ce serveur — cette liste "
                        + "n'a donc aucun effet tant qu'elle n'est pas activée.");
    }

    private static String label(String playerName, UUID playerId) {
        return playerName != null && !playerName.isBlank() ? playerName : playerId.toString();
    }

    // ---- Monnaie (issue #140) -----------------------------------------------------------------

    @Override
    public CompletableFuture<EconomyBalanceView> economyBalance(UUID playerId, int historyLimit) {
        if (economyService == null || walletRepository == null) {
            return CompletableFuture.completedFuture(new EconomyBalanceView(false,
                    "Économie indisponible sur ce serveur.", 0L, List.of()));
        }
        // Lectures SQLite asynchrones : jamais sur le thread principal.
        return economyService.balance(playerId).thenCompose(balance ->
                walletRepository.history(playerId, historyLimit).thenApply(entries -> {
                    List<EconomyLedgerView> history = new ArrayList<>();
                    for (WalletRepository.LedgerEntry entry : entries) {
                        history.add(new EconomyLedgerView(entry.type(), entry.amount(),
                                entry.context(), entry.createdAt()));
                    }
                    return new EconomyBalanceView(true, "Solde relu.", balance, List.copyOf(history));
                }))
                .exceptionally(err -> new EconomyBalanceView(false,
                        "Lecture du solde impossible : " + rootName(err), 0L, List.of()));
    }

    @Override
    public CompletableFuture<EconomyAdjustView> economyAdjust(UUID playerId, String playerName,
                                                              long amount, boolean credit, String reason) {
        String label = label(playerName, playerId);
        if (economyService == null) {
            return CompletableFuture.completedFuture(new EconomyAdjustView(false, "ERROR",
                    "Économie indisponible sur ce serveur.", 0L, 0L));
        }
        if (amount <= 0) {
            return CompletableFuture.completedFuture(new EconomyAdjustView(false, "INVALID_AMOUNT",
                    "Le montant doit être strictement positif.", 0L, 0L));
        }
        String context = (credit ? "panel-credit" : "panel-debit") + " : " + reason;
        return economyService.balance(playerId).thenCompose(before -> {
            if (credit) {
                return economyService.credit(playerId, amount, TransactionType.ADMIN_GRANT, context)
                        .thenCompose(ignored -> economyService.balance(playerId))
                        .thenApply(after -> {
                            plugin.getSLF4JLogger().info(
                                    "[economy] crédit de {} à {} ({} -> {}) : {}",
                                    amount, label, before, after, reason);
                            return new EconomyAdjustView(true, "CREDITED",
                                    label + " crédité de " + amount + " (solde : " + before + " → "
                                            + after + ").", before, after);
                        });
            }
            return economyService.debit(playerId, amount, TransactionType.ADMIN_TAKE, context)
                    .thenCompose(applied -> economyService.balance(playerId)
                            .thenApply(after -> {
                                if (!applied) {
                                    // Refus MÉTIER lisible, pas une erreur technique : le solde
                                    // n'a pas bougé et ne peut pas devenir négatif par ce chemin.
                                    return new EconomyAdjustView(false, "INSUFFICIENT_FUNDS",
                                            "Fonds insuffisants : " + label + " possède " + after
                                                    + ", débit de " + amount + " refusé. Solde inchangé.",
                                            before, after);
                                }
                                plugin.getSLF4JLogger().info(
                                        "[economy] débit de {} à {} ({} -> {}) : {}",
                                        amount, label, before, after, reason);
                                return new EconomyAdjustView(true, "DEBITED",
                                        label + " débité de " + amount + " (solde : " + before + " → "
                                                + after + ").", before, after);
                            }));
        }).exceptionally(err -> new EconomyAdjustView(false, "ERROR",
                "Opération impossible : " + rootName(err), 0L, 0L));
    }

    // ---- Utilitaires --------------------------------------------------------------------------

    /** Exécute {@code body} sur un thread <strong>asynchrone</strong> Bukkit (lecture disque, jamais le main). */
    private <T> CompletableFuture<T> async(Supplier<T> body) {
        CompletableFuture<T> out = new CompletableFuture<>();
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    out.complete(body.get());
                } catch (RuntimeException e) {
                    out.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            out.completeExceptionally(e);
        }
        return out;
    }

    /** Exécute {@code body} sur le thread principal et relaie le futur produit. */
    private <T> CompletableFuture<T> onMain(Supplier<CompletableFuture<T>> body) {
        CompletableFuture<T> out = new CompletableFuture<>();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    body.get().whenComplete((value, error) -> {
                        if (error != null) {
                            out.completeExceptionally(error);
                        } else {
                            out.complete(value);
                        }
                    });
                } catch (RuntimeException e) {
                    out.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            // Plugin en cours d'arrêt : le scheduler refuse la tâche.
            out.completeExceptionally(e);
        }
        return out;
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static NamespacedKey resolveKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return trimmed.contains(":")
                    ? NamespacedKey.fromString(trimmed)
                    : new NamespacedKey(DEFAULT_NAMESPACE, trimmed.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- Pont vers les droits Minecraft (issue #200) -------------------------------------------

    @Override
    public CompletableFuture<McRightsView> mcRightsRead(UUID playerId) {
        LuckPermsBridge.Availability availability = luckPermsBridge.availability();
        if (!availability.available()) {
            // État RÉEL et non un échec technique : le pont est simplement absent, et le panel doit
            // pouvoir l'afficher tel quel plutôt que de montrer une erreur énigmatique.
            return CompletableFuture.completedFuture(new McRightsView(true,
                    "Pont indisponible.", false, availability.reason(), List.of()));
        }
        return luckPermsBridge.readBridgeProvenance(playerId)
                .thenApply(lines -> new McRightsView(true,
                        lines.isEmpty() ? "Aucun droit géré porté par ce joueur."
                                : lines.size() + " droit(s) géré(s) porté(s).",
                        true, null, List.copyOf(lines)))
                .exceptionally(err -> new McRightsView(false,
                        "Lecture impossible : " + rootName(err), true, rootName(err), List.of()));
    }

    @Override
    public CompletableFuture<McSyncView> mcGroupSync(String groupId, String displayName,
                                                     List<McNodeSpec> nodes) {
        Set<ManagedNode> wanted = new LinkedHashSet<>();
        for (McNodeSpec spec : nodes) {
            wanted.add(new ManagedNode(spec.node(), spec.world()));
        }
        return luckPermsBridge.syncGroupDefinition(groupId, displayName, wanted)
                .thenApply(BukkitAgentActions::toSyncView)
                .exceptionally(err -> failedSync("Écriture impossible : " + rootName(err)));
    }

    @Override
    public CompletableFuture<McSyncView> mcGroupDelete(String groupId) {
        return luckPermsBridge.deleteGroup(groupId)
                .thenApply(BukkitAgentActions::toSyncView)
                .exceptionally(err -> failedSync("Suppression impossible : " + rootName(err)));
    }

    @Override
    public CompletableFuture<McSyncView> mcRightsSync(UUID playerId, List<String> groupIds) {
        return luckPermsBridge.syncUserGroups(playerId, new LinkedHashSet<>(groupIds))
                .thenApply(BukkitAgentActions::toSyncView)
                .exceptionally(err -> failedSync("Synchronisation impossible : " + rootName(err)));
    }

    private static McSyncView toSyncView(LuckPermsBridge.SyncResult result) {
        return new McSyncView(result.ok(), result.message(), result.added(), result.removed(),
                result.unchanged(), result.preserved());
    }

    private static McSyncView failedSync(String message) {
        return new McSyncView(false, message + " Aucun droit n'a été modifié.",
                List.of(), List.of(), List.of(), List.of());
    }

    // ---- Récompenses monétaires restées dues (issue #16, second lot) ---------------------------

    @Override
    public CompletableFuture<QuestRewardDebtsView> questRewardDebts(UUID playerId, int limit) {
        if (economyService == null) {
            return CompletableFuture.completedFuture(new QuestRewardDebtsView(false,
                    "Économie indisponible sur ce serveur.", List.of()));
        }
        return economyService.pendingQuestRewards(playerId, limit).thenApply(dues -> {
            List<QuestRewardDebtView> out = new ArrayList<>();
            for (QuestRewardDue due : dues) {
                out.add(new QuestRewardDebtView(due.grantId(), due.questId(), questTitleOrId(due.questId()),
                        due.occurrence(), due.rewardIndex(), due.amount(), due.attempts(),
                        due.lastError(), null));
            }
            return new QuestRewardDebtsView(true,
                    out.isEmpty() ? "Aucune récompense monétaire en attente."
                            : out.size() + " récompense(s) monétaire(s) en attente.",
                    List.copyOf(out));
        }).exceptionally(err -> new QuestRewardDebtsView(false,
                "Lecture des récompenses dues impossible : " + rootName(err), List.of()));
    }

    /**
     * Titre lisible de la quête, ou son identifiant si la définition n'existe plus. On ne devine
     * <strong>jamais</strong> un titre : une quête supprimée laisse une dette bien réelle, et
     * afficher son identifiant est plus honnête qu'un nom inventé.
     */
    private String questTitleOrId(String questId) {
        NamespacedKey key = NamespacedKey.fromString(questId);
        if (key == null) {
            return questId;
        }
        return questEngine.find(key).map(quest -> quest.title().base()).orElse(questId);
    }

    @Override
    public CompletableFuture<QuestRewardRetryView> retryQuestRewardDebt(UUID playerId, String grantId) {
        if (economyService == null) {
            return CompletableFuture.completedFuture(new QuestRewardRetryView(false, "ERROR",
                    "Économie indisponible sur ce serveur.", 0L, 0L));
        }
        // La dette est relue d'abord pour vérifier qu'elle appartient BIEN au joueur ciblé : sans
        // ce contrôle, un identifiant copié d'une autre fiche créditerait le mauvais joueur.
        return economyService.questRewardDebt(grantId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(new QuestRewardRetryView(false, "UNKNOWN_DEBT",
                        "Aucune récompense due sous cet identifiant.", 0L, 0L));
            }
            if (!found.get().playerId().equals(playerId)) {
                return CompletableFuture.completedFuture(new QuestRewardRetryView(false, "WRONG_PLAYER",
                        "Cette récompense due appartient à un autre joueur : rien n'a été fait.", 0L, 0L));
            }
            return economyService.payQuestReward(playerId, grantId).thenApply(receipt -> switch (receipt.status()) {
                case CREDITED -> new QuestRewardRetryView(true, "PAID",
                        "Récompense créditée : " + receipt.amount() + " pièce(s). Nouveau solde : "
                                + receipt.balanceAfter() + ".", receipt.amount(), receipt.balanceAfter());
                case ALREADY_CREDITED -> new QuestRewardRetryView(false, "ALREADY_PAID",
                        "Déjà réglée : aucun second crédit.", receipt.amount(), receipt.balanceAfter());
                case FAILED -> new QuestRewardRetryView(false, "ERROR",
                        "Le crédit a échoué : rien n'a été modifié, la récompense reste due.", 0L, 0L);
            });
        }).exceptionally(err -> new QuestRewardRetryView(false, "ERROR",
                "Reprise impossible : " + rootName(err) + ". Rien n'a été modifié.", 0L, 0L));
    }

    @Override
    public CompletableFuture<MutationResult> settleQuestRewardDebt(UUID playerId, String grantId, String reason) {
        if (economyService == null) {
            return CompletableFuture.completedFuture(
                    MutationResult.of(false, "ERROR", "Économie indisponible sur ce serveur."));
        }
        return economyService.questRewardDebt(grantId).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "UNKNOWN_DEBT",
                        "Aucune récompense due sous cet identifiant."));
            }
            if (!found.get().playerId().equals(playerId)) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "WRONG_PLAYER",
                        "Cette récompense due appartient à un autre joueur : rien n'a été fait."));
            }
            return economyService.settleQuestRewardManually(grantId, reason).thenApply(settled -> settled
                    ? MutationResult.of(true, "SETTLED",
                            "Récompense marquée réglée à la main : elle ne sera plus jamais payée "
                                    + "automatiquement. Aucun solde n'a été modifié par cette action.")
                    : MutationResult.of(false, "NOT_PENDING",
                            "Cette récompense n'était plus en attente (déjà payée ou déjà réglée)."));
        }).exceptionally(err -> MutationResult.of(false, "ERROR",
                "Règlement impossible : " + rootName(err) + ". Rien n'a été modifié."));
    }

    private static List<String> describeRewards(List<QuestReward> rewards) {
        List<String> out = new ArrayList<>();
        for (QuestReward reward : rewards) {
            out.add(switch (reward) {
                case ExperienceReward r -> "+" + r.amount() + " XP";
                case ItemReward r -> "+" + r.amount() + "x " + r.material();
                case VariableReward r -> "variable " + r.key() + " = " + r.value();
                case CommandReward r -> "commande console : " + truncate(r.command(), 60);
                case MoneyReward r -> "+" + r.amount() + " pièce(s)";
            });
        }
        return out;
    }

    /** Jeton technique de la cible d'un objectif : entité, matériau, id de PNJ, ou nom de monde (#78). */
    /** PNJ destinataire d'une remise (issue #123), {@code null} pour tout autre type d'objectif. */
    private static String objectiveNpc(QuestObjective objective) {
        return objective instanceof DeliverItemToNpcObjective deliver ? deliver.npcId() : null;
    }

    private static String objectiveTarget(QuestObjective objective) {
        return switch (objective) {
            case BreakBlockObjective o -> o.material().name();
            case PlaceBlockObjective o -> o.material().name();
            case KillEntityObjective o -> o.entity().name();
            case CollectItemObjective o -> o.material().name();
            case CraftItemObjective o -> o.material().name();
            case TalkToNpcObjective o -> o.npcId();
            case ReachLocationObjective o -> o.world();
            // La cible comptée d'une remise est l'OBJET ; le PNJ voyage à part (voir objectiveNpc).
            case DeliverItemToNpcObjective o -> o.material().name();
            case SmeltItemObjective o -> o.material().name();
            // Issue #185 : aucune cible unique à nommer (ni matériau, ni entité, ni monde unique) —
            // la portée est la liste « worlds », transportée à part dans le résumé.
            case DiscoverWaypointObjective o -> null;
        };
    }

    /**
     * Version <strong>structurée</strong> des récompenses (#78) : type / quantité / cible séparés,
     * commande console <strong>complète</strong> (jamais tronquée à 60, contrairement à
     * {@link #describeRewards}). {@code raw} garde la description héritée pour le debug.
     */
    private static List<RewardSummary> rewardDetails(List<QuestReward> rewards) {
        List<RewardSummary> out = new ArrayList<>();
        for (QuestReward reward : rewards) {
            out.add(switch (reward) {
                case ExperienceReward r -> new RewardSummary("EXPERIENCE", r.amount(), null, null, null,
                        "+" + r.amount() + " XP");
                case ItemReward r -> new RewardSummary("ITEM", r.amount(), r.material().name(), null, null,
                        "+" + r.amount() + "x " + r.material());
                case VariableReward r -> new RewardSummary("VARIABLE", 0, r.key(), r.value(), null,
                        "variable " + r.key() + " = " + r.value());
                case CommandReward r -> new RewardSummary("COMMAND", 0, null, null, r.command(),
                        "commande console : " + r.command());
                case MoneyReward r -> new RewardSummary("MONEY", r.amount(), null, null, null,
                        "+" + r.amount() + " pièce(s)");
            });
        }
        return out;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) + "…" : trimmed;
    }

    private static String rootName(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName();
    }
}
