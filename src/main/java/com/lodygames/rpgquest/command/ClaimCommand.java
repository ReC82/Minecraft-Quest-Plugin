package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.RPGQuestPlugin;
import com.lodygames.rpgquest.claim.ClaimSelectionService;
import com.lodygames.rpgquest.claim.ClaimService;
import com.lodygames.rpgquest.claim.model.Claim;
import com.lodygames.rpgquest.database.ClaimActionOutcome;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /claim} — commande joueur ({@code rpgquest.claim}). {@code create}
 * prend un id explicite (nécessaire : rien à « la position actuelle » tant
 * que le claim n'existe pas) et utilise la sélection en cours ({@link
 * ClaimSelectionService}, comme {@code /rpgadmin zone create}) ; toutes les
 * autres sous-commandes qui touchent un claim précis ({@code delete},
 * {@code info}, {@code trust}, {@code untrust}, {@code flag}) agissent sur
 * le claim à la position actuelle du joueur, jamais sur un id tapé à la
 * main — cohérent avec la mission, qui ne montre un argument que pour
 * {@code trust}/{@code untrust <joueur>}.
 */
public final class ClaimCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "rpgquest.claim";
    private static final List<String> TOP_LEVEL_SUBCOMMANDS =
            List.of("wand", "create", "delete", "info", "trust", "untrust", "list", "flag");
    private static final List<String> FLAG_SUBCOMMANDS = List.of("redstone");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final RPGQuestPlugin plugin;
    private final ClaimService claimService;
    private final ClaimSelectionService selectionService;

    public ClaimCommand(RPGQuestPlugin plugin, ClaimService claimService, ClaimSelectionService selectionService) {
        this.plugin = plugin;
        this.claimService = claimService;
        this.selectionService = selectionService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
            return true;
        }
        if (!hasPermission(player)) {
            return true;
        }
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "wand" -> handleWand(player);
            case "create" -> handleCreate(player, args);
            case "delete" -> handleDelete(player);
            case "info" -> handleInfo(player);
            case "trust" -> handleTrust(player, args);
            case "untrust" -> handleUntrust(player, args);
            case "list" -> handleList(player);
            case "flag" -> handleFlag(player, args);
            default -> sendUsage(player);
        }
        return true;
    }

    private void handleWand(Player player) {
        player.getInventory().addItem(selectionService.createWandItem());
        player.sendMessage(MM.deserialize(
                "<green>Outil de sélection reçu.</green> <gray>Clic gauche = position 1, clic droit = position 2.</gray>"));
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(MM.deserialize("<yellow>/claim create <id></yellow>"));
            return;
        }
        String id = args[1].toLowerCase();

        Optional<Location> pos1 = selectionService.pos1(player.getUniqueId());
        Optional<Location> pos2 = selectionService.pos2(player.getUniqueId());
        if (pos1.isEmpty() || pos2.isEmpty()) {
            player.sendMessage(MM.deserialize(
                    "<red>Sélectionnez d'abord deux positions avec</red> <yellow>/claim wand</yellow>."));
            return;
        }

        claimService.create(player, id, pos1.get(), pos2.get()).thenAccept(outcome -> runOnMainThread(() -> {
            switch (outcome) {
                case CREATED -> {
                    selectionService.clear(player.getUniqueId());
                    player.sendMessage(MM.deserialize("<green>Claim créé :</green> <white><id></white>", Placeholder.unparsed("id", id)));
                }
                case INVALID_ID -> player.sendMessage(MM.deserialize(
                        "<red>Id de claim invalide :</red> <white><id></white>", Placeholder.unparsed("id", id)));
                case DIFFERENT_WORLDS -> player.sendMessage(MM.deserialize(
                        "<red>Les deux positions doivent être dans le même monde.</red>"));
                case DUPLICATE_ID -> player.sendMessage(MM.deserialize(
                        "<red>Un claim porte déjà l'id</red> <white><id></white>.", Placeholder.unparsed("id", id)));
                case TOO_LARGE -> player.sendMessage(MM.deserialize(
                        "<red>Cette sélection dépasse la taille maximale autorisée pour un claim</red> "
                                + "<gray>(<w>x<h> max)</gray>",
                        Placeholder.unparsed("w", String.valueOf(claimService.effectiveMaxWidth(player))),
                        Placeholder.unparsed("h", String.valueOf(claimService.effectiveMaxHeight(player)))));
                case TOO_MANY_CLAIMS -> player.sendMessage(MM.deserialize(
                        "<red>Tu as déjà atteint ton nombre maximal de claims</red> <gray>(<max>).</gray>",
                        Placeholder.unparsed("max", String.valueOf(claimService.effectiveMaxClaims(player)))));
                case OVERLAPS_CLAIM -> player.sendMessage(MM.deserialize(
                        "<red>Cette sélection chevauche un claim existant.</red>"));
                case OVERLAPS_PROTECTED_ZONE -> player.sendMessage(MM.deserialize(
                        "<red>Cette sélection chevauche une zone protégée (village/safe zone).</red>"));
                case TOO_CLOSE_TO_PORTAL -> player.sendMessage(MM.deserialize(
                        "<red>Cette sélection est trop proche d'un portail.</red>"));
            }
        })).exceptionally(error -> {
            plugin.getSLF4JLogger().error("Échec de /claim create pour {}", player.getUniqueId(), error);
            return null;
        });
    }

    private void handleDelete(Player player) {
        Optional<Claim> claimOpt = claimHere(player);
        if (claimOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Tu ne te trouves dans aucun claim.</red>"));
            return;
        }
        String id = claimOpt.get().id();
        claimService.delete(player.getUniqueId(), id).thenAccept(outcome -> runOnMainThread(() ->
                sendOwnerActionResult(player, outcome, id, "supprimé")));
    }

    private void handleInfo(Player player) {
        Optional<Claim> claimOpt = claimHere(player);
        if (claimOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Tu ne te trouves dans aucun claim.</red>"));
            return;
        }
        Claim claim = claimOpt.get();
        player.sendMessage(MM.deserialize("<gold>=== <id> ===</gold>", Placeholder.unparsed("id", claim.id())));
        player.sendMessage(MM.deserialize(
                "<white>Propriétaire :</white> <gray><owner></gray>",
                Placeholder.unparsed("owner", ownerName(claim))));
        player.sendMessage(MM.deserialize(
                "<white>Monde :</white> <gray><world></gray>", Placeholder.unparsed("world", claim.world())));
        player.sendMessage(MM.deserialize(
                "<white>Bornes :</white> <gray>(<minx>, <miny>, <minz>) → (<maxx>, <maxy>, <maxz>)</gray>",
                Placeholder.unparsed("minx", String.valueOf(claim.minX())), Placeholder.unparsed("miny", String.valueOf(claim.minY())),
                Placeholder.unparsed("minz", String.valueOf(claim.minZ())), Placeholder.unparsed("maxx", String.valueOf(claim.maxX())),
                Placeholder.unparsed("maxy", String.valueOf(claim.maxY())), Placeholder.unparsed("maxz", String.valueOf(claim.maxZ()))));
        player.sendMessage(MM.deserialize(
                "<white>Membres :</white> <gray><count></gray>", Placeholder.unparsed("count", String.valueOf(claim.members().size()))));
        player.sendMessage(MM.deserialize(
                "<white>Redstone publique :</white> <gray><value></gray>",
                Placeholder.unparsed("value", claim.flags().allowPublicRedstone() ? "oui" : "non")));
    }

    private void handleTrust(Player player, String[] args) {
        handleMembership(player, args, true);
    }

    private void handleUntrust(Player player, String[] args) {
        handleMembership(player, args, false);
    }

    private void handleMembership(Player player, String[] args, boolean trust) {
        if (args.length < 2) {
            player.sendMessage(MM.deserialize(trust ? "<yellow>/claim trust <joueur></yellow>" : "<yellow>/claim untrust <joueur></yellow>"));
            return;
        }
        Optional<Claim> claimOpt = claimHere(player);
        if (claimOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Tu ne te trouves dans aucun claim.</red>"));
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(MM.deserialize(
                    "<red>Joueur introuvable ou hors-ligne :</red> <white><name></white>", Placeholder.unparsed("name", args[1])));
            return;
        }
        String id = claimOpt.get().id();
        var future = trust
                ? claimService.trust(player.getUniqueId(), id, target.getUniqueId())
                : claimService.untrust(player.getUniqueId(), id, target.getUniqueId());
        future.thenAccept(outcome -> runOnMainThread(() ->
                sendOwnerActionResult(player, outcome, id, trust ? "fait confiance à " + target.getName() + " sur" : "retiré la confiance de " + target.getName() + " sur")));
    }

    private void handleList(Player player) {
        List<Claim> claims = claimService.claimsOwnedBy(player.getUniqueId());
        if (claims.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Tu n'as aucun claim.</gray>"));
            return;
        }
        player.sendMessage(MM.deserialize(
                "<gold><count></gold> <gray>claim(s) :</gray>", Placeholder.unparsed("count", String.valueOf(claims.size()))));
        for (Claim claim : claims) {
            player.sendMessage(MM.deserialize(
                    "<yellow>- <id></yellow> <gray>(<world>)</gray>",
                    Placeholder.unparsed("id", claim.id()), Placeholder.unparsed("world", claim.world())));
        }
    }

    private void handleFlag(Player player, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("redstone")) {
            player.sendMessage(MM.deserialize("<yellow>/claim flag redstone <true|false></yellow>"));
            return;
        }
        Boolean value = parseBoolean(args[2]);
        if (value == null) {
            player.sendMessage(MM.deserialize(
                    "<red>Valeur invalide (true/false attendu) :</red> <white><value></white>", Placeholder.unparsed("value", args[2])));
            return;
        }
        Optional<Claim> claimOpt = claimHere(player);
        if (claimOpt.isEmpty()) {
            player.sendMessage(MM.deserialize("<red>Tu ne te trouves dans aucun claim.</red>"));
            return;
        }
        String id = claimOpt.get().id();
        claimService.setAllowPublicRedstone(player.getUniqueId(), id, value).thenAccept(outcome -> runOnMainThread(() ->
                sendOwnerActionResult(player, outcome, id, "mis à jour (redstone publique = " + value + ") sur")));
    }

    // ---- Utilitaires ----------------------------------------------------

    private Optional<Claim> claimHere(Player player) {
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return Optional.empty();
        }
        return claimService.claimAt(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private void sendOwnerActionResult(Player player, ClaimActionOutcome outcome, String id, String verbPhrase) {
        switch (outcome) {
            case SUCCESS -> player.sendMessage(MM.deserialize(
                    "<green>Tu as <verb> le claim</green> <white><id></white>",
                    Placeholder.unparsed("verb", verbPhrase), Placeholder.unparsed("id", id)));
            case CLAIM_NOT_FOUND -> player.sendMessage(MM.deserialize(
                    "<red>Claim introuvable :</red> <white><id></white>", Placeholder.unparsed("id", id)));
            case NOT_OWNER -> player.sendMessage(MM.deserialize(
                    "<red>Tu n'es pas le propriétaire de ce claim.</red>"));
        }
    }

    private String ownerName(Claim claim) {
        var offline = plugin.getServer().getOfflinePlayer(claim.owner());
        String name = offline.getName();
        return name != null ? name : claim.owner().toString();
    }

    private @Nullable Boolean parseBoolean(String raw) {
        if (raw.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (raw.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private boolean hasPermission(CommandSender sender) {
        if (sender.hasPermission(PERMISSION)) {
            return true;
        }
        sender.sendMessage(MM.deserialize(
                "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", PERMISSION)));
        return false;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/claim wand</yellow> <gray>- outil de sélection (clic gauche/droit)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/claim create <id></yellow> <gray>- crée un claim depuis la sélection</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/claim delete</yellow> <gray>- supprime le claim où tu te trouves (propriétaire)</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/claim info</yellow> <gray>- détail du claim où tu te trouves</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/claim trust <joueur></yellow> <gray>- fait confiance sur le claim où tu te trouves</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/claim untrust <joueur></yellow> <gray>- retire la confiance</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/claim list</yellow> <gray>- liste tes claims</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/claim flag redstone <true|false></yellow> <gray>- redstone publique sur le claim où tu te trouves</gray>"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("flag")) {
            return FLAG_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            String prefix = args[1].toLowerCase();
            return sender.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
