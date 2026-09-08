package com.lodygames.rpgquest.web.agent;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.NpcBindingRepository;
import com.lodygames.rpgquest.dialogue.DialogueCatalog;
import com.lodygames.rpgquest.dialogue.DialogueDefinitionEditor;
import com.lodygames.rpgquest.dialogue.DialogueDefinitionStore;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.model.AdvanceQuestAction;
import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.DialogueAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueDraft;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.GiveItemAction;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.OpenDialogueAction;
import com.lodygames.rpgquest.dialogue.model.OpenMerchantAction;
import com.lodygames.rpgquest.dialogue.model.QuestStateCondition;
import com.lodygames.rpgquest.dialogue.model.RunSafeCommandAction;
import com.lodygames.rpgquest.dialogue.model.SetVariableAction;
import com.lodygames.rpgquest.dialogue.model.StartQuestAction;
import com.lodygames.rpgquest.dialogue.model.TakeItemAction;
import com.lodygames.rpgquest.dialogue.model.TurnInQuestAction;
import com.lodygames.rpgquest.dialogue.model.VariableEqualsCondition;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.model.CustomItemDefinition;
import com.lodygames.rpgquest.npc.CitizensNpc;
import com.lodygames.rpgquest.npc.CitizensSpawnCoordinator;
import com.lodygames.rpgquest.npc.CitizensSpawnPlanner;
import com.lodygames.rpgquest.npc.NpcCatalog;
import com.lodygames.rpgquest.npc.NpcDefinitionStore;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.npc.NpcLoadIssue;
import com.lodygames.rpgquest.npc.QuestGiverStore;
import com.lodygames.rpgquest.npc.YamlNpcEngine;
import com.lodygames.rpgquest.npc.model.NpcDefinition;
import com.lodygames.rpgquest.player.PlayerResetService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.BreakBlockObjective;
import com.lodygames.rpgquest.quest.model.CollectItemObjective;
import com.lodygames.rpgquest.quest.model.CommandReward;
import com.lodygames.rpgquest.quest.model.CraftItemObjective;
import com.lodygames.rpgquest.quest.model.ExperienceReward;
import com.lodygames.rpgquest.quest.model.ItemReward;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
    private final YamlNpcEngine npcEngine;
    private final NpcDefinitionStore npcStore;
    private final QuestGiverStore questGiverStore;
    private final Supplier<Set<String>> allowedSpawnWorlds;
    private final DialogueDefinitionStore dialogueStore;
    private final DialogueDefinitionEditor dialogueEditor;

    public BukkitAgentActions(RPGQuestPlugin plugin, YamlQuestEngine questEngine,
                              QuestProgressEngine questProgressEngine, StoryService storyService,
                              YamlCustomItemRegistry customItemRegistry, PlayerResetService playerResetService,
                              PlayerVariableWriter variableWriter, YamlDialogueEngine dialogueEngine,
                              NpcIdentityService npcIdentityService, NpcBindingRepository npcBindingRepository,
                              YamlNpcEngine npcEngine, NpcDefinitionStore npcStore, QuestGiverStore questGiverStore,
                              Supplier<Set<String>> allowedSpawnWorlds, DialogueDefinitionStore dialogueStore,
                              DialogueDefinitionEditor dialogueEditor) {
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
        this.npcEngine = npcEngine;
        this.npcStore = npcStore;
        this.questGiverStore = questGiverStore;
        this.allowedSpawnWorlds = allowedSpawnWorlds;
        this.dialogueStore = dialogueStore;
        this.dialogueEditor = dialogueEditor;
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
                    objectiveDetails.add(new ObjectiveSummary(o.type().name(), objectiveTarget(o), amount, raw));
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
            for (QuestStep step : q.steps()) {
                for (QuestObjective objective : step.objectives()) {
                    if (objective instanceof TalkToNpcObjective t && !talk.contains(t.npcId())) {
                        talk.add(t.npcId());
                    }
                }
            }
            questLinks.add(new NpcCatalog.QuestLink(q.id().toString(), q.giver(), List.copyOf(talk)));
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
                        r.questsReferenced(), r.sources(), r.state(), List.copyOf(warnings)));
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
                            linkedNpcId, free, n.spawned()));
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
    public CompletableFuture<MutationResult> dialogueChoiceUpdate(String dialogueId, String nodeId, int choiceIndex,
                                                                  String choiceText, String nextNodeId, boolean close) {
        return applyEdit(dialogueEditor.updateChoice(dialogueId, nodeId, choiceIndex, choiceText, nextNodeId, close));
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

    // ---- Utilitaires --------------------------------------------------------------------------

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

    private static List<String> describeRewards(List<QuestReward> rewards) {
        List<String> out = new ArrayList<>();
        for (QuestReward reward : rewards) {
            out.add(switch (reward) {
                case ExperienceReward r -> "+" + r.amount() + " XP";
                case ItemReward r -> "+" + r.amount() + "x " + r.material();
                case VariableReward r -> "variable " + r.key() + " = " + r.value();
                case CommandReward r -> "commande console : " + truncate(r.command(), 60);
            });
        }
        return out;
    }

    /** Jeton technique de la cible d'un objectif : entité, matériau, id de PNJ, ou nom de monde (#78). */
    private static String objectiveTarget(QuestObjective objective) {
        return switch (objective) {
            case BreakBlockObjective o -> o.material().name();
            case PlaceBlockObjective o -> o.material().name();
            case KillEntityObjective o -> o.entity().name();
            case CollectItemObjective o -> o.material().name();
            case CraftItemObjective o -> o.material().name();
            case TalkToNpcObjective o -> o.npcId();
            case ReachLocationObjective o -> o.world();
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
