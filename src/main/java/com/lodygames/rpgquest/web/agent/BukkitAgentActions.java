package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.economy.CurrencyName;
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
import com.lodygames.rpgquest.item.delivery.ItemDelivery;
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
import com.lodygames.rpgquest.npc.NpcCatalogSources;
import com.lodygames.rpgquest.npc.NpcDefinitionStore;
import com.lodygames.rpgquest.npc.BukkitNpcPlacementProbe;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.npc.NpcPlacementPlanner;
import com.lodygames.rpgquest.npc.NpcRenameVerdict;
import com.lodygames.rpgquest.npc.SkinPreservationPlanner;
import com.lodygames.rpgquest.npc.WanderState;
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
import com.lodygames.rpgquest.quest.model.HeartReward;
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
import com.lodygames.rpgquest.quest.progress.CooldownText;
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
import java.util.HashSet;
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
    /** Issue #325 — lien MineSkin → texture signée (Citizens ne sait pas lire un lien minesk.in). */
    private final com.lodygames.rpgquest.npc.MineSkinResolver skinResolver =
            new com.lodygames.rpgquest.npc.MineSkinResolver();
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
    /**
     * Réseau de voyage du Wild (issue #156). {@code null} accepté : c'est un enrichissement de
     * diagnostic, pas une dépendance du catalogue — sans lui, la section Wild est simplement absente.
     */
    private com.lodygames.rpgquest.waystone.WaystoneService waystoneService;
    /**
     * Nombre total de découvertes de Waystones, lu à la demande (issue #156).
     *
     * <p><strong>Asynchrone volontairement.</strong> C'est une lecture SQL : l'attendre depuis le
     * thread principal gèlerait le serveur le temps de la requête — un diagnostic n'a pas le droit
     * de coûter des ticks à ce qu'il décrit. Le relevé lit donc ce compteur <em>avant</em> de passer
     * sur le thread principal, et retombe sur « non relevé » en cas d'échec.</p>
     */
    private Supplier<CompletableFuture<Integer>> waystoneDiscoveryCount;
    /**
     * Configuration de voyage, pour que l'écran cite les seuils réels (issue #156) plutôt que de
     * les supposer. {@code null} accepté : les seuils sont alors rendus comme non relevés.
     */
    private Supplier<com.lodygames.rpgquest.config.TravelConfig> travelConfigSupplier;

    /**
     * Branche les sources du diagnostic de réseau de voyage (issue #156).
     *
     * <p>Un setter et non trois paramètres de constructeur : celui-ci en compte déjà une trentaine,
     * et ce sont des enrichissements de <em>diagnostic</em> — sans eux le catalogue fonctionne, il
     * est simplement moins bavard. Chaque test existant reste inchangé.</p>
     */
    public void setTravelDiagnosticSources(
            com.lodygames.rpgquest.waystone.WaystoneService waystones,
            Supplier<CompletableFuture<Integer>> discoveries,
            Supplier<com.lodygames.rpgquest.config.TravelConfig> travel) {
        this.waystoneService = waystones;
        this.waystoneDiscoveryCount = discoveries;
        this.travelConfigSupplier = travel;
    }
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
    private final com.lodygames.rpgquest.content.publish.ContentPublishService contentPublishService;
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
    /**
     * Journal des opérations de bâtiment (issue #234). {@code null} accepté : le journal est un
     * outil de diagnostic, pas une dépendance du placement — sans lui, l'historique est simplement
     * vide, et aucune opération n'échoue pour autant.
     */
    private com.lodygames.rpgquest.database.BuildingHistoryRepository buildingHistoryRepository;
    private final com.lodygames.rpgquest.building.SchematicGateway schematicGateway;
    /** Issue #94 — facultative : absente, la génération est refusée, rien d'autre ne change. */
    private com.lodygames.rpgquest.building.plan.BuildingGenerator buildingGenerator;

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
                              com.lodygames.rpgquest.content.publish.ContentPublishService contentPublishService,
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
        this.contentPublishService = contentPublishService;
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
                data.resolve("quests"), data.resolve("stories"), data.resolve("dialogues"),
                data.resolve("hub-guides"), data.resolve("content-backups"),
                com.lodygames.rpgquest.content.ContentTombstones.besideContent(data.resolve("dialogues")));
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
                    ObjectiveSummary summary = objectiveSummary(o);
                    objectives.add(summary.raw());
                    objectiveDetails.add(summary);
                }
                steps.add(new QuestStepSummary(s.id(), objectives, objectiveDetails));
            }
            out.add(new QuestSummary(
                    q.id().toString(), q.title().base(), q.category(), q.repeatable(),
                    q.prerequisites().stream().map(NamespacedKey::toString).toList(),
                    steps, describeRewards(q.rewards()), rewardDetails(q.rewards()),
                    q.giver(), null, q.cooldown().toSeconds()));
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
                        objectives = objectiveStates(q, view.get());
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
        List<NpcCatalog.DialogueLink> dialogueLinks = NpcCatalogSources.dialogueLinks(dialogueEngine.dialogues());
        List<NpcCatalog.QuestLink> questLinks = NpcCatalogSources.questLinks(questEngine.quests());
        List<NpcCatalog.LogicalDefinition> defs = NpcCatalogSources.definitions(npcEngine.definitions());

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
                        site.aiBrief(),
                        site.world(), site.x(), site.y(), site.z(), site.facing().name(),
                        site.status().name(), site.createdBy(), site.createdAt().toString(),
                        loaded.contains(site.world())));
            }
            return done(new BuildingSiteCatalogView(List.copyOf(rows),
                    buildingSiteService.worlds(), rows.size(), placementRows()));
        });
    }

    /** Projection des bâtiments posés, partagée par le catalogue et la bibliothèque. */
    /**
     * Branche le journal des opérations de bâtiment (issue #234).
     *
     * <p>Un setter et non un paramètre de constructeur : celui-ci en compte déjà une trentaine, et
     * ajouter une dépendance <em>facultative</em> à une liste déjà longue aurait obligé chaque test
     * existant à la nommer pour rien.</p>
     */
    public void setBuildingHistoryRepository(
            com.lodygames.rpgquest.database.BuildingHistoryRepository repository) {
        this.buildingHistoryRepository = repository;
    }

    private com.lodygames.rpgquest.building.BuildingCaptureService buildingCapture;

    /** Issue #246 — dépendance facultative : sans elle, la capture est refusée avec son motif. */
    public void setBuildingCapture(com.lodygames.rpgquest.building.BuildingCaptureService capture) {
        this.buildingCapture = capture;
    }

    // ---- Villes (issue #178) ------------------------------------------------------------------

    private com.lodygames.rpgquest.city.CityRegistry cityRegistry;
    private com.lodygames.rpgquest.city.CityDiscoveryService cityDiscovery;
    private com.lodygames.rpgquest.city.PendingCityRegistry cityPendings;
    private com.lodygames.rpgquest.claim.ClaimBorderRenderer cityBorderRenderer;

    /** Branché au démarrage : registre, découvertes, gestes en attente et rendu du contour. */
    private com.lodygames.rpgquest.settings.GameSettingsService gameSettings;
    private com.lodygames.rpgquest.player.OperatorNoticeService operatorNotices;

    /** Issue #316 — message privé au joueur après un OP/DEOP réel. */
    public void setOperatorNotices(com.lodygames.rpgquest.player.OperatorNoticeService service) {
        this.operatorNotices = service;
    }

    /** Issue #335 — paramètres généraux. */
    public void setGameSettings(com.lodygames.rpgquest.settings.GameSettingsService service) {
        this.gameSettings = service;
    }

    @Override
    public CompletableFuture<GameSettingsView> settingsGet() {
        if (gameSettings == null) {
            return AgentActions.super.settingsGet();
        }
        return done(settingsView(true, "Paramètres relus sur le serveur.", gameSettings.current()));
    }

    @Override
    public CompletableFuture<GameSettingsView> settingsSet(Map<String, String> changes, String actor) {
        if (gameSettings == null) {
            return AgentActions.super.settingsGet();
        }
        // Le fichier est minuscule : l'écriture se fait hors du thread principal, jamais sur un événement.
        return CompletableFuture.supplyAsync(() -> {
            var saved = gameSettings.update(changes, actor);
            return settingsView(saved.ok(), saved.message(), saved.settings());
        });
    }

    // ---- Emplacements de PNJ (actions npc.spot.*, issue #381) ----------------------------------

    private com.lodygames.rpgquest.npc.spot.NpcSpotService npcSpots;
    private com.lodygames.rpgquest.claim.ClaimService spotClaims;

    /** Facultatif : sans service, toutes les actions npc.spot.* répondent « indisponible ». */
    public void setNpcSpots(com.lodygames.rpgquest.npc.spot.NpcSpotService service,
                            com.lodygames.rpgquest.claim.ClaimService claims) {
        this.npcSpots = service;
        this.spotClaims = claims;
    }

    @Override
    public CompletableFuture<NpcSpotCatalogView> npcSpots() {
        if (npcSpots == null) {
            return AgentActions.super.npcSpots();
        }
        return npcBindingRepository.loadAll().thenCompose(bindings -> onMain(() -> {
            Map<String, UUID> uuidByNpc = new HashMap<>();
            bindings.forEach(b -> uuidByNpc.putIfAbsent(b.npcId(), b.citizensUuid()));
            List<CitizensNpc> roster = npcIdentityService.citizensAvailable()
                    ? npcIdentityService.citizensRoster() : List.of();
            Map<UUID, CitizensNpc> byUuid = new HashMap<>();
            roster.forEach(n -> byUuid.put(n.uuid(), n));
            Set<String> allowed = allowedSpawnWorlds.get();
            List<NpcSpotSummary> rows = new ArrayList<>();
            for (com.lodygames.rpgquest.npc.spot.NpcSpot spot : npcSpots.all()) {
                rows.add(spotSummary(spot, uuidByNpc, byUuid, roster, allowed));
            }
            Map<String, String> placeable = new java.util.TreeMap<>();
            Set<String> recurring = recurringNpcIds();
            for (NpcDefinition definition : npcEngine.definitions()) {
                if (!recurring.contains(definition.id())) {
                    placeable.put(definition.id(), definition.displayName());
                }
            }
            List<String> worlds = npcSpots.all().stream().map(com.lodygames.rpgquest.npc.spot.NpcSpot::world)
                    .distinct().sorted().toList();
            return done(new NpcSpotCatalogView(true, List.copyOf(rows), worlds, placeable,
                    System.currentTimeMillis()));
        }));
    }

    /** Thread principal : lit le monde et le registre Citizens. */
    private NpcSpotSummary spotSummary(com.lodygames.rpgquest.npc.spot.NpcSpot spot, Map<String, UUID> uuidByNpc,
                                       Map<UUID, CitizensNpc> byUuid, List<CitizensNpc> roster,
                                       Set<String> allowed) {
        boolean worldUsable = plugin.getServer().getWorld(spot.world()) != null
                && allowed.stream().anyMatch(w -> w.equalsIgnoreCase(spot.world()));
        CitizensNpc assigned = spot.free() ? null
                : Optional.ofNullable(uuidByNpc.get(spot.npcId())).map(byUuid::get).orElse(null);
        com.lodygames.rpgquest.npc.spot.NpcSpotState.Position position = assigned != null && assigned.hasLocation()
                ? new com.lodygames.rpgquest.npc.spot.NpcSpotState.Position(assigned.world(), assigned.x(),
                assigned.y(), assigned.z())
                : null;
        boolean otherNearby = roster.stream()
                .filter(n -> assigned == null || !n.uuid().equals(assigned.uuid()))
                .anyMatch(n -> standsOn(n, spot));
        boolean definitionKnown = spot.free() || npcEngine.find(spot.npcId()).isPresent();
        com.lodygames.rpgquest.npc.spot.NpcSpotState state = com.lodygames.rpgquest.npc.spot.NpcSpotState.classify(
                spot, worldUsable, definitionKnown, position, otherNearby);
        String npcName = spot.free() ? null
                : npcEngine.find(spot.npcId()).map(NpcDefinition::displayName).orElse(null);
        return new NpcSpotSummary(spot.id(), spot.name(), spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(),
                spot.pitch(), spot.radius(), spot.npcId(), npcName, assigned == null ? -1 : assigned.numericId(),
                state.status().name(), state.message(), spot.createdBy(), spot.createdAt().toString(),
                spot.updatedAt().toString(), spotNotes(spot));
    }

    /** Un PNJ (Citizens) se tient-il dans le rayon de cet emplacement ? */
    private static boolean standsOn(CitizensNpc npc, com.lodygames.rpgquest.npc.spot.NpcSpot spot) {
        return npc.hasLocation() && spot.world().equals(npc.world())
                && spot.distanceTo(npc.x(), npc.y(), npc.z()) <= 1.0;
    }

    /** Dépendances à signaler : jamais bloquantes, toujours dites. */
    private List<String> spotNotes(com.lodygames.rpgquest.npc.spot.NpcSpot spot) {
        List<String> notes = new ArrayList<>();
        if (spotClaims != null) {
            spotClaims.claimAt(spot.world(), (int) Math.floor(spot.x()), (int) Math.floor(spot.y()),
                    (int) Math.floor(spot.z())).ifPresent(claim -> notes.add("Dans la parcelle « " + claim.id()
                    + " » : aucun PNJ n'y sera placé."));
        }
        if (!spot.free() && cityRegistry != null) {
            cityRegistry.all().stream()
                    .filter(city -> spot.npcId().equals(city.centralNpcId()))
                    .forEach(city -> notes.add("PNJ central de la ville « " + city.name()
                            + " » : déplacer le PNJ ne déplace ni le centre ni l'arrivée de la ville."));
        }
        return List.copyOf(notes);
    }

    /** Identifiants logiques des PNJ récurrents (#13) : leur position appartient à leur occurrence. */
    private Set<String> recurringNpcIds() {
        if (encounters == null) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        encounters.sites().forEach(site -> ids.add(site.npcId()));
        return ids;
    }

    @Override
    public CompletableFuture<MutationResult> npcSpotRename(String id, String name) {
        if (npcSpots == null) {
            return AgentActions.super.npcSpotRename(id, name);
        }
        return npcSpots.rename(id, name).thenApply(updated -> updated
                .map(spot -> MutationResult.of(true, "RENAMED", "Emplacement renommé « " + spot.name() + " »."))
                .orElseGet(() -> MutationResult.of(false, "SPOT_NOT_FOUND", "Emplacement introuvable.")));
    }

    @Override
    public CompletableFuture<MutationResult> npcSpotOrient(String id, float yaw, float pitch) {
        if (npcSpots == null) {
            return AgentActions.super.npcSpotOrient(id, yaw, pitch);
        }
        return npcSpots.orient(id, yaw, pitch).thenCompose(updated -> {
            if (updated.isEmpty()) {
                return done(MutationResult.of(false, "SPOT_NOT_FOUND", "Emplacement introuvable."));
            }
            com.lodygames.rpgquest.npc.spot.NpcSpot spot = updated.get();
            String saved = "Orientation enregistrée (regard " + fmt(spot.yaw()) + "°).";
            if (spot.free()) {
                return done(MutationResult.of(true, "ORIENTED", saved));
            }
            // Un PNJ occupe l'emplacement : il se tourne aussi, par le même chemin vérifié qu'un placement.
            return citizensRelocate(spot.npcId(), spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch())
                    .thenApply(moved -> moved.ok()
                            ? new MutationResult(true, "ORIENTED", saved + " « " + spot.npcId() + " » s'est tourné.",
                            moved.effects())
                            : MutationResult.of(true, "ORIENTED_NPC_UNCHANGED", saved + " Le PNJ n'a pas pu être "
                            + "tourné : " + moved.message()));
        });
    }

    @Override
    public CompletableFuture<MutationResult> npcSpotRelease(String id) {
        if (npcSpots == null) {
            return AgentActions.super.npcSpotRelease(id);
        }
        String previous = npcSpots.find(id).map(com.lodygames.rpgquest.npc.spot.NpcSpot::npcId).orElse(null);
        return npcSpots.release(id).thenApply(outcome -> switch (outcome) {
            case RELEASED -> {
                plugin.getSLF4JLogger().info("[npc-spot] {} libéré (PNJ {} laissé en place).", id, previous);
                yield MutationResult.of(true, "RELEASED", "Emplacement libéré. Le PNJ « " + previous
                        + " » n'a pas bougé.");
            }
            case ALREADY_FREE -> MutationResult.of(true, "ALREADY_FREE", "L'emplacement était déjà libre.");
            case ABSENT -> MutationResult.of(false, "SPOT_NOT_FOUND", "Emplacement introuvable.");
        });
    }

    @Override
    public CompletableFuture<MutationResult> npcSpotDelete(String id) {
        if (npcSpots == null) {
            return AgentActions.super.npcSpotDelete(id);
        }
        return npcSpots.delete(id).thenApply(outcome -> switch (outcome) {
            case DELETED -> {
                plugin.getSLF4JLogger().info("[npc-spot] {} supprimé.", id);
                yield MutationResult.of(true, "DELETED", "Emplacement supprimé. Aucun PNJ n'a été touché.");
            }
            case ABSENT -> MutationResult.of(true, "ABSENT", "Emplacement déjà absent : rien à faire.");
            case OCCUPIED -> MutationResult.of(false, "SPOT_OCCUPIED",
                    "Un PNJ occupe cet emplacement : libérez-le d'abord. Rien n'a été supprimé.");
        });
    }

    /**
     * Place un PNJ sur un emplacement. Ordre : contrôles, <strong>réservation</strong> (transaction
     * base), geste physique (déplacement vérifié de la MÊME entité Citizens, ou apparition si le PNJ
     * n'existe pas encore en jeu), puis <strong>compensation</strong> de la réservation si le geste
     * échoue — l'ancien emplacement est rendu au PNJ, qui n'a pas bougé.
     */
    @Override
    public CompletableFuture<MutationResult> npcSpotPlace(String spotId, String npcId) {
        if (npcSpots == null) {
            return AgentActions.super.npcSpotPlace(spotId, npcId);
        }
        Optional<com.lodygames.rpgquest.npc.spot.NpcSpot> found = npcSpots.find(spotId);
        if (found.isEmpty()) {
            return done(MutationResult.of(false, "SPOT_NOT_FOUND", "Emplacement introuvable."));
        }
        com.lodygames.rpgquest.npc.spot.NpcSpot spot = found.get();
        if (npcEngine.find(npcId).isEmpty()) {
            return done(MutationResult.of(false, "NPC_NOT_FOUND", "Aucun PNJ « " + safe(npcId) + " »."));
        }
        if (recurringNpcIds().contains(npcId)) {
            return done(MutationResult.of(false, "RECURRING_NPC", "« " + npcId + " » est un PNJ récurrent : sa "
                    + "position appartient à son occurrence (PNJ > PNJ récurrents). Rien n'a bougé."));
        }
        if (!spot.free() && !npcId.equals(spot.npcId())) {
            return done(MutationResult.of(false, "SPOT_OCCUPIED", "Emplacement occupé par « " + spot.npcId()
                    + " ». Libérez-le ou choisissez un emplacement libre. Rien n'a bougé."));
        }
        if (spotClaims != null && spotClaims.claimAt(spot.world(), (int) Math.floor(spot.x()),
                (int) Math.floor(spot.y()), (int) Math.floor(spot.z())).isPresent()) {
            return done(MutationResult.of(false, "SPOT_IN_CLAIM", "Cet emplacement est dans la parcelle d'un "
                    + "joueur : aucun PNJ n'y est placé. Rien n'a bougé."));
        }
        Optional<com.lodygames.rpgquest.npc.spot.NpcSpot> previous = npcSpots.assignedTo(npcId)
                .filter(other -> !other.id().equals(spot.id()));
        return citizensUuidOf(npcId).thenCompose(uuid -> onMain(() -> {
            Optional<CitizensNpc> blocker = npcIdentityService.citizensAvailable()
                    ? npcIdentityService.citizensRoster().stream()
                    .filter(n -> uuid.isEmpty() || !n.uuid().equals(uuid.get()))
                    .filter(n -> standsOn(n, spot)).findFirst()
                    : Optional.empty();
            if (blocker.isPresent()) {
                return done(MutationResult.of(false, "SPOT_BLOCKED", "Un autre PNJ (Citizens #"
                        + blocker.get().numericId() + ", « " + blocker.get().name() + " ») se tient déjà ici. "
                        + "Rien n'a bougé."));
            }
            return npcSpots.assign(spot.id(), npcId).thenCompose(assigned -> switch (assigned) {
                case ABSENT -> done(MutationResult.of(false, "SPOT_NOT_FOUND", "Emplacement introuvable."));
                case OCCUPIED -> done(MutationResult.of(false, "SPOT_OCCUPIED",
                        "Emplacement pris entre-temps par un autre PNJ. Rien n'a bougé."));
                case ASSIGNED, ALREADY_ASSIGNED -> placePhysically(spot, npcId, uuid.isPresent())
                        .thenCompose(result -> result.ok()
                                ? done(placed(result, spot, npcId, previous))
                                : compensate(spot, npcId, previous, assigned).thenApply(note ->
                                new MutationResult(false, result.code(), result.message() + note, result.effects())));
            });
        }));
    }

    /** Déplace la même entité si elle existe en jeu, la fait apparaître sinon. */
    private CompletableFuture<MutationResult> placePhysically(com.lodygames.rpgquest.npc.spot.NpcSpot spot,
                                                              String npcId, boolean bound) {
        if (bound) {
            return citizensRelocate(npcId, spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch());
        }
        return citizensCreate(npcId, spot.world(), spot.x(), spot.y(), spot.z(), spot.yaw(), spot.pitch())
                .thenApply(created -> new MutationResult(created.ok(), created.ok() ? "SPAWNED" : created.code(),
                        created.message(), created.effects()));
    }

    private MutationResult placed(MutationResult physical, com.lodygames.rpgquest.npc.spot.NpcSpot spot,
                                  String npcId, Optional<com.lodygames.rpgquest.npc.spot.NpcSpot> previous) {
        List<String> effects = new ArrayList<>(physical.effects());
        effects.add("spot:" + spot.id() + "=" + npcId);
        previous.ifPresent(p -> effects.add("spot:" + p.id() + "=libre"));
        String from = previous.map(p -> " (emplacement « " + p.name() + " » libéré)").orElse("");
        plugin.getSLF4JLogger().info("[npc-spot] {} placé sur {} ({}){}.", npcId, spot.id(), physical.code(),
                previous.map(p -> ", " + p.id() + " libéré").orElse(""));
        String notes = spotNotes(spot.withNpc(npcId, java.time.Instant.now())).stream()
                .map(n -> " " + n).collect(java.util.stream.Collectors.joining());
        return new MutationResult(true, "SPAWNED".equals(physical.code()) ? "PLACED_SPAWNED" : "PLACED",
                "« " + npcId + " » est sur « " + spot.name() + " »" + from + ". " + physical.message() + notes,
                List.copyOf(effects));
    }

    /** Rend la réservation telle qu'elle était avant la tentative. Renvoie une phrase à ajouter au message. */
    private CompletableFuture<String> compensate(com.lodygames.rpgquest.npc.spot.NpcSpot spot, String npcId,
                                                 Optional<com.lodygames.rpgquest.npc.spot.NpcSpot> previous,
                                                 com.lodygames.rpgquest.npc.spot.NpcSpotService.AssignOutcome assigned) {
        if (assigned == com.lodygames.rpgquest.npc.spot.NpcSpotService.AssignOutcome.ALREADY_ASSIGNED) {
            return done("");
        }
        // Ancien emplacement pris entre-temps : on libère au moins le nouveau, où le PNJ n'est pas.
        CompletableFuture<?> undo = previous.isPresent()
                ? npcSpots.assign(previous.get().id(), npcId).thenCompose(back ->
                back == com.lodygames.rpgquest.npc.spot.NpcSpotService.AssignOutcome.ASSIGNED
                        ? done((Object) back) : npcSpots.release(spot.id()).thenApply(r -> (Object) r))
                : npcSpots.release(spot.id());
        return undo.handle((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("[npc-spot] compensation impossible pour {} sur {}", npcId, spot.id(), error);
                return " La réservation n'a pas pu être annulée : vérifiez la page Emplacements.";
            }
            return " Réservation annulée.";
        });
    }

    @Override
    public CompletableFuture<MutationResult> npcSpotCapture(String npcId, String name, String actor) {
        if (npcSpots == null) {
            return AgentActions.super.npcSpotCapture(npcId, name, actor);
        }
        if (npcEngine.find(npcId).isEmpty()) {
            return done(MutationResult.of(false, "NPC_NOT_FOUND", "Aucun PNJ « " + safe(npcId) + " »."));
        }
        if (recurringNpcIds().contains(npcId)) {
            return done(MutationResult.of(false, "RECURRING_NPC", "« " + npcId + " » est un PNJ récurrent : sa "
                    + "position appartient à son occurrence."));
        }
        return citizensUuidOf(npcId).thenCompose(uuid -> {
            if (uuid.isEmpty()) {
                return done(MutationResult.of(false, "NO_CITIZENS_BINDING", "Ce PNJ n'existe pas encore dans le monde."));
            }
            return onMain(() -> {
                Optional<CitizensNpc> live = npcIdentityService.citizensRoster().stream()
                        .filter(n -> n.uuid().equals(uuid.get())).filter(CitizensNpc::hasLocation).findFirst();
                if (live.isEmpty()) {
                    return done(MutationResult.of(false, "NO_LOCATION",
                            "Citizens ne connaît pas la position de ce PNJ : rien n'a été réservé."));
                }
                CitizensNpc npc = live.get();
                String label = name == null || name.isBlank() ? npcEngine.find(npcId)
                        .map(NpcDefinition::displayName).orElse(npcId) : name;
                return npcSpots.create(npc.world(), npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch(), label,
                        actor, npcId).thenCompose(created -> switch (created.outcome()) {
                    case INVALID -> done(MutationResult.of(false, "INVALID", created.error()));
                    case IN_PROGRESS -> done(MutationResult.of(false, "IN_PROGRESS",
                            "Une réservation de ce bloc est déjà en cours : réessayez dans un instant."));
                    case CREATED -> {
                        plugin.getSLF4JLogger().info("[npc-spot] {} capturé depuis la position de {}.",
                                created.spot().id(), npcId);
                        yield done(MutationResult.of(true, "CAPTURED", "Emplacement « " + created.spot().name()
                                + " » (" + created.spot().id() + ") réservé à la position de « " + npcId
                                + " », qui l'occupe."));
                    }
                    case ALREADY_THERE -> npcSpots.assign(created.spot().id(), npcId).thenApply(a -> switch (a) {
                        case ASSIGNED, ALREADY_ASSIGNED -> MutationResult.of(true, "ALREADY_THERE",
                                "Un emplacement existait déjà ici (« " + created.spot().name() + " ») : « " + npcId
                                        + " » en est l'occupant.");
                        case OCCUPIED -> MutationResult.of(false, "SPOT_OCCUPIED", "Un emplacement existe déjà ici, "
                                + "occupé par « " + created.spot().npcId() + " ».");
                        case ABSENT -> MutationResult.of(false, "SPOT_NOT_FOUND", "Emplacement introuvable.");
                    });
                });
            });
        });
    }

    // ---- PNJ récurrents (actions encounter.*, issue #13 étape 2) -------------------------------

    private com.lodygames.rpgquest.encounter.EncounterService encounters;
    private volatile com.lodygames.rpgquest.entitlement.reward.RewardEntitlementService rewardEntitlements;

    /** Issue #422 — dépendance facultative : sans elle, les actions entitlement.* répondent « indisponible ». */
    public void setRewardEntitlements(com.lodygames.rpgquest.entitlement.reward.RewardEntitlementService service) {
        this.rewardEntitlements = service;
    }

    @Override
    public CompletableFuture<RewardEntitlementView> rewardEntitlements(UUID playerId, String status, int limit) {
        var service = rewardEntitlements;
        if (service == null) {
            return AgentActions.super.rewardEntitlements(playerId, status, limit);
        }
        var filter = status == null ? null
                : com.lodygames.rpgquest.entitlement.reward.RewardEntitlementStatus.valueOf(status);
        var catalog = service.catalog();
        List<RewardOption> options = catalog.rewards().values().stream()
                .map(reward -> new RewardOption(reward.key(), reward.label())).toList();
        return service.list(playerId, filter, limit).thenApply(rows -> new RewardEntitlementView(true,
                catalog.enabled(), rows.stream().map(BukkitAgentActions::rewardSummary).toList(), options));
    }

    private static RewardEntitlementSummary rewardSummary(
            com.lodygames.rpgquest.database.RewardEntitlementRepository.Listed listed) {
        var e = listed.entitlement();
        return new RewardEntitlementSummary(e.idempotencyKey(), e.playerId().toString(),
                listed.playerName() == null ? "" : listed.playerName(), e.source().name(), e.sourceRef(),
                e.rewardKey(), e.label() == null ? "" : e.label(), e.status().name(), e.attempts(),
                e.lastError() == null ? "" : e.lastError(), e.revokeReason() == null ? "" : e.revokeReason(),
                e.createdAt().toString(), e.updatedAt().toString(),
                e.deliveredAt() == null ? "" : e.deliveredAt().toString(),
                e.revokedAt() == null ? "" : e.revokedAt().toString());
    }

    // ---- Éditeur de tables de récompenses (issue #177, tranche 2) --------------------------------
    // Appelé sur le thread agent : lecture / écriture de entitlements.yml hors du thread principal.

    @Override
    public CompletableFuture<RewardCatalogView> rewardCatalogStatus() {
        var service = rewardEntitlements;
        if (service == null) {
            return AgentActions.super.rewardCatalogStatus();
        }
        var catalog = service.catalog();
        List<RewardCatalogEntryView> rewards = catalog.rewards().values().stream()
                .map(r -> new RewardCatalogEntryView(r.key(), r.label(), r.summary())).toList();
        List<String> errors = service.lastErrors();
        return done(new RewardCatalogView(true, java.nio.file.Files.isRegularFile(service.file()), errors.isEmpty(),
                catalog.enabled(), errors, rewards, rewardTables()));
    }

    @Override
    public CompletableFuture<MutationResult> rewardTableSave(String id, String label, String mode, boolean enabled,
                                                             List<RewardTableEntryInput> entries,
                                                             String expectedVersion, boolean create) {
        var service = rewardEntitlements;
        if (service == null) {
            return AgentActions.super.rewardTableSave(id, label, mode, enabled, entries, expectedVersion, create);
        }
        var table = new com.lodygames.rpgquest.entitlement.reward.RewardTable(id, label.trim(),
                com.lodygames.rpgquest.entitlement.reward.RewardTable.Mode.fromYaml(mode), enabled,
                entries.stream().map(e -> new com.lodygames.rpgquest.entitlement.reward.RewardTable.Entry(e.id(),
                        e.rewardKey(), e.chance())).toList());
        return done(tableMutation(service.tableEditor().save(table, expectedVersion, create)));
    }

    @Override
    public CompletableFuture<MutationResult> rewardTableDuplicate(String sourceId, String newId, String newLabel) {
        var service = rewardEntitlements;
        if (service == null) {
            return AgentActions.super.rewardTableDuplicate(sourceId, newId, newLabel);
        }
        return done(tableMutation(service.tableEditor().duplicate(sourceId, newId, newLabel)));
    }

    @Override
    public CompletableFuture<MutationResult> rewardTableToggle(String id, boolean enabled) {
        var service = rewardEntitlements;
        if (service == null) {
            return AgentActions.super.rewardTableToggle(id, enabled);
        }
        return done(tableMutation(service.tableEditor().setEnabled(id, enabled)));
    }

    private static MutationResult tableMutation(com.lodygames.rpgquest.entitlement.reward.RewardTableEditor.Outcome o) {
        List<String> effects = o.table() == null ? List.of()
                : List.of("table " + o.table().id() + " v" + o.table().version()
                + (o.table().enabled() ? " active" : " désactivée"));
        return new MutationResult(o.success(), o.code(), o.message(), effects);
    }

    @Override
    public CompletableFuture<MutationResult> declareRewardEntitlement(UUID playerId, String rewardKey,
                                                                      String reference) {
        var service = rewardEntitlements;
        if (service == null) {
            return AgentActions.super.declareRewardEntitlement(playerId, rewardKey, reference);
        }
        if (!service.catalog().enabled()) {
            return done(MutationResult.of(false, "DISABLED",
                    "Droits à récompense désactivés (entitlements.yml : enabled: false)."));
        }
        return service.declareAdmin(playerId, rewardKey, reference).handle((outcome, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                return MutationResult.of(false, "INVALID", String.valueOf(cause.getMessage()));
            }
            return outcome.created()
                    ? new MutationResult(true, "DECLARED", "Droit déclaré : remis à la prochaine connexion du joueur.",
                            List.of("Clé : " + outcome.idempotencyKey()))
                    : new MutationResult(true, "ALREADY_DECLARED",
                            "Ce droit existait déjà (même référence) : rien de plus n'a été créé.",
                            List.of("Clé : " + outcome.idempotencyKey()));
        });
    }

    @Override
    public CompletableFuture<MutationResult> revokeRewardEntitlement(String key, String reason) {
        var service = rewardEntitlements;
        if (service == null) {
            return AgentActions.super.revokeRewardEntitlement(key, reason);
        }
        return service.revoke(key, reason).thenApply(revoked -> revoked
                ? MutationResult.of(true, "REVOKED", "Droit révoqué : il ne sera jamais remis.")
                : MutationResult.of(false, "NOT_PENDING",
                        "Rien à révoquer : droit inconnu, déjà remis ou déjà révoqué."));
    }

    public void setEncounters(com.lodygames.rpgquest.encounter.EncounterService service) {
        this.encounters = service;
    }

    @Override
    public CompletableFuture<EncounterCatalogView> encounterCatalog() {
        if (encounters == null) {
            return AgentActions.super.encounterCatalog();
        }
        // La présence d'un PNJ se lit dans le registre Citizens : API du thread principal.
        return onMain(() -> done(EncounterCatalogViews.build(encounters, npcIdentityService.citizensAvailable()
                ? npcIdentityService::citizensExists : null)));
    }

    @Override
    public CompletableFuture<MutationResult> encounterSiteAction(String operation, String siteId) {
        if (encounters == null) {
            return AgentActions.super.encounterSiteAction(operation, siteId);
        }
        return onMain(() -> {
            CompletableFuture<com.lodygames.rpgquest.encounter.EncounterService.ActionResult> result =
                    switch (operation) {
                        case "retry" -> encounters.retry(siteId);
                        case "disable" -> encounters.disable(siteId);
                        case "remove" -> encounters.remove(siteId);
                        default -> CompletableFuture.completedFuture(
                                new com.lodygames.rpgquest.encounter.EncounterService.ActionResult(
                                        false, "UNKNOWN_OPERATION", "Opération inconnue : " + operation));
                    };
            return result.thenApply(r -> MutationResult.of(r.ok(), r.code(), r.message()));
        });
    }

    @Override
    public CompletableFuture<MutationResult> encounterTemplatesReload() {
        if (encounters == null) {
            return AgentActions.super.encounterTemplatesReload();
        }
        // Données pures : la relecture des fichiers se fait hors du thread principal.
        return CompletableFuture.supplyAsync(() -> {
            var report = encounters.templates().reload();
            String message = report.loaded().size() + " modèle(s) chargé(s)"
                    + (report.hasIssues() ? ", " + report.issues().size() + " fichier(s) rejeté(s)." : ".");
            return new MutationResult(true, report.hasIssues() ? "LOADED_WITH_ISSUES" : "LOADED", message,
                    report.issues().stream().map(i -> i.file() + " : " + i.message()).toList());
        });
    }

    @Override
    public CompletableFuture<MutationResult> encounterBackoffsClear() {
        if (encounters == null) {
            return AgentActions.super.encounterBackoffsClear();
        }
        int cleared = encounters.clearBackoffs();
        return done(MutationResult.of(true, "CLEARED", cleared + " délai(s) d'attente effacé(s) : "
                + "les instances concernées seront retentées à la prochaine visite."));
    }

    @Override
    public CompletableFuture<MutationResult> encounterTemplateSave(
            com.lodygames.rpgquest.encounter.model.EncounterTemplate template, boolean create) {
        if (encounters == null) {
            return AgentActions.super.encounterTemplateSave(template, create);
        }
        // Écriture disque puis relecture : hors du thread principal, comme « Relire les modèles ».
        return CompletableFuture.supplyAsync(() -> {
            com.lodygames.rpgquest.encounter.EncounterTemplateStore.Result r = create
                    ? encounterTemplateStore().create(template) : encounterTemplateStore().update(template);
            if (!r.ok()) {
                return MutationResult.of(false, r.code(), r.message());
            }
            encounters.templates().reload();
            return new MutationResult(true, r.code(), r.message(), encounterTemplateWarnings(template));
        });
    }

    @Override
    public CompletableFuture<MutationResult> encounterTemplateToggle(String templateId, boolean enabled) {
        if (encounters == null) {
            return AgentActions.super.encounterTemplateToggle(templateId, enabled);
        }
        return CompletableFuture.supplyAsync(() -> {
            var current = encounterTemplateStore().find(templateId);
            if (current.isEmpty()) {
                return MutationResult.of(false, "NOT_FOUND", "Aucun modèle valide « " + templateId
                        + " » : corrigez ou recréez le fichier avant de l'activer.");
            }
            var t = current.get();
            if (t.enabled() == enabled) {
                return MutationResult.of(true, "UNCHANGED", "Modèle « " + templateId + " » déjà "
                        + (enabled ? "actif." : "désactivé."));
            }
            var r = encounterTemplateStore().update(t.withEnabled(enabled));
            if (!r.ok()) {
                return MutationResult.of(false, r.code(), r.message());
            }
            encounters.templates().reload();
            return MutationResult.of(true, enabled ? "ENABLED" : "DISABLED", "Modèle « " + templateId + " » "
                    + (enabled ? "activé : de nouvelles rencontres pourront apparaître."
                    : "désactivé : plus aucune nouvelle rencontre ; les occurrences existantes restent en place."));
        });
    }

    @Override
    public CompletableFuture<MutationResult> encounterTemplateDelete(String templateId, boolean acknowledgeSites) {
        if (encounters == null) {
            return AgentActions.super.encounterTemplateDelete(templateId, acknowledgeSites);
        }
        return CompletableFuture.supplyAsync(() -> {
            // Recompté ici, au moment de supprimer : la page a pu être affichée avant une apparition.
            long sites = encounters.sites().stream().filter(s -> s.templateId().equals(templateId)).count();
            if (sites > 0 && !acknowledgeSites) {
                return MutationResult.of(false, "HAS_SITES", "« " + templateId + " » a encore " + sites
                        + " occurrence(s). Cochez l'accord pour les laisser en place, ou retirez-les d'abord. "
                        + "Rien n'a été supprimé.");
            }
            var r = encounterTemplateStore().delete(templateId,
                    plugin.getDataFolder().toPath().resolve("encounter-backups"));
            if (!r.ok()) {
                return MutationResult.of(false, r.code(), r.message());
            }
            encounters.templates().reload();
            List<String> effects = sites == 0 ? List.of()
                    : List.of(sites + " occurrence(s) conservée(s) : leur PNJ et leur dialogue restent en place.");
            return new MutationResult(true, r.code(), r.message(), effects);
        });
    }

    private com.lodygames.rpgquest.encounter.EncounterTemplateStore encounterTemplateStore() {
        return new com.lodygames.rpgquest.encounter.EncounterTemplateStore(encounters.templates().directory());
    }

    /** Ce qui n'empêche pas d'enregistrer mais mérite d'être dit à l'administrateur. */
    private List<String> encounterTemplateWarnings(com.lodygames.rpgquest.encounter.model.EncounterTemplate t) {
        List<String> warnings = new ArrayList<>();
        List<String> unknown = encounters.templates().unknownBiomes(t);
        if (!unknown.isEmpty()) {
            warnings.add("⚠ biome(s) inconnu(s) sur ce serveur, ignoré(s) : " + String.join(", ", unknown));
        }
        String dialogue = "rpgquest:" + t.npcId();
        if (dialogueEngine.dialogues().stream().noneMatch(d -> d.id().toString().equals(dialogue))) {
            warnings.add("⚠ aucun dialogue « " + dialogue + " » : un clic sur ce PNJ n'ouvrira rien.");
        }
        // Issue #369 : un catalogue inconnu est ignoré en jeu, un dialogue sans offre ne le montrera pas.
        for (String catalog : t.questCatalogs()) {
            if (questCatalogs != null && questCatalogs.find(catalog).isEmpty()) {
                warnings.add("⚠ catalogue de quêtes inconnu « " + catalog + " » : ignoré tant qu'il n'existe pas.");
            }
        }
        if (!t.questCatalogs().isEmpty() && !dialogueOffersCatalog(t.npcId())) {
            warnings.add("⚠ le dialogue « " + dialogue + " » n'a aucune action OFFER_CATALOG_QUEST : "
                    + "les catalogues ne seront pas proposés.");
        }
        long otherIdentity = encounters.sites().stream()
                .filter(s -> s.templateId().equals(t.id()) && !s.npcId().equals(t.npcId())).count();
        if (otherIdentity > 0) {
            warnings.add("⚠ " + otherIdentity + " occurrence(s) existante(s) gardent leur ancien npc_id ; "
                    + "seules les nouvelles prendront « " + t.npcId() + " ».");
        }
        return List.copyOf(warnings);
    }

    // ---- Catalogues de quêtes secondaires (issue #369) -----------------------------------------

    private com.lodygames.rpgquest.encounter.catalog.YamlQuestCatalogEngine questCatalogs;

    public void setQuestCatalogs(com.lodygames.rpgquest.encounter.catalog.YamlQuestCatalogEngine engine) {
        this.questCatalogs = engine;
    }

    @Override
    public CompletableFuture<QuestCatalogListView> questCatalogList() {
        if (questCatalogs == null || encounters == null) {
            return AgentActions.super.questCatalogList();
        }
        return CompletableFuture.supplyAsync(() -> QuestCatalogViews.build(questCatalogs.catalogs(),
                questCatalogs.lastReport().issues(), questEngine.quests(), encounters.templates().templates(),
                this::dialogueOffersCatalog));
    }

    private boolean dialogueOffersCatalog(String npcId) {
        String dialogue = "rpgquest:" + npcId;
        return dialogueEngine.dialogues().stream()
                .filter(d -> d.id().toString().equals(dialogue))
                .flatMap(d -> d.nodes().values().stream())
                .flatMap(n -> n.choices().stream())
                .flatMap(c -> c.actions().stream())
                .anyMatch(a -> a instanceof com.lodygames.rpgquest.dialogue.model.OfferCatalogQuestAction);
    }

    @Override
    public CompletableFuture<MutationResult> questCatalogSave(
            com.lodygames.rpgquest.encounter.catalog.QuestCatalog catalog, boolean create) {
        if (questCatalogs == null || catalog == null) {
            return AgentActions.super.questCatalogSave(catalog, create);
        }
        return CompletableFuture.supplyAsync(() -> {
            var store = new com.lodygames.rpgquest.encounter.catalog.QuestCatalogStore(questCatalogs.directory());
            var r = create ? store.create(catalog) : store.update(catalog);
            if (!r.ok()) {
                return MutationResult.of(false, r.code(), r.message());
            }
            questCatalogs.reload();
            List<String> warnings = new ArrayList<>();
            for (var entry : catalog.entries()) {
                NamespacedKey key = NamespacedKey.fromString(entry.questId());
                if (key == null || questEngine.find(key).isEmpty()) {
                    warnings.add("⚠ quête absente « " + entry.questId() + " » : gardée, jamais proposée.");
                }
            }
            return new MutationResult(true, r.code(), r.message(), List.copyOf(warnings));
        });
    }

    @Override
    public CompletableFuture<MutationResult> questCatalogAssign(String catalogId, Set<String> templateIds) {
        if (questCatalogs == null || encounters == null) {
            return AgentActions.super.questCatalogAssign(catalogId, templateIds);
        }
        return CompletableFuture.supplyAsync(() -> {
            var templates = encounters.templates().templates();
            Set<String> known = templates.stream().map(com.lodygames.rpgquest.encounter.model.EncounterTemplate::id)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> unknown = templateIds.stream().filter(id -> !known.contains(id)).toList();
            if (!unknown.isEmpty()) {
                return MutationResult.of(false, "UNKNOWN_TEMPLATE", "Modèle(s) de PNJ inconnu(s) : "
                        + String.join(", ", unknown) + ". Actualisez la page.");
            }
            List<String> effects = new ArrayList<>();
            boolean changed = false;
            for (var t : templates) {
                boolean wanted = templateIds.contains(t.id());
                boolean has = t.questCatalogs().contains(catalogId);
                if (wanted == has) {
                    continue;
                }
                List<String> next = new ArrayList<>(t.questCatalogs());
                if (wanted) {
                    next.add(catalogId);
                } else {
                    next.remove(catalogId);
                }
                var r = encounterTemplateStore().update(new com.lodygames.rpgquest.encounter.model.EncounterTemplate(
                        t.id(), t.displayName(), t.description(), t.npcId(), t.role(), t.family(), t.biomes(),
                        t.worlds(), t.weight(), t.maxSites(), t.skinUrl(), t.enabled(), List.copyOf(next)));
                if (!r.ok()) {
                    encounters.templates().reload();
                    return new MutationResult(false, r.code(), "« " + t.id() + " » : " + r.message(),
                            List.copyOf(effects));
                }
                changed = true;
                effects.add((wanted ? "proposé par « " : "retiré de « ") + t.displayName() + " »");
                if (wanted && !dialogueOffersCatalog(t.npcId())) {
                    effects.add("⚠ « " + t.displayName() + " » ne sait pas encore proposer ces quêtes : son "
                            + "dialogue n'a pas l'action « Proposer une quête du catalogue ».");
                }
            }
            if (changed) {
                encounters.templates().reload();
            }
            return new MutationResult(true, changed ? "ASSIGNED" : "UNCHANGED",
                    changed ? "PNJ mis à jour." : "PNJ inchangés.", List.copyOf(effects));
        });
    }

    @Override
    public CompletableFuture<MutationResult> questCatalogDelete(String catalogId, boolean detach) {
        if (questCatalogs == null || encounters == null) {
            return AgentActions.super.questCatalogDelete(catalogId, detach);
        }
        return CompletableFuture.supplyAsync(() -> {
            var users = encounters.templates().templates().stream()
                    .filter(t -> t.questCatalogs().contains(catalogId)).toList();
            if (!users.isEmpty() && !detach) {
                return MutationResult.of(false, "IN_USE", "Catalogue « " + catalogId + " » attribué à : "
                        + users.stream().map(com.lodygames.rpgquest.encounter.model.EncounterTemplate::id)
                                .collect(java.util.stream.Collectors.joining(", "))
                        + ". Cochez « Retirer aussi le catalogue de ces modèles » pour confirmer.");
            }
            List<String> effects = new ArrayList<>();
            for (var t : users) {
                List<String> remaining = t.questCatalogs().stream().filter(c -> !c.equals(catalogId)).toList();
                var r = encounterTemplateStore().update(new com.lodygames.rpgquest.encounter.model.EncounterTemplate(
                        t.id(), t.displayName(), t.description(), t.npcId(), t.role(), t.family(), t.biomes(),
                        t.worlds(), t.weight(), t.maxSites(), t.skinUrl(), t.enabled(), remaining));
                if (!r.ok()) {
                    encounters.templates().reload();
                    return MutationResult.of(false, r.code(), "Dissociation de « " + t.id() + " » impossible : "
                            + r.message() + " Catalogue conservé.");
                }
                effects.add("retiré du modèle « " + t.id() + " »");
            }
            if (!users.isEmpty()) {
                encounters.templates().reload();
            }
            var r = new com.lodygames.rpgquest.encounter.catalog.QuestCatalogStore(questCatalogs.directory())
                    .delete(catalogId, plugin.getDataFolder().toPath().resolve("quest-catalog-backups"));
            questCatalogs.reload();
            return new MutationResult(r.ok(), r.code(), r.message(), List.copyOf(effects));
        });
    }

    @Override
    public Map<UUID, String> encounterCitizens() {
        return encounters == null ? Map.of() : encounters.citizensSites();
    }

    private GameSettingsView settingsView(boolean ok, String message, com.lodygames.rpgquest.settings.GameSettings s) {
        return new GameSettingsView(ok, message, settingsValues(s),
                settingsValues(com.lodygames.rpgquest.settings.GameSettings.DEFAULTS),
                gameSettings.updatedAt(), gameSettings.updatedBy(), gameSettings.loadProblem());
    }

    private static Map<String, String> settingsValues(com.lodygames.rpgquest.settings.GameSettings s) {
        return Map.of("currency_name", s.currencyName(),
                "beta_warning_enabled", String.valueOf(s.betaWarningEnabled()),
                "beta_warning_text", s.betaWarningText(),
                "admin_bypass_available", String.valueOf(s.adminBypassAvailable()),
                "admin_bypass_max_minutes", String.valueOf(s.adminBypassMaxMinutes()));
    }

    public void setCities(com.lodygames.rpgquest.city.CityRegistry registry,
                          com.lodygames.rpgquest.city.CityDiscoveryService discovery,
                          com.lodygames.rpgquest.city.PendingCityRegistry pendings,
                          com.lodygames.rpgquest.claim.ClaimBorderRenderer renderer) {
        this.cityRegistry = registry;
        this.cityDiscovery = discovery;
        this.cityPendings = pendings;
        this.cityBorderRenderer = renderer;
    }

    @Override
    public CompletableFuture<CityCatalogView> cities() {
        if (cityRegistry == null || cityDiscovery == null) {
            return AgentActions.super.cities();
        }
        CompletableFuture<List<NpcBindingRepository.Binding>> bindingsFuture = npcBindingRepository == null
                ? CompletableFuture.completedFuture(List.of()) : npcBindingRepository.loadAll();
        return cityDiscovery.discoveriesByCity().thenCombine(bindingsFuture, (counts, bindings) -> Map.entry(counts, bindings))
                .thenCompose(loaded -> onMain(() -> {
            var counts = loaded.getKey();
            List<CitizensNpc> roster = npcIdentityService == null ? List.of() : npcIdentityService.citizensRoster();
            var all = cityRegistry.all();
            List<CitySummary> rows = new ArrayList<>();
            for (var city : all) {
                var b = com.lodygames.rpgquest.city.CityPerimeter.bounds(city);
                List<String> overlaps = all.stream()
                        .filter(other -> !other.id().equals(city.id())
                                && com.lodygames.rpgquest.city.CityPerimeter.overlaps(city, other))
                        .map(other -> other.id()).toList();
                rows.add(new CitySummary(city.id(), city.name(), city.world(), city.x(), city.y(), city.z(),
                        city.blockX(), city.blockZ(), city.detectionDistance(), city.active(),
                        city.createdAt().toString(), plugin.getServer().getWorld(city.world()) != null,
                        b.minX(), b.maxX(), b.minZ(), b.maxZ(), b.width(),
                        counts.getOrDefault(city.id(), 0), overlaps,
                        cityAnchors(city, loaded.getValue(), roster)));
            }
            List<CityPendingMove> moves = new ArrayList<>();
            for (var m : cityPendings.armedMoves()) {
                Player p = plugin.getServer().getPlayer(UUID.fromString(m.playerId()));
                moves.add(new CityPendingMove(p == null ? m.playerId() : p.getName(), m.cityId(),
                        m.requestedBy() == null ? "" : m.requestedBy(), m.expiresAt().toString()));
            }
            return CompletableFuture.completedFuture(new CityCatalogView(true, rows, moves,
                    com.lodygames.rpgquest.city.CityPerimeter.MIN_DISTANCE,
                    com.lodygames.rpgquest.city.CityPerimeter.MAX_DISTANCE,
                    com.lodygames.rpgquest.city.CityPerimeter.DEFAULT_DISTANCE));
        }));
    }

    /**
     * Issue #322 — PNJ central relu en direct (définition, liaison Citizens, position du registre
     * Citizens sans charger de chunk) et point d'arrivée. Thread principal.
     */
    private CityAnchorsView cityAnchors(com.lodygames.rpgquest.travel.beacon.model.VillageCenter city,
                                        List<NpcBindingRepository.Binding> bindings, List<CitizensNpc> roster) {
        String npcId = city.centralNpcId();
        var definition = city.hasCentralNpc() && npcEngine != null ? npcEngine.find(npcId) : Optional.<NpcDefinition>empty();
        Optional<UUID> citizensUuid = city.hasCentralNpc()
                ? bindings.stream().filter(b -> b.npcId().equals(npcId))
                        .map(NpcBindingRepository.Binding::citizensUuid).findFirst()
                : Optional.empty();
        CitizensNpc npc = citizensUuid.flatMap(uuid -> roster.stream()
                .filter(n -> n.uuid().equals(uuid)).findFirst()).orElse(null);
        var status = com.lodygames.rpgquest.city.CityAnchors.npc(city, definition.isPresent(),
                npcIdentityService != null && npcIdentityService.citizensAvailable(), citizensUuid.isPresent(), npc);
        var arrivalStatus = com.lodygames.rpgquest.city.CityAnchors.arrival(city);
        var arrival = city.arrival();
        return new CityAnchorsView(city.hasCentralNpc() ? npcId : null,
                definition.map(NpcDefinition::displayName).orElse(null),
                status.code(), status.severity().name(), status.message(), status.world(),
                status.x(), status.y(), status.z(), status.live(), status.distance(),
                arrival == null ? null : arrival.world(), arrival == null ? null : arrival.x(),
                arrival == null ? null : arrival.y(), arrival == null ? null : arrival.z(),
                arrival == null ? null : arrival.yaw(), arrival == null ? null : arrival.pitch(),
                arrivalStatus.code(), arrivalStatus.severity().name(), arrivalStatus.message());
    }

    @Override
    public CompletableFuture<MutationResult> cityLinkNpc(String id, String npcId) {
        if (cityRegistry == null) {
            return AgentActions.super.cityLinkNpc(id, npcId);
        }
        return onMain(() -> {
            if (npcId != null && (npcEngine == null || npcEngine.find(npcId).isEmpty())) {
                return done(MutationResult.of(false, "UNKNOWN_NPC", "Aucun PNJ « " + safe(npcId)
                        + " » : choisissez un PNJ existant (identifiant stable, pas le nom affiché)."));
            }
            return cityRegistry.linkNpc(id, npcId).thenApply(r -> {
                if (!r.ok()) {
                    return MutationResult.of(false, "REFUSED", r.error());
                }
                return npcId == null
                        ? new MutationResult(true, "UNLINKED", "« " + r.city().name() + " » n'a plus de PNJ central. "
                                + "Son centre et ses découvertes sont inchangés.", List.of())
                        : new MutationResult(true, "LINKED", "« " + npcId + " » est le PNJ central de « "
                                + r.city().name() + " ». Le centre de détection ne change pas : rafraîchissez "
                                + "pour relire la position du PNJ.", List.of("npc: " + npcId));
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> citySetArrival(String id, String playerName) {
        if (cityRegistry == null) {
            return AgentActions.super.citySetArrival(id, playerName);
        }
        return onMain(() -> {
            var city = cityRegistry.find(id);
            if (city.isEmpty()) {
                return done(MutationResult.of(false, "NOT_FOUND", "Ville introuvable : " + safe(id)));
            }
            Player player = plugin.getServer().getPlayerExact(playerName);
            if (player == null) {
                return done(MutationResult.of(false, "OFFLINE", "« " + safe(playerName)
                        + " » n'est pas connecté : l'arrivée est relevée à sa position en jeu."));
            }
            org.bukkit.Location here = player.getLocation();
            if (!here.getWorld().getName().equals(city.get().world())) {
                return done(MutationResult.of(false, "OTHER_WORLD", player.getName() + " est dans "
                        + here.getWorld().getName() + " : l'arrivée doit être dans le monde de la ville ("
                        + city.get().world() + ")."));
            }
            String unsafe = com.lodygames.rpgquest.city.CityAnchors.unsafeArrival(
                    new BukkitNpcPlacementProbe(here.getWorld(), java.util.Set.of()),
                    here.getBlockX(), here.getBlockY(), here.getBlockZ());
            if (unsafe != null) {
                return done(MutationResult.of(false, "UNSAFE", "Position de " + player.getName()
                        + " refusée comme arrivée : " + unsafe + ". Placez-vous debout sur un sol plein."));
            }
            var arrival = new com.lodygames.rpgquest.travel.beacon.model.VillageCenter.Arrival(
                    here.getWorld().getName(), here.getBlockX() + 0.5, here.getY(), here.getBlockZ() + 0.5,
                    here.getYaw(), here.getPitch());
            return cityRegistry.setArrival(id, arrival).thenApply(r -> {
                if (!r.ok()) {
                    return MutationResult.of(false, "REFUSED", r.error());
                }
                var status = com.lodygames.rpgquest.city.CityAnchors.arrival(r.city());
                return new MutationResult(true, "ARRIVAL_SET", "Arrivée de « " + r.city().name()
                        + " » relevée sur " + player.getName() + " : " + arrival.world() + " "
                        + here.getBlockX() + " " + here.getBlockY() + " " + here.getBlockZ() + ". "
                        + status.message(), List.of("yaw: " + Math.round(arrival.yaw()),
                        "pitch: " + Math.round(arrival.pitch())));
            });
        });
    }

    @Override
    public CompletableFuture<MutationResult> cityClearArrival(String id) {
        if (cityRegistry == null) {
            return AgentActions.super.cityClearArrival(id);
        }
        return onMain(() -> cityRegistry.clearArrival(id)).thenApply(r -> r.ok()
                ? new MutationResult(true, "ARRIVAL_CLEARED", "« " + r.city().name()
                        + " » : le menu « Villages » arrive de nouveau au centre de la ville.", List.of())
                : MutationResult.of(false, "REFUSED", r.error()));
    }

    @Override
    public CompletableFuture<MutationResult> cityRename(String id, String name) {
        if (cityRegistry == null) {
            return AgentActions.super.cityRename(id, name);
        }
        return onMain(() -> cityRegistry.rename(id, name)).thenApply(r -> r.ok()
                ? new MutationResult(true, "RENAMED", "Ville « " + r.city().id() + " » renommée « "
                        + r.city().name() + " ». Son identifiant et ses découvertes sont inchangés.",
                        List.of("name: " + r.city().name()))
                : MutationResult.of(false, "REFUSED", r.error()));
    }

    @Override
    public CompletableFuture<MutationResult> cityResize(String id, int distance) {
        if (cityRegistry == null) {
            return AgentActions.super.cityResize(id, distance);
        }
        return onMain(() -> cityRegistry.resize(id, distance)).thenApply(r -> {
            if (!r.ok()) {
                return MutationResult.of(false, "REFUSED", r.error());
            }
            var b = com.lodygames.rpgquest.city.CityPerimeter.bounds(r.city());
            return new MutationResult(true, "RESIZED", "« " + r.city().name() + " » s'étend désormais de "
                    + distance + " blocs dans chaque direction depuis son centre (" + b.width() + " × "
                    + b.width() + " blocs).", List.of("X " + b.minX() + " … " + b.maxX(),
                    "Z " + b.minZ() + " … " + b.maxZ()));
        });
    }

    @Override
    public CompletableFuture<MutationResult> citySetActive(String id, boolean active) {
        if (cityRegistry == null) {
            return AgentActions.super.citySetActive(id, active);
        }
        return onMain(() -> cityRegistry.setActive(id, active)).thenApply(r -> r.ok()
                ? new MutationResult(true, active ? "ENABLED" : "DISABLED", "« " + r.city().name() + " » "
                        + (active ? "est active : elle peut de nouveau être découverte et apparaît dans le menu Villages."
                                : "est inactive : plus de découverte ni d'entrée dans le menu Villages. Les découvertes "
                                        + "déjà faites sont conservées."), List.of())
                : MutationResult.of(false, "REFUSED", r.error()));
    }

    @Override
    public CompletableFuture<MutationResult> cityArmMove(String id, String playerName, String requestedBy) {
        if (cityRegistry == null) {
            return AgentActions.super.cityArmMove(id, playerName, requestedBy);
        }
        return onMain(() -> {
            var city = cityRegistry.find(id);
            if (city.isEmpty()) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "NOT_FOUND", "Ville introuvable : " + safe(id)));
            }
            Player player = plugin.getServer().getPlayerExact(playerName);
            if (player == null) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "OFFLINE",
                        "« " + safe(playerName) + " » n'est pas connecté : le centre se désigne en jeu."));
            }
            if (!com.lodygames.rpgquest.permission.RpgPermissions.canManageCities(player)) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "FORBIDDEN",
                        player.getName() + " n'a pas la permission " + com.lodygames.rpgquest.permission.RpgPermissions.ADMIN_CITY
                                + " : il ne pourrait pas utiliser l'outil."));
            }
            cityPendings.armMove(player.getUniqueId().toString(), id,
                    requestedBy == null ? "PlugAdmin" : "PlugAdmin:" + requestedBy);
            player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<gold>PlugAdmin :</gold> <gray>votre prochain clic droit avec "
                            + "l'outil de ville deviendra le nouveau centre de</gray> <white><name></white><gray>. "
                            + "Valable 3 minutes.</gray> <yellow>/rpgadmin ville outil</yellow> <gray>si vous ne l'avez pas.</gray>",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", city.get().name())));
            return CompletableFuture.completedFuture(new MutationResult(true, "ARMED",
                    "Déplacement prêt : le prochain clic droit de " + player.getName()
                            + " avec l'outil de ville déplacera le centre de « " + city.get().name()
                            + " ». Valable 3 minutes ; rafraîchissez ensuite cette page pour relire le résultat.",
                    List.of()));
        });
    }

    @Override
    public CompletableFuture<MutationResult> cityShowPerimeter(String id, String playerName) {
        if (cityRegistry == null || cityBorderRenderer == null) {
            return AgentActions.super.cityShowPerimeter(id, playerName);
        }
        return onMain(() -> {
            var city = cityRegistry.find(id);
            if (city.isEmpty()) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "NOT_FOUND", "Ville introuvable : " + safe(id)));
            }
            Player player = plugin.getServer().getPlayerExact(playerName);
            if (player == null) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "OFFLINE",
                        "« " + safe(playerName) + " » n'est pas connecté."));
            }
            var b = com.lodygames.rpgquest.city.CityPerimeter.bounds(city.get());
            boolean shown = cityBorderRenderer.showArea(player, city.get().world(), b.minX(), b.maxX(), b.minZ(), b.maxZ());
            if (!shown) {
                return CompletableFuture.completedFuture(MutationResult.of(false, "OTHER_WORLD",
                        player.getName() + " n'est pas dans le monde " + city.get().world() + " : rien n'est affiché."));
            }
            return CompletableFuture.completedFuture(new MutationResult(true, "SHOWN",
                    "Contour de « " + city.get().name() + " » affiché à " + player.getName()
                            + " pendant 15 secondes (particules orange, colonnes aux coins). Rien n'est modifié.",
                    List.of()));
        });
    }

    @Override
    public CompletableFuture<MutationResult> cityDelete(String id) {
        if (cityRegistry == null) {
            return AgentActions.super.cityDelete(id);
        }
        return onMain(() -> cityRegistry.delete(id)).thenApply(r -> r.ok()
                ? new MutationResult(true, "DELETED", "Ville « " + r.city().name() + " » (" + r.city().id()
                        + ") supprimée, avec ses découvertes. Les quêtes qui la citent ne peuvent plus progresser.",
                        List.of())
                : MutationResult.of(false, "REFUSED", r.error()));
    }

    /**
     * {@code building.placement.capture.preview}. Sur le thread principal : le relevé lit le monde
     * (sans charger aucun chunk).
     */
    @Override
    public CompletableFuture<BuildingCaptureView> buildingCapturePreview(BuildingCaptureRequest request) {
        if (buildingCapture == null) {
            return AgentActions.super.buildingCapturePreview(request);
        }
        return onMain(() -> done(captureView(buildingCapture.preview(captureRequest(request)))));
    }

    @Override
    public CompletableFuture<MutationResult> buildingCapture(BuildingCaptureRequest request, String actor,
                                                             String token) {
        if (buildingCapture == null) {
            return AgentActions.super.buildingCapture(request, actor, token);
        }
        return onMain(() -> {
            var result = buildingCapture.capture(captureRequest(request), actor, token);
            return done(result.captured()
                    ? new MutationResult(true, "CAPTURED", "Construction capturée comme « "
                            + result.definition().name() + " » (" + result.definition().id()
                            + ", version 1) : elle est dans la bibliothèque, prête à être posée sur un "
                            + "autre emplacement. Le bâtiment posé et l'original sont inchangés.",
                            result.effects())
                    : MutationResult.of(false, "REFUSED", result.error()));
        });
    }

    private static com.lodygames.rpgquest.building.BuildingCaptureService.Request captureRequest(
            BuildingCaptureRequest r) {
        return new com.lodygames.rpgquest.building.BuildingCaptureService.Request(r.siteId(),
                new com.lodygames.rpgquest.building.BuildingCaptureService.Margins(r.west(), r.east(),
                        r.down(), r.up(), r.north(), r.south()),
                r.newId(), r.name(), r.description(), r.category());
    }

    /** Projette l'aperçu de capture en vue transportable — refusé ou non, il porte ses motifs. */
    static BuildingCaptureView captureView(
            com.lodygames.rpgquest.building.BuildingCaptureService.Preview preview) {
        Map<String, Object> details = new LinkedHashMap<>();
        var request = preview.request();
        details.put("site_id", request.siteId());
        details.put("new_id", request.newId());
        details.put("name", request.name());
        details.put("description", request.description());
        details.put("category", request.category());
        var m = request.margins();
        details.put("margins", Map.of("west", m.west(), "east", m.east(), "down", m.down(),
                "up", m.up(), "north", m.north(), "south", m.south()));
        if (preview.placement() != null) {
            details.put("source_building_id", preview.placement().buildingId());
            details.put("source_building_version", preview.placement().buildingVersion());
            details.put("source_rotation", preview.placement().rotationDegrees());
            details.put("placed_footprint", preview.placement().footprint().label());
        }
        if (preview.source() != null) {
            details.put("source_building_name", preview.source().name());
        }
        if (preview.region() != null) {
            var region = preview.region();
            details.put("world", region.world());
            details.put("region", region.label());
            details.put("size", region.sizeX() + " × " + region.sizeZ() + " × " + region.sizeY());
            details.put("volume", region.blockCount());
            int[] anchor = preview.localAnchor();
            details.put("local_anchor", anchor[0] + " / " + anchor[1] + " / " + anchor[2]);
        }
        if (preview.front() != null) {
            details.put("front", preview.front().name());
        }
        preview.survey().filter(com.lodygames.rpgquest.building.CaptureSurvey::complete).ifPresent(sv -> {
            details.put("non_air", sv.nonAirBlocks());
            details.put("containers", sv.containers().size());
            details.put("filled_containers", sv.filledContainers());
            details.put("entities", sv.entityCount());
            details.put("entity_types", new LinkedHashMap<>(new java.util.TreeMap<>(sv.entities())));
            details.put("materials", sv.materials());
        });
        return new BuildingCaptureView(preview.capturable(), details, preview.refusals(),
                preview.warnings(), preview.token());
    }

    private com.lodygames.rpgquest.building.BuildingRetirement buildingRetirement;

    /** Issue #284 — dépendance facultative : sans elle, le retrait est refusé avec son motif. */
    public void setBuildingRetirement(com.lodygames.rpgquest.building.BuildingRetirement retirement) {
        this.buildingRetirement = retirement;
    }

    @Override
    public CompletableFuture<MutationResult> buildingDefinitionRetire(String id, String by) {
        if (buildingRetirement == null) {
            return done(MutationResult.of(false, "UNAVAILABLE",
                    "Le retrait de bâtiments n'est pas disponible sur ce serveur."));
        }
        // Déplacement de fichiers : jamais sur le thread principal.
        return CompletableFuture.supplyAsync(() -> {
            var outcome = buildingRetirement.retire(id, by);
            return new MutationResult(outcome.ok(), outcome.code().name(), outcome.message(),
                    outcome.effects());
        }).exceptionally(error -> MutationResult.of(false, "ERROR",
                "Échec du retrait : " + rootName(error)));
    }

    /**
     * Issue #94 — dépendance <strong>facultative</strong>, comme l'historique : sans elle, la
     * génération est refusée avec son motif, et rien d'autre ne cesse de fonctionner.
     */
    public void setBuildingGenerator(
            com.lodygames.rpgquest.building.plan.BuildingGenerator generator) {
        this.buildingGenerator = generator;
    }

    @Override
    public CompletableFuture<BuildingGenerationView> buildingGenerate(String planYaml,
                                                                      boolean replace) {
        return buildingGenerate(planYaml, replace, 0);
    }

    @Override
    public CompletableFuture<BuildingGenerationView> buildingGenerate(String planYaml, boolean replace,
                                                                      int expectedVersion) {
        if (buildingGenerator == null) {
            return done(new BuildingGenerationView(false, "UNAVAILABLE",
                    "La génération de bâtiments n'est pas disponible sur ce serveur.",
                    "", "", 0, 0, 0, 0, 0, false, List.of()));
        }
        // Écritures de fichiers : hors du thread principal. Le rechargement de la bibliothèque
        // remplace une référence volatile, il n'a pas besoin du thread principal non plus.
        return CompletableFuture.supplyAsync(() -> {
            var r = buildingGenerator.generate(planYaml, replace, expectedVersion);
            return new BuildingGenerationView(r.ok(), r.code(), r.message(), r.buildingId(),
                    r.schematic(), r.sizeX(), r.sizeY(), r.sizeZ(), r.blocks(), r.version(),
                    r.replaced(), r.effects());
        }).exceptionally(error -> new BuildingGenerationView(false, "ERROR",
                "Échec de la génération : " + rootName(error), "", "", 0, 0, 0, 0, 0, false,
                List.of()));
    }

    @Override
    public CompletableFuture<BuildingPlanVerdictView> buildingPlanAudit(String planYaml) {
        return buildingPlanAudit(planYaml, 0, 0);
    }

    @Override
    public CompletableFuture<BuildingPlanVerdictView> buildingPlanAudit(String planYaml,
                                                                       int maxWidth,
                                                                       int maxHeight) {
        if (buildingGenerator == null) {
            return done(new BuildingPlanVerdictView(false, "UNAVAILABLE",
                    "La génération de bâtiments n'est pas disponible sur ce serveur.",
                    "", "", 0, 0, 0, 0, List.of(), List.of(), false, Map.of()));
        }
        // Aucune écriture ici, mais l'expansion d'un plan de plusieurs milliers de cellules n'a
        // rien à faire sur le thread principal : elle n'y gagnerait rien et y coûterait un tick.
        return CompletableFuture.supplyAsync(() -> {
            var v = buildingGenerator.inspect(planYaml,
                    new com.lodygames.rpgquest.building.plan.PlanRepair.Limits(maxWidth, maxHeight));
            return new BuildingPlanVerdictView(v.ok(), v.code(), v.message(), v.buildingId(),
                    v.kind(), v.sizeX(), v.sizeY(), v.sizeZ(), v.blocks(), v.codes(),
                    v.warnings(), v.exists(), metricsOf(v.metrics()), repairOf(v.repair()));
        }).exceptionally(error -> new BuildingPlanVerdictView(false, "ERROR",
                "Échec de l'audit du plan : " + rootName(error), "", "", 0, 0, 0, 0,
                List.of(), List.of(), false, Map.of()));
    }

    @Override
    public CompletableFuture<BuildingPlanPreviewView> buildingPlanPreview(String planYaml) {
        return buildingPlanPreview(planYaml, 0, 0);
    }

    @Override
    public CompletableFuture<BuildingPlanPreviewView> buildingPlanPreview(String planYaml,
                                                                         int maxWidth,
                                                                         int maxHeight) {
        if (buildingGenerator == null) {
            return done(new BuildingPlanPreviewView(false, "UNAVAILABLE",
                    "La génération de bâtiments n'est pas disponible sur ce serveur.",
                    "", "", 0, 0, 0, 0, 0, 0, 0, "", List.of(), List.of(), List.of(), List.of()));
        }
        return CompletableFuture.supplyAsync(() -> {
            var exam = buildingGenerator.preview(planYaml,
                    new com.lodygames.rpgquest.building.plan.PlanRepair.Limits(maxWidth, maxHeight));
            var v = exam.verdict();
            var p = exam.preview();
            return new BuildingPlanPreviewView(v.ok(), v.code(), v.message(), v.buildingId(),
                    v.kind(), v.sizeX(), v.sizeY(), v.sizeZ(), v.blocks(),
                    p == null ? 0 : p.anchorX(), p == null ? 0 : p.anchorY(),
                    p == null ? 0 : p.anchorZ(), p == null ? "" : p.front(),
                    v.codes(), v.warnings(),
                    slices(p == null ? List.of() : p.plans()),
                    slices(p == null ? List.of() : p.elevations()),
                    p == null ? null : voxels(p.voxels()), repairOf(v.repair()));
        }).exceptionally(error -> new BuildingPlanPreviewView(false, "ERROR",
                "Échec de l'aperçu du plan : " + rootName(error), "", "", 0, 0, 0, 0, 0, 0, 0, "",
                List.of(), List.of(), List.of(), List.of()));
    }

    /** La réparation proposée par le moteur (#282), à plat ; vide s'il n'y en a pas. */
    private static Map<String, Object> repairOf(
            com.lodygames.rpgquest.building.plan.BuildingGenerator.Repair repair) {
        if (repair == null) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("plan", repair.plan());
        out.put("changes", repair.changes());
        out.put("codes", repair.codes());
        out.put("accepted", repair.accepted());
        return out;
    }

    private static BuildingVoxelsView voxels(
            com.lodygames.rpgquest.building.plan.PlanPreview.Voxels source) {
        List<Map<String, String>> palette = new ArrayList<>(source.palette().size());
        for (var entry : source.palette()) {
            palette.add(Map.of("symbol", String.valueOf(entry.symbol()), "block", entry.block(),
                    "role", String.valueOf(entry.role()), "shape", entry.shape()));
        }
        return new BuildingVoxelsView(palette, source.layers(), source.omittedReason());
    }

    private static List<BuildingSliceView> slices(
            List<com.lodygames.rpgquest.building.plan.PlanPreview.Slice> source) {
        List<BuildingSliceView> out = new ArrayList<>(source.size());
        for (var slice : source) {
            out.add(new BuildingSliceView(slice.id(), slice.label(), slice.axes(), slice.rows()));
        }
        return out;
    }

    /**
     * Les mesures, à plat.
     *
     * <p>Les clés sont recopiées une par une, délibérément. La leçon de #239 : une sérialisation
     * écrite à la main ne transporte que ce qu'on y met, et une clé oubliée ne provoque aucune
     * erreur — elle disparaît simplement de l'écran.</p>
     */
    private static Map<String, Object> metricsOf(
            com.lodygames.rpgquest.building.plan.ArchitecturalAudit.Metrics m) {
        if (m == null) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("solidCells", m.solidCells());
        out.put("glazedCells", m.glazedCells());
        out.put("glazedFacades", m.glazedFacades());
        out.put("lightSources", m.lightSources());
        out.put("openEnvelopeCellsHigh", m.openEnvelopeCellsHigh());
        out.put("floors", m.floors());
        out.put("reachableFloors", m.reachableFloors());
        out.put("furnishingCells", m.furnishingCells());
        out.put("envelopeMaterials", m.envelopeMaterials());
        out.put("topMaterialShare", m.topMaterialShare());
        out.put("topMaterial", m.topMaterial() == null ? "" : m.topMaterial());
        return out;
    }

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
            // Issue #234 : la version de la bibliothèque AUJOURD'HUI, pour pouvoir dire « une
            // version plus récente existe » sans jamais toucher au bâtiment posé.
            var definition = buildingLibraryService.find(placement.buildingId());
            int libraryVersion = definition
                    .map(com.lodygames.rpgquest.building.model.BuildingDefinition::version)
                    .orElse(0);
            String librarySha = definition
                    .flatMap(d -> schematicGateway.fingerprint(d.schematic()))
                    .orElse("");
            var source = buildingPlacements.restoreSourceFor(placement.siteId());
            rows.add(new BuildingPlacementSummary(placement.siteId(), placement.buildingId(), name,
                    placement.world(), placement.anchorX(), placement.anchorY(),
                    placement.anchorZ(), placement.rotationDegrees(),
                    placement.minX(), placement.minY(), placement.minZ(),
                    placement.maxX(), placement.maxY(), placement.maxZ(),
                    placement.placedBy(), placement.placedAt().toString(),
                    source.restorable(),
                    placement.buildingVersion(), placement.schematicSha256(),
                    libraryVersion, librarySha,
                    placement.outdatedAgainst(libraryVersion, librarySha),
                    buildingPlacements.desiredRotation(placement.siteId()).orElse(-1),
                    buildingPlacements.diverges(placement.siteId()),
                    source.label()));
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
                    definition.version(), buildingLibraryService.bundled(definition.id())));
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

    /**
     * {@code building.placement.rollback} — devenu « libérer l'emplacement » (issue #234).
     *
     * <p>La source est désormais la <strong>baseline originale</strong> quand elle existe, et non la
     * sauvegarde de la dernière opération. Pour un emplacement dont la pose est la seule opération —
     * le seul cas qui pouvait exister avant ce lot — les deux coïncident, donc rien ne change pour
     * ce qui est déjà en production.</p>
     */
    @Override
    public CompletableFuture<MutationResult> buildingPlacementRollback(String siteId) {
        return onMain(() -> {
            var source = buildingPlacements.restoreSourceFor(siteId);
            return buildingPlacements.free(siteId, "panel")
                    .thenApply(result -> result.freed()
                            ? new MutationResult(true, "RESTORED",
                                    "Terrain restauré et " + siteId + " libéré (« "
                                            + result.previous().buildingId() + " » retiré).",
                                    List.of("emprise: " + result.previous().footprint().label(),
                                            "source: " + source.label()))
                            : MutationResult.of(false, "REFUSED", result.error()))
                    .exceptionally(error -> MutationResult.of(false, "ERROR",
                            "Échec de la restauration : " + rootName(error)));
        });
    }

    // ---- Réorienter, remplacer, journal (issue #234) --------------------------------------------

    /**
     * {@code building.placement.retarget.preview}. Sur le thread principal : le comptage des blocs
     * non-air lit le monde.
     */
    @Override
    public CompletableFuture<BuildingRetargetView> buildingRetargetPreview(String siteId,
                                                                           String buildingId,
                                                                           String facing) {
        return onMain(() -> {
            var target = parseFacing(facing);
            var current = buildingPlacements.at(siteId);
            boolean sameBuilding = current.isPresent()
                    && current.get().buildingId().equals(blankToNull(buildingId) == null
                            ? current.get().buildingId() : buildingId);
            var preview = sameBuilding
                    ? buildingPlacements.previewReorient(siteId, target)
                    : buildingPlacements.previewReplace(siteId,
                            blankToNull(buildingId) == null ? "" : buildingId, target);
            return done(retargetView(siteId, buildingId, preview));
        });
    }

    @Override
    public CompletableFuture<MutationResult> buildingReorient(String siteId, String facing,
                                                               String actor, String token) {
        return onMain(() -> buildingPlacements
                .reorient(siteId, parseFacing(facing), actor, token)
                .thenApply(BukkitAgentActions::retargetResult)
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec de la réorientation : " + rootName(error))));
    }

    @Override
    public CompletableFuture<MutationResult> buildingReplace(String siteId, String buildingId,
                                                              String facing, String actor,
                                                              String token) {
        return onMain(() -> buildingPlacements
                .replace(siteId, buildingId, parseFacing(facing), actor, token)
                .thenApply(BukkitAgentActions::retargetResult)
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec du remplacement : " + rootName(error))));
    }

    @Override
    public CompletableFuture<BuildingHistoryView> buildingHistory(String siteId) {
        if (buildingHistoryRepository == null) {
            return done(new BuildingHistoryView(siteId, List.of()));
        }
        return buildingHistoryRepository.forSite(siteId, 50).thenApply(entries -> {
            List<BuildingHistoryLine> lines = new ArrayList<>();
            for (var entry : entries) {
                var footprint = entry.footprint();
                lines.add(new BuildingHistoryLine(
                        entry.operation() == null ? "?" : entry.operation().name(),
                        entry.operation() == null ? "opération inconnue"
                                : entry.operation().label(),
                        entry.buildingId(), entry.buildingVersion(), entry.shortSha(),
                        entry.rotationDegrees(),
                        footprint == null ? "" : footprint.label(),
                        entry.actor(), entry.at().toString(), entry.ok(), entry.detail()));
            }
            return new BuildingHistoryView(siteId, List.copyOf(lines));
        });
    }

    /**
     * Projette un aperçu de transformation en vue transportable.
     *
     * <p>Un aperçu refusé renvoie quand même une vue : elle porte les motifs, et c'est ce que
     * l'écran doit afficher. Renvoyer « rien » obligerait le panel à inventer un message.</p>
     */
    private BuildingRetargetView retargetView(String siteId, String buildingId,
            com.lodygames.rpgquest.building.BuildingPlacementService.RetargetPreview preview) {
        if (preview.site() == null || preview.current() == null || preview.target() == null) {
            return new BuildingRetargetView(false,
                    preview.operation() == null ? "" : preview.operation().name(),
                    siteId, "", "", "", "", 0, "", 0L,
                    buildingId == null ? "" : buildingId, "", 0, "", 0L, 0, 0, 0, -1L, false,
                    preview.source().label(), preview.source().restorable(),
                    preview.refusals(), preview.warnings(), preview.token());
        }
        var site = preview.site();
        var current = preview.current();
        var target = preview.target();
        return new BuildingRetargetView(preview.applicable(), preview.operation().name(),
                site.id(), site.name(), site.facing().name(),
                current.buildingId(),
                buildingLibraryService.find(current.buildingId())
                        .map(com.lodygames.rpgquest.building.model.BuildingDefinition::name)
                        .orElse(current.buildingId()),
                current.rotationDegrees(), preview.currentFootprint().label(),
                preview.currentFootprint().blockCount(),
                target.id(), target.name(), preview.targetRotation(),
                preview.targetFootprint().label(), preview.targetFootprint().blockCount(),
                target.sizeX(), target.sizeY(), target.sizeZ(),
                preview.targetNonAir(), preview.overlapping(),
                preview.source().label(), preview.source().restorable(),
                preview.refusals(), preview.warnings(), preview.token());
    }

    /**
     * Traduit un résultat de transformation.
     *
     * <p>Le cas <strong>compensé</strong> a son propre code : le monde est cohérent, l'opération n'a
     * simplement pas eu lieu. Le confondre avec une erreur ordinaire ferait croire à un état douteux
     * là où il n'y en a pas.</p>
     */
    private static MutationResult retargetResult(
            com.lodygames.rpgquest.building.BuildingPlacementService.RetargetResult result) {
        if (result.applied()) {
            var placement = result.placement();
            return new MutationResult(true, "APPLIED",
                    "« " + placement.buildingId() + " » posé sur " + placement.siteId()
                            + ", tourné de " + placement.rotationLabel() + ", emprise "
                            + placement.footprint().label() + ".",
                    List.of("rotation: " + placement.rotationLabel(),
                            "emprise: " + placement.footprint().label(),
                            "version: " + placement.buildingVersion(),
                            "empreinte: " + placement.shortSha(),
                            "compensation: " + placement.backupSchematic()));
        }
        return MutationResult.of(false, result.compensated() ? "COMPENSATED" : "REFUSED",
                result.error());
    }

    /** Lit une orientation ; {@code null} si elle est absente ou inconnue (le service refusera). */
    private static com.lodygames.rpgquest.building.model.Facing parseFacing(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return com.lodygames.rpgquest.building.model.Facing
                    .valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    /**
     * Issue #94 — brief architectural. Même forme que {@link #buildingSiteDescribe}, champ distinct :
     * enregistrer un brief ne touche pas à la note, et inversement.
     */
    @Override
    public CompletableFuture<MutationResult> buildingSiteBrief(String id, String brief) {
        return buildingSiteService.brief(id, brief)
                .thenApply(updated -> updated
                        .map(site -> new MutationResult(true, "BRIEFED",
                                site.aiBrief().isEmpty()
                                        ? "Brief de « " + site.id() + " » effacé."
                                        : "Brief de « " + site.id() + " » enregistré ("
                                                + site.aiBrief().length() + " caractères).",
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
        // #239 : un placement actif interdit la suppression, même si l'état de la fiche n'avait pas
        // suivi (redémarrage pendant une pose) — c'est le placement qui porte le terrain d'origine.
        if (buildingPlacements != null && id != null && buildingPlacements.at(id.trim()).isPresent()) {
            return done(MutationResult.of(false, "SITE_OCCUPIED", occupiedMessage(id)));
        }
        return buildingSiteService.delete(id)
                .thenApply(outcome -> switch (outcome) {
                    case DELETED -> new MutationResult(true, "DELETED",
                            "Emplacement « " + safe(id) + " » supprimé.",
                            List.of("aucun bloc du monde n'a été modifié"));
                    case ABSENT -> MutationResult.of(true, "ABSENT",
                            "Aucun emplacement « " + safe(id) + " » : rien à supprimer.");
                    case OCCUPIED -> MutationResult.of(false, "SITE_OCCUPIED", occupiedMessage(id));
                })
                .exceptionally(error -> MutationResult.of(false, "ERROR",
                        "Échec de la suppression : " + rootName(error)));
    }

    private static String occupiedMessage(String id) {
        return "Un bâtiment est posé sur l'emplacement « " + safe(id) + " » : retirez-le d'abord "
                + "(« Retirer le bâtiment et restaurer le terrain »). Supprimer le repère maintenant "
                + "laisserait le bâtiment sans moyen de restaurer le terrain. Rien n'a été modifié.";
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
        if ("dialogues".equals(kind)) {
            return dialogueDelete(id);
        }
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

    /**
     * Issue #248 — suppression de la copie serveur d'un dialogue.
     *
     * <p>Le serveur <strong>revérifie</strong> d'abord, sur ce qu'il a chargé, qu'aucun PNJ ni aucun
     * autre dialogue n'en dépend ({@link DialogueDeletionGuard}) — l'aperçu du panel peut dater. Le
     * deleter vérifie ensuite les guides de Hub sur le disque, sauvegarde, supprime et inscrit une
     * pierre tombale. Les dialogues sont enfin rechargés, et le constat est rendu : combien sont
     * chargés, et s'il reste des erreurs de chargement.</p>
     *
     * <p>Rien d'autre n'est touché : ni PNJ, ni quête démarrée par ce dialogue, ni progression.</p>
     */
    private CompletableFuture<MutationResult> dialogueDelete(String id) {
        return dialogueDefinitions().thenCompose(view -> {
            List<String> blockers = DialogueDeletionGuard.blockers(id, view.dialogues());
            if (!blockers.isEmpty()) {
                return done(MutationResult.of(false, "REFERENCED", "Dialogue encore utilisé — "
                        + String.join(" ; ", blockers) + ". Rien n'a été supprimé."));
            }
            return CompletableFuture.supplyAsync(() -> contentDeleter.delete("dialogues", id))
                    .thenCompose(result -> {
                        if (!result.ok()) {
                            return done(MutationResult.of(false, result.code(), result.message()));
                        }
                        return onMain(() -> {
                            var report = dialogueEngine.reload();
                            boolean stillLoaded = dialogueEngine.dialogues().stream()
                                    .anyMatch(d -> DialogueDeletionGuard.key(d.id().toString())
                                            .equals(DialogueDeletionGuard.key(id)));
                            String constat = report.loaded().size() + " dialogue(s) rechargé(s), "
                                    + report.issues().size() + " erreur(s) de chargement"
                                    + (stillLoaded ? " — ATTENTION : un autre fichier déclare encore "
                                            + "cet identifiant." : ".");
                            return done(new MutationResult(!stillLoaded,
                                    stillLoaded ? "STILL_LOADED" : "DELETED",
                                    result.message() + " " + constat,
                                    List.of("dialogues/" + result.file(),
                                            "sauvegarde: " + result.backupPath())));
                        });
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
            // La texture à reconduire est résolue AVANT le thread principal (appel réseau possible
            // pour un lien personnalisé ; l'apparence par défaut est embarquée).
            return npcSkinSourceRepository.find(citizensUuid)
                    .thenCompose(recorded -> skinResolver.resolve(urlToKeep(recorded))
                            .thenCompose(texture -> onMain(() ->
                                    done(renameKeepingSkin(citizensUuid, name, recorded, texture)))))
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

    /** Lien dont la texture sera reconduite : la source enregistrée, sinon l'apparence par défaut. */
    private static String urlToKeep(Optional<NpcSkinSourceRepository.SkinSource> recorded) {
        return recorded.filter(r -> r.kind() == NpcSkinSourceRepository.Kind.URL)
                .map(NpcSkinSourceRepository.SkinSource::value)
                .orElse(NpcIdentityService.DEFAULT_SKIN_URL);
    }

    /**
     * Issue #325 — résout un lien MineSkin puis pose sa texture sur le thread principal, relue
     * après écriture. Le résultat dit exactement ce qui s'est passé : jamais « demande transmise ».
     */
    private CompletableFuture<com.lodygames.rpgquest.npc.NpcBehaviourOutcome> applyMineSkin(UUID citizensUuid,
                                                                                       String url) {
        return skinResolver.resolve(url).thenCompose(resolution -> resolution.ok()
                ? onMain(() -> done(npcIdentityService.applyCitizensSkinTexture(citizensUuid, url,
                        resolution.texture())))
                : done(new com.lodygames.rpgquest.npc.NpcBehaviourOutcome(false, "SKIN_RESOLVE_FAILED",
                        resolution.failure().message())));
    }

    /**
     * Thread principal : décide si le renommage est sûr pour l'apparence, puis renomme.
     *
     * <ul>
     *   <li>PNJ <strong>non joueur</strong> (villageois, zombie…) : aucun skin en jeu, le nom
     *       n'influence rien → renommage libre ;</li>
     *   <li>PNJ joueur avec une <strong>source enregistrée</strong> par le panel : elle est
     *       réappliquée avant le renommage → apparence réellement conservée ;</li>
     *   <li>PNJ joueur <strong>sans source connue</strong> : l'apparence par défaut est posée puis
     *       enregistrée (issue #325), pour qu'un nom neuf ne lui donne pas le skin d'un inconnu.</li>
     * </ul>
     *
     * <p>Les textures sont posées par {@code SkinTrait} (issue #325) et relues : un lien
     * {@code minesk.in} n'est jamais transmis tel quel à Citizens.</p>
     */
    private RenameOutcome renameKeepingSkin(UUID citizensUuid, String name,
                                            Optional<NpcSkinSourceRepository.SkinSource> recorded,
                                            com.lodygames.rpgquest.npc.MineSkinResolver.Resolution texture) {
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

        if (plan.decision() == SkinPreservationPlanner.Decision.APPLY_DEFAULT_THEN_RENAME) {
            // Issue #325 : aucune source connue → l'apparence par défaut est posée PUIS enregistrée
            // (RenameOutcome.record), pour que le prochain renommage la reconduise à l'identique.
            var applied = npcIdentityService.applyCitizensSkinTexture(citizensUuid,
                    NpcIdentityService.DEFAULT_SKIN_URL, texture.texture());
            if (!applied.ok()) {
                return new RenameOutcome(MutationResult.of(false, "SKIN_ANCHOR_FAILED",
                        "Renommage annulé : l'apparence par défaut n'a pas pu être posée ("
                                + applied.message() + "). Aucun nom n'a été changé."), null);
            }
            RenameOutcome renamed = renameOnly(citizensUuid, name, previousName,
                    " Apparence par défaut appliquée.", "citizens:skin-url=" + NpcIdentityService.DEFAULT_SKIN_URL);
            return renamed.result().ok()
                    ? new RenameOutcome(renamed.result(), new NpcSkinSourceRepository.SkinSource(
                            NpcSkinSourceRepository.Kind.URL, NpcIdentityService.DEFAULT_SKIN_URL))
                    : renamed;
        }

        NpcSkinSourceRepository.SkinSource source = recorded.orElseThrow();
        if (source.kind() == NpcSkinSourceRepository.Kind.URL && !texture.ok()) {
            return new RenameOutcome(MutationResult.of(false, "SKIN_RESOLVE_FAILED",
                    "Renommage annulé : son apparence n'a pas pu être relue. " + texture.failure().message()
                            + " Aucun nom n'a été changé."), null);
        }
        boolean anchored = source.kind() == NpcSkinSourceRepository.Kind.URL
                ? npcIdentityService.applyCitizensSkinTexture(citizensUuid, source.value(), texture.texture()).ok()
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

    // ---- Issue #197 : un seul nom de personnage, coordonné sur toutes ses couches -------------

    /**
     * Issue #197 — renommage coordonné : définition RPGQuest <em>et</em> nom Citizens.
     *
     * <p>Citizens est traité <strong>en premier</strong> parce que c'est la seule des deux couches
     * qui peut refuser : si l'apparence du PNJ est de type joueur et que RPGQuest n'en connaît pas
     * la source, {@code citizensRename} s'arrête sans rien toucher. En commençant par elle, un refus
     * laisse les <em>deux</em> couches intactes — alors que l'ordre inverse produirait exactement le
     * défaut signalé en jeu : un catalogue qui affiche « Elias » et un personnage qui s'appelle
     * encore « Guide » au-dessus de la tête.</p>
     */
    @Override
    public CompletableFuture<MutationResult> npcIdentityRename(String npcId, String name) {
        String id = npcId == null ? "" : npcId.trim();
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty() || wanted.length() > 48) {
            return done(MutationResult.of(false, "INVALID_NAME", "Nom invalide : 1 à 48 caractères attendus."));
        }
        Optional<NpcDefinition> definition = npcEngine.find(id);
        return citizensUuidOf(id).thenCompose(uuid -> {
            if (definition.isEmpty() && uuid.isEmpty()) {
                return done(MutationResult.of(false, "NOT_FOUND",
                        "« " + safe(id) + " » n'a ni définition RPGQuest ni PNJ Citizens lié : "
                                + "il n'y a aucun nom à changer."));
            }
            String previous = definition.map(NpcDefinition::displayName).orElse("");
            if (uuid.isEmpty()) {
                // Aucune couche en jeu : la définition est la seule identité de ce personnage. Ce
                // n'est pas un échec, et surtout pas un demi-succès — il n'y a rien à coordonner.
                MutationResult written = writeDisplayName(definition.get(), wanted);
                String readBack = readDefinitionName(id);
                return done(verdict(id, wanted, previous,
                        wanted.equals(readBack)
                                ? NpcRenameVerdict.Layer.UPDATED : NpcRenameVerdict.Layer.STALE,
                        NpcRenameVerdict.Layer.ABSENT,
                        "", readBack, written.message(), written.effects()));
            }
            return citizensRename(id, wanted).thenCompose(inGame -> {
                if (!inGame.ok()) {
                    // Refus ou panne côté Citizens : la définition n'a pas été touchée, et on le dit
                    // explicitement plutôt que de laisser croire à un renommage à moitié fait.
                    return done(new MutationResult(false, inGame.code(),
                            inGame.message() + " Le nom du catalogue RPGQuest n'a pas été modifié "
                                    + "non plus : aucune couche n'a changé.",
                            inGame.effects()));
                }
                MutationResult written = definition.isEmpty()
                        ? MutationResult.of(true, "NO_DEFINITION", "")
                        : writeDisplayName(definition.get(), wanted);
                return verifyBothLayers(id, wanted, previous, uuid.get(),
                        definition.isPresent(), inGame, written);
            });
        });
    }

    /**
     * Réécrit le <strong>seul</strong> nom affiché d'une définition, en conservant tout le reste.
     *
     * <p>On n'emprunte pas {@code npcDefinitionUpdate} ici : ce chemin reconstruit la définition
     * depuis les champs d'un formulaire et perd la description. Un renommage ne doit rien effacer
     * d'autre que l'ancien nom.</p>
     */
    private MutationResult writeDisplayName(NpcDefinition definition, String wanted) {
        NpcDefinition renamed;
        try {
            renamed = new NpcDefinition(definition.id(), wanted, definition.description(),
                    definition.dialogueId(), definition.role(), definition.enabled());
        } catch (IllegalArgumentException e) {
            return MutationResult.of(false, "INVALID", e.getMessage());
        }
        NpcDefinitionStore.Result r = npcStore.update(renamed);
        npcEngine.reload();
        List<String> effects = new ArrayList<>();
        if (r.file() != null) {
            effects.add("npcs/" + r.file());
        }
        return new MutationResult(r.ok(), r.code(), r.message(), List.copyOf(effects));
    }

    /**
     * Relit les deux couches après l'opération et refuse de rendre un succès que seule l'une
     * justifierait.
     *
     * <p>C'est la garantie demandée par l'issue : « ne jamais prétendre au succès complet si une
     * seule couche a changé ». On ne se fie donc pas aux codes de retour — on relit le nom Citizens
     * dans le registre et le nom de la définition dans le moteur rechargé.</p>
     */
    private CompletableFuture<MutationResult> verifyBothLayers(String id, String wanted, String previous,
                                                               UUID citizensUuid, boolean hasDefinition,
                                                               MutationResult inGame, MutationResult written) {
        return onMain(() -> done(npcIdentityService.citizensNameOf(citizensUuid)))
                .thenApply(readBack -> {
                    String citizensName = readBack.orElse("");
                    String definitionName = readDefinitionName(id);
                    return verdict(id, wanted, previous,
                            !hasDefinition ? NpcRenameVerdict.Layer.ABSENT
                                    : wanted.equals(definitionName)
                                            ? NpcRenameVerdict.Layer.UPDATED : NpcRenameVerdict.Layer.STALE,
                            wanted.equals(citizensName)
                                    ? NpcRenameVerdict.Layer.UPDATED : NpcRenameVerdict.Layer.STALE,
                            citizensName, definitionName, written.message(),
                            combine(inGame.effects(), written.effects()));
                });
    }

    /** Nom actuellement porté par la définition, relu sur le moteur rechargé. Vide si absente. */
    private String readDefinitionName(String npcId) {
        return npcEngine.find(npcId).map(NpcDefinition::displayName).orElse("");
    }

    /**
     * Projette le verdict pur en {@link MutationResult}, et n'y ajoute que la note sur les
     * dialogues — qui est une observation, pas une partie de la décision.
     */
    private MutationResult verdict(String id, String wanted, String previous,
                                   NpcRenameVerdict.Layer definition, NpcRenameVerdict.Layer citizens,
                                   String citizensName, String definitionName,
                                   String definitionFailure, List<String> effects) {
        NpcRenameVerdict.Verdict v = NpcRenameVerdict.of(id, wanted, previous, definition, citizens,
                citizensName, definitionName, definitionFailure);
        String message = v.ok() ? v.message() + dialogueSpeakerNote(previous) : v.message();
        return new MutationResult(v.ok(), v.code(), message,
                v.ok() ? combine(effects, List.of("npc:name=" + wanted)) : effects);
    }

    /**
     * Les dialogues qui désignaient l'ancien nom comme locuteur, <strong>sans rien y changer</strong>.
     *
     * <p>Le {@code speaker} d'un nœud est un texte d'auteur : il peut être partagé par plusieurs
     * personnages, porter une mise en forme, ou désigner volontairement autre chose que le nom du
     * PNJ. Le réécrire automatiquement reviendrait à modifier du contenu que personne n'a relu.
     * On se contente donc de signaler où regarder.</p>
     */
    private String dialogueSpeakerNote(String previousName) {
        String previous = previousName == null ? "" : previousName.trim();
        if (previous.isEmpty()) {
            return "";
        }
        List<String> affected = new ArrayList<>();
        for (DialogueDefinition dialogue : dialogueEngine.dialogues()) {
            for (DialogueNode node : dialogue.nodes().values()) {
                if (previous.equalsIgnoreCase(node.speaker() == null ? "" : node.speaker().trim())) {
                    affected.add(dialogue.id().toString());
                    break;
                }
            }
        }
        if (affected.isEmpty()) {
            return "";
        }
        Collections.sort(affected);
        return " À vérifier à la main : " + affected.size() + " dialogue(s) nomment encore « "
                + previous + " » comme locuteur (" + String.join(", ", affected) + "). "
                + "Aucun texte n'a été réécrit automatiquement — un locuteur peut être partagé.";
    }

    /** Concatène deux listes d'effets sans doublon, en gardant l'ordre de lecture. */
    private static List<String> combine(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first == null ? List.of() : first);
        for (String s : second == null ? List.<String>of() : second) {
            if (!all.contains(s)) {
                all.add(s);
            }
        }
        return List.copyOf(all);
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
            if (!byPlayerName) {
                // Issue #325 : la texture exacte du lien est posée puis relue — succès = vérifié.
                return applyMineSkin(citizensUuid, raw).thenCompose(applied -> applied.ok()
                        ? npcSkinSourceRepository.save(citizensUuid, NpcSkinSourceRepository.Kind.URL, raw)
                                .thenApply(ignored -> new MutationResult(true, "SKIN_APPLIED",
                                        "Apparence posée sur le personnage et vérifiée. Les joueurs "
                                                + "proches la voient tout de suite.",
                                        List.of("citizens:skin-url=" + raw)))
                        : done(MutationResult.of(false, applied.code(),
                                applied.message() + " Le personnage garde son apparence actuelle.")));
            }
            return onMain(() -> {
                if (!npcIdentityService.pinCitizensSkinToName(citizensUuid, raw)) {
                    return done(MutationResult.of(false, "SKIN_REFUSED",
                            "Citizens a refusé la demande de skin (PNJ introuvable, ou type de PNJ "
                                    + "sans skin). Le skin précédent est conservé."));
                }
                // Pseudo : Citizens télécharge lui-même le skin du compte, en tâche de fond. On ne
                // peut confirmer que la prise en compte, jamais le rendu ni l'existence du compte.
                return done(new MutationResult(true, "SKIN_REQUESTED",
                        "Demande transmise : le skin du compte « " + raw + " » arrive en jeu dans "
                                + "quelques secondes si ce compte existe.",
                        List.of("citizens:skin-player=" + raw)));
            }).thenCompose(result -> result.ok()
                    ? npcSkinSourceRepository.save(citizensUuid, NpcSkinSourceRepository.Kind.NAME, raw)
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
        return citizensProvision(null, null, displayName, skinValue, skinByPlayerName, world, x, y, z, yaw, pitch);
    }

    /**
     * Issue #325 — même création, avec l'identifiant choisi par le panel (qui a déjà écarté les
     * identifiants pris, d'où « lucien_2 ») et le dialogue initial à déclarer. Un identifiant déjà
     * défini reste refusé ici : c'est le dernier rempart contre un doublon, quel que soit le panel.
     */
    @Override
    public CompletableFuture<CitizensProvisionResult> citizensProvision(
            String requestedId, String dialogueId, String displayName, String skinValue,
            boolean skinByPlayerName, String world, Double x, Double y, Double z, Float yaw, Float pitch) {

        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 128 || name.indexOf('\n') >= 0) {
            return done(CitizensProvisionResult.reject("INVALID_NAME",
                    "Nom manquant, trop long (128 maximum) ou multi-ligne."));
        }
        String explicitId = requestedId == null ? "" : requestedId.trim();
        String npcId = explicitId.isEmpty() ? slugify(name) : explicitId;
        if (npcId.isEmpty() || !NPC_ID_PATTERN.matcher(npcId).matches()) {
            return done(CitizensProvisionResult.reject("INVALID_NAME",
                    "Impossible de déduire un identifiant technique de « " + safe(name) + " » : "
                            + "ajouter au moins une lettre ou un chiffre."));
        }
        // Le skin est validé AVANT toute création : une URL invalide ne doit jamais laisser un PNJ
        // derrière elle, ni être silencieusement remplacée par autre chose. Vide = apparence par
        // défaut (issue #325) : un PNJ joueur a toujours une source d'apparence enregistrée.
        String requestedSkin = skinValue == null ? "" : skinValue.trim();
        boolean byPlayer = skinByPlayerName && !requestedSkin.isEmpty();
        String skin = requestedSkin.isEmpty() ? NpcIdentityService.DEFAULT_SKIN_URL : requestedSkin;
        {
            boolean valid = byPlayer
                    ? NpcIdentityService.isValidSkinPlayerName(skin)
                    : NpcIdentityService.isValidMineSkinUrl(skin);
            if (!valid) {
                return done(CitizensProvisionResult.reject("INVALID_SKIN", byPlayer
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
            return writeDefinition(npcId, name, dialogueId, null, true, true).thenCompose(defResult -> {
                if (!defResult.ok()) {
                    return done(CitizensProvisionResult.reject(defResult.code(), defResult.message()));
                }
                // 2) Apparition + liaison, par le parcours canonique (avec son propre rollback).
                return citizensCreate(npcId, spot.world(), spot.x(), spot.y(), spot.z(), finalYaw, finalPitch)
                        .thenCompose(created -> {
                            if (!created.ok()) {
                                return compensate(npcId, name, created.code(), created.message());
                            }
                            return applyOptionalSkin(npcId, skin, byPlayer).thenApply(skinOutcome ->
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
     * Applique le skin demandé (ou l'apparence par défaut), et décrit honnêtement ce qui s'est passé
     * — jamais un succès muet.
     *
     * <p>Un refus de Citizens ici ne détruit pas le PNJ : il est créé, lié et valide. On le dit, on
     * marque le résultat d'un code distinct ({@code PROVISIONED_SKIN_FAILED}) et on indique où
     * réappliquer le skin. Détruire un PNJ correct parce qu'une texture n'a pas été acceptée serait
     * un nettoyage disproportionné.</p>
     */
    private CompletableFuture<SkinOutcome> applyOptionalSkin(String npcId, String skin, boolean byPlayerName) {
        return citizensSkin(npcId, skin, byPlayerName).thenApply(r -> r.ok()
                ? new SkinOutcome(true, byPlayerName
                        ? "Apparence demandée (visible en jeu dans quelques secondes)."
                        : NpcIdentityService.DEFAULT_SKIN_URL.equals(skin)
                                ? "Apparence par défaut posée et vérifiée."
                                : "Apparence posée et vérifiée.")
                : new SkinOutcome(false, "Le PNJ est créé, mais son apparence n'a pas été appliquée ("
                        + r.message() + "). La réappliquer depuis « Apparence » sur sa fiche."));
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
        return citizensRelocate(npcId, world, x, y, z, yaw, pitch);
    }

    /**
     * Déplace le PNJ lié. Issue #325 : orientation {@code null} = celle du PNJ est gardée, et si le
     * PNJ se promène, le centre de sa zone devient la destination — jamais une position temporaire
     * de sa promenade.
     */
    @Override
    public CompletableFuture<MutationResult> citizensRelocate(String npcId, String world,
                                                              double x, double y, double z,
                                                              Float yaw, Float pitch) {
        String worldName = world == null ? "" : world.trim();
        String positionError = CitizensSpawnPlanner.positionError(x, y, z,
                yaw == null ? 0f : yaw, pitch == null ? 0f : pitch);
        if (positionError != null) {
            return done(MutationResult.of(false, "INVALID_POSITION", positionError));
        }
        Set<String> allowed = allowedSpawnWorlds.get();
        if (worldName.isEmpty() || allowed.stream().noneMatch(w -> w.equalsIgnoreCase(worldName))) {
            return done(MutationResult.of(false, "UNKNOWN_WORLD",
                    "Ce monde n'accepte pas de PNJ (« " + safe(world) + " »). Mondes possibles : "
                            + allowed + "."));
        }
        if (!npcIdentityService.citizensAvailable()) {
            return done(MutationResult.of(false, "CITIZENS_UNAVAILABLE", "Citizens n'est pas disponible."));
        }
        return citizensUuidOf(npcId).thenCompose(uuid -> {
            if (uuid.isEmpty()) {
                return done(MutationResult.of(false, "NO_CITIZENS_BINDING",
                        "Ce PNJ n'existe pas encore dans le monde."));
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
                            "La hauteur Y doit être comprise entre " + w.getMinHeight() + " et "
                                    + (w.getMaxHeight() - 1) + "."));
                }
                // Sûreté de l'arrivée : même critère que le placement automatique — sol praticable
                // et deux cases libres. On ne casse ni ne pose jamais de bloc pour y arriver.
                NpcPlacementPlanner.Probe probe = new BukkitNpcPlacementProbe(w, java.util.Set.of());
                if (!NpcPlacementPlanner.isAcceptable((int) Math.floor(x), (int) Math.floor(y),
                        (int) Math.floor(z), probe)) {
                    return done(MutationResult.of(false, "UNSAFE_DESTINATION",
                            "Cet endroit ne laisse pas assez de place au PNJ : il faut un sol solide "
                                    + "et deux blocs d'air au-dessus, sans eau ni lave. Le PNJ n'a pas bougé."));
                }
                Optional<CitizensNpc> current = npcIdentityService.citizensRoster().stream()
                        .filter(n -> n.uuid().equals(citizensUuid)).findFirst();
                float finalYaw = yaw != null ? yaw
                        : current.filter(CitizensNpc::hasLocation).map(CitizensNpc::yaw).orElse(0f);
                float finalPitch = pitch != null ? pitch
                        : current.filter(CitizensNpc::hasLocation).map(CitizensNpc::pitch).orElse(0f);

                org.bukkit.Location target = new org.bukkit.Location(w, x, y, z, finalYaw, finalPitch);
                Optional<org.bukkit.Location> after = npcIdentityService.moveCitizens(citizensUuid, target);
                if (after.isEmpty()) {
                    return done(MutationResult.of(false, "CITIZENS_NPC_MISSING",
                            "Le personnage est introuvable en jeu (supprimé ?)."));
                }
                org.bukkit.Location reached = after.get();
                boolean sameWorld = reached.getWorld() != null
                        && reached.getWorld().getName().equalsIgnoreCase(worldName);
                boolean close = Math.abs(reached.getX() - x) < 1.0 && Math.abs(reached.getY() - y) < 1.0
                        && Math.abs(reached.getZ() - z) < 1.0;
                if (!sameWorld || !close) {
                    // Vérification réelle plutôt que supposition : Citizens n'a pas appliqué.
                    return done(MutationResult.of(false, "MOVE_NOT_APPLIED",
                            "Le déplacement n'a pas été appliqué : le PNJ est toujours à "
                                    + describe(reached) + "."));
                }
                // Promenade active : le centre de la zone suit la destination explicite.
                com.lodygames.rpgquest.npc.NpcBehaviourOutcome reanchored =
                        npcIdentityService.reanchorWander(citizensUuid, reached.clone());
                String wanderNote = "WANDER_REANCHORED".equals(reanchored.code())
                        ? " " + reanchored.message() : "";
                List<String> effects = new ArrayList<>(List.of("citizens:moved=" + describe(reached)));
                if (!wanderNote.isEmpty()) {
                    effects.add("citizens:wander-anchor=" + describe(reached));
                }
                return done(new MutationResult(true, "MOVED",
                        "PNJ déplacé vers " + describe(reached) + "." + wanderNote, List.copyOf(effects)));
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
        // Issue #325 : une promenade déjà active garde SON centre. La position actuelle du PNJ n'est
        // alors qu'un point temporaire de sa promenade, jamais un nouveau départ.
        Optional<WanderState> active = npcIdentityService.wanderOf(citizensUuid)
                .filter(ws -> ws.enabled() && ws.hasAnchor());
        if (active.isPresent()) {
            World w = plugin.getServer().getWorld(active.get().anchorWorld());
            if (w != null) {
                return new org.bukkit.Location(w, active.get().anchorX(), active.get().anchorY(),
                        active.get().anchorZ());
            }
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
     * Issue #325 — « Arrêter la promenade et revenir au départ ». Le centre est lu AVANT l'arrêt
     * (après, Citizens l'a oublié), puis la destination est contrôlée comme un déplacement. Si le
     * retour est impossible, la promenade reste arrêtée et le message dit où est le PNJ.
     */
    @Override
    public CompletableFuture<MutationResult> citizensWanderStop(String npcId, boolean returnHome) {
        return withBoundCitizens(npcId, uuid -> {
            Optional<WanderState> before = npcIdentityService.wanderOf(uuid);
            com.lodygames.rpgquest.npc.NpcBehaviourOutcome stopped = npcIdentityService.disableWander(uuid);
            if (!stopped.ok() || !returnHome) {
                return toMutation(stopped);
            }
            WanderState w = before.orElse(null);
            World world = w == null || !w.hasAnchor() ? null : plugin.getServer().getWorld(w.anchorWorld());
            if (world == null) {
                return MutationResult.of(false, "WANDER_STOPPED_NO_HOME",
                        "Promenade arrêtée, mais son point de départ est inconnu : le PNJ est resté "
                                + "où il était. Utilisez « Déplacer » pour le replacer.");
            }
            NpcPlacementPlanner.Probe probe = new BukkitNpcPlacementProbe(world, java.util.Set.of());
            if (!NpcPlacementPlanner.isAcceptable((int) Math.floor(w.anchorX()), (int) Math.floor(w.anchorY()),
                    (int) Math.floor(w.anchorZ()), probe)) {
                return MutationResult.of(false, "WANDER_STOPPED_HOME_UNSAFE",
                        "Promenade arrêtée, mais son point de départ ne laisse plus assez de place au "
                                + "PNJ : il est resté où il était. Utilisez « Déplacer » pour le replacer.");
            }
            Optional<CitizensNpc> current = npcIdentityService.citizensRoster().stream()
                    .filter(n -> n.uuid().equals(uuid) && n.hasLocation()).findFirst();
            org.bukkit.Location home = new org.bukkit.Location(world, w.anchorX(), w.anchorY(), w.anchorZ(),
                    current.map(CitizensNpc::yaw).orElse(0f), current.map(CitizensNpc::pitch).orElse(0f));
            Optional<org.bukkit.Location> reached = npcIdentityService.moveCitizens(uuid, home);
            boolean back = reached.isPresent() && reached.get().getWorld() != null
                    && reached.get().getWorld().getName().equalsIgnoreCase(world.getName())
                    && reached.get().distanceSquared(home) < 1.0;
            if (!back) {
                return MutationResult.of(false, "WANDER_STOPPED_NOT_RETURNED",
                        "Promenade arrêtée, mais le retour au départ n'a pas été appliqué. "
                                + "Utilisez « Déplacer » pour le replacer.");
            }
            return new MutationResult(true, "WANDER_STOPPED_RETURNED",
                    "Promenade arrêtée. Le PNJ est revenu à son point de départ (" + describe(home) + ").",
                    List.of("citizens:moved=" + describe(home)));
        });
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
        // Registre Citizens = API main-thread ; liaisons et sources d'apparence = base async.
        // Les DEUX lectures de base sont consommées AVANT d'entrer sur le thread principal : y
        // attendre un Future gèlerait le serveur le temps de la requête (leçon de l'issue #156).
        return npcBindingRepository.loadAll().thenCompose(bindings -> npcSkinSourceRepository.loadAll()
                .thenCompose(skinSources -> {
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
                            n.wander() == null ? null : n.wander().yRange(),
                            // Issue #197 — apparence. Le type dit si la question du skin a un sens ;
                            // la source dit si RPGQuest sait la reproduire. Absence de source ≠
                            // absence de skin : l'API publique de Citizens ne permet pas de le lire.
                            n.entityType(),
                            skinSources.containsKey(n.uuid())
                                    ? skinSources.get(n.uuid()).kind().name() : null,
                            skinSources.containsKey(n.uuid())
                                    ? skinSources.get(n.uuid()).value() : null,
                            skinMatchesSource(n, skinSources.get(n.uuid()))));
                }
                rows.sort((a, b) -> Integer.compare(a.numericId(), b.numericId()));
                return done(new CitizensRosterView(true, List.copyOf(rows), rows.size(), available2, linked));
            });
        }));
    }

    /**
     * Issue #325 — le PNJ porte-t-il réellement l'apparence enregistrée ? Compare l'image de la
     * texture lue sur Citizens à celle du lien enregistré. {@code null} = on ne peut pas le savoir
     * (pas un PNJ joueur, source par pseudo, lien personnalisé pas encore relu depuis le
     * démarrage) — jamais « oui » par défaut. Thread principal.
     */
    private Boolean skinMatchesSource(CitizensNpc npc, NpcSkinSourceRepository.SkinSource source) {
        if (!npc.playerType() || source == null || source.kind() != NpcSkinSourceRepository.Kind.URL) {
            return null;
        }
        Optional<String> expected = skinResolver.knownHash(source.value());
        if (expected.isEmpty()) {
            return null;
        }
        return npcIdentityService.appliedSkinHash(npc.uuid()).map(expected.get()::equals).orElse(false);
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
                    d.id().toString().toLowerCase(Locale.ROOT), d.startNodeId(), orderedNodes(d),
                    d.entries().stream().map(e -> e.nodeId()).toList()));
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
            case com.lodygames.rpgquest.dialogue.model.OfferCatalogQuestAction x -> new DialogueCatalog.Action(
                    "OFFER_CATALOG_QUEST", x.catalogId(), null, x.catalogId() == null
                    ? "OFFER_CATALOG_QUEST (catalogues du PNJ)" : "OFFER_CATALOG_QUEST " + x.catalogId());
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
            case com.lodygames.rpgquest.dialogue.model.QuestAvailableCondition x -> new DialogueCatalog.Condition(
                    "QUEST_AVAILABLE", x.questId().toString(), null, "QUEST_AVAILABLE " + x.questId(), false);
            case HasItemCondition x -> new DialogueCatalog.Condition("HAS_ITEM", x.material().name(),
                    Integer.toString(x.amount()), "HAS_ITEM " + x.amount() + "x " + x.material().name(), false);
            case HasPermissionCondition x -> new DialogueCatalog.Condition("HAS_PERMISSION", x.permission(), null,
                    "HAS_PERMISSION " + x.permission(), false);
            case VariableEqualsCondition x -> new DialogueCatalog.Condition("VARIABLE_EQUALS", x.key(), x.value(),
                    "VARIABLE_EQUALS " + x.key() + " = " + x.value(), false);
            case LacksCustomItemCondition x -> new DialogueCatalog.Condition("LACKS_CUSTOM_ITEM",
                    x.itemId().toString(), null, "LACKS_CUSTOM_ITEM " + x.itemId(), false);
            case com.lodygames.rpgquest.dialogue.model.HasInventorySpaceCondition x -> new DialogueCatalog.Condition(
                    "HAS_INVENTORY_SPACE", null, Integer.toString(x.slots()),
                    "HAS_INVENTORY_SPACE " + x.slots(), false);
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
    public CompletableFuture<ResetPreview> resetPreview(UUID playerId,
                                                        PlayerResetService.ResetScope scope) {
        return onMain(() -> playerResetService.previewReset(playerId, scope).thenApply(preview -> {
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
                case ON_COOLDOWN -> MutationResult.of(false, "ON_COOLDOWN",
                        "Quête renouvelable encore en recharge (" + CooldownText.format(outcome.retryAfter())
                                + ") : " + questId + " — réinitialiser pour passer outre.");
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
                case INVENTORY_FULL -> MutationResult.of(false, "INVENTORY_FULL",
                        "Non terminée : les récompenses de " + questId + " ne tiennent pas dans l'inventaire de "
                                + target.getName() + ". Rien n'a été accordé.");
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
                case INVENTORY_FULL -> MutationResult.of(false, "INVENTORY_FULL",
                        "Story " + id + " : quête " + r.completedQuestId() + " non terminée, ses récompenses ne "
                                + "tiennent pas dans l'inventaire de " + target.getName() + ". Rien n'a bougé.");
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
            // Issue #318 : plus de reste jeté au sol — tout ou rien, et un refus honnête au panel.
            ItemDelivery.Result delivery = ItemDelivery.deliver(target, stack.get());
            if (!delivery.delivered()) {
                target.sendMessage(ItemDelivery.inventoryFullMessage(delivery.missingSlots()));
                return done(MutationResult.of(false, "INVENTORY_FULL",
                        "Rien donné : l'inventaire de " + target.getName() + " est plein ("
                                + delivery.missingSlots() + " emplacement(s) à libérer)."));
            }
            String msg = amount + "x " + itemId + " donné à " + target.getName();
            return done(new MutationResult(true, "GIVEN", msg,
                    List.of(amount + "x « " + displayName + " » (" + itemId + ")")));
        });
    }

    @Override
    public CompletableFuture<MutationResult> resetConfirm(UUID playerId, String playerName,
                                                           PlayerResetService.ResetScope scope) {
        return onMain(() -> playerResetService.reset(playerId, playerName == null ? "" : playerName, scope)
                .thenApply(summary -> new MutationResult(true, "RESET_DONE",
                        scope.label() + " effectué.",
                        List.of(summary.online()
                                ? (scope.wipesInventory()
                                        ? "Inventaire, équipement et coffre de l'Ender vidés : "
                                                + Math.max(summary.inventoryItemsRemoved(), 0) + " objet(s)"
                                        : "Objets RPGQuest retirés : "
                                                + Math.max(summary.inventoryItemsRemoved(), 0)
                                                + " (inventaire vanilla conservé)")
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
        // Issue #156 : le nombre de découvertes de Waystones est une lecture SQL. Elle est faite
        // ICI, hors du thread principal, et son résultat est passé au relevé — jamais attendue
        // depuis le thread principal, où elle coûterait des ticks à ce qu'elle décrit. Un échec
        // donne -1, soit « non relevé », et n'empêche jamais le relevé d'aboutir.
        CompletableFuture<Integer> discoveries = waystoneDiscoveryCount == null
                ? CompletableFuture.completedFuture(-1)
                : waystoneDiscoveryCount.get().exceptionally(error -> {
                    plugin.getSLF4JLogger().warn("Impossible de compter les découvertes de Waystones", error);
                    return -1;
                });
        return discoveries.thenCompose(this::travelCatalogOnMain);
    }

    private CompletableFuture<TravelCatalogView> travelCatalogOnMain(int waystoneDiscoveries) {
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
                        w.biomeInstance(), w.x(), w.y(), w.z(), w.active(), w.modelVersion(), pairedBeaconId,
                        w.createdAt() == null ? "" : w.createdAt().toString()));
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
                        b.modelVersion(), b.isAutoGenerated(), b.biomeInstance(), pairedWaypointId,
                        b.createdAt() == null ? "" : b.createdAt().toString()));
            }

            // Issue #156 : la raison de chaque appariement manquant, pas seulement sa liste.
            List<UnpairedHubInstance> unpaired = new ArrayList<>();
            for (TravelBeaconService.PairingGap gap : travelBeaconService.hubPairingGaps()) {
                unpaired.add(new UnpairedHubInstance(gap.waypointId(), gap.biomeInstance(), gap.biomeKey(),
                        gap.x(), gap.z(), gap.attempts(), gap.nextRetryEpochMs(), gap.inProgress(),
                        gap.nearestBeaconDistance()));
            }

            // Issue #156 : le référentiel que l'écran doit pouvoir citer sans l'inventer. Absent =
            // rendu comme « non relevé », jamais une valeur par défaut qui passerait pour mesurée.
            var travel = travelConfigSupplier == null ? null : travelConfigSupplier.get();
            // regionSize est un long en configuration, comme cellSize : borné pour l'affichage.
            int regionSize = travel == null ? 0
                    : (int) Math.min(Integer.MAX_VALUE, travel.waypoint().regionSize());
            int wpSpacing = travel == null ? 0 : travel.waypoint().minimumSpacing();
            int pairMin = travel == null ? 0 : travel.beacon().pairMinSpacing();
            int pairMax = travel == null ? 0 : travel.beacon().pairMaxSpacing();
            boolean hubBeacons = travel != null && travel.beacon().hubGenerationEnabled();
            String hub = hubConfig.get().world();
            // Le spawn du Hub vient de la MÊME source sûre que la Pierre de retour (#210) : jamais
            // une coordonnée figée. Inconnu = on le dit, plutôt que d'afficher des distances
            // calculées depuis (0,0).
            var spawn = hubRescueTarget.get()
                    .filter(l -> l.getWorld() != null && l.getWorld().getName().equals(hub));
            int spawnX = spawn.map(org.bukkit.Location::getBlockX).orElse(0);
            int spawnZ = spawn.map(org.bukkit.Location::getBlockZ).orElse(0);

            // Le réseau du WILD, qui n'est pas celui des bornes : sans lui, un administrateur
            // cherchant « pourquoi je ne trouve plus de bornes » ne verrait jamais que les bornes
            // n'existent pas là-bas, et que ce qui compte y est le nombre de Waystones DÉCOUVERTES.
            List<WaystoneNetworkSummary> waystoneNetworks = new ArrayList<>();
            if (waystoneService != null) {
                Map<String, List<com.lodygames.rpgquest.waystone.model.Waystone>> byWorld =
                        new java.util.LinkedHashMap<>();
                for (var ws : waystoneService.all()) {
                    byWorld.computeIfAbsent(ws.world(), k -> new ArrayList<>()).add(ws);
                }
                int discovered = waystoneDiscoveries;
                // cellSize est un long en configuration (une cellule peut être très grande) ;
                // l'écran l'affiche en blocs, donc un int suffit — on borne plutôt que de tronquer
                // silencieusement.
                int cellSize = travel == null ? 0
                        : (int) Math.min(Integer.MAX_VALUE, travel.waystone().cellSize());
                double chance = travel == null ? 0d : travel.waystone().chance();
                int wsSpacing = travel == null ? 0 : travel.waystone().minimumSpacing();
                for (var entry : byWorld.entrySet()) {
                    var list = entry.getValue();
                    int minX = list.stream().mapToInt(com.lodygames.rpgquest.waystone.model.Waystone::x)
                            .min().orElse(0);
                    int maxX = list.stream().mapToInt(com.lodygames.rpgquest.waystone.model.Waystone::x)
                            .max().orElse(0);
                    int minZ = list.stream().mapToInt(com.lodygames.rpgquest.waystone.model.Waystone::z)
                            .min().orElse(0);
                    int maxZ = list.stream().mapToInt(com.lodygames.rpgquest.waystone.model.Waystone::z)
                            .max().orElse(0);
                    waystoneNetworks.add(new WaystoneNetworkSummary(entry.getKey(), list.size(),
                            discovered, cellSize, chance, wsSpacing, minX, maxX, minZ, maxZ));
                }
            }

            TravelCatalogView view = new TravelCatalogView(List.copyOf(waypointSummaries), List.copyOf(beaconSummaries),
                    waypointSummaries.size(), beaconSummaries.size(), autoGenerated, List.copyOf(unpaired),
                    System.currentTimeMillis(),
                    hub, spawnX, spawnZ, spawn.isPresent(),
                    regionSize, pairMin, pairMax, wpSpacing, hubBeacons,
                    List.copyOf(waystoneNetworks));
            return CompletableFuture.completedFuture(view);
        });
    }

    /**
     * Issue #156 — appariement ciblé d'une seule instance du Hub. Toute la décision vit dans
     * {@code TravelBeaconService#pairHubInstance} : ici, uniquement le passage sur le thread
     * principal (la recherche d'emplacement lit le monde) et la mise en forme du résultat.
     */
    @Override
    public CompletableFuture<MutationResult> pairHubBeacon(String waypointId) {
        if (waypointId == null || waypointId.isBlank()) {
            return done(MutationResult.of(false, "INVALID", "Waypoint non précisé."));
        }
        return onMain(() -> {
            Optional<String> refusal = travelBeaconService.pairHubInstance(waypointId.trim());
            if (refusal.isPresent()) {
                // Un refus n'est pas une panne : il n'existe peut-être aucun emplacement valable.
                return done(MutationResult.of(false, "NOT_PAIRED", refusal.get()));
            }
            return done(new MutationResult(true, "PAIRED",
                    "Borne posée et appariée à l'instance du waypoint " + waypointId.trim() + ".",
                    List.of("Rafraîchir le catalogue pour voir la borne et ses coordonnées.")));
        });
    }

    @Override
    public CompletableFuture<MutationResult> renameWaypoint(String waypointId, String displayName) {
        if (waypointId == null || waypointId.isBlank()) {
            return done(MutationResult.of(false, "INVALID", "Waypoint non précisé."));
        }
        return onMain(() -> waypointService.rename(waypointId.trim(), displayName).thenApply(r -> {
            if (!r.ok()) {
                return MutationResult.of(false, r.code(), r.message());
            }
            return new MutationResult(true, r.code(), r.message(), List.of(
                    "Ancien nom : « " + r.previousName() + " ».",
                    r.signsRefreshed() ? "Panneaux réécrits en jeu."
                            : "Chunk non chargé : panneaux réécrits à son prochain chargement.",
                    "Identifiant, position et découvertes inchangés."));
        }));
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
                summonMaxAlive, List.copyOf(abilitiesSummary), def.bossMode().name(), def.communityWeight(),
                def.participationRewards());
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionCreate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive, String bossMode, Integer communityWeight,
            List<String> participationRewards) {
        return writeMobDefinition(id, category, enabled, entityType, displayName, spawnChance, worlds, biomes, zones,
                health, damage, speed, armor, knockbackResistance, scale, creeperExplosionRadius, particle, sound,
                xpReward, maxPopulation, enragedHealthFraction, enragedSpeedMultiplier, enragedDamageMultiplier,
                summonEntityType, summonAmount, summonChance, summonCooldownSeconds, summonMaxAlive, bossMode,
                communityWeight, participationRewards, true);
    }

    @Override
    public CompletableFuture<MutationResult> mobDefinitionUpdate(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive, String bossMode, Integer communityWeight,
            List<String> participationRewards) {
        return writeMobDefinition(id, category, enabled, entityType, displayName, spawnChance, worlds, biomes, zones,
                health, damage, speed, armor, knockbackResistance, scale, creeperExplosionRadius, particle, sound,
                xpReward, maxPopulation, enragedHealthFraction, enragedSpeedMultiplier, enragedDamageMultiplier,
                summonEntityType, summonAmount, summonChance, summonCooldownSeconds, summonMaxAlive, bossMode,
                communityWeight, participationRewards, false);
    }

    @SuppressWarnings("removal") // Particle/Sound#valueOf : voir la justification dans SpecialMobDefinitionParser.
    private CompletableFuture<MutationResult> writeMobDefinition(String id, String category, boolean enabled,
            String entityType, String displayName, double spawnChance, List<String> worlds, List<String> biomes,
            List<String> zones, Double health, Double damage, Double speed, Double armor,
            Double knockbackResistance, Double scale, Double creeperExplosionRadius, String particle, String sound,
            Integer xpReward, Integer maxPopulation, Double enragedHealthFraction, Double enragedSpeedMultiplier,
            Double enragedDamageMultiplier, String summonEntityType, Integer summonAmount, Double summonChance,
            Integer summonCooldownSeconds, Integer summonMaxAlive, String bossMode, Integer communityWeight,
            List<String> participationRewards, boolean create) {
        List<MobAbility> abilities = new ArrayList<>();
        try {
            // Issue #461 : ce que le formulaire n'édite pas (drops, capacités hors Enragé/Renforts, récompenses
            // d'un ancien panel) est repris du profil actuel. S'il est illisible, on refuse plutôt que
            // d'effacer silencieusement ces champs.
            Optional<SpecialMobDefinition> current = create ? Optional.empty() : mobDefinitionStore.find(resolveKey(id));
            if (!create && current.isEmpty() && mobDefinitionStore.hasFile(resolveKey(id))) {
                return done(MutationResult.of(false, "CURRENT_UNREADABLE", "Le profil actuel « " + safe(id)
                        + " » ne se relit pas : modification refusée pour ne perdre ni drops ni "
                        + "capacités. Restaurer d'abord le fichier mobs/ (voir Diagnostics)."));
            }
            current.ifPresent(c -> c.abilities().stream()
                    .filter(a -> !(a instanceof EnragedAbility) && !(a instanceof SummonOnDamageAbility))
                    .forEach(abilities::add));
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
                    // Issue #175 : le formulaire n'édite pas la table de drops -- une modification la
                    // conserve au lieu de l'effacer (elle disparaissait à chaque enregistrement).
                    abilities, current.map(SpecialMobDefinition::drops).orElse(List.of()),
                    xpReward, maxPopulation,
                    bossMode == null ? com.lodygames.rpgquest.mob.model.BossMode.QUEST
                            : com.lodygames.rpgquest.mob.model.BossMode.valueOf(bossMode.toUpperCase(Locale.ROOT)),
                    communityWeight == null ? SpecialMobDefinition.DEFAULT_COMMUNITY_WEIGHT : communityWeight,
                    // Issue #176 : absent (ancien panel) = conservé, comme la table de drops ; liste vide = retiré.
                    participationRewards != null ? participationRewards
                            : current.map(SpecialMobDefinition::participationRewards).orElse(List.of()));

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
        SpecialMobDefinition toggled = current.withEnabled(enabled);
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

    // ---- Boss communautaires (issue #175) -------------------------------------------------------

    private com.lodygames.rpgquest.mob.community.CommunityBossService communityBosses;
    private com.lodygames.rpgquest.mob.community.CommunityBossSettingsStore communityBossSettingsStore;
    private com.lodygames.rpgquest.mob.community.CommunityBossRewards communityBossRewards;

    /** Dépendance facultative (même logique de setter que l'historique des bâtiments). */
    public void setCommunityBosses(com.lodygames.rpgquest.mob.community.CommunityBossService service,
                                   com.lodygames.rpgquest.mob.community.CommunityBossSettingsStore settingsStore,
                                   com.lodygames.rpgquest.mob.community.CommunityBossRewards rewards) {
        this.communityBosses = service;
        this.communityBossSettingsStore = settingsStore;
        this.communityBossRewards = rewards;
    }

    @Override
    public CompletableFuture<CommunityBossView> communityBossStatus() {
        if (communityBosses == null) {
            return AgentActions.super.communityBossStatus();
        }
        var settings = communityBosses.settings();
        var state = communityBosses.snapshot();
        List<CommunityBossInstanceView> instances = new ArrayList<>();
        for (var i : state.instances()) {
            instances.add(new CommunityBossInstanceView(i.entityId().toString(), i.profileId(), i.profileName(),
                    i.world(), i.x(), i.y(), i.z(), i.biome(), i.waypointName(), i.waypointDistance(),
                    i.waypointDirection(), i.spawnedAtMillis(), i.test()));
        }
        List<CommunityBossProfileView> profiles = new ArrayList<>();
        for (SpecialMobDefinition def : mobRegistry.definitions()) {
            if (def.category() == MobCategory.BOSS) {
                profiles.add(new CommunityBossProfileView(def.id().asString(), def.displayName(), def.enabled(),
                        def.bossMode().name(), def.communityWeight(), List.copyOf(def.allowedWorlds()),
                        List.copyOf(def.allowedBiomes()), def.participationRewards()));
            }
        }
        return done(new CommunityBossView(true, settings.enabled(), settings.intervalMinutes(), settings.maxActive(),
                settings.participationRadius(), state.nextDueMillis(), state.lastSpawnMillis(), state.lastAttemptMillis(), state.lastOutcome(),
                System.currentTimeMillis(), List.copyOf(instances), List.copyOf(profiles), recentDefeats(),
                rewardTables()));
    }

    /** Issue #177 : tables de tirage du catalogue chargé (vide sans service ou sans tables). */
    private List<RewardTableView> rewardTables() {
        var service = rewardEntitlements;
        if (service == null) {
            return List.of();
        }
        var catalog = service.catalog();
        List<RewardTableView> views = new ArrayList<>();
        for (var table : catalog.tables().values()) {
            List<RewardTableEntryView> entries = table.entries().stream()
                    .map(e -> new RewardTableEntryView(e.id(), e.rewardKey(), service.label(e.rewardKey()),
                            catalog.find(e.rewardKey()).isPresent(),
                            com.lodygames.rpgquest.entitlement.reward.RewardTable.percent(e.chance()),
                            com.lodygames.rpgquest.entitlement.reward.RewardTableEditor.chanceValue(e.chance())
                                    .toString()))
                    .toList();
            views.add(new RewardTableView(table.id(), table.label(), table.mode().yaml(), table.mode().label(),
                    table.enabled(), table.version(),
                    com.lodygames.rpgquest.entitlement.reward.RewardTable.percent(table.totalChance()),
                    com.lodygames.rpgquest.entitlement.reward.RewardTableDraw.explain(table), entries));
        }
        return List.copyOf(views);
    }

    private List<CommunityBossDefeatView> recentDefeats() {
        if (communityBossRewards == null) {
            return List.of();
        }
        List<CommunityBossDefeatView> views = new ArrayList<>();
        for (var d : communityBossRewards.recentDefeats()) {
            List<CommunityBossParticipantView> participants = d.participants().stream()
                    .map(p -> new CommunityBossParticipantView(p.playerId().toString(), p.name(),
                            p.verdict() == com.lodygames.rpgquest.mob.community.BossParticipation.Verdict.ELIGIBLE,
                            p.verdict() == com.lodygames.rpgquest.mob.community.BossParticipation.Verdict.ELIGIBLE
                                    ? "" : p.reason(d.radius())))
                    .toList();
            views.add(new CommunityBossDefeatView(d.entityId().toString(), d.profileId(), d.profileName(),
                    d.defeatedAtMillis(), d.world(), d.biome(), d.radius(), d.killerName() == null ? "" : d.killerName(), d.test(),
                    participants, d.rewardKeys(), d.rewardStatus(), d.draws()));
        }
        return List.copyOf(views);
    }

    @Override
    public CompletableFuture<MutationResult> communityBossSettingsSet(boolean enabled, int intervalMinutes,
                                                                      int maxActive, Integer participationRadius) {
        if (communityBosses == null) {
            return AgentActions.super.communityBossSettingsSet(enabled, intervalMinutes, maxActive,
                    participationRadius);
        }
        com.lodygames.rpgquest.mob.community.CommunityBossSettings next;
        try {
            next = new com.lodygames.rpgquest.mob.community.CommunityBossSettings(enabled, intervalMinutes, maxActive,
                    participationRadius != null ? participationRadius
                            : communityBosses.settings().participationRadius());
        } catch (IllegalArgumentException e) {
            return done(MutationResult.of(false, "INVALID", e.getMessage()));
        }
        var previous = communityBosses.settings();
        try {
            communityBossSettingsStore.save(next); // fichier : hors du thread principal (thread agent).
        } catch (java.io.IOException e) {
            return done(MutationResult.of(false, "ERROR", "Écriture impossible : " + e.getMessage()));
        }
        return onMain(() -> {
            communityBosses.settingsChanged(previous, next);
            return done(MutationResult.of(true, "UPDATED", next.enabled()
                    ? "Boss communautaires actifs : un essai toutes les " + next.intervalMinutes() + " min, "
                    + next.maxActive() + " boss maximum en même temps, récompenses à " + next.participationRadius()
                    + " blocs au plus."
                    : "Boss communautaires en pause : plus aucune apparition automatique. Récompenses à "
                    + next.participationRadius() + " blocs au plus."));
        });
    }

    @Override
    public CompletableFuture<MutationResult> communityBossTestSpawn(String profileId, String playerName) {
        if (communityBosses == null) {
            return AgentActions.super.communityBossTestSpawn(profileId, playerName);
        }
        return onMain(() -> done(toMutation(communityBosses.testSpawn(profileId, playerName))));
    }

    @Override
    public CompletableFuture<MutationResult> communityBossRemove(String target) {
        if (communityBosses == null) {
            return AgentActions.super.communityBossRemove(target);
        }
        return onMain(() -> {
            if ("tests".equals(target)) {
                return done(toMutation(communityBosses.removeTestInstances()));
            }
            UUID entityId;
            try {
                entityId = UUID.fromString(target);
            } catch (IllegalArgumentException e) {
                return done(MutationResult.of(false, "INVALID", "Identifiant de boss invalide."));
            }
            return done(toMutation(communityBosses.remove(entityId)));
        });
    }

    private static MutationResult toMutation(com.lodygames.rpgquest.mob.community.CommunityBossService.Outcome o) {
        return MutationResult.of(o.ok(), o.code(), o.message());
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
            // #371 : appliquer inclut d'office les familles liées — l'administrateur n'a pas à
            // deviner qu'une quête citant un PNJ neuf exige aussi de recharger les PNJ.
            ContentReloadService.ReloadResult result = apply
                    ? contentReloadService.reloadWithDependencies(requested)
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

    // ---- Publication de contenu vers DEV (issue #47) -------------------------------------------

    /**
     * {@code content.dev.state}. Sur le thread principal : la lecture des identifiants chargés
     * interroge les registres que les listeners du thread principal utilisent.
     */
    @Override
    public CompletableFuture<DevContentStateView> contentDevState() {
        return onMain(() -> {
            List<DevContentFile> files = new ArrayList<>();
            java.util.Map<String, List<String>> runtime = new LinkedHashMap<>();
            for (var kind : com.lodygames.rpgquest.content.publish.PublishKind.values()) {
                for (var file : contentPublishService.list(kind)) {
                    files.add(new DevContentFile(kind.directory(), file.slug(), file.sha256(),
                            file.bytes()));
                }
                runtime.put(kind.directory(), contentPublishService.runtimeIds(kind));
            }
            return done(new DevContentStateView(List.copyOf(files), java.util.Map.copyOf(runtime),
                    contentReloadService.runtimeHash()));
        });
    }

    /** {@code content.dev.read}. Lecture de fichier seule : aucun registre n'est consulté. */
    @Override
    public CompletableFuture<DevContentFileText> contentDevRead(String kind, String slug) {
        var resolved = com.lodygames.rpgquest.content.publish.PublishKind.of(kind);
        if (resolved.isEmpty()) {
            return done(new DevContentFileText(kind == null ? "" : kind, slug == null ? "" : slug,
                    false, false, "", ""));
        }
        String sha = contentPublishService.state(resolved.get(), slug, null).sha256();
        var text = contentPublishService.readDev(resolved.get(), slug);
        // Présent mais illisible par la borne : on le DIT, au lieu de renvoyer un fichier vide qui
        // ferait croire à une ressource vide.
        boolean present = !sha.isEmpty();
        boolean tooLarge = present && text.isEmpty();
        return done(new DevContentFileText(resolved.get().directory(), slug, present, tooLarge,
                sha, text.orElse("")));
    }

    /**
     * {@code content.publish}. Sur le thread principal, parce que l'écriture est immédiatement
     * suivie d'un rechargement qui permute des ensembles lus par ce thread.
     */
    @Override
    public CompletableFuture<ContentPublishResultView> contentPublish(String kind, String slug,
                                                                      String yaml,
                                                                      String expectedDevSha,
                                                                      String expectedId) {
        var resolved = com.lodygames.rpgquest.content.publish.PublishKind.of(kind);
        if (resolved.isEmpty()) {
            return done(refusedPublish("INVALID_KIND",
                    "Famille de contenu non publiable : « " + safe(kind) + " ».", kind, slug));
        }
        return onMain(() -> done(publishView(contentPublishService.publish(resolved.get(), slug,
                yaml, expectedDevSha, expectedId))));
    }

    @Override
    public CompletableFuture<ContentPublishResultView> contentPublishRollback(String kind,
                                                                              String slug,
                                                                              String backupPath,
                                                                              String expectedId) {
        var resolved = com.lodygames.rpgquest.content.publish.PublishKind.of(kind);
        if (resolved.isEmpty()) {
            return done(refusedPublish("INVALID_KIND",
                    "Famille de contenu non publiable : « " + safe(kind) + " ».", kind, slug));
        }
        return onMain(() -> done(publishView(contentPublishService.rollback(resolved.get(), slug,
                backupPath, expectedId))));
    }

    /**
     * {@code content.publish.batch} (issue #427). Sur le thread principal, comme la publication
     * unitaire : l'écriture du lot et son rechargement unique ne doivent pas s'entrelacer avec les
     * listeners qui lisent les ensembles actifs.
     */
    @Override
    public CompletableFuture<ContentPublishBatchView> contentPublishBatch(
            List<ContentPublishBatchItem> items, boolean dryRun) {
        List<com.lodygames.rpgquest.content.publish.ContentPublishService.BatchItem> batch =
                new ArrayList<>();
        for (ContentPublishBatchItem item : items == null ? List.<ContentPublishBatchItem>of() : items) {
            batch.add(new com.lodygames.rpgquest.content.publish.ContentPublishService.BatchItem(
                    com.lodygames.rpgquest.content.publish.PublishKind.of(item.kind()).orElse(null),
                    item.slug(), item.yaml(), item.expectedDevSha(), item.expectedId()));
        }
        return onMain(() -> done(batchView(contentPublishService.publishBatch(batch, dryRun))));
    }

    private static ContentPublishBatchView batchView(
            com.lodygames.rpgquest.content.publish.ContentPublishService.BatchOutcome o) {
        List<ContentPublishBatchItemView> items = o.items().stream()
                .map(i -> new ContentPublishBatchItemView(
                        i.kind() == null ? "" : i.kind().directory(), i.slug(), i.expectedId(),
                        i.status(), i.created(), i.devShaBefore(), i.devShaAfter(), i.sourceSha(),
                        i.backupPath(), i.runtimeConfirmed(), i.problems()))
                .toList();
        return new ContentPublishBatchView(o.ok(), o.code(), o.message(), o.dryRun(),
                o.filesRestored(), items, o.otherProblems(), o.families(),
                o.autoIncludedFamilies(), o.reloadCode(), o.loadedCount(), o.runtimeHash(),
                o.verifiedAt() == null ? "" : o.verifiedAt().toString());
    }

    private static ContentPublishResultView publishView(
            com.lodygames.rpgquest.content.publish.ContentPublishService.PublishOutcome o) {
        return new ContentPublishResultView(o.ok(), o.code(), o.message(),
                o.kind() == null ? "" : o.kind().directory(), o.slug(), o.expectedId(),
                o.devShaBefore(), o.devShaAfter(), o.sourceSha(), o.created(), o.backupPath(),
                o.reloadApplied(), o.reloadCode(), o.reloadMessage(),
                o.loadedCount(), o.issueCount(), o.runtimeConfirmed(), o.runtimeHash(),
                o.verifiedAt() == null ? "" : o.verifiedAt().toString(),
                o.reloadDetail().referenceErrors(), o.reloadDetail().preexistingReferenceErrors(),
                o.reloadDetail().families(), o.reloadDetail().autoIncludedFamilies(),
                o.reloadDetail().suggestedFamilies());
    }

    private static ContentPublishResultView refusedPublish(String code, String message,
                                                           String kind, String slug) {
        return new ContentPublishResultView(false, code, message, kind == null ? "" : kind,
                slug == null ? "" : slug, "", "", "", "", false, "", false, "", "", 0, 0,
                false, "", "");
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
                // Issue #316 : le joueur est prévenu en privé, seulement maintenant que l'état relu a changé.
                String notice = operatorNotices == null ? "Le joueur n'a pas été prévenu (service indisponible)."
                        : operatorNotices.changed(playerId, !op, op)
                                == com.lodygames.rpgquest.player.OperatorNoticeService.Delivery.DELIVERED
                                ? "Message privé envoyé au joueur."
                                : "Joueur hors ligne : il sera prévenu à sa prochaine connexion.";
                return done(new MutationResult(true, op ? "OPPED" : "DEOPPED",
                        label + (op ? " est désormais OP. " : " n'est plus OP. ") + notice,
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
                        "Récompense créditée : " + CurrencyName.amount(receipt.amount()) + ". Nouveau solde : "
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
                case MoneyReward r -> "+" + CurrencyName.amount(r.amount());
                case HeartReward r -> heartRewardText(r);
                case com.lodygames.rpgquest.quest.model.TableReward r -> "tirage table " + r.tableId();
            });
        }
        return out;
    }

    /**
     * Objectif structuré (#78). Issue #185 : la portée et la règle de comptage voyagent avec
     * l'objectif, sinon le panel afficherait une ligne qui ne dit pas ce que le joueur doit faire.
     */
    private static ObjectiveSummary objectiveSummary(QuestObjective o) {
        int amount = QuestObjective.requiredAmount(o);
        String raw = QuestObjective.describe(o) + " (x" + amount + ")";
        List<String> objectiveWorlds = o instanceof DiscoverWaypointObjective d ? d.worlds() : List.of();
        String countMode = o instanceof DiscoverWaypointObjective d ? d.countMode().name()
                : o instanceof com.lodygames.rpgquest.quest.model.DiscoverCityObjective c
                        ? c.countMode().name() : null;
        return new ObjectiveSummary(o.type().name(), objectiveTarget(o), amount, raw, objectiveNpc(o),
                objectiveWorlds, countMode);
    }

    /**
     * Issue #79 : progression de l'étape courante, chaque ligne accompagnée de son objectif
     * structuré. La vue du moteur suit l'ordre des objectifs de l'étape ; si l'étape ou le nombre
     * d'objectifs ne correspond plus (définition rechargée), la ligne reste sans structure plutôt
     * que d'être associée au mauvais objectif.
     */
    static List<ObjectiveState> objectiveStates(QuestDefinition quest, QuestStepProgressView view) {
        List<QuestObjective> defined = quest.steps().stream()
                .filter(step -> step.id().equals(view.stepId()))
                .findFirst().map(QuestStep::objectives).orElse(List.of());
        boolean aligned = defined.size() == view.objectives().size();
        List<ObjectiveState> out = new ArrayList<>();
        for (int i = 0; i < view.objectives().size(); i++) {
            ObjectiveProgressView o = view.objectives().get(i);
            out.add(new ObjectiveState(o.description(), o.current(), o.total(),
                    aligned ? objectiveSummary(defined.get(i)) : null));
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
            // Issue #178 : la ville précise (identifiant stable) ; null pour « X villes distinctes ».
            case com.lodygames.rpgquest.quest.model.DiscoverCityObjective o -> o.city();
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
                        "+" + CurrencyName.amount(r.amount()));
                // target = clé de droit explicite (null = propre à la quête), comme la clé d'une VARIABLE.
                case HeartReward r -> new RewardSummary("HEART", r.hearts(), r.grantKey(), null, null,
                        heartRewardText(r));
                // Issue #177 : target = identifiant de la table, comme la clé d'une VARIABLE.
                case com.lodygames.rpgquest.quest.model.TableReward r -> new RewardSummary("TABLE", 0, r.tableId(),
                        null, null, "tirage table " + r.tableId());
            });
        }
        return out;
    }

    /** Issue #166 : « +1 cœur(s) de vie maximum », suivi de la clé de droit si elle est partagée. */
    private static String heartRewardText(HeartReward reward) {
        return "+" + reward.hearts() + " cœur(s) de vie maximum"
                + (reward.grantKey() != null ? " [" + reward.grantKey() + "]" : "");
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
