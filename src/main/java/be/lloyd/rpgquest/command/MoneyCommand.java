package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.RPGQuestPlugin;
import be.lloyd.rpgquest.economy.EconomyService;
import be.lloyd.rpgquest.economy.PayOutcome;
import be.lloyd.rpgquest.economy.TransactionType;
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
 * {@code /money [pay|admin]}. Sans argument : affiche le solde de
 * l'exécutant ({@code rpgquest.money}, comme {@code /quest progress}).
 * {@code pay} est ouvert au même public ; {@code admin give|take|set} est
 * réservé à {@code rpgquest.admin}, même conception que {@code /customitem
 * give}.
 */
public final class MoneyCommand implements CommandExecutor, TabCompleter {

    private static final String PLAYER_PERMISSION = "rpgquest.money";
    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("pay", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("give", "take", "set");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final EconomyService economyService;

    public MoneyCommand(RPGQuestPlugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            handleBalance(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "pay" -> handlePay(sender, args);
            case "admin" -> handleAdmin(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    // ---- Joueur -------------------------------------------------------------

    private void handleBalance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PLAYER_PERMISSION)) {
            return;
        }
        economyService.balance(player.getUniqueId()).thenAccept(balance -> runOnMainThread(() ->
                player.sendMessage(MM.deserialize(
                        "<gold>Solde :</gold> <white><balance> pièce(s)</white>",
                        Placeholder.unparsed("balance", String.valueOf(balance))))));
    }

    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PLAYER_PERMISSION)) {
            return;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }
        Player target = resolveTarget(sender, args[1]);
        if (target == null) {
            return;
        }
        Long amount = parseAmount(sender, args[2]);
        if (amount == null) {
            return;
        }

        economyService.pay(player.getUniqueId(), target.getUniqueId(), amount).thenAccept(outcome -> runOnMainThread(() -> {
            switch (outcome) {
                case PAID -> {
                    player.sendMessage(MM.deserialize(
                            "<green>Envoyé</green> <white><amount></white> <green>à</green> <white><target></white>",
                            Placeholder.unparsed("amount", String.valueOf(amount)),
                            Placeholder.unparsed("target", target.getName())));
                    target.sendMessage(MM.deserialize(
                            "<green>Reçu</green> <white><amount></white> <green>de</green> <white><from></white>",
                            Placeholder.unparsed("amount", String.valueOf(amount)),
                            Placeholder.unparsed("from", player.getName())));
                }
                case INSUFFICIENT_FUNDS -> player.sendMessage(MM.deserialize("<red>Fonds insuffisants.</red>"));
                case SAME_PLAYER -> player.sendMessage(MM.deserialize("<red>Tu ne peux pas te payer toi-même.</red>"));
                case INVALID_AMOUNT -> player.sendMessage(MM.deserialize("<red>Montant invalide.</red>"));
            }
        })).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Échec de /money pay pour {}", player.getUniqueId(), error);
            return null;
        });
    }

    // ---- Admin --------------------------------------------------------------

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        if (args.length < 4) {
            sendUsage(sender);
            return;
        }
        Player target = resolveTarget(sender, args[2]);
        if (target == null) {
            return;
        }
        Long amount = parseAmount(sender, args[3]);
        if (amount == null) {
            return;
        }
        String context = "admin:" + sender.getName();

        switch (args[1].toLowerCase()) {
            case "give" -> economyService.credit(target.getUniqueId(), amount, TransactionType.ADMIN_GRANT, context)
                    .thenAccept(v -> runOnMainThread(() -> sendAdminResult(sender, target, "donné(s) à", amount)))
                    .exceptionally(error -> logAdminError(sender, error));
            case "take" -> economyService.debit(target.getUniqueId(), amount, TransactionType.ADMIN_TAKE, context)
                    .thenAccept(success -> runOnMainThread(() -> {
                        if (success) {
                            sendAdminResult(sender, target, "retiré(s) à", amount);
                        } else {
                            sender.sendMessage(MM.deserialize(
                                    "<red>Solde insuffisant chez <player>.</red>",
                                    Placeholder.unparsed("player", target.getName())));
                        }
                    }))
                    .exceptionally(error -> logAdminError(sender, error));
            case "set" -> economyService.adminSet(target.getUniqueId(), amount, context)
                    .thenAccept(v -> runOnMainThread(() -> sender.sendMessage(MM.deserialize(
                            "<green>Solde de <player> fixé à</green> <white><amount></white>",
                            Placeholder.unparsed("player", target.getName()),
                            Placeholder.unparsed("amount", String.valueOf(amount))))))
                    .exceptionally(error -> logAdminError(sender, error));
            default -> sendUsage(sender);
        }
    }

    private void sendAdminResult(CommandSender sender, Player target, String verb, long amount) {
        sender.sendMessage(MM.deserialize(
                "<green><amount> pièce(s) <verb></green> <white><player></white>",
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("verb", verb),
                Placeholder.unparsed("player", target.getName())));
    }

    private Void logAdminError(CommandSender sender, Throwable error) {
        plugin.getSLF4JLogger().error("Échec de /money admin pour {}", sender.getName(), error);
        return null;
    }

    // ---- Utilitaires ----------------------------------------------------

    private @Nullable Player resolveTarget(CommandSender sender, String name) {
        Player target = sender.getServer().getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(MM.deserialize(
                    "<red>Joueur introuvable ou hors-ligne :</red> <white><name></white>",
                    Placeholder.unparsed("name", name)));
        }
        return target;
    }

    private @Nullable Long parseAmount(CommandSender sender, String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) {
                sender.sendMessage(MM.deserialize(
                        "<red>Montant invalide (doit être strictement positif) :</red> <white><amount></white>",
                        Placeholder.unparsed("amount", raw)));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            sender.sendMessage(MM.deserialize(
                    "<red>Montant invalide (nombre attendu) :</red> <white><amount></white>",
                    Placeholder.unparsed("amount", raw)));
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
        sender.sendMessage(MM.deserialize("<yellow>/money</yellow> <gray>- affiche ton solde</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/money pay <joueur> <montant></yellow> <gray>- envoie des pièces</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/money admin give|take|set <joueur> <montant></yellow> <gray>- (rpgquest.admin)</gray>"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("pay")) {
            return onlinePlayerNames(sender, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return ADMIN_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            return onlinePlayerNames(sender, args[2]);
        }
        return List.of();
    }

    private List<String> onlinePlayerNames(CommandSender sender, String prefix) {
        String lowerPrefix = prefix.toLowerCase();
        return sender.getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lowerPrefix))
                .toList();
    }
}
