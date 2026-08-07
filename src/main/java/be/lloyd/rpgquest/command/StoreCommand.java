package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.store.StoreClient;
import be.lloyd.rpgquest.store.StoreOrderSummary;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * {@code /store history [joueur|uuid]} ({@code rpgquest.admin}) — historique
 * des commandes/livraisons de la boutique, consultable par l'administrateur
 * (mission étape 22, point 11). Interroge directement web-api (jamais de
 * copie locale de l'historique côté plugin).
 */
public final class StoreCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final int DEFAULT_LIMIT = 20;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final StoreClient storeClient;
    private final Logger logger;

    public StoreCommand(StoreClient storeClient, Logger logger) {
        this.storeClient = storeClient;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(MM.deserialize("<red>Permission manquante :</red> <white><permission></white>",
                    Placeholder.unparsed("permission", ADMIN_PERMISSION)));
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("history")) {
            sender.sendMessage(MM.deserialize("<yellow>/store history [joueur|uuid]</yellow>"));
            return true;
        }

        UUID playerUuid = null;
        if (args.length >= 2) {
            playerUuid = resolvePlayerUuid(args[1]);
            if (playerUuid == null) {
                sender.sendMessage(MM.deserialize(
                        "<red>Joueur ou UUID introuvable :</red> <white><value></white>", Placeholder.unparsed("value", args[1])));
                return true;
            }
        }

        UUID finalPlayerUuid = playerUuid;
        storeClient.fetchOrderHistory(finalPlayerUuid, DEFAULT_LIMIT).thenAccept(orders -> displayHistory(sender, orders))
                .exceptionally(error -> {
                    logger.error("Échec de la récupération de l'historique boutique.", error);
                    sender.sendMessage(MM.deserialize(
                            "<red>Impossible de contacter web-api (voir la console).</red>"));
                    return null;
                });
        return true;
    }

    private void displayHistory(CommandSender sender, List<StoreOrderSummary> orders) {
        if (orders.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>Aucune commande trouvée.</gray>"));
            return;
        }
        sender.sendMessage(MM.deserialize("<gold>Historique boutique (<count>) :</gold>",
                Placeholder.unparsed("count", String.valueOf(orders.size()))));
        for (StoreOrderSummary order : orders) {
            sender.sendMessage(MM.deserialize(
                    "<yellow><product></yellow> <gray>—</gray> <white><player></white> <gray>—</gray> "
                            + "<status_color><status></status_color> <gray>(<date>)</gray>",
                    Placeholder.unparsed("product", order.productId()),
                    Placeholder.unparsed("player", order.playerName() != null ? order.playerName() : order.playerUuid()),
                    Placeholder.parsed("status_color", statusColor(order.status())),
                    Placeholder.unparsed("status", order.status()),
                    Placeholder.unparsed("date", order.createdAt())));
        }
    }

    private String statusColor(String status) {
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PAID" -> "<green>";
            case "REFUNDED" -> "<aqua>";
            case "FAILED" -> "<red>";
            default -> "<gray>";
        };
    }

    private @Nullable UUID resolvePlayerUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            // Pas un UUID littéral : tente un pseudo connu (en ligne ou déjà vu par le serveur).
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(value);
        return offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline() ? offlinePlayer.getUniqueId() : null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return "history".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("history") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(p -> p.getName())
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
