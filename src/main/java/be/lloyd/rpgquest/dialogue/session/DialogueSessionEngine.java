package be.lloyd.rpgquest.dialogue.session;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.bootstrap.PluginService;
import be.lloyd.rpgquest.database.PlayerVariableRepository;
import be.lloyd.rpgquest.dialogue.YamlDialogueEngine;
import be.lloyd.rpgquest.dialogue.model.AdvanceQuestAction;
import be.lloyd.rpgquest.dialogue.model.CloseAction;
import be.lloyd.rpgquest.dialogue.model.DialogueAction;
import be.lloyd.rpgquest.dialogue.model.DialogueChoice;
import be.lloyd.rpgquest.dialogue.model.DialogueCondition;
import be.lloyd.rpgquest.dialogue.model.DialogueDefinition;
import be.lloyd.rpgquest.dialogue.model.DialogueNode;
import be.lloyd.rpgquest.dialogue.model.GiveItemAction;
import be.lloyd.rpgquest.dialogue.model.HasItemCondition;
import be.lloyd.rpgquest.dialogue.model.HasPermissionCondition;
import be.lloyd.rpgquest.dialogue.model.OpenDialogueAction;
import be.lloyd.rpgquest.dialogue.model.QuestStateCondition;
import be.lloyd.rpgquest.dialogue.model.RunSafeCommandAction;
import be.lloyd.rpgquest.dialogue.model.SetVariableAction;
import be.lloyd.rpgquest.dialogue.model.StartQuestAction;
import be.lloyd.rpgquest.dialogue.model.TakeItemAction;
import be.lloyd.rpgquest.dialogue.model.TurnInQuestAction;
import be.lloyd.rpgquest.dialogue.model.VariableEqualsCondition;
import be.lloyd.rpgquest.dialogue.render.DialogueChoiceHandler;
import be.lloyd.rpgquest.dialogue.render.DialogueRenderer;
import be.lloyd.rpgquest.dialogue.render.VisibleChoice;
import be.lloyd.rpgquest.quest.progress.QuestProgressEngine;
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
    private final Logger logger;

    private final Map<UUID, DialogueSession> sessions = new ConcurrentHashMap<>();
    private volatile DialogueRenderer renderer;

    public DialogueSessionEngine(RPGQuestPlugin plugin, YamlDialogueEngine dialogueEngine,
                                  QuestProgressEngine questProgressEngine, PlayerVariableRepository variableRepository) {
        this.plugin = plugin;
        this.dialogueEngine = dialogueEngine;
        this.questProgressEngine = questProgressEngine;
        this.variableRepository = variableRepository;
        this.logger = plugin.getSLF4JLogger();
    }

    /** Câblé après construction par le bootstrap (dépendance circulaire évitée : voir dialogue.render.DialogueChoiceHandler). */
    public void setRenderer(DialogueRenderer renderer) {
        this.renderer = renderer;
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
        return new DialogueNpcInteractListener(this, dialogueEngine);
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
        visibleChoices(player, node).thenAccept(visible -> runOnMainThread(() -> {
            sessions.put(player.getUniqueId(), new DialogueSession(dialogue.id(), node.id()));
            renderer.render(player, dialogue, node, visible);
        }));
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
        evaluateAll(player, choice.conditions()).thenAccept(stillVisible -> runOnMainThread(() -> {
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
            executeAction(player, action);
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

    private void executeAction(Player player, DialogueAction action) {
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
            case OpenDialogueAction ignored -> {
                // Géré par l'appelant (transition, arrêt anticipé) : jamais atteint ici.
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

    private CompletableFuture<Boolean> evaluateCondition(Player player, DialogueCondition condition) {
        return switch (condition) {
            case QuestStateCondition c -> questProgressEngine.stateOf(player.getUniqueId(), c.questId())
                    .thenApply(state -> state == c.state());
            case HasItemCondition c -> CompletableFuture.completedFuture(
                    player.getInventory().containsAtLeast(new ItemStack(c.material()), c.amount()));
            case HasPermissionCondition c -> CompletableFuture.completedFuture(player.hasPermission(c.permission()));
            case VariableEqualsCondition c -> variableRepository.get(player.getUniqueId(), c.key())
                    .thenApply(opt -> opt.map(v -> v.equals(c.value())).orElse(false));
        };
    }

    private CompletableFuture<Boolean> evaluateAll(Player player, List<DialogueCondition> conditions) {
        if (conditions.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        List<CompletableFuture<Boolean>> checks = conditions.stream()
                .map(condition -> evaluateCondition(player, condition))
                .toList();
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(v -> checks.stream().allMatch(CompletableFuture::join));
    }

    private CompletableFuture<List<VisibleChoice>> visibleChoices(Player player, DialogueNode node) {
        List<DialogueChoice> choices = node.choices();
        List<CompletableFuture<Boolean>> checks = choices.stream()
                .map(choice -> evaluateAll(player, choice.conditions()))
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
