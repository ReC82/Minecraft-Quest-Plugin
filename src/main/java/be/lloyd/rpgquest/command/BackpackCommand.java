package be.lloyd.rpgquest.command;

import be.lloyd.rpgquest.backpack.BackpackService;
import be.lloyd.rpgquest.backpack.ItemArraySerializer;
import be.lloyd.rpgquest.backpack.model.BackpackSize;
import be.lloyd.rpgquest.database.BackpackRepository;
import be.lloyd.rpgquest.entitlement.EntitlementService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * {@code /backpack [recover [n]]} ({@code rpgquest.backpack}) — ouvre ton
 * backpack, ou liste/réclame le contenu de ta boîte de récupération
 * (mission point 9). {@code /backpack admin grant|revoke <joueur> [taille]}
 * ({@code rpgquest.admin}) — même conception que {@code /money admin
 * give|take|set} (mission point 5, "commandes admin d'octroi/retrait").
 */
public final class BackpackCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "rpgquest.backpack";
    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("recover", "admin");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("grant", "revoke");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final BackpackService backpackService;
    private final EntitlementService entitlementService;
    private final Logger logger;

    public BackpackCommand(BackpackService backpackService, EntitlementService entitlementService, Logger logger) {
        this.backpackService = backpackService;
        this.entitlementService = entitlementService;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            handleAdmin(sender, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("recover")) {
            handleRecover(sender, args);
            return true;
        }
        handleOpen(sender);
        return true;
    }

    // ---- Ouverture ------------------------------------------------------------------------

    private void handleOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PERMISSION)) {
            return;
        }
        backpackService.open(player).thenAccept(outcome -> {
            if (outcome == BackpackService.OpenOutcome.NO_ACCESS) {
                player.sendMessage(MM.deserialize(
                        "<red>Tu n'as accès à aucun backpack pour l'instant.</red>"));
            }
        }).exceptionally(error -> {
            logger.error("Échec de l'ouverture du backpack de {}.", player.getUniqueId(), error);
            return null;
        });
    }

    // ---- Récupération -----------------------------------------------------------------------

    private void handleRecover(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendPlayerOnly(sender);
            return;
        }
        if (!hasPermission(sender, PERMISSION)) {
            return;
        }
        if (args.length < 2) {
            listOverflow(player);
            return;
        }
        Long entryNumber = parseIndex(sender, args[1]);
        if (entryNumber == null) {
            return;
        }
        claimOverflow(player, entryNumber);
    }

    private void listOverflow(Player player) {
        backpackService.unclaimedOverflow(player.getUniqueId()).thenAccept(entries -> {
            if (entries.isEmpty()) {
                player.sendMessage(MM.deserialize("<gray>Aucun objet en attente de récupération.</gray>"));
                return;
            }
            player.sendMessage(MM.deserialize("<gold>Objets en attente de récupération :</gold>"));
            for (int i = 0; i < entries.size(); i++) {
                BackpackRepository.OverflowEntry entry = entries.get(i);
                player.sendMessage(MM.deserialize(
                        "<yellow>#<n></yellow> <gray>(<reason>, <date>)</gray>",
                        Placeholder.unparsed("n", String.valueOf(i + 1)),
                        Placeholder.unparsed("reason", entry.reason()),
                        Placeholder.unparsed("date", entry.createdAt().toString())));
            }
            player.sendMessage(MM.deserialize("<gray>/backpack recover <numéro> pour réclamer.</gray>"));
        }).exceptionally(error -> {
            logger.error("Échec de la liste de récupération pour {}.", player.getUniqueId(), error);
            return null;
        });
    }

    private void claimOverflow(Player player, long entryNumber) {
        backpackService.unclaimedOverflow(player.getUniqueId()).thenAccept(entries -> {
            int index = (int) entryNumber - 1;
            if (index < 0 || index >= entries.size()) {
                player.sendMessage(MM.deserialize("<red>Numéro invalide.</red>"));
                return;
            }
            BackpackRepository.OverflowEntry entry = entries.get(index);
            backpackService.claimOverflow(entry.id()).thenAccept(claimed -> {
                if (!claimed) {
                    player.sendMessage(MM.deserialize("<red>Cette entrée a déjà été réclamée.</red>"));
                    return;
                }
                deliverAndNotify(player, entry);
            });
        }).exceptionally(error -> {
            logger.error("Échec de la réclamation pour {}.", player.getUniqueId(), error);
            return null;
        });
    }

    private void deliverAndNotify(Player player, BackpackRepository.OverflowEntry entry) {
        ItemStack[] items;
        try {
            items = ItemArraySerializer.deserialize(entry.contents());
        } catch (IOException e) {
            logger.error("Entrée de récupération illisible pour {} (id={}).", player.getUniqueId(), entry.id(), e);
            player.sendMessage(MM.deserialize("<red>Cette entrée est illisible, contacte un administrateur.</red>"));
            return;
        }
        int delivered = 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            delivered++;
            var leftover = player.getInventory().addItem(item);
            for (ItemStack overflowItem : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflowItem);
            }
        }
        player.sendMessage(MM.deserialize(
                "<green><count> objet(s) récupéré(s)</green> <gray>(déposés au sol si l'inventaire était plein).</gray>",
                Placeholder.unparsed("count", String.valueOf(delivered))));
    }

    // ---- Admin ----------------------------------------------------------------------------

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!hasPermission(sender, ADMIN_PERMISSION)) {
            return;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }
        Player target = resolveTarget(sender, args[2]);
        if (target == null) {
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "grant" -> handleGrant(sender, target, args);
            case "revoke" -> handleRevoke(sender, target);
            default -> sendUsage(sender);
        }
    }

    private void handleGrant(CommandSender sender, Player target, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(MM.deserialize("<yellow>/backpack admin grant <joueur> <taille></yellow>"));
            return;
        }
        BackpackSize size = parseSize(sender, args[3]);
        if (size == null) {
            return;
        }
        String reason = "admin:" + sender.getName();
        entitlementService.grant(target.getUniqueId(), BackpackService.ENTITLEMENT_KEY, size.name(), reason)
                .thenCompose(ignored -> backpackService.applySizeChange(target.getUniqueId(), size))
                .thenRun(() -> sender.sendMessage(MM.deserialize(
                        "<green>Backpack <size></green> <green>accordé à</green> <white><player></white>",
                        Placeholder.unparsed("size", size.name()), Placeholder.unparsed("player", target.getName()))))
                .exceptionally(error -> {
                    logger.error("Échec de /backpack admin grant pour {}.", target.getUniqueId(), error);
                    sender.sendMessage(MM.deserialize("<red>Erreur : voir la console.</red>"));
                    return null;
                });
    }

    private void handleRevoke(CommandSender sender, Player target) {
        String reason = "admin:" + sender.getName();
        entitlementService.revoke(target.getUniqueId(), BackpackService.ENTITLEMENT_KEY, reason)
                .thenCompose(ignored -> backpackService.effectiveSize(target))
                .thenCompose(sizeOpt -> sizeOpt.isPresent()
                        ? backpackService.applySizeChange(target.getUniqueId(), sizeOpt.get())
                        : java.util.concurrent.CompletableFuture.completedFuture(null))
                .thenRun(() -> sender.sendMessage(MM.deserialize(
                        "<green>Avantage backpack retiré à</green> <white><player></white>",
                        Placeholder.unparsed("player", target.getName()))))
                .exceptionally(error -> {
                    logger.error("Échec de /backpack admin revoke pour {}.", target.getUniqueId(), error);
                    sender.sendMessage(MM.deserialize("<red>Erreur : voir la console.</red>"));
                    return null;
                });
    }

    // ---- Utilitaires ----------------------------------------------------------------------

    private @Nullable Player resolveTarget(CommandSender sender, String name) {
        Player target = sender.getServer().getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(MM.deserialize(
                    "<red>Joueur introuvable ou hors-ligne :</red> <white><name></white>", Placeholder.unparsed("name", name)));
        }
        return target;
    }

    private @Nullable BackpackSize parseSize(CommandSender sender, String raw) {
        try {
            return BackpackSize.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MM.deserialize(
                    "<red>Taille invalide :</red> <white><size></white> <gray>(SMALL, MEDIUM, LARGE)</gray>",
                    Placeholder.unparsed("size", raw)));
            return null;
        }
    }

    private @Nullable Long parseIndex(CommandSender sender, String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value < 1) {
                sender.sendMessage(MM.deserialize("<red>Numéro invalide.</red>"));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            sender.sendMessage(MM.deserialize("<red>Numéro invalide :</red> <white><value></white>",
                    Placeholder.unparsed("value", raw)));
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
        sender.sendMessage(MM.deserialize("<yellow>/backpack</yellow> <gray>- ouvre ton backpack</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/backpack recover [numéro]</yellow> <gray>- liste/réclame les objets en attente</gray>"));
        sender.sendMessage(MM.deserialize(
                "<yellow>/backpack admin grant|revoke <joueur> [taille]</yellow> <gray>- (rpgquest.admin)</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return ADMIN_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return sender.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("grant")) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return java.util.Arrays.stream(BackpackSize.values()).map(s -> s.name().toLowerCase(Locale.ROOT))
                    .filter(name -> name.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
