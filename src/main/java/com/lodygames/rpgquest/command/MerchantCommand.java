package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.economy.merchant.MerchantLoadIssue;
import com.lodygames.rpgquest.economy.merchant.MerchantLoadReport;
import com.lodygames.rpgquest.economy.merchant.YamlMerchantRegistry;
import com.lodygames.rpgquest.economy.merchant.model.MerchantDefinition;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /merchant reload|validate|list} — entièrement administratif
 * ({@code rpgquest.admin}) : un marchand n'a pas de sous-commande joueur, il
 * ne s'atteint que via un dialogue ({@code OPEN_MERCHANT}), voir {@code
 * docs/ECONOMY.md}.
 */
public final class MerchantCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("reload", "validate", "list");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlMerchantRegistry registry;

    public MerchantCommand(YamlMerchantRegistry registry) {
        this.registry = registry;
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
            default -> sendUsage(sender);
        }
        return true;
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
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
