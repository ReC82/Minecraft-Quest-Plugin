package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.database.PlayerProfile;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class RPGQuestCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("version", "help", "profile");
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

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
                    "<gold>RPGQuest</gold> <gray>v<version></gray>",
                    Placeholder.unparsed("version", plugin.getPluginMeta().getVersion())));
            case "help" -> sendHelp(sender);
            case "profile" -> handleProfile(sender, args);
            default -> {
                sender.sendMessage(MM.deserialize(
                        "<red>Commande inconnue :</red> <white><sub></white>",
                        Placeholder.unparsed("sub", sub)));
                sendHelp(sender);
            }
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold><bold>RPGQuest</bold></gold> <gray>- aide</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgquest version</yellow> <gray>- affiche la version du plugin</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgquest profile [joueur]</yellow> <gray>- affiche un profil joueur</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgquest help</yellow> <gray>- affiche ce message</gray>"));
    }

    private void handleProfile(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String targetName = args[1];
            Player online = plugin.getServer().getPlayerExact(targetName);
            if (online != null) {
                sendProfile(sender, online.getUniqueId(), online.getName());
                return;
            }

            // Résolution hors-ligne potentiellement bloquante : jamais sur le thread principal.
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                @SuppressWarnings("deprecation")
                OfflinePlayer offline = plugin.getServer().getOfflinePlayer(targetName);
                String resolvedName = offline.getName() != null ? offline.getName() : targetName;
                sendProfile(sender, offline.getUniqueId(), resolvedName);
            });
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Précise un pseudo :</red> <yellow>/rpgquest profile <joueur></yellow>"));
            return;
        }
        sendProfile(sender, player.getUniqueId(), player.getName());
    }

    private void sendProfile(CommandSender sender, UUID uuid, String fallbackName) {
        plugin.getPlayerProfileService().getOrLoad(uuid).whenComplete((profileOpt, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        sender.sendMessage(MM.deserialize("<red>Erreur lors de la lecture du profil.</red>"));
                        plugin.getSLF4JLogger().warn("Erreur de lecture du profil {}", uuid, error);
                        return;
                    }
                    if (profileOpt.isEmpty()) {
                        sender.sendMessage(MM.deserialize(
                                "<red>Aucun profil trouvé pour</red> <white><name></white>",
                                Placeholder.unparsed("name", fallbackName)));
                        return;
                    }
                    sendProfileMessage(sender, profileOpt.get());
                }));
    }

    private void sendProfileMessage(CommandSender sender, PlayerProfile profile) {
        sender.sendMessage(MM.deserialize(
                "<gold><bold>Profil</bold></gold> <white><name></white>",
                Placeholder.unparsed("name", profile.lastName())));
        sender.sendMessage(MM.deserialize(
                "<gray>UUID :</gray> <white><uuid></white>",
                Placeholder.unparsed("uuid", profile.uuid().toString())));
        sender.sendMessage(MM.deserialize(
                "<gray>Créé le :</gray> <white><date></white>",
                Placeholder.unparsed("date", TIMESTAMP_FORMAT.format(profile.createdAt()))));
        sender.sendMessage(MM.deserialize(
                "<gray>Mis à jour le :</gray> <white><date></white>",
                Placeholder.unparsed("date", TIMESTAMP_FORMAT.format(profile.updatedAt()))));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("profile")) {
            String prefix = args[1].toLowerCase();
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
