package be.lloyd.rpgquest.admin;

import java.util.List;
import java.util.Locale;
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
 * {@code /rpgadmin} — commande d'administration racine, point d'entrée pour
 * les futurs sous-systèmes d'administration du monde (aplatissement à cette
 * étape ; zones, portails, mobs spéciaux dans des étapes ultérieures,
 * ajoutés comme d'autres branches de {@link #onCommand}). Toutes les
 * sous-commandes exigent {@code rpgquest.admin.world} et un joueur en jeu
 * (jamais la console, qui n'a pas de position à centrer) — {@code /rpgadmin
 * flatten} ne prend d'ailleurs aucune coordonnée explicite dans sa syntaxe.
 */
public final class RpgAdminCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "rpgquest.admin.world";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("flatten");
    private static final List<String> FLATTEN_SUBCOMMANDS = List.of("confirm", "cancel", "undo");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final FlattenService flattenService;

    public RpgAdminCommand(FlattenService flattenService) {
        this.flattenService = flattenService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", PERMISSION)));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize(
                    "<red>Cette commande doit être exécutée par un joueur en jeu (position requise, non fournie en argument).</red>"));
            return true;
        }
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("flatten")) {
            handleFlatten(player, args);
        } else {
            sendUsage(player);
        }
        return true;
    }

    private void handleFlatten(Player player, String[] args) {
        if (args.length < 2) {
            sendFlattenUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "confirm" -> handleConfirm(player);
            case "cancel" -> handleCancel(player);
            case "undo" -> handleUndo(player);
            default -> handlePreview(player, args);
        }
    }

    private void handlePreview(Player player, String[] args) {
        Integer radius = parseInt(args[1]);
        if (radius == null) {
            player.sendMessage(MM.deserialize(
                    "<red>Rayon invalide :</red> <white><value></white>", Placeholder.unparsed("value", args[1])));
            return;
        }
        Integer height = null;
        if (args.length >= 3) {
            height = parseInt(args[2]);
            if (height == null) {
                player.sendMessage(MM.deserialize(
                        "<red>Hauteur invalide :</red> <white><value></white>", Placeholder.unparsed("value", args[2])));
                return;
            }
        }

        FlattenService.PreviewResult result = flattenService.preview(player, radius, height);
        switch (result.outcome()) {
            case CREATED -> {
                FlattenEstimate estimate = result.estimate();
                player.sendMessage(MM.deserialize("""
                        <gold>=== Aperçu d'aplatissement ===</gold>""".stripIndent()));
                player.sendMessage(MM.deserialize(
                        "<white>Forme :</white> <gray><shape></gray> <white>Rayon :</white> <gray><radius></gray> <white>Hauteur :</white> <gray><y></gray>",
                        Placeholder.unparsed("shape", estimate.shape().name()),
                        Placeholder.unparsed("radius", String.valueOf(estimate.radius())),
                        Placeholder.unparsed("y", String.valueOf(estimate.y()))));
                player.sendMessage(MM.deserialize(
                        "<white>Colonnes :</white> <gray><columns></gray> <white>Blocs (estimation majorante) :</white> <gray><blocks></gray>",
                        Placeholder.unparsed("columns", String.valueOf(estimate.columnCount())),
                        Placeholder.unparsed("blocks", String.valueOf(estimate.blockEstimate()))));
                player.sendMessage(MM.deserialize(
                        "<yellow>/rpgadmin flatten confirm</yellow> <gray>pour exécuter, ou</gray> "
                                + "<yellow>/rpgadmin flatten cancel</yellow> <gray>pour annuler.</gray>"));
            }
            case INVALID_RADIUS -> player.sendMessage(MM.deserialize(
                    "<red>Rayon invalide (doit être entre 1 et le maximum configuré).</red>"));
            case INVALID_HEIGHT -> player.sendMessage(MM.deserialize(
                    "<red>Hauteur hors des limites du monde.</red>"));
            case FORBIDDEN_WORLD -> player.sendMessage(MM.deserialize(
                    "<red>L'aplatissement est désactivé dans ce monde.</red>"));
            case ALREADY_PENDING -> player.sendMessage(MM.deserialize(
                    "<yellow>Un aperçu est déjà en attente de confirmation.</yellow>"));
            case ALREADY_ACTIVE -> player.sendMessage(MM.deserialize(
                    "<red>Un aplatissement est déjà en cours ; attendez sa fin ou annulez-le.</red>"));
        }
    }

    private void handleConfirm(Player player) {
        switch (flattenService.confirm(player)) {
            case STARTED -> player.sendMessage(MM.deserialize("<green>Aplatissement lancé.</green>"));
            case NO_PENDING -> player.sendMessage(MM.deserialize(
                    "<red>Aucun aperçu en attente. Lancez d'abord</red> <yellow>/rpgadmin flatten <rayon></yellow>."));
            case EXPIRED -> player.sendMessage(MM.deserialize(
                    "<red>L'aperçu a expiré. Relancez</red> <yellow>/rpgadmin flatten <rayon></yellow>."));
            case ALREADY_ACTIVE -> player.sendMessage(MM.deserialize(
                    "<red>Un aplatissement est déjà en cours.</red>"));
        }
    }

    private void handleCancel(Player player) {
        switch (flattenService.cancel(player)) {
            case CANCELLED_PENDING -> player.sendMessage(MM.deserialize("<green>Aperçu annulé.</green>"));
            case CANCELLED_ACTIVE -> player.sendMessage(MM.deserialize(
                    "<green>Aplatissement en cours annulé</green> <gray>(le travail déjà effectué reste ; utilisez</gray> "
                            + "<yellow>/rpgadmin flatten undo</yellow> <gray>pour l'annuler).</gray>"));
            case NOTHING_TO_CANCEL -> player.sendMessage(MM.deserialize(
                    "<gray>Rien à annuler.</gray>"));
        }
    }

    private void handleUndo(Player player) {
        switch (flattenService.undo(player)) {
            case UNDONE -> player.sendMessage(MM.deserialize("<green>Dernier aplatissement annulé (undo).</green>"));
            case NOTHING_TO_UNDO -> player.sendMessage(MM.deserialize(
                    "<gray>Aucun aplatissement à annuler.</gray>"));
            case OPERATION_IN_PROGRESS -> player.sendMessage(MM.deserialize(
                    "<red>Attendez la fin (ou annulez) l'aplatissement en cours avant d'annuler.</red>"));
        }
    }

    private @Nullable Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendUsage(CommandSender sender) {
        sendFlattenUsage(sender);
    }

    private void sendFlattenUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize(
                "<yellow>/rpgadmin flatten <rayon> [hauteur]</yellow> <gray>- aperçu d'aplatissement centré sur vous</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin flatten confirm</yellow> <gray>- exécute l'aperçu en attente</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin flatten cancel</yellow> <gray>- annule l'aperçu ou l'opération en cours</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/rpgadmin flatten undo</yellow> <gray>- annule le dernier aplatissement</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("flatten")) {
            return FLATTEN_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
