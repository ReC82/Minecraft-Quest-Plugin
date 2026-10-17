package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.economy.merchant.MerchantLoadIssue;
import com.lodygames.rpgquest.economy.merchant.MerchantLoadReport;
import com.lodygames.rpgquest.economy.merchant.MerchantTradeService;
import com.lodygames.rpgquest.economy.merchant.YamlMerchantRegistry;
import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /merchant reload|validate|list|open} — entièrement administratif
 * ({@code rpgquest.admin}) : un marchand n'a pas de sous-commande joueur, il
 * ne s'atteint que via un dialogue ({@code OPEN_MERCHANT}), voir {@code
 * docs/ECONOMY.md}. {@code open <id>} (issue #336) ouvre la vitrine à
 * l'administrateur lui-même, pour vérifier des prix sans câbler de dialogue :
 * les échanges y sont réels (son propre solde, son propre inventaire).
 */
public final class MerchantCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("reload", "validate", "list", "open");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlMerchantRegistry registry;
    private final MerchantTradeService tradeService;

    public MerchantCommand(YamlMerchantRegistry registry) {
        this(registry, null);
    }

    /** @param tradeService {@code null} = {@code open} indisponible */
    public MerchantCommand(YamlMerchantRegistry registry, MerchantTradeService tradeService) {
        this.registry = registry;
        this.tradeService = tradeService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!hasPermission(sender)) {
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> sendReport(sender, "Rechargement", registry.reload());
            case "validate" -> sendReport(sender, "Validation", registry.validate());
            case "list" -> handleList(sender);
            case "open" -> handleOpen(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleOpen(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>À lancer en jeu : la vitrine s'ouvre pour toi.</red>"));
            return;
        }
        if (args.length < 2 || tradeService == null) {
            sendUsage(sender);
            return;
        }
        NamespacedKey id = args[1].contains(":") ? NamespacedKey.fromString(args[1].toLowerCase())
                : NamespacedKey.fromString("rpgquest:" + args[1].toLowerCase());
        if (id == null) {
            sender.sendMessage(MM.deserialize("<red>Identifiant de marchand invalide.</red>"));
            return;
        }
        tradeService.openShop(player, id);
    }

    private void handleList(CommandSender sender) {
        List<MerchantDefinition> merchants = registry.merchants();
        if (merchants.isEmpty()) {
            sender.sendMessage(MM.deserialize("<gray>Aucun marchand chargé.</gray>"));
            return;
        }
        sender.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>marchand(s) chargé(s) :</gray>",
                Placeholder.unparsed("count", String.valueOf(merchants.size()))));
        for (MerchantDefinition merchant : merchants) {
            sender.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>(<offers> offre(s))</gray>",
                    Placeholder.unparsed("id", merchant.id().toString()),
                    Placeholder.unparsed("offers", String.valueOf(merchant.offers().size()))));
        }
    }

    private void sendReport(CommandSender sender, String action, MerchantLoadReport report) {
        sender.sendMessage(MM.deserialize(
                "<gold><action></gold> <gray>: <loaded> marchand(s) chargé(s), <errors> erreur(s).</gray>",
                Placeholder.unparsed("action", action),
                Placeholder.unparsed("loaded", String.valueOf(report.loaded().size())),
                Placeholder.unparsed("errors", String.valueOf(report.issues().size()))));

        for (MerchantLoadIssue issue : report.issues()) {
            sender.sendMessage(MM.deserialize(
                    "<red>- [<file>]</red> <white><message></white>",
                    Placeholder.unparsed("file", issue.file()),
                    Placeholder.unparsed("message", issue.message())));
        }
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

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/merchant reload</yellow> <gray>- recharge les marchands</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/merchant validate</yellow> <gray>- valide sans appliquer</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/merchant list</yellow> <gray>- liste les marchands chargés</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/merchant open <id></yellow> <gray>- ouvre la vitrine pour toi "
                + "(échanges réels)</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && "open".equalsIgnoreCase(args[0])) {
            return registry.merchants().stream().map(m -> m.id().toString())
                    .filter(id -> id.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
