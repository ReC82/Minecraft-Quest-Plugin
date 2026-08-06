package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.RPGQuestPlugin;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RPGQuestCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("version", "help");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;

    public RPGQuestCommand(RPGQuestPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        String sub = args.length > 0 ? args[0] : "help";

        switch (sub.toLowerCase()) {
            case "version" -> sender.sendMessage(MM.deserialize(
                    "<gold>RPGQuest</gold> <gray>v" + plugin.getPluginMeta().getVersion() + "</gray>"));
            case "help" -> sendHelp(sender);
            default -> {
                sender.sendMessage(MM.deserialize(
                        "<red>Commande inconnue :</red> <white>" + sub + "</white>"));
                sendHelp(sender);
            }
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold><bold>RPGQuest</bold></gold> <gray>- aide</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgquest version</yellow> <gray>- affiche la version du plugin</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgquest help</yellow> <gray>- affiche ce message</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
