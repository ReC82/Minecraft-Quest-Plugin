package com.lodygames.rpgquest.command;

import com.lodygames.rpgquest.resource.ResourceNodeRegistry;
import com.lodygames.rpgquest.resource.ResourceNodeService;
import com.lodygames.rpgquest.resource.model.ResourceNodeDefinition;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /resourcenode create|remove|inspect} ({@code rpgquest.admin},
 * outil d'administration comme {@code /customitem give|list}). Toutes les
 * sous-commandes agissent sur le bloc visé par le joueur (rayon de 6 blocs),
 * comme un outil de construction classique.
 */
public final class ResourceNodeCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "rpgquest.admin";
    private static final String DEFAULT_NAMESPACE = "rpgquest";
    private static final double REACH = 6.0;
    private static final List<String> TOP_LEVEL_SUBCOMMANDS = List.of("create", "remove", "inspect");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final ResourceNodeRegistry typeRegistry;
    private final ResourceNodeService service;

    public ResourceNodeCommand(ResourceNodeRegistry typeRegistry, ResourceNodeService service) {
        this.typeRegistry = typeRegistry;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String @NotNull [] args) {
        if (!hasPermission(sender)) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Cette commande doit être exécutée par un joueur.</red>"));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "remove" -> handleRemove(player);
            case "inspect" -> handleInspect(player);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            sendUsage(player);
            return;
        }
        NamespacedKey typeId = resolveId(args[1]);
        if (typeId == null) {
            player.sendMessage(MM.deserialize(
                    "<red>ID invalide :</red> <white><id></white>", Placeholder.unparsed("id", args[1])));
            return;
        }

        Block target = targetBlock(player);
        if (target == null) {
            sendNoTarget(player);
            return;
        }

        switch (service.createNode(target, typeId)) {
            case CREATED -> player.sendMessage(MM.deserialize(
                    "<green>Nœud créé :</green> <white><id></white>", Placeholder.unparsed("id", typeId.toString())));
            case UNKNOWN_TYPE -> player.sendMessage(MM.deserialize(
                    "<red>Type de nœud inconnu :</red> <white><id></white>", Placeholder.unparsed("id", typeId.toString())));
            case ALREADY_A_NODE -> player.sendMessage(MM.deserialize(
                    "<red>Il y a déjà un nœud à cette position.</red>"));
        }
    }

    private void handleRemove(Player player) {
        Block target = targetBlock(player);
        if (target == null) {
            sendNoTarget(player);
            return;
        }
        if (service.removeNode(target)) {
            player.sendMessage(MM.deserialize("<green>Nœud supprimé.</green>"));
        } else {
            player.sendMessage(MM.deserialize("<gray>Il n'y a aucun nœud à cette position.</gray>"));
        }
    }

    private void handleInspect(Player player) {
        Block target = targetBlock(player);
        if (target == null) {
            sendNoTarget(player);
            return;
        }
        Optional<ResourceNodeService.NodeInfo> info = service.inspectNode(target);
        if (info.isEmpty()) {
            player.sendMessage(MM.deserialize("<gray>Il n'y a aucun nœud à cette position.</gray>"));
            return;
        }
        ResourceNodeService.NodeInfo nodeInfo = info.get();
        player.sendMessage(MM.deserialize(
                "<gold>Nœud :</gold> <white><id></white>", Placeholder.unparsed("id", nodeInfo.typeId().toString())));
        if (nodeInfo.depleted()) {
            player.sendMessage(MM.deserialize(
                    "<white>État :</white> <red>épuisé</red> <gray>(respawn dans <seconds>s)</gray>",
                    Placeholder.unparsed("seconds", String.valueOf(nodeInfo.remainingRespawnSeconds()))));
        } else {
            player.sendMessage(MM.deserialize("<white>État :</white> <green>actif</green>"));
        }
    }

    private @Nullable Block targetBlock(Player player) {
        RayTraceResult result = player.rayTraceBlocks(REACH);
        return result == null ? null : result.getHitBlock();
    }

    private void sendNoTarget(Player player) {
        player.sendMessage(MM.deserialize("<red>Aucun bloc visé à portée.</red>"));
    }

    private @Nullable NamespacedKey resolveId(String raw) {
        return raw.contains(":") ? NamespacedKey.fromString(raw) : new NamespacedKey(DEFAULT_NAMESPACE, raw);
    }

    private boolean hasPermission(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        sender.sendMessage(MM.deserialize(
                "<red>Permission manquante :</red> <white><permission></white>", Placeholder.unparsed("permission", ADMIN_PERMISSION)));
        return false;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<yellow>/resourcenode create <typeId></yellow> <gray>- crée un nœud sur le bloc visé</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/resourcenode remove</yellow> <gray>- supprime le nœud du bloc visé</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/resourcenode inspect</yellow> <gray>- inspecte le nœud du bloc visé</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            return TOP_LEVEL_SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            String prefix = args[1].toLowerCase();
            return typeRegistry.types().stream()
                    .map(ResourceNodeDefinition::id)
                    .map(NamespacedKey::toString)
                    .filter(id -> id.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
