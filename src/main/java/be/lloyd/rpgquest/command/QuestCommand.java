package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.quest.QuestLoadIssue;
import be.lloyd.rpgquest.quest.QuestLoadReport;
import be.lloyd.rpgquest.quest.YamlQuestEngine;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class QuestCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "validate");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlQuestEngine questEngine;

    public QuestCommand(YamlQuestEngine questEngine) {
        this.questEngine = questEngine;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("admin")) {
            switch (args[1].toLowerCase()) {
                case "reload" -> handleReload(sender);
                case "validate" -> handleValidate(sender);
                default -> sendUsage(sender);
            }
            return true;
        }
        sendUsage(sender);
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!hasPermission(sender)) {
            return;
        }
        sendReport(sender, "Rechargement", questEngine.reload());
    }

    private void handleValidate(CommandSender sender) {
        if (!hasPermission(sender)) {
            return;
        }
        sendReport(sender, "Validation", questEngine.validate());
    }

    private boolean hasPermission(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        sender.sendMessage(MM.deserialize(
                "<red>Permission manquante :</red> <white><permission></white>",
                Placeholder.unparsed("permission", ADMIN_PERMISSION)));
        return false;
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

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/quest admin reload</yellow> <gray>- recharge les quêtes depuis le disque</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/quest admin validate</yellow> <gray>- valide les quêtes sans les appliquer</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return "admin".startsWith(args[0].toLowerCase()) ? List.of("admin") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            String prefix = args[1].toLowerCase();
            return ADMIN_SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
