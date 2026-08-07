package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.quest.QuestLoadIssue;
import com.lodygames.rpgquest.quest.QuestLoadReport;
import com.lodygames.rpgquest.quest.QuestMessages;
import com.lodygames.rpgquest.quest.QuestMessagesService;
import com.lodygames.rpgquest.quest.YamlQuestEngine;
import com.lodygames.rpgquest.quest.model.QuestDefinition;
import com.lodygames.rpgquest.quest.model.QuestState;
import com.lodygames.rpgquest.quest.progress.AbandonOutcome;
import com.lodygames.rpgquest.quest.progress.AcceptOutcome;
import com.lodygames.rpgquest.quest.progress.CompleteOutcome;
import com.lodygames.rpgquest.quest.progress.ObjectiveProgressView;
import com.lodygames.rpgquest.quest.progress.QuestProgressEngine;
import com.lodygames.rpgquest.quest.progress.QuestStepProgressView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class QuestCommand implements CommandExecutor, TabCompleter {

    private static final String PLAYER_PERMISSION = "rpgquest.quest";
    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS =
            List.of("list", "accept", "progress", "abandon", "complete", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "validate");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final YamlQuestEngine questEngine;
    private final QuestProgressEngine progressEngine;
    private final QuestMessagesService messagesService;

    public QuestCommand(RPGQuestPlugin plugin, YamlQuestEngine questEngine, QuestProgressEngine progressEngine,
                         QuestMessagesService messagesService) {
        this.plugin = plugin;
        this.questEngine = questEngine;
        this.progressEngine = progressEngine;
        this.messagesService = messagesService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> handleList(sender);
            case "accept" -> handleAccept(sender, args);
            case "progress" -> handleProgress(sender, args);
            case "abandon" -> handleAbandon(sender, args);
            case "complete" -> handleComplete(sender, args);
            case "admin" -> handleAdmin(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    // ---- Commandes joueur -------------------------------------------------

    private void handleList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PLAYER_PERMISSION)) {
            return;
        }

        progressEngine.allStates(player.getUniqueId()).thenAccept(states ->
                runOnMainThread(() -> {
                    sender.sendMessage(messages().format("quest.list-header"));
                    for (Map.Entry<NamespacedKey, QuestState> entry : states.entrySet()) {
                        questEngine.find(entry.getKey()).ifPresent(quest -> sender.sendMessage(messages().format(
                                "quest.list-entry",
                                Placeholder.unparsed("quest", quest.title().base()),
                                Placeholder.unparsed("category", quest.category()),
                                Placeholder.unparsed("state", entry.getValue().name()))));
                    }
                }));
    }

    private void handleAccept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PLAYER_PERMISSION)) {
            return;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        NamespacedKey questId = resolveOrWarn(sender, args[1]);
        if (questId == null) {
            return;
        }

        progressEngine.accept(player, questId).thenAccept(outcome ->
                runOnMainThread(() -> sendAcceptResult(player, questId, outcome)));
    }

    private void sendAcceptResult(Player player, NamespacedKey questId, AcceptOutcome outcome) {
        String questName = questName(questId);
        switch (outcome.result()) {
            case ACCEPTED -> player.sendMessage(messages().format(
                    "quest.accepted", Placeholder.unparsed("quest", questName)));
            case UNKNOWN_QUEST -> player.sendMessage(messages().format(
                    "quest.unknown", Placeholder.unparsed("quest", questName)));
            case ALREADY_ACTIVE -> player.sendMessage(messages().format(
                    "quest.already-active", Placeholder.unparsed("quest", questName)));
            case NOT_REPEATABLE -> player.sendMessage(messages().format(
                    "quest.not-repeatable", Placeholder.unparsed("quest", questName)));
            case MISSING_PREREQUISITES -> player.sendMessage(messages().format(
                    "quest.prerequisites-missing",
                    Placeholder.unparsed("quest", questName),
                    Placeholder.unparsed("missing", outcome.missingPrerequisites().stream()
                            .map(this::questName).collect(Collectors.joining(", ")))));
        }
    }

    private void handleAbandon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PLAYER_PERMISSION)) {
            return;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        NamespacedKey questId = resolveOrWarn(sender, args[1]);
        if (questId == null) {
            return;
        }

        AbandonOutcome outcome = progressEngine.abandon(player, questId);
        String questName = questName(questId);
        if (outcome == AbandonOutcome.ABANDONED) {
            player.sendMessage(messages().format("quest.abandoned", Placeholder.unparsed("quest", questName)));
        } else {
            player.sendMessage(messages().format("quest.nothing-to-abandon", Placeholder.unparsed("quest", questName)));
        }
    }

    private void handleProgress(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PLAYER_PERMISSION)) {
            return;
        }

        if (args.length >= 2) {
            NamespacedKey questId = resolveOrWarn(sender, args[1]);
            if (questId == null) {
                return;
            }
            Optional<QuestStepProgressView> view = progressEngine.activeStepView(player.getUniqueId(), questId);
            if (view.isEmpty()) {
                player.sendMessage(messages().format("quest.progress-empty"));
                return;
            }
            for (ObjectiveProgressView objective : view.get().objectives()) {
                player.sendMessage(messages().format("quest.progress-objective-line",
                        Placeholder.unparsed("objective", objective.description()),
                        Placeholder.unparsed("current", String.valueOf(objective.current())),
                        Placeholder.unparsed("total", String.valueOf(objective.total()))));
            }
            return;
        }

        progressEngine.allStates(player.getUniqueId()).thenAccept(states -> runOnMainThread(() -> {
            List<Map.Entry<NamespacedKey, QuestState>> active = states.entrySet().stream()
                    .filter(entry -> entry.getValue() != QuestState.NOT_STARTED)
                    .toList();
            if (active.isEmpty()) {
                player.sendMessage(messages().format("quest.progress-empty"));
                return;
            }
            for (Map.Entry<NamespacedKey, QuestState> entry : active) {
                player.sendMessage(messages().format("quest.progress-line",
                        Placeholder.unparsed("quest", questName(entry.getKey())),
                        Placeholder.unparsed("state", entry.getValue().name())));
            }
        }));
    }

    // ---- Admin --------------------------------------------------------------

    private void handleComplete(CommandSender sender, String[] args) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        NamespacedKey questId = resolveOrWarn(sender, args[1]);
        if (questId == null) {
            return;
        }

        progressEngine.forceComplete(player, questId).thenAccept(outcome -> runOnMainThread(() -> {
            String questName = questName(questId);
            switch (outcome) {
                case COMPLETED -> player.sendMessage(messages().format(
                        "quest.force-completed", Placeholder.unparsed("quest", questName)));
                case ALREADY_COMPLETED -> player.sendMessage(messages().format(
                        "quest.not-repeatable", Placeholder.unparsed("quest", questName)));
                case UNKNOWN_QUEST -> player.sendMessage(messages().format(
                        "quest.unknown", Placeholder.unparsed("quest", questName)));
            }
        }));
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "reload" -> handleAdminReload(sender);
            case "validate" -> handleAdminValidate(sender);
            default -> sendUsage(sender);
        }
    }

    private void handleAdminReload(CommandSender sender) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        messagesService.reload();
        sendReport(sender, "Rechargement", progressEngine.reloadQuestDefinitions());
    }

    private void handleAdminValidate(CommandSender sender) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        sendReport(sender, "Validation", questEngine.validate());
    }

    private void sendReport(CommandSender sender, String action, QuestLoadReport report) {
        sender.sendMessage(MM.deserialize(
                "<gold><action></gold> <gray>: <loaded> quête(s) chargée(s), <errors> erreur(s).</gray>",
                Placeholder.unparsed("action", action),
                Placeholder.unparsed("loaded", String.valueOf(report.loaded().size())),
                Placeholder.unparsed("errors", String.valueOf(report.issues().size()))));

        for (QuestLoadIssue issue : report.issues()) {
            sender.sendMessage(MM.deserialize(
                    "<red>- [<file>]</red> <white><message></white>",
                    Placeholder.unparsed("file", issue.file()),
                    Placeholder.unparsed("message", issue.message())));
        }
    }

    // ---- Utilitaires ----------------------------------------------------

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(messages().format("permission.denied", Placeholder.unparsed("permission", permission)));
        return false;
    }

    private @Nullable NamespacedKey resolveOrWarn(CommandSender sender, String raw) {
        NamespacedKey key = raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
        if (key == null) {
            sender.sendMessage(messages().format("quest.unknown", Placeholder.unparsed("quest", raw)));
        }
        return key;
    }

    private String questName(NamespacedKey questId) {
        return questEngine.find(questId).map(quest -> quest.title().base()).orElse(questId.toString());
    }

    private void sendPlayerOnly(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/quest list</yellow> <gray>- liste les quêtes et leur état</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/quest accept <id></yellow> <gray>- accepte une quête</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/quest progress [id]</yellow> <gray>- affiche ta progression</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/quest abandon <id></yellow> <gray>- abandonne une quête active</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/quest complete <id></yellow> <gray>- force la fin (rpgquest.admin, test)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/quest admin reload</yellow> <gray>- recharge quêtes+messages (rpgquest.admin)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/quest admin validate</yellow> <gray>- valide sans appliquer (rpgquest.admin)</gray>"));
    }

    private QuestMessages messages() {
        return messagesService.current();
    }

    private void runOnMainThread(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String prefix = args[1].toLowerCase();
            if (sub.equals("admin")) {
                return ADMIN_SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
            }
            if (sub.equals("accept") || sub.equals("progress") || sub.equals("abandon") || sub.equals("complete")) {
                return questEngine.quests().stream()
                        .map(QuestDefinition::id)
                        .map(NamespacedKey::toString)
                        .filter(id -> id.startsWith(prefix))
                        .toList();
            }
        }
        return List.of();
    }
}
