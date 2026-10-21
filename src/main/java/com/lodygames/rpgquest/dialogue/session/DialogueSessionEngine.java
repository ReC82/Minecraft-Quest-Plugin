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
import com.lodygames.rpgquest.dialogue.model.DialogueEntry;
import com.lodygames.rpgquest.dialogue.model.DialogueNode;
import com.lodygames.rpgquest.dialogue.model.GiveItemAction;
import com.lodygames.rpgquest.dialogue.model.GiveStarterKitAction;
import com.lodygames.rpgquest.dialogue.model.HasItemCondition;
import com.lodygames.rpgquest.dialogue.model.HasMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.HasPermissionCondition;
import com.lodygames.rpgquest.dialogue.model.HasInventorySpaceCondition;
import com.lodygames.rpgquest.dialogue.model.LacksCustomItemCondition;
import com.lodygames.rpgquest.dialogue.model.NegatedCondition;
import com.lodygames.rpgquest.dialogue.model.NoMainClaimCondition;
import com.lodygames.rpgquest.dialogue.model.OpenDialogueAction;
import com.lodygames.rpgquest.dialogue.model.OfferCatalogQuestAction;
import com.lodygames.rpgquest.dialogue.model.OpenMerchantAction;
import com.lodygames.rpgquest.dialogue.model.PendingDeliveryCondition;
import com.lodygames.rpgquest.dialogue.model.QuestAvailableCondition;
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
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferSelector;
import com.lodygames.rpgquest.encounter.catalog.CatalogOfferService;
import com.lodygames.rpgquest.item.YamlCustomItemRegistry;
import com.lodygames.rpgquest.item.delivery.CustomItemGiveCommands;
import com.lodygames.rpgquest.item.delivery.ItemDelivery;
import com.lodygames.rpgquest.npc.NpcIdentityService;
import com.lodygames.rpgquest.player.StarterToolKitService;
import com.lodygames.rpgquest.quest.model.LocalizedText;
import com.lodygames.rpgquest.quest.progress.AcceptOutcome;
import com.lodygames.rpgquest.quest.progress.DeliveryOutcome;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /** Joueurs dont un choix est en cours d'évaluation (issue #72 : anti-double-clic). */
    private final Set<UUID> choosing = ConcurrentHashMap.newKeySet();
    /** Issue #158 : dernière réplique montrée par joueur et par nœud (pas de répétition immédiate). */
    private final DialogueLineRotation lineRotation =
            new DialogueLineRotation(bound -> java.util.concurrent.ThreadLocalRandom.current().nextInt(bound));
    private volatile DialogueRenderer renderer;
    /** Valeurs dynamiques substituées dans le texte d'un nœud juste avant son rendu (issue #24). */
    private volatile DialogueTextPlaceholders placeholders = DialogueTextPlaceholders.none();
    /** Issue #12 — notifie le service de signal visuel qu'un nœud vient d'être affiché. */
    private volatile NodePresentedListener nodePresented;
    /** Issue #369 — offres de quêtes de catalogue ; {@code null} tant que rien n'est câblé. */
    private volatile CatalogOfferService catalogOffers;
    /** Issue #369 — quête réellement montrée dans l'offre en cours, par joueur : c'est elle, et elle seule, que « Commencer » accepte. */
    private final Map<UUID, NamespacedKey> shownOffers = new ConcurrentHashMap<>();
    /**
     * Issue #467 : résultat de la remise {@code DELIVER_QUEST_ITEMS} que le choix en cours vient
     * d'effectuer, consommé par le nœud affiché juste après. Une remise qui termine la quête la
     * retire des quêtes actives : relu en direct, {@code %delivery_status%} répondait « Je n'attends
     * aucun matériau de ta part » à la place du bilan de la remise réussie.
     */
    private final Map<UUID, DeliveryOutcome> justDelivered = new ConcurrentHashMap<>();

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

    /** Issue #369 — câblé par le bootstrap quand les PNJ récurrents et leurs catalogues existent. */
    public void setCatalogOffers(CatalogOfferService catalogOffers) {
        this.catalogOffers = catalogOffers;
    }

    @Override
    public void start() {
        // Rien à démarrer : le chargement des définitions est un service séparé (YamlDialogueEngine), déjà démarré avant celui-ci.
    }

    @Override
    public void stop() {
        sessions.clear();
        shownOffers.clear();
        lineRotation.clear();
    }

    public void unloadForPlayer(UUID playerId) {
        sessions.remove(playerId);
        shownOffers.remove(playerId);
        lineRotation.forget(playerId);
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
            questProgressEngine.dialogueEnded(player);
            player.sendMessage(MM.deserialize(
                    "<red>Dialogue introuvable :</red> <white><id></white>",
                    Placeholder.unparsed("id", dialogueId.toString())));
            return;
        }
        DialogueDefinition dialogue = dialogueOpt.get();
        entryNode(player, dialogue).thenAccept(nodeId -> openNode(player, dialogue, nodeId))
                .exceptionally(error -> {
                    logger.error("Échec de l'ouverture du dialogue {} pour {}", dialogueId, player.getUniqueId(), error);
                    runOnMainThread(() -> questProgressEngine.dialogueEnded(player));
                    return null;
                });
    }

    /**
     * Issue #158 — nœud d'accueil réel de ce joueur : la première entrée ({@code entries:}) dont
     * toutes les conditions passent, sinon {@code start}. Les entrées sont évaluées une à une, dans
     * l'ordre du fichier, avec le même {@link #evaluateAll} que les choix — un PNJ dont la quête
     * est terminée accueille donc le joueur selon l'état persisté de sa progression.
     */
    CompletableFuture<String> entryNode(Player player, DialogueDefinition dialogue) {
        CompletableFuture<String> resolved = CompletableFuture.completedFuture(null);
        for (DialogueEntry entry : dialogue.entries()) {
            resolved = resolved.thenCompose(found -> found != null
                    ? CompletableFuture.completedFuture(found)
                    : evaluateAll(player, dialogue.id(), entry.conditions())
                            .thenApply(passes -> passes ? entry.nodeId() : null));
        }
        return resolved.thenApply(found -> found != null ? found : dialogue.startNodeId());
    }

    private void openNode(Player player, DialogueDefinition dialogue, String nodeId) {
        DialogueNode node = dialogue.nodes().get(nodeId);
        DeliveryOutcome delivered = justDelivered.remove(player.getUniqueId());
        visibleChoices(player, dialogue, node).thenAccept(visible -> runOnMainThread(() -> {
            sessions.put(player.getUniqueId(), new DialogueSession(dialogue.id(), node.id()));
            // Substitution juste avant le rendu, sur le thread principal : la valeur affichée est
            // donc lue au plus tard possible (état réel au moment où le joueur voit le texte).
            renderer.render(player, dialogue,
                    withQuestNotices(player,
                            placeholders.apply(player, dialogue.id(), withChosenLine(player, dialogue, node), delivered)),
                    visible);
            // Issue #12 : c'est le SEUL endroit où un nœud est réellement affiché au joueur, donc
            // le seul endroit où « lu » a un sens. Ouvrir un PNJ ne présente que le nœud de
            // départ : les branches non parcourues restent non lues, et donc toujours signalées.
            if (nodePresented != null) {
                nodePresented.accept(player, dialogue.id(), node.id());
            }
        }));
    }

    /** Issue #158 : le nœud tel qu'affiché, avec une réplique tirée parmi {@code text} + {@code variants}. */
    private DialogueNode withChosenLine(Player player, DialogueDefinition dialogue, DialogueNode node) {
        List<LocalizedText> lines = node.lines();
        int index = lineRotation.pick(player.getUniqueId(), dialogue.id() + "#" + node.id(), lines.size());
        return index == 0 ? node : node.withSpeakerAndText(node.speaker(), lines.get(index));
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
        // Issue #158 : on part du nœud d'accueil réel du joueur (entrée conditionnelle ou start).
        return entryNode(player, dialogue).thenCompose(first -> {
            reachable.add(first);
            return expand(player, dialogue, java.util.List.of(first), reachable);
        }).thenApply(ignored -> java.util.Set.copyOf(reachable));
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
        if (CatalogOfferNodes.isOfferNode(nodeId) && !dialogue.nodes().containsKey(nodeId)) {
            onCatalogOfferChoice(player, dialogue, nodeId, choiceIndex);
            return;
        }
        DialogueNode node = dialogue.nodes().get(nodeId);
        if (node == null || choiceIndex < 0 || choiceIndex >= node.choices().size()) {
            return;
        }
        DialogueChoice choice = node.choices().get(choiceIndex);

        // Issue #72 : un seul choix à la fois par joueur. La réévaluation est asynchrone : sans cette
        // garde, deux clics rapides sur « Réclamer mon acte » passaient tous deux la condition
        // « n'a pas l'acte » avant que le premier ne l'ait remis — deux actes.
        UUID playerId = player.getUniqueId();
        if (!choosing.add(playerId)) {
            return;
        }
        // Re-vérification défensive : la visibilité initiale garantit l'affichage, pas la validité au moment du clic.
        choiceAvailable(player, dialogue, choice).whenComplete((stillVisible, error) -> runOnMainThread(() -> {
            try {
                DialogueSession current = sessions.get(playerId);
                // Le nœud a pu changer entre le clic et la réponse (fermeture, autre choix) : la
                // décision prise sur l'ancien nœud n'a plus cours.
                if (error != null || current == null
                        || !current.dialogueId().equals(dialogueId) || !current.nodeId().equals(nodeId)) {
                    return;
                }
                if (!Boolean.TRUE.equals(stillVisible)) {
                    // Issue #475 : le choix n'a plus cours (inventaire vidé entre l'affichage et le
                    // clic…). La fenêtre se ferme au clic : le même nœud est réaffiché, recalculé,
                    // plutôt que de laisser le joueur devant rien.
                    openNode(player, dialogue, nodeId);
                    return;
                }
                executeActionsAndTransition(player, dialogue, choice);
            } finally {
                choosing.remove(playerId);
            }
        }));
    }

    private void executeActionsAndTransition(Player player, DialogueDefinition dialogue, DialogueChoice choice) {
        // Issue #318 : tout ce que le choix remet doit tenir AVANT la première action. Sinon une
        // variable « déjà donné », une quête ou le nœud « voici ton objet » avançaient alors que
        // l'objet finissait au sol. Refus = aucune action du choix, le joueur peut réessayer.
        int missingSlots = ItemDelivery.missingSlots(player.getInventory(), itemsDeliveredBy(choice));
        if (missingSlots > 0) {
            closeSession(player);
            player.sendMessage(ItemDelivery.inventoryFullMessage(missingSlots));
            return;
        }
        for (DialogueAction action : choice.actions()) {
            if (action instanceof CloseAction) {
                closeSession(player);
                return;
            }
            if (action instanceof OpenDialogueAction open) {
                justDelivered.remove(player.getUniqueId()); // bilan propre au PNJ quitté
                open(player, open.dialogueId());
                return;
            }
            if (action instanceof OpenMerchantAction openMerchant) {
                closeSession(player);
                merchantTradeService.openShop(player, openMerchant.merchantId());
                return;
            }
            if (action instanceof OfferCatalogQuestAction offer) {
                presentCatalogOffer(player, dialogue, offer);
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

    private void rememberDelivery(Player player, DeliveryOutcome outcome) {
        if (outcome != null) {
            justDelivered.put(player.getUniqueId(), outcome);
        }
    }

    private void closeSession(Player player) {
        sessions.remove(player.getUniqueId());
        justDelivered.remove(player.getUniqueId());
        shownOffers.remove(player.getUniqueId());
        player.closeDialog();
        // Issue #636 : fenêtre fermée — le bilan des quêtes terminées pendant le dialogue part maintenant.
        questProgressEngine.dialogueEnded(player);
    }

    /**
     * Issue #636 — clic sur un PNJ dont le dialogue va s'ouvrir, appelé AVANT la progression
     * {@code TALK_TO_NPC} du même clic : une quête rendue par ce clic n'affiche aucun titre
     * par-dessus la fenêtre qui s'ouvre.
     */
    void expectDialogue(Player player, NamespacedKey dialogueId) {
        if (dialogueEngine.find(dialogueId).isPresent()) {
            questProgressEngine.expectDialogue(player);
        }
    }

    // ---- Offres de catalogue (issue #369) ------------------------------------------------------

    /**
     * Calcule l'offre du catalogue (lecture seule) puis affiche un nœud synthétique : offre, tâche
     * en cours ou « rien pour le moment ». Le locuteur est celui du nœud d'accueil du dialogue.
     */
    private void presentCatalogOffer(Player player, DialogueDefinition dialogue, OfferCatalogQuestAction action) {
        CatalogOfferService service = catalogOffers;
        String speaker = dialogue.nodes().get(dialogue.startNodeId()).speaker();
        if (service == null) {
            showOfferNode(player, dialogue, CatalogOfferNodes.unavailable(speaker), null);
            return;
        }
        String npcId = DialogueNpcResolution.resolve(null, dialogue.id());
        service.offerFor(player.getUniqueId(), npcId, action.catalogId()).whenComplete((offer, error) -> {
            if (error != null) {
                logger.error("Échec du calcul de l'offre de catalogue pour {} ({})", player.getUniqueId(),
                        dialogue.id(), error);
                runOnMainThread(() -> showOfferNode(player, dialogue, CatalogOfferNodes.unavailable(speaker), null));
                return;
            }
            NamespacedKey offered = offer.decision().kind() == CatalogOfferSelector.Kind.OFFER && offer.quest() != null
                    ? offer.quest().id() : null;
            runOnMainThread(() -> showOfferNode(player, dialogue, CatalogOfferNodes.forOffer(speaker, offer), offered));
        });
    }

    private void showOfferNode(Player player, DialogueDefinition dialogue, DialogueNode node, NamespacedKey offered) {
        if (!player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        sessions.put(playerId, new DialogueSession(dialogue.id(), node.id()));
        if (offered != null) {
            shownOffers.put(playerId, offered);
        } else {
            shownOffers.remove(playerId);
        }
        List<VisibleChoice> visible = new ArrayList<>();
        for (int i = 0; i < node.choices().size(); i++) {
            visible.add(new VisibleChoice(i, node.choices().get(i).text().base()));
        }
        // Issue #475 : le locuteur (« %npc_name% ») et la description de quête de l'offre passent
        // par les mêmes valeurs dynamiques qu'un nœud écrit dans le fichier.
        renderer.render(player, dialogue, withQuestNotices(player, placeholders.apply(player, dialogue.id(), node)),
                visible);
    }

    /**
     * Issue #636 — le nœud va être affiché : le focus du dialogue est renouvelé (aucun grand titre
     * de quête par-dessus la fenêtre) et chaque quête terminée depuis le nœud précédent est
     * signalée une fois, sous la réplique. Le fichier du dialogue n'est jamais modifié : seule la
     * copie rendue reçoit la ligne, après les valeurs dynamiques (un titre de quête n'est jamais
     * interprété comme un {@code %placeholder%}).
     */
    private DialogueNode withQuestNotices(Player player, DialogueNode node) {
        List<net.kyori.adventure.text.Component> notices = questProgressEngine.dialogueShown(player);
        if (notices.isEmpty()) {
            return node;
        }
        StringBuilder suffix = new StringBuilder();
        notices.forEach(notice -> suffix.append('\n').append(MM.serialize(notice)));
        Map<String, String> withNotices = new java.util.LinkedHashMap<>();
        node.text().byLocale().forEach((locale, text) -> withNotices.put(locale, text + "\n" + suffix));
        return node.withSpeakerAndText(node.speaker(), new LocalizedText(withNotices));
    }

    private void onCatalogOfferChoice(Player player, DialogueDefinition dialogue, String nodeId, int choiceIndex) {
        UUID playerId = player.getUniqueId();
        switch (CatalogOfferNodes.choiceOf(nodeId, choiceIndex)) {
            case ACCEPT -> {
                NamespacedKey questId = shownOffers.remove(playerId);
                if (questId == null || !choosing.add(playerId)) {
                    return;
                }
                closeSession(player);
                // Le moteur revérifie tout (état, prérequis, délai) : une offre périmée est refusée
                // proprement, jamais attribuée deux fois (putIfAbsent + anti-double-clic).
                questProgressEngine.accept(player, questId).whenComplete((outcome, error) -> runOnMainThread(() -> {
                    choosing.remove(playerId);
                    if (error != null) {
                        logger.error("Échec de l'acceptation de {} (catalogue) pour {}", questId, playerId, error);
                        return;
                    }
                    switch (outcome.result()) {
                        case ACCEPTED -> { }
                        case ON_COOLDOWN -> questProgressEngine.showCooldown(player, questId, outcome.retryAfter());
                        default -> player.sendMessage(MM.deserialize(CatalogOfferNodes.STALE_OFFER));
                    }
                }));
            }
            case LATER -> open(player, dialogue.id());
            case REFUSE -> {
                closeSession(player);
                player.sendMessage(MM.deserialize(CatalogOfferNodes.REFUSED));
            }
            case BACK -> open(player, dialogue.id());
            case CLOSE -> closeSession(player);
            case NONE -> {
                // Clic hors des choix connus : ignoré.
            }
        }
    }

    private void executeAction(Player player, DialogueDefinition dialogue, DialogueAction action) {
        switch (action) {
            case StartQuestAction a -> questProgressEngine.accept(player, a.questId()).thenAccept(outcome -> {
                // Issue #13 : une quête renouvelable encore en recharge ne doit pas échouer en silence.
                if (outcome.result() == AcceptOutcome.Result.ON_COOLDOWN) {
                    questProgressEngine.showCooldown(player, a.questId(), outcome.retryAfter());
                }
            }).exceptionally(error -> {
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
            case DeliverQuestItemsAction a -> rememberDelivery(player, questProgressEngine.deliverTo(
                    player, DialogueNpcResolution.resolve(a.npcId(), dialogue.id())));
            case OpenDialogueAction ignored -> {
                // Géré par l'appelant (transition, arrêt anticipé) : jamais atteint ici.
            }
            case OpenMerchantAction ignored -> {
                // Géré par l'appelant (fermeture de la session, ouverture de la vitrine) : jamais atteint ici.
            }
            case OfferCatalogQuestAction ignored -> {
                // Géré par l'appelant (nœud d'offre synthétique) : jamais atteint ici.
            }
            case CloseAction ignored -> {
                // Géré par l'appelant (transition, arrêt anticipé) : jamais atteint ici.
            }
        }
    }

    private void giveItem(Player player, Material material, int amount) {
        ItemDelivery.Result delivery = ItemDelivery.deliver(player, new ItemStack(material, amount));
        if (!delivery.delivered()) {
            player.sendMessage(ItemDelivery.inventoryFullMessage(delivery.missingSlots()));
        }
    }

    /**
     * Objets que remettraient les actions de {@code choice} : {@code GIVE_ITEM}, les
     * {@code RUN_SAFE_COMMAND} de la forme {@code customitem give %player% <id> [n]}, et les
     * récompenses d'objets d'un {@code TURN_IN_QUEST}.
     */
    List<ItemStack> itemsDeliveredBy(DialogueChoice choice) {
        List<ItemStack> items = new ArrayList<>();
        for (DialogueAction action : choice.actions()) {
            if (action instanceof GiveItemAction give) {
                items.add(new ItemStack(give.material(), give.amount()));
            } else if (action instanceof RunSafeCommandAction command) {
                CustomItemGiveCommands.itemFor(command.command(), customItemRegistry).ifPresent(items::add);
            } else if (action instanceof TurnInQuestAction turnIn) {
                items.addAll(questProgressEngine.rewardItems(turnIn.questId()));
            }
        }
        return items;
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
            // Issue #13 : la règle d'acceptation elle-même (QuestProgressEngine.availability), jamais
            // une copie — une offre affichée est donc une offre réellement acceptable.
            case QuestAvailableCondition c -> questProgressEngine.availability(player.getUniqueId(), c.questId(), false)
                    .thenApply(QuestProgressEngine.Availability::available);
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
            // Issue #85 : lecture de l'inventaire principal, sur le thread du clic — aucune requête.
            case HasInventorySpaceCondition c -> CompletableFuture.completedFuture(
                    freeStorageSlots(player) >= c.slots());
            // Issue #123 : lecture pure de l'état de remise, déjà en mémoire — aucune requête, donc
            // un future déjà complété, évalué à chaque ouverture de nœud sans coût.
            case PendingDeliveryCondition c -> CompletableFuture.completedFuture(
                    questProgressEngine.hasPendingDelivery(
                            player.getUniqueId(), DialogueNpcResolution.resolve(c.npcId(), dialogueId)));
            case NegatedCondition c -> evaluateCondition(player, dialogueId, c.inner()).thenApply(result -> !result);
        };
    }

    /** Emplacements vides de l'inventaire principal (ni armure, ni seconde main). */
    static int freeStorageSlots(Player player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                free++;
            }
        }
        return free;
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

    /**
     * Un choix est proposé si ses conditions passent et, issue #475, si sa remise
     * {@code DELIVER_QUEST_ITEMS} retirerait réellement quelque chose : un bouton de remise qui ne
     * peut que répondre « rien d'utile » n'est jamais cliquable, quel que soit le contenu du
     * dialogue. Le texte du nœud ({@code %delivery_status%}, {@code %delivery_hint%}) dit pourquoi.
     */
    private CompletableFuture<Boolean> choiceAvailable(Player player, DialogueDefinition dialogue, DialogueChoice choice) {
        return evaluateAll(player, dialogue.id(), choice.conditions())
                .thenApply(passes -> passes && deliveryWouldTakeSomething(player, dialogue, choice));
    }

    private boolean deliveryWouldTakeSomething(Player player, DialogueDefinition dialogue, DialogueChoice choice) {
        for (DialogueAction action : choice.actions()) {
            if (action instanceof DeliverQuestItemsAction deliver && !questProgressEngine.hasDeliverableItems(
                    player, DialogueNpcResolution.resolve(deliver.npcId(), dialogue.id()))) {
                return false;
            }
        }
        return true;
    }

    /** Choix réellement visibles pour ce joueur : source unique d'évaluation des conditions. */
    public CompletableFuture<List<VisibleChoice>> visibleChoices(Player player, DialogueDefinition dialogue,
                                                                 DialogueNode node) {
        List<DialogueChoice> choices = node.choices();
        List<CompletableFuture<Boolean>> checks = choices.stream()
                .map(choice -> choiceAvailable(player, dialogue, choice))
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
