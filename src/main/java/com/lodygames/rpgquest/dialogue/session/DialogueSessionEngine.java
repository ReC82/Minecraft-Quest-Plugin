package com.lodygames.rpgquest.dialogue.session;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.bootstrap.PluginService;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.database.PlayerVariableRepository;
import com.lodygames.rpgquest.dialogue.DialogueNpcResolution;
import com.lodygames.rpgquest.dialogue.DialogueTextPlaceholders;
import com.lodygames.rpgquest.dialogue.YamlDialogueEngine;
import com.lodygames.rpgquest.dialogue.model.AdvanceQuestAction;
import com.lodygames.rpgquest.dialogue.model.CloseAction;
import com.lodygames.rpgquest.dialogue.model.DeliverQuestItemsAction;
import com.lodygames.rpgquest.dialogue.model.DialogueAction;
import com.lodygames.rpgquest.dialogue.model.DialogueChoice;
import com.lodygames.rpgquest.dialogue.model.DialogueCondition;
import com.lodygames.rpgquest.dialogue.model.DialogueDefinition;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.GiveItemAction;
import com.lodygames.rpgquest.dialogue.model.GiveStarterKitAction;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.NoMainClaimCondition;
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
import com.lodygames.rpgquest.dialogue.render.DialogueChoiceHandler;
import com.lodygames.rpgquest.dialogue.render.DialogueRenderer;
import com.lodygames.rpgquest.dialogue.render.VisibleChoice;
import com.lodygames.rpgquest.economy.merchant.MerchantTradeService;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.player.StarterToolKitService;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Orchestre l'ouverture des dialogues, l'évaluation des conditions, la
 * sélection des choix et l'exécution de leurs actions. Le rendu concret est
 * délégué à un {@link DialogueRenderer} interchangeable (voir
 * {@code dialogue.render}). Les sessions sont **volatiles, en mémoire
 * uniquement** — aucune persistance, une déconnexion ferme simplement la
 * session (voir docs/ARCHITECTURE.md pour le raisonnement).
 */
public final class DialogueSessionEngine implements PluginService, DialogueChoiceHandler {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final YamlDialogueEngine dialogueEngine;
    private final QuestProgressEngine questProgressEngine;
    private final PlayerVariableRepository variableRepository;
    private final MerchantTradeService merchantTradeService;
    private final NpcIdentityService npcIdentityService;
    private final ClaimService claimService;
    private final YamlCustomItemRegistry customItemRegistry;
    private final StarterToolKitService starterToolKitService;
    private final Logger logger;

    private final Map<UUID, DialogueSession> sessions = new ConcurrentHashMap<>();
    private volatile DialogueRenderer renderer;
    /** Valeurs dynamiques substituées dans le texte d'un nœud juste avant son rendu (issue #24). */
    private volatile DialogueTextPlaceholders placeholders = DialogueTextPlaceholders.none();
    /** Issue #12 — notifie le service de signal visuel qu'un nœud vient d'être affiché. */
    private volatile NodePresentedListener nodePresented;

    public DialogueSessionEngine(RPGQuestPlugin plugin, YamlDialogueEngine dialogueEngine,
                                  QuestProgressEngine questProgressEngine, PlayerVariableRepository variableRepository,
                                  MerchantTradeService merchantTradeService, NpcIdentityService npcIdentityService,
                                  ClaimService claimService, YamlCustomItemRegistry customItemRegistry,
                                  StarterToolKitService starterToolKitService) {
        this.plugin = plugin;
        this.dialogueEngine = dialogueEngine;
        this.questProgressEngine = questProgressEngine;
        this.variableRepository = variableRepository;
        this.merchantTradeService = merchantTradeService;
        this.npcIdentityService = npcIdentityService;
        this.claimService = claimService;
        this.customItemRegistry = customItemRegistry;
        this.starterToolKitService = starterToolKitService;
        this.logger = plugin.getSLF4JLogger();
    }

    /** Câblé après construction par le bootstrap (dépendance circulaire évitée : voir dialogue.render.DialogueChoiceHandler). */
    public void setRenderer(DialogueRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Installe les valeurs dynamiques disponibles dans le texte des nœuds (issue #24 : {@code
     * %wild_conditions%}). Même patron que {@link #setRenderer} plutôt qu'un argument de
     * constructeur : les sources de ces valeurs (état d'un monde, services métier) sont construites
     * ailleurs dans le bootstrap, et l'absence de substitution reste un comportement valide
     * ({@link DialogueTextPlaceholders#none()} par défaut).
     */
    public void setPlaceholders(DialogueTextPlaceholders placeholders) {
        this.placeholders = placeholders;
    }

    @Override
    public void start() {
        // Rien à démarrer : le chargement des définitions est un service séparé (YamlDialogueEngine), déjà démarré avant celui-ci.
    }

    @Override
    public void stop() {
        sessions.clear();
    }

    public void unloadForPlayer(UUID playerId) {
        sessions.remove(playerId);
    }

    /** Écouteur de clic sur PNJ (entité nommée) à enregistrer sans condition, comme la connexion des quêtes. */
    public Listener npcInteractListener() {
        return new DialogueNpcInteractListener(this, dialogueEngine, npcIdentityService);
    }

    /**
     * Équivalent Citizens de {@link #npcInteractListener()} — {@code null} si Citizens n'est pas
     * installé/actif (voir {@link NpcIdentityService#citizensAvailable()}), auquel cas seul
     * {@link #npcInteractListener()} doit être enregistré.
     */
    public @Nullable Listener citizensNpcInteractListener() {
        return npcIdentityService.citizensAvailable()
                ? new DialogueCitizensNpcInteractListener(this, dialogueEngine, npcIdentityService)
                : null;
    }

    public void open(Player player, NamespacedKey dialogueId) {
        Optional<DialogueDefinition> dialogueOpt = dialogueEngine.find(dialogueId);
        if (dialogueOpt.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Dialogue introuvable :</red> <white><id></white>",
                    Placeholder.unparsed("id", dialogueId.toString())));
            return;
        }
        DialogueDefinition dialogue = dialogueOpt.get();
        openNode(player, dialogue, dialogue.startNodeId());
    }

    private void openNode(Player player, DialogueDefinition dialogue, String nodeId) {
        DialogueNode node = dialogue.nodes().get(nodeId);
        visibleChoices(player, dialogue, node).thenAccept(visible -> runOnMainThread(() -> {
            sessions.put(player.getUniqueId(), new DialogueSession(dialogue.id(), node.id()));
            // Substitution juste avant le rendu, sur le thread principal : la valeur affichée est
            // donc lue au plus tard possible (état réel au moment où le joueur voit le texte).
            renderer.render(player, dialogue, placeholders.apply(player, dialogue.id(), node), visible);
            // Issue #12 : c'est le SEUL endroit où un nœud est réellement affiché au joueur, donc
            // le seul endroit où « lu » a un sens. Ouvrir un PNJ ne présente que le nœud de
            // départ : les branches non parcourues restent non lues, et donc toujours signalées.
            if (nodePresented != null) {
                nodePresented.accept(player, dialogue.id(), node.id());
            }
        }));
    }

    /**
     * Issue #12 — observateur appelé quand un nœud est <strong>réellement affiché</strong> à un
     * joueur. Branché par le service de signal visuel ; {@code null} si la fonctionnalité est
     * désactivée, et dans ce cas rien n'est enregistré.
     */
    public interface NodePresentedListener {
        void accept(Player player, NamespacedKey dialogueId, String nodeId);
    }

    public void setNodePresentedListener(NodePresentedListener listener) {
        this.nodePresented = listener;
    }

    /**
     * Issue #12 — nœuds <strong>réellement atteignables</strong> par ce joueur depuis le nœud de
     * départ, en suivant uniquement les choix dont les conditions passent au moment du calcul.
     *
     * <p>Réutilise {@link #visibleChoices} : les conditions ne sont donc évaluées qu'à un seul
     * endroit du code, et un dialogue dont les conditions changent (prérequis acquis, objet
     * obtenu, variable posée) voit son ensemble atteignable évoluer sans règle dupliquée.</p>
     *
     * <p>Parcours en largeur borné par le nombre de nœuds du dialogue — un dialogue n'en compte
     * qu'une poignée, et chaque nœud n'est visité qu'une fois.</p>
     */
    public CompletableFuture<java.util.Set<String>> reachableNodes(Player player, DialogueDefinition dialogue) {
        java.util.Set<String> reachable = java.util.concurrent.ConcurrentHashMap.newKeySet();
        reachable.add(dialogue.startNodeId());
        return expand(player, dialogue, java.util.List.of(dialogue.startNodeId()), reachable)
                .thenApply(ignored -> java.util.Set.copyOf(reachable));
    }

    /** Une vague du parcours en largeur : évalue les choix visibles des nœuds de la vague. */
    private CompletableFuture<Void> expand(Player player, DialogueDefinition dialogue,
                                           java.util.List<String> frontier,
                                           java.util.Set<String> reachable) {
        if (frontier.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        java.util.List<CompletableFuture<java.util.List<String>>> perNode = new ArrayList<>();
        for (String nodeId : frontier) {
            DialogueNode node = dialogue.nodes().get(nodeId);
            if (node == null) {
                continue;
            }
            perNode.add(visibleChoices(player, dialogue, node).thenApply(visible -> {
                java.util.List<String> next = new ArrayList<>();
                for (VisibleChoice choice : visible) {
                    DialogueChoice original = node.choices().get(choice.index());
                    String target = original.next();
                    if (target == null || target.isBlank() || !dialogue.nodes().containsKey(target)) {
                        continue;
                    }
                    // Un choix qui ferme le dialogue ne mène nulle part : ne pas le suivre.
                    if (original.actions().stream().anyMatch(a -> a instanceof CloseAction)) {
                        continue;
                    }
                    if (reachable.add(target)) {
                        next.add(target);
                    }
                }
                return next;
            }));
        }
        if (perNode.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(perNode.toArray(CompletableFuture[]::new)).thenCompose(v -> {
            java.util.List<String> nextFrontier = new ArrayList<>();
            perNode.forEach(f -> nextFrontier.addAll(f.join()));
            return expand(player, dialogue, nextFrontier, reachable);
        });
    }

    @Override
    public void onChoiceSelected(Player player, NamespacedKey dialogueId, String nodeId, int choiceIndex) {
        DialogueSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.dialogueId().equals(dialogueId) || !session.nodeId().equals(nodeId)) {
            // Session périmée/incohérente (ex. clic différé après fermeture, ou après un /dialogue open externe) : ignoré.
            return;
        }
        Optional<DialogueDefinition> dialogueOpt = dialogueEngine.find(dialogueId);
        if (dialogueOpt.isEmpty()) {
            sessions.remove(player.getUniqueId());
            return;
        }
        DialogueDefinition dialogue = dialogueOpt.get();
        DialogueNode node = dialogue.nodes().get(nodeId);
        if (node == null || choiceIndex < 0 || choiceIndex >= node.choices().size()) {
            return;
        }
        DialogueChoice choice = node.choices().get(choiceIndex);

        // Re-vérification défensive : la visibilité initiale garantit l'affichage, pas la validité au moment du clic.
        evaluateAll(player, dialogueId, choice.conditions()).thenAccept(stillVisible -> runOnMainThread(() -> {
            if (!stillVisible) {
                return;
            }
            executeActionsAndTransition(player, dialogue, choice);
        }));
    }

    private void executeActionsAndTransition(Player player, DialogueDefinition dialogue, DialogueChoice choice) {
        for (DialogueAction action : choice.actions()) {
            if (action instanceof CloseAction) {
                closeSession(player);
                return;
            }
            if (action instanceof OpenDialogueAction open) {
                open(player, open.dialogueId());
                return;
            }
            if (action instanceof OpenMerchantAction openMerchant) {
                closeSession(player);
                merchantTradeService.openShop(player, openMerchant.merchantId());
                return;
            }
            executeAction(player, dialogue, action);
        }

        if (choice.next() != null) {
            openNode(player, dialogue, choice.next());
        } else {
            closeSession(player);
        }
    }

    private void closeSession(Player player) {
        sessions.remove(player.getUniqueId());
        player.closeDialog();
    }

    private void executeAction(Player player, DialogueDefinition dialogue, DialogueAction action) {
        switch (action) {
            case StartQuestAction a -> questProgressEngine.accept(player, a.questId()).exceptionally(error -> {
                logger.error("Échec de START_QUEST {} pour {}", a.questId(), player.getUniqueId(), error);
                return null;
            });
            case AdvanceQuestAction a -> questProgressEngine.advanceStep(player, a.questId());
            case TurnInQuestAction a -> questProgressEngine.forceComplete(player, a.questId()).exceptionally(error -> {
                logger.error("Échec de TURN_IN_QUEST {} pour {}", a.questId(), player.getUniqueId(), error);
                return null;
            });
            case GiveItemAction a -> giveItem(player, a.material(), a.amount());
            case TakeItemAction a -> player.getInventory().removeItem(new ItemStack(a.material(), a.amount()));
            case SetVariableAction a -> variableRepository.set(player.getUniqueId(), a.key(), a.value())
                    .exceptionally(error -> {
                        logger.error("Échec de SET_VARIABLE {} pour {}", a.key(), player.getUniqueId(), error);
                        return null;
                    });
            case RunSafeCommandAction a -> runSafeCommand(player, a.command());
            case GiveStarterKitAction ignored -> starterToolKitService.requestKit(player);
            // Issue #123 : la remise elle-même vit dans le moteur de quêtes (retrait exact,
            // anti double-clic, persistance) ; le dialogue ne fait que la déclencher pour le PNJ
            // porteur de ce dialogue, ou celui explicitement nommé.
            case DeliverQuestItemsAction a -> questProgressEngine.deliverTo(
                    player, DialogueNpcResolution.resolve(a.npcId(), dialogue.id()));
            case OpenDialogueAction ignored -> {
                // Géré par l'appelant (transition, arrêt anticipé) : jamais atteint ici.
            }
            case OpenMerchantAction ignored -> {
                // Géré par l'appelant (fermeture de la session, ouverture de la vitrine) : jamais atteint ici.
            }
            case CloseAction ignored -> {
                // Géré par l'appelant (transition, arrêt anticipé) : jamais atteint ici.
            }
        }
    }

    private void giveItem(Player player, Material material, int amount) {
        ItemStack stack = new ItemStack(material, amount);
        player.getInventory().addItem(stack).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void runSafeCommand(Player player, String commandTemplate) {
        // Le nom de la commande a déjà été validé contre la liste blanche au chargement.
        // Seule substitution : %player% (nom du joueur, contraint par Mojang), jamais de texte libre concaténé.
        String command = commandTemplate.replace("%player%", player.getName());
        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
    }

    // ---- Conditions ----------------------------------------------------

    private CompletableFuture<Boolean> evaluateCondition(Player player, NamespacedKey dialogueId,
                                                         DialogueCondition condition) {
        return switch (condition) {
            case QuestStateCondition c -> questProgressEngine.stateOf(player.getUniqueId(), c.questId())
                    .thenApply(state -> state == c.state());
            case HasItemCondition c -> CompletableFuture.completedFuture(
                    player.getInventory().containsAtLeast(new ItemStack(c.material()), c.amount()));
            case HasPermissionCondition c -> CompletableFuture.completedFuture(player.hasPermission(c.permission()));
            case VariableEqualsCondition c -> variableRepository.get(player.getUniqueId(), c.key())
                    .thenApply(opt -> opt.map(v -> v.equals(c.value())).orElse(false));
            case NoMainClaimCondition ignored -> CompletableFuture.completedFuture(
                    claimService.claimsOwnedBy(player.getUniqueId()).isEmpty());
            case HasMainClaimCondition ignored -> CompletableFuture.completedFuture(
                    claimService.mainClaimOf(player.getUniqueId()).isPresent());
            case LacksCustomItemCondition c -> CompletableFuture.completedFuture(!hasCustomItem(player, c.itemId()));
            // Issue #123 : lecture pure de l'état de remise, déjà en mémoire — aucune requête, donc
            // un future déjà complété, évalué à chaque ouverture de nœud sans coût.
            case PendingDeliveryCondition c -> CompletableFuture.completedFuture(
                    questProgressEngine.hasPendingDelivery(
                            player.getUniqueId(), DialogueNpcResolution.resolve(c.npcId(), dialogueId)));
            case NegatedCondition c -> evaluateCondition(player, dialogueId, c.inner()).thenApply(result -> !result);
        };
    }

    private boolean hasCustomItem(Player player, NamespacedKey itemId) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && customItemRegistry.identify(stack).map(itemId::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private CompletableFuture<Boolean> evaluateAll(Player player, NamespacedKey dialogueId,
                                                   List<DialogueCondition> conditions) {
        if (conditions.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        List<CompletableFuture<Boolean>> checks = conditions.stream()
                .map(condition -> evaluateCondition(player, dialogueId, condition))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(v -> checks.stream().allMatch(CompletableFuture::join));
    }

    /** Choix réellement visibles pour ce joueur : source unique d'évaluation des conditions. */
    public CompletableFuture<List<VisibleChoice>> visibleChoices(Player player, DialogueDefinition dialogue,
                                                                 DialogueNode node) {
        List<DialogueChoice> choices = node.choices();
        List<CompletableFuture<Boolean>> checks = choices.stream()
                .map(choice -> evaluateAll(player, dialogue.id(), choice.conditions()))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).thenApply(v -> {
            List<VisibleChoice> visible = new ArrayList<>();
            for (int i = 0; i < choices.size(); i++) {
                if (checks.get(i).join()) {
                    visible.add(new VisibleChoice(i, choices.get(i).text().base()));
                }
            }
            return visible;
        });
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
