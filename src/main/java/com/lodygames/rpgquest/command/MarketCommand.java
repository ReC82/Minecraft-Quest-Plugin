package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.database.MarketListingRecord;
import com.lodygames.rpgquest.database.MarketRepository;
import com.lodygames.rpgquest.economy.market.MarketService;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /market [sell|cancel|admin]}. Sans argument : ouvre la vitrine
 * partagée ({@code rpgquest.market}, comme {@code /quests}). {@code sell}/
 * {@code cancel} sont des alternatives texte aux actions déjà possibles au
 * clic dans la vitrine (voir {@link MarketService}) ; {@code admin list}
 * (`rpgquest.admin`) est un outil de modération en lecture seule.
 */
public final class MarketCommand implements CommandExecutor, TabCompleter {

    private static final String PLAYER_PERMISSION = "rpgquest.market";
    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("sell", "cancel", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("list");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final MarketService marketService;
    private final MarketRepository marketRepository;

    public MarketCommand(RPGQuestPlugin plugin, MarketService marketService, MarketRepository marketRepository) {
        this.plugin = plugin;
        this.marketService = marketService;
        this.marketRepository = marketRepository;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            handleOpen(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "sell" -> handleSell(sender, args);
            case "cancel" -> handleCancel(sender, args);
            case "admin" -> handleAdmin(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PLAYER_PERMISSION)) {
            return;
        }
        marketService.open(player);
    }

    private void handleSell(CommandSender sender, String[] args) {
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
        Long price = parsePositiveLong(sender, args[1]);
        if (price == null) {
            return;
        }
        marketService.sell(player, price);
    }

    private void handleCancel(CommandSender sender, String[] args) {
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
        Long id = parsePositiveLong(sender, args[1]);
        if (id == null) {
            return;
        }
        marketService.cancel(player, id);
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("list")) {
            sendUsage(sender);
            return;
        }
        marketRepository.activeListings().thenAccept(listings -> runOnMainThread(() -> {
            if (listings.isEmpty()) {
                sender.sendMessage(MM.deserialize("<gray>Aucune offre active sur le marché.</gray>"));
                return;
            }
            sender.sendMessage(MM.deserialize(
                    "<gold><count></gold> <gray>offre(s) active(s) :</gray>",
                    Placeholder.unparsed("count", String.valueOf(listings.size()))));
            for (MarketListingRecord listing : listings) {
                sender.sendMessage(MM.deserialize(
                        "<yellow>#<id></yellow> <gray>-</gray> <white><seller></white> <gray>:</gray> <gold><price></gold>",
                        Placeholder.unparsed("id", String.valueOf(listing.id())),
                        Placeholder.unparsed("seller", listing.sellerName().orElse(listing.sellerUuid().toString())),
                        Placeholder.unparsed("price", String.valueOf(listing.price()))));
            }
        })).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Échec de /market admin list", error);
            return null;
        });
    }

    private @Nullable Long parsePositiveLong(CommandSender sender, String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                sender.sendMessage(MM.deserialize(
                        "<red>Valeur invalide (doit être strictement positive) :</red> <white><value></white>",
                        Placeholder.unparsed("value", raw)));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            sender.sendMessage(MM.deserialize(
                    "<red>Valeur invalide (nombre attendu) :</red> <white><value></white>", Placeholder.unparsed("value", raw)));
            return null;
        }
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(MM.deserialize(
                "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", permission)));
        return false;
    }

    private void sendPlayerOnly(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/market</yellow> <gray>- ouvre la vitrine du marché</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/market sell <prix></yellow> <gray>- vend l'objet en main</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/market cancel <id></yellow> <gray>- annule une de tes offres</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/market admin list</yellow> <gray>- liste toutes les offres (rpgquest.admin)</gray>"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return ADMIN_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
